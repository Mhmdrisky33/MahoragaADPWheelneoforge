package com.mahoraga.skill;

import com.mahoraga.adapt.AdaptationKeys;
import com.mahoraga.adapt.AdaptationManager;
import com.mahoraga.adapt.Track;
import com.mahoraga.network.MahoragaNetwork;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 日常轨的「**挖掘适应**」—— 让退魔之剑从一把剑变成万能工具。
 *
 * ==================== 两段奖励（主人 2026-09-14 定） ====================
 *
 * - **挖到 100%**：可以**秒破除基岩之外的任何方块**。
 *   （基岩不用特判 —— 它的硬度是 `-1`，原版 `getDestroyProgress` 直接返回 0，谁也挖不动。）
 * - **100% 以上**：每多 100% 加一级**时运**，挖矿掉落翻着往上涨（封顶 IX）。
 *
 * ==================== 🔴 为什么要搞同步 ====================
 *
 * "能不能秒破"是**客户端**说了算的 —— 破坏计时器跑在客户端，它不知道服务端的适应进度，
 * 就会一直按普通速度慢慢挖（服务端再快也没用）。
 * 所以跟多段跳一样：服务端每秒把进度**只发给本人**（{@link com.mahoraga.network.MiningPowerPacket}），
 * 客户端存进这里的静态字段，挖矿那一刻直接读缓存。
 */
public final class MiningAdaptation {
    private MiningAdaptation() {
    }

    /** 「秒破」的速度 —— 石头(1.5)一帧过，黑曜石(50)两 tick，远古残骸(30)一帧。 */
    public static final float INSTANT_SPEED = 1000.0F;

    /** **万能工具**的基础挖掘速度 —— 比钻石镐（8）还快一点，石头木头泥土全能挖。 */
    public static final float UNIVERSAL_SPEED = 9.0F;

    /** 时运等级上限。 */
    private static final int MAX_FORTUNE = 9;

    /** 客户端缓存的挖掘适应百分比（服务端同步过来的）。 */
    private static volatile int clientPower;

    /** 上一次发给这个客户端的值 —— 没变就不发包。 */
    private static final Map<UUID, Integer> LAST_SENT = new ConcurrentHashMap<>();

    public static void setClient(int percent) {
        clientPower = percent;
    }

    public static int clientPower() {
        return clientPower;
    }

    /**
     * 挖掘适应百分比 —— **客户端读缓存，服务端读存档**。
     * （客户端的 PersistentData 拿不到适应进度，这是老坑了。）
     */
    public static int power(Player player) {
        if (player.level().isClientSide) return clientPower;

        return AdaptationManager.percent(
                AdaptationManager.data(player).get(Track.DAILY, AdaptationKeys.DAILY_MINING));
    }

    /** 够不够"秒破"（≥100%）。 */
    public static boolean isInstant(Player player) {
        return power(player) >= 100;
    }

    /** 时运等级：100% 时 0 级（只有秒破），之后**每 +100% 加一级**。 */
    public static int fortune(Player player) {
        int pct = power(player);
        return pct < 100 ? 0 : Math.min(MAX_FORTUNE, pct / 100 - 1);
    }

    /** 服务端每秒同步一次（值没变就不发）。 */
    public static void sync(ServerPlayer player) {
        int pct = AdaptationManager.percent(
                AdaptationManager.data(player).get(Track.DAILY, AdaptationKeys.DAILY_MINING));

        Integer last = LAST_SENT.put(player.getUUID(), pct);
        if (last != null && last == pct) return;

        MahoragaNetwork.sendMiningPower(player, pct);
    }

    public static void clear(Player player) {
        LAST_SENT.remove(player.getUUID());
    }
}
