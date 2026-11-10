package com.mahoraga.network;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** NeoForge 1.21.1 custom-payload networking. */
public final class MahoragaNetwork {
    private MahoragaNetwork() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(LiquidWalkPacket.TYPE, LiquidWalkPacket.STREAM_CODEC, LiquidWalkPacket::handle);
        registrar.playToClient(JumpTierPacket.TYPE, JumpTierPacket.STREAM_CODEC, JumpTierPacket::handle);
        registrar.playToClient(MiningPowerPacket.TYPE, MiningPowerPacket.STREAM_CODEC, MiningPowerPacket::handle);
        registrar.playToClient(DarkSightPacket.TYPE, DarkSightPacket.STREAM_CODEC, DarkSightPacket::handle);
        registrar.playToClient(EatSpeedPacket.TYPE, EatSpeedPacket.STREAM_CODEC, EatSpeedPacket::handle);
        registrar.playToClient(AdaptationHudPacket.TYPE, AdaptationHudPacket.STREAM_CODEC, AdaptationHudPacket::handle);
        registrar.playToServer(SlashRequestPacket.TYPE, SlashRequestPacket.STREAM_CODEC, SlashRequestPacket::handle);
    }

    public static void sendLiquidWalk(ServerPlayer player, byte mask) { PacketDistributor.sendToPlayer(player, new LiquidWalkPacket(mask & 0xFF)); }
    public static void sendJumpTier(ServerPlayer player, int tiers) { PacketDistributor.sendToPlayer(player, new JumpTierPacket(tiers)); }
    public static void sendMiningPower(ServerPlayer player, int percent) { PacketDistributor.sendToPlayer(player, new MiningPowerPacket(percent)); }
    public static void sendDarkSight(ServerPlayer player, DarkSightPacket packet) { PacketDistributor.sendToPlayer(player, packet); }
    public static void sendEatSpeed(ServerPlayer player, int percent) { PacketDistributor.sendToPlayer(player, new EatSpeedPacket(percent)); }
    public static void sendSlashRequest() { PacketDistributor.sendToServer(new SlashRequestPacket()); }
    public static void sendHud(ServerPlayer player, AdaptationHudPacket packet) { PacketDistributor.sendToPlayer(player, packet); }
}
