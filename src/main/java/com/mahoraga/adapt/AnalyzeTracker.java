package com.mahoraga.adapt;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * 解析线的「观测」—— 每次挥击 / 每次结算都喂一点数据进来，慢慢攒出标签。
 *
 * ==================== 为什么全部基于"玩家的攻击数据" ====================
 *
 * 原版生物**根本没有**"回血速度""闪避率""减伤百分比"这些量，
 * 想读也没得读。所以所有判定都换成"**你观察到什么现象**"：
 *
 * | 标签 | 靠什么看出来 |
 * |---|---|
 * | 高血量 / 高护甲 / 阶段无敌 / 免疫负面 | 攻击那一刻直接读目标（瞬时） |
 * | 锁血 / 高减伤 | 上一刀**实际掉血** vs 上一刀的**伤害**（对比） |
 * | 快速回复 | 上次打完之后，它的血**涨回去了** |
 * | 闪避 | 连着几次"打中了但**一滴血没掉**" |
 * | 传送 | 上次到现在，位置**凭空跳了 8 格以上** |
 * | 召唤 | 附近有多出来的、**以它为主人**的生物 |
 *
 * 🔴 **"实际掉血"用血量差算，不是用伤害值** ——
 * `LivingHurtEvent` 触发时伤害**还没结算**，那一刻读 `getHealth()` 读到的是攻击前的血。
 * 所以真正能用的快照点在 **`LivingDamageEvent`**（护甲减伤之后、`setHealth` 之前）。
 */
public final class AnalyzeTracker {
    /**
     * 🔴 「特殊适应」的门槛 —— **只有最大生命 ≥ 100 的生物才参与解析**。
     *
     * 不设这道门的话，蜘蛛（16 血）这种货色也能把"会回血 / 会闪避 / 会传送"一堆条件凑齐，
     * 随手就发一堆强力破解，太变态了。血厚的怪（铁傀儡 / 掠夺兽 / 各种 Boss）才配。
     */
    public static final float MIN_SPECIAL_HP = 100.0F;
    /** 血厚线。 */
    private static final float HP_THRESHOLD = 100.0F;
    /** 硬线（原版护甲 20 封顶，15 已经算硬）。 */
    private static final int ARMOR_THRESHOLD = 15;
    /** 掉血不到伤害的这个比例 → 判定为「锁血」。 */
    private static final float LOCK_RATIO = 0.5F;
    /** 掉血不到伤害的这个比例 → 判定为「有减伤」。 */
    private static final float REDUCTION_RATIO = 0.7F;
    /** 连着打空几次算"会闪避"。 */
    private static final int DODGE_HITS = 3;
    /**
     * 一次位移超过这个格数就算传送。
     *
     * <p>⚠️ 2026-09-15 从 8 格放宽到 <b>5 格</b> —— 8 格太严，
     * 末影人一个短闪（3~4 格）根本判不出来，"瞬移"这条几乎抽不到。
     */
    private static final double TELEPORT_DISTANCE = 5.0D;
    /** 召唤物的搜查半径。 */
    private static final double MINION_RANGE = 16.0D;

    private AnalyzeTracker() {
    }

    // ==================== 类型判定（当场就能看出来，不用观察）====================

    /**
     * <b>它会不会飞</b> —— 类型判定，打第一刀就能知道。
     *
     * <p>两道判据：1.20.1 自带的 {@code FlyingMob} 基类（幻翼 / 恶魂 / 蜜蜂…），
     * 外加一串"能悬空但没继承 FlyingMob"的（末影龙 / 凋灵 / 烈焰人 / 恼鬼…）。
     */
    public static boolean canFly(LivingEntity entity) {
        if (entity instanceof net.minecraft.world.entity.FlyingMob) return true;

        String id = idOf(entity);

        return id.equals("minecraft:ghast")
                || id.equals("minecraft:phantom")
                || id.equals("minecraft:vex")
                || id.equals("minecraft:bee")
                || id.equals("minecraft:parrot")
                || id.equals("minecraft:bat")
                || id.equals("minecraft:allay")
                || id.equals("minecraft:ender_dragon")
                || id.equals("minecraft:wither")
                || id.equals("minecraft:blaze");
    }

    /**
     * <b>它会不会瞬移</b> —— 类型判定，同样打第一刀就知道。
     *
     * <p>（另一条路是"位置凭空跳 5 格以上"的观测判定，两条都会往「瞬移」标签上写。）
     */
    public static boolean isTeleporterType(LivingEntity entity) {
        String id = idOf(entity);

        return id.equals("minecraft:enderman")
                || id.equals("minecraft:shulker")
                || id.equals("minecraft:endermite")
                || id.equals("minecraft:illusioner")
                || id.equals("minecraft:evoker");
    }

    /** 生物注册名 —— 解析记录就是按它分的。 */
    public static String idOf(LivingEntity entity) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return id == null ? "unknown" : id.toString();
    }

    /**
     * 玩家**挥出**一次攻击（`AttackEntityEvent`）。
     * 这里读到的 `getHealth()` 是"上一次攻击结算之后"的血量 —— 正好拿来对比。
     */
    public static void onSwing(Player player, LivingEntity target) {
        String id = idOf(target);

        // 🔴 血太少的怪不配触发「特殊适应」——
        //    顺手把旧版本给它留下的记录清掉（免得老存档里蜘蛛还挂着标签）。
        if (target.getMaxHealth() < MIN_SPECIAL_HP) {
            AnalyzeData low = AnalyzeData.of(player);
            if (low.hasRecord(id)) low.clearRecord(id);
            return;
        }

        AnalyzeData data = AnalyzeData.of(player);
        data.setMaxHealth(id, target.getMaxHealth());

        // —— 一眼就能看出来的（瞬时）
        if (target.getMaxHealth() >= HP_THRESHOLD) data.addTag(id, AnalyzeTag.HIGH_HP);
        if (target.getArmorValue() >= ARMOR_THRESHOLD) data.addTag(id, AnalyzeTag.HIGH_ARMOR);
        if (target.isInvulnerable()) data.addTag(id, AnalyzeTag.PHASE);

        if (!target.canBeAffected(new MobEffectInstance(MobEffects.POISON, 100, 0))) {
            data.addTag(id, AnalyzeTag.IMMUNE_DEBUFF);
        }
        if (hasMinion(target)) data.addTag(id, AnalyzeTag.SUMMONER);

        // —— 要靠"上一次的快照"对比的
        if (!data.hasSnapshot(id)) return;

        double[] last = data.lastPos(id);
        double dx = target.getX() - last[0];
        double dy = target.getY() - last[1];
        double dz = target.getZ() - last[2];

        if (dx * dx + dy * dy + dz * dz > TELEPORT_DISTANCE * TELEPORT_DISTANCE) {
            data.addTag(id, AnalyzeTag.TELEPORT);
        }

        float lastHp = data.lastHp(id);
        float lastDamage = data.lastDamage(id);
        float now = target.getHealth();

        // 血比上次还多 —— 它自己回血了（这一刀还没落下去，所以只可能是它回的）
        if (now > lastHp + 0.01F) {
            data.addTag(id, AnalyzeTag.FAST_HEAL);
            data.setDodge(id, 0);
            return;
        }

        float dealt = lastHp - now;                 // 上一刀**实际**掉了多少血

        if (dealt <= 0.01F) {
            // 打中了，但一滴血没掉 —— 算一次"打空"
            int dodge = data.dodge(id) + 1;
            data.setDodge(id, dodge);

            if (dodge >= DODGE_HITS) data.addTag(id, AnalyzeTag.DODGE);
            return;
        }

        data.setDodge(id, 0);

        if (lastDamage > 0.0F) {
            if (dealt < lastDamage * LOCK_RATIO) {
                data.addTag(id, AnalyzeTag.LOCK_BLOOD);
            } else if (dealt < lastDamage * REDUCTION_RATIO) {
                data.addTag(id, AnalyzeTag.HIGH_REDUCTION);
            }
        }
    }

    /**
     * 伤害**结算后**存快照（`LivingDamageEvent`）。
     *
     * ⚠️ `LivingDamageEvent` 里 `getHealth()` 还是**攻击前**的血 —— 这正是我们要的：
     * 下次挥击时拿它减去当时的血，就是这一刀的实际掉血。
     */
    public static void onDamage(Player player, LivingEntity victim, float amount) {
        // 血太少的怪连快照都不存 —— 不然会有个空记录把 /mahoraga 刷满
        if (victim.getMaxHealth() < MIN_SPECIAL_HP) return;

        AnalyzeData.of(player).snapshot(idOf(victim),
                victim.getHealth(), amount,
                victim.getX(), victim.getY(), victim.getZ());
    }

    /** 附近有没有"以它为主人"的生物。 */
    private static boolean hasMinion(LivingEntity target) {
        for (LivingEntity other : target.level().getEntitiesOfClass(LivingEntity.class,
                target.getBoundingBox().inflate(MINION_RANGE), e -> e != target && e.isAlive())) {

            if (other instanceof OwnableEntity ownable && ownable.getOwner() == target) return true;
        }
        return false;
    }
}
