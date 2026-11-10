package com.mahoraga.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.PostChain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 借出 {@code LevelRenderer} 的**实体轮廓后处理链**（`entityEffect`）。
 *
 * 黑暗透视要自己画彩色轮廓，画完得手动跑一次这个后处理才能合成到画面上
 * （原版那次跑的时机比我们早）。字段是私有的，只能用 accessor 借。
 *
 * 名字里的 `mahoraga$` 前缀是 Mixin 的惯例 —— 避免和别的模组撞名。
 */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {
    @Accessor(value = "entityEffect", remap = false)
    PostChain mahoraga$entityEffect();
}
