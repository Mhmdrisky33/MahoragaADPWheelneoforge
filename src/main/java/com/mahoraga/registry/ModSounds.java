package com.mahoraga.registry;

import com.mahoraga.MahoragaMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.neoforge.registries.DeferredHolder;

public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(BuiltInRegistries.SOUND_EVENT, MahoragaMod.MOD_ID);

    public static final DeferredHolder<SoundEvent, SoundEvent> WHEEL = SOUNDS.register("wheel",
            () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MahoragaMod.MOD_ID, "wheel")));

    /** 剑气挥出时的破空声（素材直接搬自原模组，同一个 .ogg）。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> SLASH = SOUNDS.register("slash",
            () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MahoragaMod.MOD_ID, "slash")));

    /**
     * <b>落刀声</b> —— 那套连斩收尾的那一下，又沉又脆。
     *
     * <p>素材搬自原模组（`jujutsu_kaisen/sounds/cleave.ogg`，GPL-3.0）。
     */
    public static final DeferredHolder<SoundEvent, SoundEvent> CLEAVE = SOUNDS.register("cleave",
            () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MahoragaMod.MOD_ID, "cleave")));

    private ModSounds() {
    }

    public static void register(IEventBus bus) {
        SOUNDS.register(bus);
    }
}
