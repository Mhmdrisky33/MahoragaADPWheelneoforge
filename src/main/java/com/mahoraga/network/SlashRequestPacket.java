package com.mahoraga.network;

import com.mahoraga.MahoragaMod;
import com.mahoraga.skill.SignatureAttack;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.minecraft.server.level.ServerPlayer;

public record SlashRequestPacket() implements CustomPacketPayload {
    public static final Type<SlashRequestPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MahoragaMod.MOD_ID, "slash_request"));
    public static final StreamCodec<FriendlyByteBuf, SlashRequestPacket> STREAM_CODEC = StreamCodec.unit(new SlashRequestPacket());
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public void handle(IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) SignatureAttack.trySwingSlash(player);
    }
}
