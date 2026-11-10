package com.mahoraga.network;

import com.mahoraga.MahoragaMod;
import com.mahoraga.client.WheelHud;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.util.ArrayList;
import java.util.List;

public record AdaptationHudPacket(boolean wheel, List<Entry> entries, int spinSeq) implements CustomPacketPayload {
    public record Entry(String key, int track, int percent) {}
    public static final Type<AdaptationHudPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MahoragaMod.MOD_ID, "adaptation_hud"));
    public static final StreamCodec<FriendlyByteBuf, AdaptationHudPacket> STREAM_CODEC = StreamCodec.of((buf, packet) -> packet.encode(buf), AdaptationHudPacket::decode);

    private static AdaptationHudPacket decode(FriendlyByteBuf buf) {
        boolean wheel = buf.readBoolean();
        int size = buf.readVarInt();
        if (size < 0 || size > 128) throw new IllegalArgumentException("Invalid HUD entry count: " + size);
        List<Entry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) entries.add(new Entry(buf.readUtf(256), buf.readByte(), buf.readVarInt()));
        return new AdaptationHudPacket(wheel, entries, buf.readVarInt());
    }
    private void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(wheel); buf.writeVarInt(entries.size());
        for (Entry entry : entries) { buf.writeUtf(entry.key(), 256); buf.writeByte(entry.track()); buf.writeVarInt(entry.percent()); }
        buf.writeVarInt(spinSeq);
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public void handle(IPayloadContext context) { WheelHud.accept(this); }
}
