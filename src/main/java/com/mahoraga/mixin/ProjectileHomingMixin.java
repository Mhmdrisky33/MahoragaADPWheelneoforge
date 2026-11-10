package com.mahoraga.mixin;

import com.mahoraga.skill.ProjectileHoming;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把「投掷物自动追踪」挂到**每一个投射物的 tick 尾巴**上。
 *
 * 为什么要 mixin：
 * - 投射物不是 `LivingEntity`，Forge 的 `LivingTickEvent` 收不到它；
 * - Re:Zero 那边的注释里写明 **`EntityJoinLevelEvent` 那条路一直没验证通**，
 *   所以他们（以及现在的我们）都用注入 `Projectile#tick` 的办法。
 *
 * ⚠️ 注入在 **TAIL**：等原版把这一 tick 的移动、碰撞、命中全算完，我们再掰方向 ——
 * 反过来（HEAD）会被原版本 tick 的重力和碰撞覆盖掉。
 *
 * ⚠️ 客户端也会跑这个方法，但 `ProjectileHoming.steer` 第一句就判 "不是 ServerLevel 就返回"，
 * 所以客户端完全不掺和（服务端改完速度靠 `hasImpulse` 同步过去）。
 *
 * 两个注入点分工：
 * - **HEAD** = {@code applySpeedBoost}：出手那一下按投掷适应放大初速（只生效一次）
 * - **TAIL** = {@code steer}：等原版把这一帧的移动算完，再把方向掰向目标
 */
@Mixin(Projectile.class)
public abstract class ProjectileHomingMixin {
    /** 上一步锁定的目标 id（-1 = 没锁）—— 存在实例上，跟着这发投射物走。 */
    @Unique
    private int mahoraga$homingTarget = -1;

    /** 上一 tick 的位置，用来判断"这一发还在飞吗"。 */
    @Unique
    private Vec3 mahoraga$lastPos;

    /**
     * **出手速度加成** —— 注入 **HEAD**：赶在原版跑第一帧的移动之前把初速放大。
     *
     * ⚠️ 必须 HEAD 不能 TAIL：TAIL 的时候这一帧已经按原速度飞出去了，
     * 表现会变成"飞了一小段才突然加速"，手感很怪。
     *
     * ⚠️ 只会生效一次（内部有标记），不是每 tick 都乘。
     */
    @Inject(method = "tick", at = @At("HEAD"), remap = false)
    private void mahoraga$speedBoost(CallbackInfo ci) {
        ProjectileHoming.applySpeedBoost((Projectile) (Object) this);
    }

    @Inject(method = "tick", at = @At("TAIL"), remap = false)
    private void mahoraga$homingTick(CallbackInfo ci) {
        Projectile self = (Projectile) (Object) this;

        Vec3 pos = self.position();
        boolean moving = mahoraga$lastPos == null
                || pos.distanceToSqr(mahoraga$lastPos) > 1.0E-6D;

        mahoraga$lastPos = pos;

        if (moving) {
            mahoraga$homingTarget = ProjectileHoming.steer(self, mahoraga$homingTarget);
        } else {
            // 停住了（插在地上 / 被抓住）→ 罢手并把重力还回去
            ProjectileHoming.release(self);
            mahoraga$homingTarget = -1;
        }
    }
}
