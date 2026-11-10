package com.mahoraga.skill;

import com.mahoraga.adapt.AdaptationKeys;
import com.mahoraga.adapt.AdaptationManager;
import com.mahoraga.adapt.Track;
import com.mahoraga.registry.ModSounds;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * <b>伏魔御厨子</b> —— 宿傩的开放式领域，抄自原模组 {@code shrine/MalevolentShrine}。
 *
 * <p>主人 2026-09-15 的要求说得非常具体：
 * <blockquote>
 *   增加一个适应群殴……达到一百可以解锁伏魔御厨子的领域零点五秒，
 *   <b>不要那个神龛的模型</b>，就是瞬间对所有人施加一个捌
 * </blockquote>
 *
 * <h3>于是和原版的关系是这样的</h3>
 * <pre>
 *   原版：立起一座神龛 + 一片"必中领域"，之后每 5 tick 对领域里每个生物放一次「捌」
 *   我们：**不立神龛、也不留领域** —— 展开那一瞬间，对半径内**所有**生物各来一套「捌」，
 *        然后气浪一闪就结束（这就是"零点五秒"的含义）
 * </pre>
 * 说实话这个改法比原版更适合我们：原版的领域要维持十几秒、每 5 tick 扫一遍全场，
 * 放在单人档里就是"点一下把周围清空"，一秒内解决反而更有"御厨子"那种一刀切完的干脆。
 *
 * <p><b>怎么练</b>：被 <b>{@value #SWARM_COUNT} 只以上</b>生物同时围攻时，
 * 每秒喂一份进度给 {@link AdaptationKeys#SWARM}（防御轨）。散了就不再涨、30 秒后开始衰减 ——
 * 和别的战斗轨适应一个规矩。
 *
 * <p>解锁后挂到**退魔之剑右键**那一套（见 {@code BladeAbilities}）。
 */
public final class MalevolentShrine {
    /** 判定"被围攻"的半径。 */
    public static final double SWARM_RADIUS = 12.0D;
    /** 几只算"围攻"。 */
    public static final int SWARM_COUNT = 5;
    /** 领域生效半径。 */
    private static final double DOMAIN_RADIUS = 15.0D;

    private static final RandomSource RANDOM = RandomSource.create();

    private MalevolentShrine() {
    }

    /** 解锁判据：群殴适应满 100%。 */
    public static boolean unlocked(Player player) {
        return AdaptationManager.data(player).get(Track.DEFENSE, AdaptationKeys.SWARM)
                >= AdaptationManager.COMPLETE;
    }

    /**
     * 身边围着几只**敌人**（给「群殴适应」数数用）。
     *
     * <p>只数 {@link Enemy}（僵尸骷髅那类主动敌对生物）——
     * 路过的牛和羊不算，自己驯服的狗也不算。
     */
    public static int countEnemies(Player player) {
        double radiusSqr = SWARM_RADIUS * SWARM_RADIUS;
        int count = 0;

        for (LivingEntity entity : player.level().getEntitiesOfClass(LivingEntity.class,
                player.getBoundingBox().inflate(SWARM_RADIUS))) {

            if (entity == player || !entity.isAlive()) continue;
            if (!(entity instanceof Enemy)) continue;
            if (entity instanceof OwnableEntity pet && player.equals(pet.getOwner())) continue;
            if (entity.distanceToSqr(player) > radiusSqr) continue;

            count++;
        }
        return count;
    }

    /**
     * 展开领域 —— **瞬间**对范围内所有东西各施加一套「捌」。
     *
     * <p>不建神龛、不留领域实体、不等下一次攻击：右键的当下就结算完。
     */
    public static void cast(Player player) {
        if (player.level().isClientSide) return;
        if (!(player.level() instanceof ServerLevel level)) return;

        Vec3 center = player.position();

        // 展开的一声 —— 低频轰鸣，压得人耳朵发闷
        level.playSound(null, center.x, center.y, center.z,
                SoundEvents.WITHER_SPAWN, SoundSource.PLAYERS, 1.1F, 1.5F);
        level.playSound(null, center.x, center.y, center.z,
                ModSounds.CLEAVE.get(), SoundSource.PLAYERS, 1.4F, 0.75F);

        drawDomain(level, center);

        // ---- 瞬发：范围内每个生物一套「捌」 ----
        double radiusSqr = DOMAIN_RADIUS * DOMAIN_RADIUS;

        for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class,
                new AABB(center, center).inflate(DOMAIN_RADIUS))) {

            if (victim == player || !victim.isAlive()) continue;
            if (victim.position().distanceToSqr(center) > radiusSqr) continue;

            SukunaCleave.perform(player, victim);
        }
    }

    /** 领域展开的视觉 —— 暗红气浪 + 一圈斩痕，一闪而过。 */
    private static void drawDomain(ServerLevel level, Vec3 center) {
        DustParticleOptions red = new DustParticleOptions(new Vector3f(0.55F, 0.04F, 0.04F), 2.6F);

        // 外圈气浪：贴地画一圈，慢慢往外推
        for (int i = 0; i < 180; i++) {
            double angle = RANDOM.nextDouble() * Math.PI * 2.0D;
            double r = DOMAIN_RADIUS * (0.35D + RANDOM.nextDouble() * 0.65D);

            double x = center.x + Math.cos(angle) * r;
            double z = center.z + Math.sin(angle) * r;

            level.sendParticles(red, x, center.y + 0.2D + RANDOM.nextDouble() * 2.5D, z,
                    1, 0.0D, 0.15D, 0.0D, 0.0D);
        }

        // 中心的"什么都没有"的一瞬
        level.sendParticles(ParticleTypes.SCULK_SOUL, center.x, center.y + 1.0D, center.z,
                60, 3.0D, 1.2D, 3.0D, 0.05D);
        level.sendParticles(ParticleTypes.FLASH, center.x, center.y + 1.2D, center.z,
                2, 1.0D, 0.6D, 1.0D, 0.0D);

        // 一圈贴地的斩痕（示意"领域边界"）
        for (int i = 0; i < 96; i++) {
            double angle = Math.PI * 2.0D * i / 96.0D;

            level.sendParticles(ParticleTypes.SWEEP_ATTACK,
                    center.x + Math.cos(angle) * DOMAIN_RADIUS, center.y + 0.25D,
                    center.z + Math.sin(angle) * DOMAIN_RADIUS,
                    1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }
}
