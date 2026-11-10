package com.mahoraga.network;

import com.mahoraga.MahoragaMod;
import com.mahoraga.skill.EatAdaptation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record EatSpeedPacket(int percent) implements CustomPacketPayload {
    public static final Type<EatSpeedPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MahoragaMod.MOD_ID, "eat_speed"));
    public static final StreamCodec<FriendlyByteBuf, EatSpeedPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> buf.writeVarInt(packet.percent),
            buf -> new EatSpeedPacket(buf.readVarInt()));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public void handle(IPayloadContext context) { EatAdaptation.setClientPercent(this.percent); }
}
