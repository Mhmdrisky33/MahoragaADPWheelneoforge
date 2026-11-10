package dev.mahoraga;

import dev.mahoraga.command.MahoragaCommand;
import dev.mahoraga.event.ModEvents;
import dev.mahoraga.registry.ModAttachments;
import dev.mahoraga.registry.ModComponents;
import dev.mahoraga.registry.ModItems;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

@Mod(MahoragaMod.MODID)
public class MahoragaMod {
    public static final String MODID = "mahoraga";

    public MahoragaMod(IEventBus modBus) {
        ModItems.ITEMS.register(modBus);
        ModComponents.COMPONENTS.register(modBus);
        ModAttachments.ATTACHMENTS.register(modBus);
        modBus.addListener(ModEvents::addCreative);

        NeoForge.EVENT_BUS.addListener(ModEvents::onIncomingDamage);
        NeoForge.EVENT_BUS.addListener(ModEvents::onDeath);
        NeoForge.EVENT_BUS.addListener(MahoragaCommand::register);
    }
}
