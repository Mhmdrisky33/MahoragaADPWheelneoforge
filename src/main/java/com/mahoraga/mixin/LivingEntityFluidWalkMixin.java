package com.mahoraga.mixin;

import com.mahoraga.adapt.LiquidWalk;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 水和岩浆任一适应到 200% → 能站在液体表面。
 *
 * 原版里「炽足兽」踩岩浆用的就是这个方法（Strider 也只覆盖了它），所以照抄同一条路。
 *
 * ⚠️ **能不能站这一种液体，由 {@link LiquidWalk#matches} 说了算** ——
 * 它还会管"眼睛泡在水里就不算能站"，不然后果是人在水底时周围的水全变实心、
 * **卡在水下浮不起来**（狮子之心踩过的坑）。
 *
 * ⚠️ **这里绝对不能读 NBT。** 这个方法会被 {@code LiquidBlock#getCollisionShape}
 * 在碰撞检测里每帧问很多次 —— 只查缓存好的位掩码，O(1) 且零分配。
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityFluidWalkMixin {
    @Inject(method = "canStandOnFluid", at = @At("HEAD"), cancellable = true)
    private void mahoraga$walkOnLiquid(FluidState state, CallbackInfoReturnable<Boolean> cir) {
        if (state.isEmpty()) return;

        Entity self = (Entity) (Object) this;
        if (!(self instanceof Player player)) return;
        if (!LiquidWalk.matches(player, state)) return;

        cir.setReturnValue(true);
    }
}
