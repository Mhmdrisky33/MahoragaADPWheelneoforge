package com.mahoraga.skill;

import com.mahoraga.adapt.AdaptationData;
import com.mahoraga.adapt.AdaptationKeys;
import com.mahoraga.adapt.AdaptationManager;
import com.mahoraga.adapt.Track;
import com.mahoraga.registry.ModDamageTypes;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;

/**
 * <b>火焰附加</b> —— 适应了火焰之后，每一刀都带着火。
 *
 * <p>主人 2026-09-15：<i>"适应火焰后攻击目前也不能附加火焰，修复一下"</i>。
 *
 * <p>原先只有 {@link LightningStrike}（**雷击**适应）会顺手点着目标，
 * 而"被火烧过"这件事本身（着火 / 火焰 / 岩浆三条）一直没有回报 —— 这次补上。
 *
 * <p>三条里**任意一条满 100%** 就生效（被岩浆烫过和被火烧过，学会的是同一件事），
 * 进度取三者最高：
 * <pre>
 *   100% → 每一刀点着目标 4 秒
 *   200% → 点着 8 秒，并追加一记火焰伤害
 * </pre>
 *
 * <p>⚠️ 只负责"点火 + 补伤害"，**不重复 {@link LightningStrike} 的雷电** ——
 * 那一条有它自己的进度和表现，两条各练各的。
 */
public final class FlameStrike {
    /** 生效门槛。 */
    private static final float NEED = AdaptationManager.COMPLETE;
    /** 二档（200%）。 */
    private static final float TIER_2 = 2.0F;
    /** 点燃时长（秒）。 */
    private static final int FIRE_SECONDS = 4;
    private static final int FIRE_SECONDS_T2 = 8;
    /** 二档追加的火焰伤害 —— 按攻击力的比例算。 */
    private static final float BURN_SCALE = 0.30F;

    private FlameStrike() {
    }

    /**
     * 攻击命中时调一次（放在攻击链里，紧挨着 {@link LightningStrike}）。
     */
    public static void onHit(Player player, LivingEntity victim, AdaptationData data) {
        if (victim.isDeadOrDying()) return;
        if (!(player.level() instanceof ServerLevel level)) return;

        float progress = flameProgress(data);
        if (progress < NEED) return;

        boolean strong = progress >= TIER_2;

        // ① 点着它
        victim.igniteForSeconds(strong ? (float) FIRE_SECONDS_T2 : (float) FIRE_SECONDS);

        // ② 二档再补一记火焰伤害（走自己的伤害类型，免得把平砍那一下算重）
        if (strong) {
            float base = (float) player.getAttributeValue(Attributes.ATTACK_DAMAGE);
            float amount = Math.max(0.0F, base) * BURN_SCALE;

            if (amount > 0.0F) {
                victim.invulnerableTime = 0;            // 不清无敌帧会被整段吃掉
                victim.hurt(ModDamageTypes.spell(player), amount);
            }
        }

        // ③ 表现：火苗从伤口窜出来 + 一声划火
        double x = victim.getX();
        double y = victim.getY() + victim.getBbHeight() * 0.5D;
        double z = victim.getZ();
        double dx = victim.getBbWidth() * 0.5D;
        double dy = victim.getBbHeight() * 0.4D;

        level.sendParticles(ParticleTypes.FLAME, x, y, z, 7, dx, dy, dx, 0.02D);
        level.sendParticles(ParticleTypes.SMALL_FLAME, x, y - dy * 0.5D, z, 4, dx, dy * 0.4D, dx, 0.01D);

        level.playSound(null, victim.blockPosition(), SoundEvents.FIRECHARGE_USE,
                SoundSource.PLAYERS, 0.4F, strong ? 1.4F : 1.6F);
    }

    /**
     * 「火焰」适应的进度 —— 着火 / 火焰 / 岩浆**三条取最高**。
     *
     * <p>对玩家来说这三件事没区别（都是"被烧了"），分三条键是原版伤害类型的锅。
     */
    public static float flameProgress(AdaptationData data) {
        return Math.max(data.get(Track.DEFENSE, AdaptationKeys.FIRE),
                Math.max(data.get(Track.DEFENSE, AdaptationKeys.FIRE_IN),
                        data.get(Track.DEFENSE, AdaptationKeys.LAVA)));
    }

    /** 火焰适应到底练到多少了（`/mahoraga` 那类诊断用）。 */
    public static int flamePercent(Player player) {
        return AdaptationManager.percent(flameProgress(AdaptationManager.data(player)));
    }
}
