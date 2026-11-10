package com.mahoraga.client;

import com.mahoraga.MahoragaMod;
import com.mahoraga.client.render.SlashRenderer;
import com.mahoraga.client.render.WheelRenderer;
import com.mahoraga.client.render.WorldSlashRenderer;
import com.mahoraga.registry.ModEntities;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.common.EventBusSubscriber;

@EventBusSubscriber(modid = MahoragaMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class MahoragaClientEvents {
    private MahoragaClientEvents() {
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.WHEEL.get(), WheelRenderer::new);
        event.registerEntityRenderer(ModEntities.SLASH.get(), SlashRenderer::new);
        event.registerEntityRenderer(ModEntities.WORLD_SLASH.get(), WorldSlashRenderer::new);
    }

    /**
     * **左上角的法轮 HUD**（法轮图标 + 正在适应的项目列表 + 完成时转 360 度）。
     *
     * `registerAboveAll` = 画在原版 HUD 全部内容**之上**（不会被经验条 / 聊天栏压住）。
     */
    @SubscribeEvent
    public static void onRegisterOverlays(RegisterGuiLayersEvent event) {
        event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(MahoragaMod.MOD_ID, "mahoraga_wheel_hud"),
                (gui, graphics, partialTick, screenWidth, screenHeight) -> WheelHud.render(graphics));
    }
}
