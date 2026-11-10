package com.mahoraga.skill;

import com.mahoraga.adapt.AdaptationData;
import com.mahoraga.adapt.AdaptationManager;
import com.mahoraga.registry.ModDamageTypes;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>开</b> —— 宿傩那一招火焰箭，抄自原模组 {@code shrine/FireArrow} +
 * {@code entity/projectile/FireArrowProjectile}。
 *
 * <p>主人 2026-09-15 的定位：**作为「适应火焰」的奖励**，挂在退魔之剑右键上。
 *
 * <h3>原版什么样，我们改成什么样</h3>
 * <pre>
 *   原版：起手蓄力 1 秒（火球跟着准星走）→ 5 格/tick 飞出去
 *         → 命中点炸出一片 15 格半径的火海（EXPLOSIVE_POWER 3 × power，封顶 15）
 *
 *   我们：起手蓄力 0.5 秒 → 1.6 格/tick 飞出去
 *         → 命中点炸开 **4 格**半径的火焰
 * </pre>
 * 🔴 **范围是故意砍下来的**（主人："你要调小开的范围啊，那招破坏力太恐怖了"）。
 * 15 格在单人档里等于半个村子没了；4 格既能清场又不至于把存档烧了。
 *
 * <p>不建投射物实体 —— 火球是**纯粒子 + 逐 tick 位移**，碰到生物或方块就炸。
 * 好处：不破坏地形（我们自己算范围伤害，不走原版的爆破碎方块），也不会出现"实体卡住"。
 */
public final class FireArrow {
    /** 起手蓄力（tick）。 */
    private static final int CHARGE = 10;
    /** 火球每 tick 前进多远。 */
    private static final double SPEED = 1.6D;
    /** 最多飞多久（tick）—— 26 格左右。 */
    private static final int FLIGHT = 24;
    /** 命中半径（火球擦到就算中）。 */
    private static final double HIT_RADIUS = 1.1D;
    /** 🔴 **爆炸半径** —— 原版 15，我们砍到 4。 */
    private static final double BLAST_RADIUS = 4.0D;
    /** 爆炸基础伤害（中心值，边缘递减）。 */
    private static final float BLAST_DAMAGE = 22.0F;
    /** 直接命中的额外伤害。 */
    private static final float DIRECT_DAMAGE = 12.0F;
    /** 点燃时长（秒）。 */
    private static final int FIRE_SECONDS = 8;

    private static final RandomSource RANDOM = RandomSource.create();

    /** 还在蓄力的玩家：剩余 tick + 威力系数。 */
    private static final Map<UUID, int[]> CHARGING = new ConcurrentHashMap<>();
    /** 已经飞出去的火球。 */
    private static final Map<UUID, List<Bolt>> FLYING = new ConcurrentHashMap<>();

    /** 一颗在飞的火球 —— record 不可变，所以每 tick 整体替换。 */
    private record Bolt(Vec3 pos, Vec3 step, int left) {
    }

    private FireArrow() {
    }

    /** 解锁判据：**适应火焰**（着火 / 火焰 / 岩浆任一满 100%）。 */
    public static boolean unlocked(Player player) {
        AdaptationData data = AdaptationManager.data(player);
        return FlameStrike.flameProgress(data) >= AdaptationManager.COMPLETE;
    }

    /** 适应到 200% 之后威力翻倍（伤害 ×1.5、半径 ×1.25）—— 练得越多越像原版那记"开"。 */
    private static float powerOf(Player player) {
        return FlameStrike.flameProgress(AdaptationManager.data(player)) >= 2.0F ? 1.5F : 1.0F;
    }

    /** 右键释放：开始蓄力。 */
    public static void cast(Player player) {
        if (player.level().isClientSide) return;

        CHARGING.put(player.getUUID(), new int[]{CHARGE});

        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 1.0F, 0.85F);
    }

    /** 每 tick 推进（由 {@code AdaptationManager} 调）。 */
    public static void tick(Player player) {
        if (player.level().isClientSide) return;

        tickCharge(player);
        tickBolts(player);
    }

    // ==================== 蓄力 ====================

    private static void tickCharge(Player player) {
        int[] state = CHARGING.get(player.getUUID());

        if (state == null) return;

        if (state[0]-- > 0) {
            // 火在手里聚起来
            if (player.level() instanceof ServerLevel level) {
                Vec3 at = player.getEyePosition().add(player.getLookAngle().scale(0.6D));

                level.sendParticles(ParticleTypes.FLAME, at.x, at.y, at.z, 3, 0.1D, 0.1D, 0.1D, 0.02D);
            }
            return;
        }

        CHARGING.remove(player.getUUID());
        launch(player);
    }

    private static void launch(Player player) {
        if (!(player.level() instanceof ServerLevel level)) return;

        Vec3 look = player.getLookAngle().normalize();
        Vec3 start = player.getEyePosition().add(look.scale(0.8D));

        FLYING.computeIfAbsent(player.getUUID(), k -> new ArrayList<>())
                .add(new Bolt(start, look.scale(SPEED), FLIGHT));

        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 1.2F, 0.8F);
    }

    // ==================== 火球飞行 ====================

    private static void tickBolts(Player player) {
        List<Bolt> bolts = FLYING.get(player.getUUID());

        if (bolts == null || bolts.isEmpty()) return;

        List<Bolt> keep = new ArrayList<>();

        for (Bolt bolt : bolts) {
            if (bolt.left() <= 0) {
                detonate(player, bolt.pos());
                continue;
            }

            Vec3 next = bolt.pos().add(bolt.step());

            // 撞到生物 → 直接算命中
            LivingEntity struck = firstVictim(player, bolt.pos(), next);

            if (struck != null) {
                struck.invulnerableTime = 0;
                struck.hurt(ModDamageTypes.spell(player), DIRECT_DAMAGE * powerOf(player));
                detonate(player, next);
                continue;
            }

            // 撞到方块 → 炸
            if (!player.level().getBlockState(net.minecraft.core.BlockPos.containing(next)).isAir()) {
                detonate(player, next);
                continue;
            }

            drawBolt(player, next);
            keep.add(new Bolt(next, bolt.step(), bolt.left() - 1));
        }

        FLYING.put(player.getUUID(), keep);
    }

    /** 这一步路径上有没有生物（用一个小包围盒扫）。 */
    private static LivingEntity firstVictim(Player owner, Vec3 from, Vec3 to) {
        for (LivingEntity victim : owner.level().getEntitiesOfClass(LivingEntity.class,
                new AABB(from, to).inflate(HIT_RADIUS))) {

            if (victim == owner || !victim.isAlive()) continue;
            if (victim.position().distanceToSqr(to) > (HIT_RADIUS + victim.getBbWidth()) * (HIT_RADIUS + victim.getBbWidth())) continue;

            return victim;
        }
        return null;
    }

    private static void drawBolt(Player player, Vec3 at) {
        if (!(player.level() instanceof ServerLevel level)) return;

        level.sendParticles(ParticleTypes.FLAME, at.x, at.y, at.z, 6, 0.12D, 0.12D, 0.12D, 0.02D);
        level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, at.x, at.y, at.z, 2, 0.1D, 0.1D, 0.1D, 0.01D);
        level.sendParticles(ParticleTypes.SMOKE, at.x, at.y, at.z, 2, 0.1D, 0.1D, 0.1D, 0.01D);
    }

    // ==================== 命中：火焰爆炸 ====================

    /**
     * 炸开 —— **半径 4 格的火焰**（原版 15，砍下来的）。
     *
     * <p>不用原版爆炸（那会挖掉地形、还要处理爆炸保护），自己算范围伤害：
     * 中心全额、边缘递减，全部点燃，外加一片火焰柱粒子。
     */
    private static void detonate(Player owner, Vec3 at) {
        if (!(owner.level() instanceof ServerLevel level)) return;

        double radius = BLAST_RADIUS * (powerOf(owner) > 1.0F ? 1.25D : 1.0D);
        float damage = BLAST_DAMAGE * powerOf(owner);

        for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class,
                new AABB(at, at).inflate(radius))) {

            if (victim == owner || !victim.isAlive()) continue;

            double distance = victim.position().distanceTo(at);
            double falloff = 1.0D - Math.min(1.0D, distance / radius);

            victim.invulnerableTime = 0;
            victim.hurt(ModDamageTypes.spell(owner), (float) (damage * (0.35D + 0.65D * falloff)));
            victim.igniteForSeconds((float) FIRE_SECONDS);

            // 从爆心往外推
            Vec3 push = victim.position().subtract(at).normalize().scale(0.9D * falloff);
            victim.push(push.x, 0.35D * falloff, push.z);
            victim.hurtMarked = true;
        }

        drawBlast(level, at, radius);

        level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE,
                SoundSource.PLAYERS, 1.6F, 0.75F);
    }

    /** 火焰柱 + 一圈冲击火环（原版那两根柱子的简化版）。 */
    private static void drawBlast(ServerLevel level, Vec3 at, double radius) {
        // 中心的爆炸
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y + 0.4D, at.z,
                1, 0.0D, 0.0D, 0.0D, 0.0D);

        // 火柱：在外圈随机位置往上舔
        for (int i = 0; i < 70; i++) {
            double angle = RANDOM.nextDouble() * Math.PI * 2.0D;
            double r = RANDOM.nextDouble() * radius;

            double x = at.x + Math.cos(angle) * r;
            double z = at.z + Math.sin(angle) * r;
            double y = at.y + RANDOM.nextDouble() * 2.2D;

            level.sendParticles(ParticleTypes.FLAME, x, y, z, 3, 0.1D, 0.35D, 0.1D, 0.03D);
            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, x, y, z, 1, 0.1D, 0.3D, 0.1D, 0.02D);
        }

        // 贴地的一圈火环
        for (int i = 0; i < 48; i++) {
            double angle = Math.PI * 2.0D * i / 48.0D;

            level.sendParticles(ParticleTypes.LAVA,
                    at.x + Math.cos(angle) * radius, at.y + 0.15D, at.z + Math.sin(angle) * radius,
                    1, 0.0D, 0.0D, 0.0D, 0.0D);
        }

        level.sendParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y + 0.5D, at.z,
                20, radius * 0.5D, 0.6D, radius * 0.5D, 0.02D);
    }

    /** 玩家下线 → 把蓄力和半路的火球都丢掉。 */
    public static void clear(UUID playerId) {
        CHARGING.remove(playerId);
        FLYING.remove(playerId);
    }

    /** 诊断用。 */
    public static int flamePercent(Player player) {
        return AdaptationManager.percent(
                FlameStrike.flameProgress(AdaptationManager.data(player)));
    }
}
