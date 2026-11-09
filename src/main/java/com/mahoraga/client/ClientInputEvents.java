package com.mahoraga.client;

import com.mahoraga.MahoragaMod;
import com.mahoraga.network.MahoragaNetwork;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * 客户端输入 → 告诉服务端"我挥空了"。
 *
 * 服务端的 {@code AttackEntityEvent} 只有打中实体才会触发，
 * 而剑气是**防风筝**用的 —— 敌人离得远、你打不着，挥空也得能甩出去。
 * 挥空这件事服务端看不到，只能客户端说一声。
 *
 * 挖方块不会走到这里（那是 {@code LeftClickBlock} 那条路），所以不会一边挖矿一边甩剑气。
 */
@EventBusSubscriber(modid = MahoragaMod.MOD_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class ClientInputEvents {
    private ClientInputEvents() {
    }

    @SubscribeEvent
    public static void onLeftClickEmpty(PlayerInteractEvent.LeftClickEmpty event) {
        MahoragaNetwork.sendSlashRequest();
    }

    /**
     * 退出世界 / 断线 → 把左上角的法轮 HUD 清掉。
     *
     * 不清的话，下次进的服务器要是没在 1 秒内推新数据，左上角会挂着**上一局**的列表。
     */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        WheelHud.reset();
    }
}
