package com.mahoraga.client;

import com.mahoraga.MahoragaMod;
import com.mahoraga.mixin.LevelRendererAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * **手动催一次"实体轮廓"后处理**。
 *
 * 原版流程是：把发光实体画进离屏缓冲 → 跑 `entity_outline` 后处理合成到主画面。
 * 但原版那次 process 发生在**我们画轮廓之前**，等于白画 —— 画完没人合成，屏幕上啥也没有。
 *
 * 所以这里在 {@code AFTER_BLOCK_ENTITIES}（也就是我们画完之后）再 process 一次。
 * `entityEffect` 是 `LevelRenderer` 的私有字段，靠 {@link LevelRendererAccessor} 拿。
 *
 * `request()` / `pending` 那一对是为了**只在真的要画的时候**才跑，
 * 平时（没有透视）一帧的开销就是一次 boolean 判断。
 */
@EventBusSubscriber(modid = MahoragaMod.MOD_ID, value = Dist.CLIENT)
public final class OutlineEffectSupport {
    private OutlineEffectSupport() {
    }

    private static boolean pending;

    /** 由 {@link DarkSightRenderer} 在画轮廓之前调一次。 */
    public static void request() {
        pending = true;
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;
        if (!pending) return;

        pending = false;

        Minecraft mc = Minecraft.getInstance();
        if (!mc.levelRenderer.shouldShowEntityOutlines()) return;

        PostChain effect = ((LevelRendererAccessor) mc.levelRenderer).mahoraga$entityEffect();
        if (effect == null) return;

        effect.process(event.getPartialTick().getGameTimeDeltaPartialTick(false));
        mc.getMainRenderTarget().bindWrite(false);
    }
}
