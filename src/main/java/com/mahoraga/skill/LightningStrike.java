package com.mahoraga.skill;

import com.mahoraga.adapt.AdaptationManager;
import com.mahoraga.registry.ModDamageTypes;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;

/**
 * 「**雷击**」适应的攻击侧奖励 —— **攻击附带火焰与雷电**（主人 2026-09-14 定）。
 *
 * ==================== 怎么练 ====================
 *
 * 这条在**防御轨**（键是 `damage:minecraft:lightning_bolt`），所以：
 * - 被闪电劈中 → 自动累（原版就有这条伤害类型）；
 * - **雷雨天站在露天** → 每 20 tick 喂一次（不然这玩意太难碰上，没法测）；
 * - `/mahoraga set defense lightning 100`。
 *
 * ==================== 满 100% 之后打人会怎样 ====================
 *
 * | 档位 | 火焰 | 雷电 |
 * |---|---|---|
 * | **100%** | 点燃 4 秒 | 附加「攻击力 × 30%」的雷击伤害（无视护甲） |
 * | **200%** | 点燃 8 秒 | ×60% |
 * | **500%** | — | 再追加「目标最大生命 × 5%」 |
 *
 * ⚠️ 雷电伤害走本模组自己的 `mahoraga:lightning` ——
 * 既记在玩家头上，又**不会再喂一次适应**（否则自己喂自己，越打越强还无限递归）。
 */
public final class LightningStrike {
    private LightningStrike() {
    }

    /** 满 100% 才开始带电。 */
    private static final float NEED = AdaptationManager.COMPLETE;

    /** 200%：火焰翻倍、雷击翻倍。 */
    private static final float TIER_2 = 2.0F;

    /** 500%：追加百分比伤害。 */
    private static final float TIER_5 = 5.0F;

    private static final int FIRE_SECONDS = 4;
    private static final int FIRE_SECONDS_T2 = 8;

    private static final float THUNDER = 0.30F;
    private static final float THUNDER_T2 = 0.60F;
    private static final float HP_PERCENT = 0.05F;

    /**
     * 每次攻击调一次（在 {@code AdaptationManager#onHurt} 的攻击分支里）。
     *
     * @param progress 「雷击」适应的进度（1.0 = 100%）
     */
    public static void onHit(Player player, LivingEntity victim, float progress) {
        if (progress < NEED) return;
        if (victim.isDeadOrDying()) return;
        if (!(player.level() instanceof ServerLevel level)) return;

        boolean strong = progress >= TIER_2;

        // ① 火焰 —— 直接给目标点着
        victim.setSecondsOnFire(strong ? FIRE_SECONDS_T2 : FIRE_SECONDS);

        // ② 雷电 —— 一记无视护甲的附加伤害
        float base = (float) player.getAttributeValue(Attributes.ATTACK_DAMAGE);
        if (base < 0.0F) base = 0.0F;

        float amount = base * (strong ? THUNDER_T2 : THUNDER);

        if (progress >= TIER_5) {
            amount += victim.getMaxHealth() * HP_PERCENT;
        }

        if (amount > 0.0F) {
            // 先清无敌帧，不然这一下会被目标的受击冷却整段吃掉
            victim.invulnerableTime = 0;
            victim.hurt(ModDamageTypes.lightning(player), amount);
        }

        // ③ 表现：电火花 + 火星 + 一声闷雷
        double x = victim.getX();
        double y = victim.getY() + victim.getBbHeight() * 0.5D;
        double z = victim.getZ();
        double dx = victim.getBbWidth() * 0.5D;
        double dy = victim.getBbHeight() * 0.4D;

        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, x, y, z,
                8, dx, dy, dx, 0.05D);
        level.sendParticles(ParticleTypes.FLAME, x, y, z,
                6, dx, dy * 0.6D, dx, 0.02D);

        level.playSound(null, victim.blockPosition(), SoundEvents.LIGHTNING_BOLT_IMPACT,
                SoundSource.PLAYERS, 0.45F, strong ? 1.15F : 1.35F);
        level.playSound(null, victim.blockPosition(), SoundEvents.FIRECHARGE_USE,
                SoundSource.PLAYERS, 0.35F, strong ? 1.5F : 1.7F);

        // ④ **真的从天上引一道雷下来**（主人 2026-09-15："攻击会引雷，无视天气，现在不行"）
        //
        //    以前只做了"附加雷电伤害 + 电火花"，天上一片云都没有 —— 看着不像引雷。
        //    现在真劈：原版的 `LightningBolt` 本来就能凭空生成，**根本不需要雷雨天**，
        //    这就是主人说的"无视天气"（不用等下雨，随时能劈）。
        //
        //    ⚠️ 真雷会点燃地面、也会劈到站太近的自己，所以给它 1 秒冷却 ——
        //       连打的时候不至于每刀都"天降正义"。
        summonBolt(player, victim);
    }

    /** 引雷的冷却（tick）—— 免得连打时满屏闪电。 */
    private static final int BOLT_COOLDOWN = 20;

    /** 每个人上次引雷的时刻。 */
    private static final java.util.Map<java.util.UUID, Long> LAST_BOLT =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 在目标头顶召唤一道**真的**闪电。
     *
     * <p>不做"纯视觉"（`setVisualOnly`）—— 主人要的是引雷，那就得能劈伤、能点着火。
     * `setCause` 认主，这样劈死的算玩家的击杀。
     */
    private static void summonBolt(Player player, LivingEntity victim) {
        if (!(player.level() instanceof ServerLevel level)) return;

        long now = level.getGameTime();
        Long last = LAST_BOLT.get(player.getUUID());

        if (last != null && now - last < BOLT_COOLDOWN) return;

        LAST_BOLT.put(player.getUUID(), now);

        net.minecraft.world.entity.LightningBolt bolt =
                net.minecraft.world.entity.EntityType.LIGHTNING_BOLT.create(level);

        if (bolt == null) return;

        bolt.moveTo(victim.getX(), victim.getY(), victim.getZ());

        if (player instanceof net.minecraft.server.level.ServerPlayer server) {
            bolt.setCause(server);
        }

        level.addFreshEntity(bolt);
    }
}
