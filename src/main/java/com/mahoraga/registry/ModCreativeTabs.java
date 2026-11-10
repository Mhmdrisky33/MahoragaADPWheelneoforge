package com.mahoraga.registry;

import com.mahoraga.MahoragaMod;
import com.mahoraga.handler.BladeHandler;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/** 把两件物品塞进原版的创造模式物品栏，方便直接拿。 */
@EventBusSubscriber(modid = MahoragaMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class ModCreativeTabs {
    private ModCreativeTabs() {
    }

    @SubscribeEvent
    public static void onBuildContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.COMBAT) {
            // 用 newBlade() 而不是裸的 item —— 创造模式拿出来的也得自带亡灵杀手 V
            event.accept(BladeHandler.newBlade(event.getParameters().holders()));
        } else if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) {
            event.accept(ModItems.MAHORAGA_WHEEL.get());
        }
    }
}
