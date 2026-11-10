package com.mahoraga.skill;

import com.mahoraga.handler.BladeHandler;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.joml.Vector3f;

/**
 * 「攻击适应」命中特效 —— 打**已经摸透的生物**时，每一刀都炸一簇粒子出来。
 *
 * ==================== 颜色怎么定的（主人 2026-09-14 定） ====================
 *
 * 两条轴交叉：
 *
 * 1. **色相看"你适应了什么"** —— 复用剑刃皮肤那套判据（{@link BladeHandler#skinMode}）：
 *    - 适应了**火**（火焰 / 着火 / 岩浆）→ 暖色（深橙 → 白热）
 *    - 适应了**寒冷**（细雪）→ 冷色（冰蓝 → 冷白）
 *    - 都没适应 → 紫色系（魔虚罗原本的颜色）
 *
 * 2. **亮度看"适应到几档"** —— 每 100% 一档，共 10 档：
 *    100% 时是深色，1000% 时亮到发白。**档位越高粒子越多、越大、音调越高。**
 *
 * 用的 `DustParticleOptions` 可以**直接给 RGB**，所以是真·任意颜色，
 * 不像原版粒子那样只有固定几种色。
 *
 * 这是**纯表现**，不带任何伤害 —— 伤害那套在 {@code AdaptationManager#applyAttackEffects}。
 */
public final class HitFx {
    private HitFx() {
    }

    /** 打中一只"攻击适应 ≥ 100%"的生物时调用。{@code pct} 是百分比（100 = 100%）。 */
    public static void play(Player player, LivingEntity victim, int pct) {
        if (pct < 100) return;
        if (!(player.level() instanceof ServerLevel level)) return;
        if (victim.isDeadOrDying() && pct < 200) return;

        int tier = Math.max(1, Math.min(10, pct / 100));
        int element = BladeHandler.skinMode(player);

        float[] rgb = color(element, tier);
        float scale = 0.80F + tier * 0.07F;

        double x = victim.getX();
        double y = victim.getY() + victim.getBbHeight() * 0.55D;
        double z = victim.getZ();
        double dx = victim.getBbWidth() * 0.45D;
        double dy = victim.getBbHeight() * 0.35D;

        // 主色：能任意调色的粉尘
        level.sendParticles(new DustParticleOptions(new Vector3f(rgb[0], rgb[1], rgb[2]), scale),
                x, y, z, 6 + tier * 2, dx, dy, dx, 0.02D);

        // 佐味：火→火星、冰→雪花、都没有→末地烛光
        level.sendParticles(flavor(element), x, y, z, 4 + tier, dx, dy, dx, 0.05D);

        // 音调随档位往上走 —— 越熟越"脆"
        level.playSound(null, victim.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME,
                SoundSource.PLAYERS, 0.30F, 0.72F + tier * 0.09F);
    }

    private static ParticleOptions flavor(int element) {
        return switch (element) {
            case BladeHandler.SKIN_FIRE -> ParticleTypes.FLAME;
            case BladeHandler.SKIN_FROST -> ParticleTypes.SNOWFLAKE;
            default -> ParticleTypes.END_ROD;
        };
    }

    /**
     * 算出这一下的颜色：从元素的"深色档"线性插值到"白热档"，程度由档位决定。
     *
     * @param tier 1~10
     */
    private static float[] color(int element, int tier) {
        float t = (tier - 1) / 9.0F;

        return switch (element) {
            // 火：深橙红 → 白热
            case BladeHandler.SKIN_FIRE -> lerp(new float[]{1.00F, 0.28F, 0.05F},
                    new float[]{1.00F, 0.96F, 0.62F}, t);

            // 寒冷：冰蓝 → 冷白
            case BladeHandler.SKIN_FROST -> lerp(new float[]{0.18F, 0.52F, 1.00F},
                    new float[]{0.86F, 0.98F, 1.00F}, t);

            // 无属性：魔虚罗的紫 → 白
            default -> lerp(new float[]{0.55F, 0.32F, 1.00F},
                    new float[]{1.00F, 1.00F, 1.00F}, t);
        };
    }

    private static float[] lerp(float[] from, float[] to, float t) {
        return new float[]{
                from[0] + (to[0] - from[0]) * t,
                from[1] + (to[1] - from[1]) * t,
                from[2] + (to[2] - from[2]) * t
        };
    }
}
