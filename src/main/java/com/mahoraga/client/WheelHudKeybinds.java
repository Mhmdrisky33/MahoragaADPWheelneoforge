package com.mahoraga.client;

import com.mahoraga.MahoragaMod;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = MahoragaMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class WheelHudKeybinds {
    public static final KeyMapping OPEN_SETTINGS = new KeyMapping(
            "key.mahoraga.hud_settings", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O,
            "key.categories.misc");

    private WheelHudKeybinds() {}

    @SubscribeEvent
    public static void register(RegisterKeyMappingsEvent event) {
        event.register(OPEN_SETTINGS);
    }
}
