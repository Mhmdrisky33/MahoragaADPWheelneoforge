package dev.mahoraga.registry;

import com.mojang.serialization.Codec;
import dev.mahoraga.MahoragaMod;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModComponents {
    public static final DeferredRegister<DataComponentType<?>> COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, MahoragaMod.MODID);

    /** true = Blade of Exorcism sudah "patah" (sudah menyelamatkan dari kematian). */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> BROKEN =
            COMPONENTS.register("broken", () -> DataComponentType.<Boolean>builder()
                    .persistent(Codec.BOOL)
                    .networkSynchronized(ByteBufCodecs.BOOL)
                    .build());
}
