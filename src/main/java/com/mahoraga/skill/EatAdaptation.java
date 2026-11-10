package com.mahoraga.skill;

import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;

import java.util.List;
import java.util.Random;

/**
 * **进食适应** —— 吃得越多，吃得越快。
 *
 * 主人 2026-09-15 定的三档：
 * - **越吃越快**：全程连续，每 100% 提升 0.5 倍速，最高 3 倍（32 tick → 11 tick）
 * - **满 100%**：**饱食度永久锁满**（和"跑步 300%"用的是同一套 `lockFood`）
 * - **满 200%**：每次吃完额外送一个**随机正面药水效果**
 *
 * ==================== 为什么要单独一个客户端缓存 ====================
 *
 * 进食时长在**客户端和服务端各算一遍**（两边都跑 `LivingEntity#updateUsingItem`）。
 * 只改服务端的话，服务端 11 tick 就把东西吃完了，客户端还在那儿嚼 32 tick —— 动画对不上。
 * 所以进度通过 {@link com.mahoraga.network.EatSpeedPacket} 同步一份到客户端，
 * 两边用同一个 {@link #eatTicks(int)} 算，天然一致。
 *
 * ⚠️ 本类**不放 `@SubscribeEvent`** —— 事件全挂在 `AdaptationManager` 上。
 * 这个项目被"事件所在类没注册"坑过两次（`BladeHandler.ForgeEvents`），
 * 能确定在跑的那一个类就别再赌。
 */
public final class EatAdaptation {
    private EatAdaptation() {
    }

    /** 原版吃东西的时长（tick）。 */
    public static final int BASE_TICKS = 32;
    /** 最快能压到多少 tick（再快就没有"吃"的动作了）。 */
    public static final int FASTEST_TICKS = 11;

    /** 随机正面效果的池子。
     *  🔴 **不含漂浮**（主人明确要求）—— 那玩意儿会把人顶上天，是整人不是奖励。 */
    private static final List<Holder<MobEffect>> BUFFS = List.of(
            MobEffects.MOVEMENT_SPEED,      // 迅捷
            MobEffects.DIG_SPEED,            // 急迫
            MobEffects.DAMAGE_BOOST,         // 力量
            MobEffects.JUMP,                 // 跳跃提升
            MobEffects.REGENERATION,         // 生命恢复
            MobEffects.DAMAGE_RESISTANCE,    // 抗性提升
            MobEffects.FIRE_RESISTANCE,      // 防火
            MobEffects.WATER_BREATHING,      // 水下呼吸
            MobEffects.NIGHT_VISION,         // 夜视
            MobEffects.HEALTH_BOOST,         // 生命提升
            MobEffects.ABSORPTION,           // 伤害吸收
            MobEffects.SATURATION,           // 饱和
            MobEffects.LUCK,                 // 幸运
            MobEffects.DOLPHINS_GRACE,       // 海豚的恩惠
            MobEffects.CONDUIT_POWER,        // 潮涌能量
            MobEffects.SLOW_FALLING,         // 缓降
            MobEffects.HERO_OF_THE_VILLAGE   // 村庄英雄
    );

    private static final Random RANDOM = new Random();

    /** 客户端缓存的进食适应进度（百分比）。0 = 还没同步过 → 按原版速度。 */
    private static int clientPercent = 0;

    public static void setClientPercent(int percent) {
        clientPercent = percent;
    }

    public static int clientPercent() {
        return clientPercent;
    }

    /**
     * 这一次进食要多少 tick。
     *
     * 不满 100% 不动（还是原版 32）；之后**每 100% 快 0.5 倍**，最多 3 倍：
     * `100% → 21 ｜ 200% → 16 ｜ 300% → 13 ｜ 400%+ → 11`
     */
    public static int eatTicks(int percent) {
        if (percent < 100) return BASE_TICKS;

        float factor = 1.0F + Math.min(4, percent / 100) * 0.5F;

        return Math.max(FASTEST_TICKS, Math.round(BASE_TICKS / factor));
    }

    /** 随机效果持续多久（tick）：200% → 10 秒，400% → 20 秒，600%+ → 30 秒。 */
    public static int buffTicks(int percent) {
        return 200 + tier(percent) * 200;
    }

    /** 随机效果的等级：200% → I，400% → II，600%+ → III。 */
    public static int buffAmplifier(int percent) {
        return tier(percent);
    }

    /** 0 / 1 / 2 —— 满 200% 才有，之后每 200% 涨一档。 */
    private static int tier(int percent) {
        return Math.max(0, Math.min(2, (percent - 200) / 200));
    }

    public static Holder<MobEffect> rollBuff() {
        return BUFFS.get(RANDOM.nextInt(BUFFS.size()));
    }

    public static int buffCount() {
        return BUFFS.size();
    }
}
