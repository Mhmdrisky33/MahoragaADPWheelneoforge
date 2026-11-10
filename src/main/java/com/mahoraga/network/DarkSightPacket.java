package com.mahoraga.network;

import com.mahoraga.MahoragaMod;
import com.mahoraga.client.ClientDarkSight;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.util.ArrayList;
import java.util.List;

public record DarkSightPacket(List<Integer> hostile, List<Integer> passive, List<Integer> players, List<Integer> elite) implements CustomPacketPayload {
    public static final Type<DarkSightPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MahoragaMod.MOD_ID, "dark_sight"));
    public static final StreamCodec<FriendlyByteBuf, DarkSightPacket> STREAM_CODEC = StreamCodec.of((buf, packet) -> packet.encode(buf), DarkSightPacket::decode);

    private static List<Integer> readIds(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > 65536) throw new IllegalArgumentException("Invalid dark sight entity count: " + count);
        List<Integer> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) ids.add(buf.readVarInt());
        return ids;
    }
    private static void writeIds(FriendlyByteBuf buf, List<Integer> ids) {
        buf.writeVarInt(ids.size());
        for (int id : ids) buf.writeVarInt(id);
    }
    private static DarkSightPacket decode(FriendlyByteBuf buf) {
        return new DarkSightPacket(readIds(buf), readIds(buf), readIds(buf), readIds(buf));
    }
    private void encode(FriendlyByteBuf buf) {
        writeIds(buf, hostile); writeIds(buf, passive); writeIds(buf, players); writeIds(buf, elite);
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public void handle(IPayloadContext context) {
        ClientDarkSight.set(hostile, passive, players, elite);
    }
}
