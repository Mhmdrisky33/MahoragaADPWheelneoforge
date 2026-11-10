package com.mahoraga.skill;

import com.mahoraga.adapt.AdaptationData;
import com.mahoraga.adapt.AdaptationKeys;
import com.mahoraga.adapt.AdaptationManager;
import com.mahoraga.adapt.Track;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * <b>满象</b> —— 十种影法术里那头会喷水的式神，抄自原模组
 * {@code ten_shadows/ability/PiercingWater}（它本体是个 32 格射程的蓝色水柱光束）。
 *
 * <p><b>怎么解锁</b>（主人 2026-09-15 定）：练会了"水"这件事就抄会了它 ——
 * <pre>
 *   ▸ 溺水（{@code damage:minecraft:drown}）防御适应满 100%
 *   ▸ 或者 浸泡水中（{@code daily:water}）日常适应满 100%
 * </pre>
 * 两条任一条满就行：被水淹过和一直泡在水里，学会的是同一件事。
 * 解锁后挂到**退魔之剑右键**那一套（见 {@code BladeAbilities}）。
 *
 * <h3>实现取舍</h3>
 * 原版是"生成一个光束实体、每 tick 推进"；我们这边**不建实体**，
 * 直接一次性做一条射线：撞墙就停在墙前，沿途的生物吃伤害 + 被推 + 身上的火被浇灭，
 * 全程撒水花。这样做的好处是零注册、零 tick 开销，而且不会有"实体卡在半路"的怪事。
 */
public final class WaterJet {
    /** 射程（格）—— 原版是 32，我们地形更碎，26 够用。 */
    private static final double RANGE = 26.0D;
    /** 命中判定半径（从射线到生物中心）。 */
    private static final double HIT_RADIUS = 1.3D;
    /** 单次伤害 —— 抄原版 {@code PiercingWaterEntity#getDamage}（11）。 */
    private static final float DAMAGE = 11.0F;
    /** 推力。 */
    private static final double KNOCKBACK = 0.55D;

    private WaterJet() {
    }

    /** 解锁判据：溺水 **或** 泡水，任一条满 100%。 */
    public static boolean unlocked(Player player) {
        AdaptationData data = AdaptationManager.data(player);

        return data.get(Track.DEFENSE, AdaptationKeys.DROWN) >= AdaptationManager.COMPLETE
                || data.get(Track.DAILY, AdaptationKeys.DAILY_WATER) >= AdaptationManager.COMPLETE;
    }

    /** 真正喷出去（由 {@code BladeAbilities} 在右键时调）。 */
    public static void cast(Player player) {
        if (player.level().isClientSide) return;
        if (!(player.level() instanceof ServerLevel level)) return;

        player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);

        Vec3 from = player.getEyePosition();
        Vec3 look = player.getLookAngle().normalize();
        Vec3 reach = from.add(look.scale(RANGE));

        // 撞到方块就停在墙前（不然水会穿过山）
        BlockHitResult blocked = level.clip(new ClipContext(from, reach,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));

        Vec3 end = blocked.getType() == HitResult.Type.MISS ? reach : blocked.getLocation();

        // ---- 伤害：先框出这一条线的包围盒，再逐个判"离射线够不够近" ----
        for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class,
                new AABB(from, end).inflate(HIT_RADIUS + 1.0D))) {

            if (victim == player || !victim.isAlive()) continue;

            Vec3 center = victim.position().add(0.0D, victim.getBbHeight() * 0.5D, 0.0D);

            if (distanceToSegment(from, end, center) > HIT_RADIUS + victim.getBbWidth() * 0.5D) continue;

            // 水冲过来是"推"不是"打飞"，所以只给一点点
            Vec3 push = look.scale(KNOCKBACK);
            victim.push(push.x, 0.18D, push.z);
            victim.hurtMarked = true;

            victim.invulnerableTime = 0;
            // 用我们的"附加伤害"类型：认主（算玩家击杀、怪会记仇），而且穿甲
            victim.hurt(com.mahoraga.registry.ModDamageTypes.spell(player), DAMAGE);

            // 水能灭火 —— 顺手把目标身上的火浇了
            victim.clearFire();
        }

        // ---- 表现：一道水柱 + 末端炸开的水花 ----
        drawJet(level, from, end);

        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.PLAYER_SPLASH, SoundSource.PLAYERS, 1.2F, 0.9F);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BUCKET_EMPTY, SoundSource.PLAYERS, 0.9F, 1.1F);
    }

    /** 沿射线撒水。 */
    private static void drawJet(ServerLevel level, Vec3 from, Vec3 to) {
        Vec3 dir = to.subtract(from);
        double length = dir.length();

        if (length < 0.01D) return;

        Vec3 step = dir.normalize().scale(0.55D);

        for (double travelled = 0.0D; travelled < length; travelled += 0.55D) {
            Vec3 at = from.add(step.scale(travelled / 0.55D));

            level.sendParticles(ParticleTypes.FALLING_WATER, at.x, at.y, at.z,
                    2, 0.1D, 0.1D, 0.1D, 0.0D);
        }

        // 落点：哗地炸开一朵
        level.sendParticles(ParticleTypes.SPLASH, to.x, to.y, to.z,
                40, 0.7D, 0.35D, 0.7D, 0.2D);
        level.sendParticles(ParticleTypes.CLOUD, to.x, to.y, to.z,
                6, 0.4D, 0.2D, 0.4D, 0.02D);
    }

    /** 点到**线段**的距离（射线射程外的不算"贴着线"）。 */
    private static double distanceToSegment(Vec3 from, Vec3 to, Vec3 point) {
        Vec3 segment = to.subtract(from);
        double lengthSqr = segment.lengthSqr();

        if (lengthSqr < 1.0E-6D) return point.distanceTo(from);

        double t = point.subtract(from).dot(segment) / lengthSqr;
        t = Math.max(0.0D, Math.min(1.0D, t));

        return point.distanceTo(from.add(segment.scale(t)));
    }
}
