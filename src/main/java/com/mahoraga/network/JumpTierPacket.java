package com.mahoraga.network;

import com.mahoraga.MahoragaMod;
import com.mahoraga.adapt.AirJump;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record JumpTierPacket(int tiers) implements CustomPacketPayload {
    public static final Type<JumpTierPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MahoragaMod.MOD_ID, "jump_tier"));
    public static final StreamCodec<FriendlyByteBuf, JumpTierPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> buf.writeVarInt(packet.tiers),
            buf -> new JumpTierPacket(buf.readVarInt()));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public void handle(IPayloadContext context) { AirJump.setClient(this.tiers); }
}
