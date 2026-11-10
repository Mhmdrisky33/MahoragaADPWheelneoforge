package com.mahoraga.network;

import com.mahoraga.MahoragaMod;
import com.mahoraga.skill.MiningAdaptation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record MiningPowerPacket(int percent) implements CustomPacketPayload {
    public static final Type<MiningPowerPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MahoragaMod.MOD_ID, "mining_power"));
    public static final StreamCodec<FriendlyByteBuf, MiningPowerPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> buf.writeVarInt(packet.percent),
            buf -> new MiningPowerPacket(buf.readVarInt()));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public void handle(IPayloadContext context) { MiningAdaptation.setClient(this.percent); }
}
