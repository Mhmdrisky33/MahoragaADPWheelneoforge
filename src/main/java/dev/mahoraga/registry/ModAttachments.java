package dev.mahoraga.registry;

import dev.mahoraga.MahoragaMod;
import dev.mahoraga.data.AdaptData;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public class ModAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, MahoragaMod.MODID);

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<AdaptData>> ADAPT =
            ATTACHMENTS.register("adapt", () -> AttachmentType
                    .builder(() -> new AdaptData())
                    .serialize(AdaptData.CODEC)
                    .copyOnDeath()
                    .build());
}
