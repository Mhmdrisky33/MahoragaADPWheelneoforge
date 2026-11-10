package com.mahoraga.network;

import com.mahoraga.MahoragaMod;
import com.mahoraga.adapt.LiquidWalk;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record LiquidWalkPacket(int mask) implements CustomPacketPayload {
    public static final Type<LiquidWalkPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MahoragaMod.MOD_ID, "liquid_walk"));
    public static final StreamCodec<FriendlyByteBuf, LiquidWalkPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> buf.writeByte(packet.mask),
            buf -> new LiquidWalkPacket(buf.readUnsignedByte()));

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public void handle(IPayloadContext context) { LiquidWalk.setClient(this.mask); }
}
