package com.mahoraga.skill;

import com.mahoraga.MahoragaMod;
import com.mahoraga.adapt.AdaptationData;
import com.mahoraga.adapt.AdaptationKeys;
import com.mahoraga.adapt.Track;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

/**
 * 投掷物适应带来的"挥手弹开 / 反弹"。
 *
 * 分档（按该投掷物种类的适应百分比）：
 * - **100%**：被击中时自动挥手把投掷物弹开（挨打照样算，适应继续叠）；
 * - **200%**：弹开改成**反弹** —— 直接飞回原主人身上；
 * - **之后每 +100%**：法轮转一圈，反弹的**速度**跟着涨一档（箭的伤害跟速度挂钩，所以伤害一起涨），
 *   1000% 封顶。
 *
 * 实现方式：**在命中点造一发"复制品"按新方向射出去**。
 * 为什么不直接改原投掷物的速度 —— 原版的箭 / 雪球在命中回调里会 `discard()` 自己，
 * 事件回调跑完它就被删了，改速度等于白改。
 */
public final class ProjectileCounter {
    /** 打上这个标记的投掷物不再被弹（防止"弹出去的又弹回来"无限套娃）。 */
    private static final String REFLECTED_TAG = "mahoraga_reflected";

    /** 反弹基础速度（格 / tick）。原版箭大约 3。 */
    private static final double BASE_SPEED = 1.5D;
    /** 每高一档加多少速度。 */
    private static final double SPEED_PER_TIER = 0.5D;
    /** 复制品生成点离命中点多远（避免刚生成就砸在自己身上）。 */
    private static final double SPAWN_OFFSET = 1.5D;

    private ProjectileCounter() {
    }

    /** 100% 一档：0 = 还没到 100%，1 = 100~199%，…，10 = 1000%。 */
    public static int tier(float value) {
        int pct = (int) Math.floor(value * 100.0F);
        return Math.max(0, Math.min(10, pct / 100));
    }

    /** 在挨打事件里调用：够档就弹。 */
    public static void tryDeflect(Player player, DamageSource source, AdaptationData data, String key) {
        Entity direct = source.getDirectEntity();

        if (direct == null || direct == player) return;
        if (!AdaptationKeys.isProjectile(source, direct, player)) return;
        if (direct.getPersistentData().getBoolean(REFLECTED_TAG)) return;
        if (!(direct.level() instanceof ServerLevel level)) return;

        int tier = tier(data.get(Track.DEFENSE, key));
        if (tier <= 0) return;

        deflect(level, player, direct, tier);
    }

    private static void deflect(ServerLevel level, Player player, Entity projectile, int tier) {
        Entity copy = projectile.getType().create(level);
        if (copy == null) return;

        Vec3 from = projectile.position();
        boolean reflect = tier >= 2;                 // 200% 起才是"飞回原主人"
        Vec3 direction;

        if (reflect) {
            Entity shotOwner = projectile instanceof Projectile shot ? shot.getOwner() : null;
            LivingEntity shooter = shotOwner instanceof LivingEntity owner && owner != player
                    ? owner : null;

            if (shooter != null && !shooter.isRemoved()) {
                Vec3 aim = shooter.getEyePosition().subtract(from);
                direction = aim.lengthSqr() < 1.0E-4D ? outward(player, from) : aim.normalize();
            } else {
                direction = outward(player, from);
            }
        } else {
            direction = outward(player, from).add(0.0D, 0.3D, 0.0D).normalize();
        }

        double speed = BASE_SPEED + SPEED_PER_TIER * Math.max(0, tier - 2);

        copy.moveTo(from.x + direction.x * SPAWN_OFFSET,
                from.y + direction.y * SPAWN_OFFSET,
                from.z + direction.z * SPAWN_OFFSET);

        if (copy instanceof Projectile shot) {
            shot.setOwner(player);
        }
        if (copy instanceof AbstractArrow arrow) {
            arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        }

        copy.setDeltaMovement(direction.scale(speed));
        copy.hasImpulse = true;
        copy.getPersistentData().putBoolean(REFLECTED_TAG, true);

        level.addFreshEntity(copy);

        // ★ 必须把**原来那一发**删掉。
        //   原版箭在"打中一个打不动的目标"时**不会**自动清除自己（免疫时 hurt() 返回 false，
        //   那一段丢弃逻辑就跳过了）→ 不删的话画面上会同时飞着两发：
        //   一发是我们弹出去的，一发是骷髅原来射的。
        if (!projectile.isRemoved()) {
            projectile.discard();
        }

        // 挥手 + 一声金铁交鸣，让主人看得见听得见
        player.swing(InteractionHand.MAIN_HAND, true);
        level.playSound(null, player.blockPosition(), SoundEvents.SHIELD_BLOCK, SoundSource.PLAYERS,
                0.9F, reflect ? 0.8F : 1.4F);
    }

    /** 从玩家指向命中点的方向（兜底用视线）。 */
    private static Vec3 outward(Player player, Vec3 from) {
        Vec3 dir = from.subtract(player.position());
        return dir.lengthSqr() < 1.0E-4D ? player.getLookAngle() : dir.normalize();
    }
}
