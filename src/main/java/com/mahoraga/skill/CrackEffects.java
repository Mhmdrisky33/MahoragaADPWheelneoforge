package com.mahoraga.skill;

import com.mahoraga.adapt.AnalyzeData;
import com.mahoraga.registry.ModDamageTypes;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 让「独特破解」**真的生效** —— 打中那个生物时，从你学会的破解里挑一个打出去。
 *
 * ==================== 三个设计选择 ====================
 *
 * ① **每个破解对同一只生物只打一次**（主人 2026-09-14 定）。
 *    打过就记进 `AnalyzeData#fired`，之后再也不放 —— 否则"削减生命上限"（永久属性修饰符，
 *    重放还是同一个值、看不出变化）和"流血不止"（每次叠一层）表现完全对不上，
 *    玩家会觉得"有的只触发一次、有的老是触发"。
 *
 * ② **每次攻击只触发一个**，而且**每个破解有自己的冷却**（默认 4 秒）。
 *    刚学会一堆破解时让它们**一个一个慢慢放**，而不是一秒全砸对面脸上。
 *
 * ③ **自增益不用冷却**，战斗中一直续着（`BUFF_TICKS` 每次攻击刷新）——
 *    它本来就是"你从这个生物身上学来的本事"，属于被动加成。
 *
 * ⚠️ **属性类破解一律用 `addTransientModifier` + 固定 UUID**：
 * 每次先 remove 再 add → 天然就是"刷新时长"，也**不会越叠越多**（否则打十下减 20% 生命上限，
 * 对面直接被你削成 1 滴血）。
 *
 * ⚠️ 补刀前必须 `invulnerableTime = 0` —— 我们是在 `LivingDamageEvent` 之后补的，
 * 无敌帧会把这一下整段吃掉。
 */
public final class CrackEffects {
    /** 每个破解的触发冷却（tick）—— 4 秒。 */
    private static final int COOLDOWN = 80;
    /** 「禁止治疗」持续。 */
    private static final int NO_HEAL_TICKS = 160;
    /** 「虚弱标记」持续。 */
    private static final int MARK_TICKS = 160;
    /** 「禁锢」持续。 */
    private static final int LOCK_TICKS = 50;
    /** 「禁止传送」持续。 */
    private static final int TELEPORT_LOCK_TICKS = 80;
    /** 自增益每轮续多久。 */
    private static final int BUFF_TICKS = 200;
    /** 清召唤 / 找召唤物的半径。 */
    private static final double MINION_RANGE = 16.0D;

    /** `/mahoraga wheel reset` 摘属性要用，所以是 public。 */
    public static final UUID ID_MAX_HEALTH = UUID.fromString("8a1f5c30-2d47-4b19-9e63-71c0a4f8d2b5");
    public static final UUID ID_ARMOR = UUID.fromString("c4e29b71-5f83-4a06-8d1c-3b7e5a902f64");
    public static final UUID ID_ATTACK = UUID.fromString("17d6a8c2-90b4-4e75-a3f8-6c2d1e804b39");

    private CrackEffects() {
    }

    /** 攻击命中时调用（放在攻击链的末尾）。 */
    public static void apply(Player player, LivingEntity victim, String entityId, float damage) {
        if (victim.isDeadOrDying()) return;

        AnalyzeData data = AnalyzeData.of(player);

        applyBuffs(player, data, entityId);

        List<String> owned = data.cracks(entityId);
        if (owned.isEmpty()) return;

        // 🔴 每个破解对**同一只生物只打一次**（主人 2026-09-14 定）。
        //    少了这一道，同一个破解会每隔 4 秒往外放一次 —— "削减生命上限"那种永久修饰符
        //    看着像只触发一次（remove 再 add 是同一个值），"流血不止"却会一直叠，两边表现对不上。
        List<String> fired = data.fired(entityId);
        long now = player.level().getGameTime();
        List<CrackPool.Kind> ready = new ArrayList<>();

        for (String name : owned) {
            if (fired.contains(name)) continue;         // 打过了，不再放

            CrackPool.Kind kind = kindOf(name);
            if (kind != null && now >= data.cooldownUntil(kind.id())) ready.add(kind);
        }
        if (ready.isEmpty()) return;

        CrackPool.Kind kind = ready.get(player.getRandom().nextInt(ready.size()));
        fire(player, victim, kind, damage);

        data.setCooldownUntil(kind.id(), now + COOLDOWN);
        data.markFired(entityId, kind.name());
    }

    // ==================== 自增益 ====================

    private static void applyBuffs(Player player, AnalyzeData data, String entityId) {
        for (String id : data.buffs(entityId)) {
            MobEffect effect = BuiltInRegistries.MOB_EFFECTS.getValue(ResourceLocation.tryParse(id));
            if (effect == null) continue;

            player.addEffect(new MobEffectInstance(effect, BUFF_TICKS, 0, true, false, true));
        }
    }

    // ==================== 减益 ====================

    private static void fire(Player player, LivingEntity victim, CrackPool.Kind kind, float damage) {
        switch (kind) {
            // ★ 「捌」—— 宿傩那套连斩（2026-09-15 换成它的演出）。
            //   演出一比一照抄原模组，**伤害还是这一条原本的算法**：
            //   目标最大生命的 5%，走法术伤害。只是不再立刻结算，而是连斩 25 tick 后落刀。
            case PERCENT_DAMAGE -> SukunaCleave.perform(player, victim);

            // 真伤 —— 护甲、附魔、抗性、吸收一概不认
            case TRUE_DAMAGE -> strike(player, victim, Math.max(4.0F, damage * 0.3F));

            // 固定的穿甲重击
            case PENETRATE -> strike(player, victim, 10.0F);

            case CUT_MAX_HEALTH -> cut(victim, Attributes.MAX_HEALTH, ID_MAX_HEALTH, -0.02D, true);
            case CUT_ARMOR -> cut(victim, Attributes.ARMOR, ID_ARMOR, -2.0D, false);
            case CUT_DAMAGE -> cut(victim, Attributes.ATTACK_DAMAGE, ID_ATTACK, -0.08D, true);

            case NO_HEAL -> NoHeal.mark(victim, NO_HEAL_TICKS);

            case BLEED -> EternalBleeding.cut(player, victim);

            case MARK -> {
                boolean weakened = victim.addEffect(new MobEffectInstance(
                        MobEffects.WEAKNESS, MARK_TICKS, 1, true, false, true));

                victim.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, MARK_TICKS, 1,
                        true, false, true));

                // ⚠️ **Boss 免疫备用池**（大纲第七章）：末影龙之类完全免疫状态效果的，
                // `addEffect` 会返回 false —— 这时**转成一记真伤**，别让这一刀白打。
                if (!weakened) strike(player, victim, 8.0F);
            }

            case LOCK_MOVE -> SpaceLock.lock(victim, LOCK_TICKS);
            case LOCK_TELEPORT -> SpaceLock.lock(victim, TELEPORT_LOCK_TICKS);

            case CLEAR_MINION -> clearMinions(victim);

            // **无量空处**：以自己为圆心铺开领域，把周围所有东西钉住 8 秒。
            // 注意中心钉在展开那一刻的位置 —— 领域是"一块地"，不是跟着你跑的光环。
            case VOID_DOMAIN -> VoidDomain.open(player);
        }

        effect(player, victim);
    }

    /** 补一记真伤 —— 先清无敌帧，不然整段被吃掉。 */
    private static void strike(Player player, LivingEntity victim, float amount) {
        if (amount <= 0.0F || victim.isDeadOrDying()) return;

        victim.invulnerableTime = 0;
        victim.hurt(ModDamageTypes.trueDamage(player), amount);
    }

    /**
     * 削减一项属性。
     * 每次先 remove 再 add —— 既是"刷新"，也保证**不会越叠越多**。
     */
    private static void cut(LivingEntity target, Attribute attribute, UUID id,
                            double amount, boolean multiply) {
        AttributeInstance instance = target.getAttribute(attribute);
        if (instance == null) return;

        if (instance.getModifier(id) != null) instance.removeModifier(id);

        instance.addTransientModifier(new AttributeModifier(id, "mahoraga_crack", amount,
                multiply ? AttributeModifier.Operation.MULTIPLY_TOTAL
                        : AttributeModifier.Operation.ADDITION));
    }

    private static void clearMinions(LivingEntity target) {
        for (LivingEntity other : target.level().getEntitiesOfClass(LivingEntity.class,
                target.getBoundingBox().inflate(MINION_RANGE), e -> e != target && e.isAlive())) {

            if (other instanceof OwnableEntity ownable && ownable.getOwner() == target) {
                other.discard();
            }
        }
    }

    /** 一个"破解打出去"的小反馈 —— 暴击粒子 + 附魔台的声音（音调拉高，听着像"解密"）。 */
    private static void effect(Player player, LivingEntity victim) {
        if (player.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.CRIT,
                    victim.getX(), victim.getY() + victim.getBbHeight() * 0.6D, victim.getZ(),
                    12, victim.getBbWidth() * 0.4D, victim.getBbHeight() * 0.3D,
                    victim.getBbWidth() * 0.4D, 0.1D);
        }

        player.level().playSound(null, victim.blockPosition(), SoundEvents.ENCHANTMENT_TABLE_USE,
                SoundSource.PLAYERS, 0.5F, 1.6F);
    }

    private static CrackPool.Kind kindOf(String name) {
        try {
            return CrackPool.Kind.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
