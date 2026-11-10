package com.mahoraga.mixin;

import com.mahoraga.adapt.LiquidWalk;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 站在液体表面上时 **不被水流推动**。
 *
 * 不然你站在水面上，脚下的水流还在把你往一个方向拽 ——
 * 表现就是"站上去走两步就被冲走"，很难受。
 * （原版炽足兽也是重写了这一条。）
 *
 * 同样只查 {@link LiquidWalk} 的缓存，不读 NBT。
 */
@Mixin(Entity.class)
public abstract class EntityFluidPushMixin {
    @Inject(method = "isPushedByFluid", at = @At("HEAD"), cancellable = true, remap = false)
    private void mahoraga$noFluidPush(CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof Player player)) return;
        // 只有在"确实正站在液体表面上"时才算数 —— 这个掩码本身就排除了
        // 潜行、旁观、以及"眼睛还泡在水里"的情况，所以游泳时照样会被水流推。
        if (LiquidWalk.currentMask(player) == 0) return;

        cir.setReturnValue(false);
    }
}
