package com.mahoraga.skill;

import com.mahoraga.network.DarkSightPacket;
import com.mahoraga.network.MahoragaNetwork;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * **黑暗透视**（日常「身处黑暗」适应的奖励）—— 抄 Re:Zero 智慧之书的「生命之视」。
 *
 * ==================== 和以前有什么不一样 ====================
 *
 * 以前是给周围每只生物挂原版 `GLOWING`：
 * - 死白一个色，分不出敌友；
 * - 那是**实体自己的状态**，联机时全服可见 —— 别人也能看到你的透视；
 * - 每 tick 都要给一堆实体加效果。
 *
 * 现在：
 * - 服务端**按类别扫一遍**（敌对 / 被动 / 玩家 / 精英），算好名单发给本人；
 * - 客户端照着名单画**彩色穿墙轮廓**（{@code DarkSightRenderer}）；
 * - **只有本人看得见**，而且名单没变就不发包。
 *
 * ==================== 什么时候有 ====================
 *
 * 黑暗适应 ≥ **200%** 才开始有（100% 那档只给夜视，那是另一条，在
 * {@code AdaptationManager#applyDailyEffects} 里）。
 * 范围随进度线性放大：200% 时 8 格 → 1000% 时 40 格。
 *
 * 「精英」的判据和**特殊适应（解析线）**用的是同一个门槛 ——
 * 最大生命 ≥ 100，也就是铁傀儡、掠夺兽、凋灵、末影龙、监守者这一批。
 * 于是**金色轮廓 = 值得解析的目标**，扫一眼就知道该打谁。
 */
public final class DarkSight {
    private DarkSight() {
    }

    /** 解锁门槛（200%）。 */
    private static final float THRESHOLD = 2.0F;

    /** 门槛处的视野半径（格）。 */
    private static final double BASE_RANGE = 8.0D;

    /** 1000% 时的视野半径（封顶）。 */
    private static final double MAX_RANGE = 40.0D;

    /** 「精英」的血量门槛 —— 和解析线保持一致。 */
    private static final float ELITE_HP = 100.0F;

    private static final DarkSightPacket EMPTY =
            new DarkSightPacket(List.of(), List.of(), List.of(), List.of());

    /**
     * 上一次发出去的名单。**只在真的变了的时候才发包** ——
     * 生物站着不动的时候，一秒钟一个空包也没必要。
     */
    private static final Map<UUID, DarkSightPacket> LAST_SENT = new ConcurrentHashMap<>();

    /**
     * 每秒调一次（跟着 {@code applyDailyEffects} 走）。
     *
     * @param progress 黑暗适应的进度（1.0 = 100%）
     */
    public static void update(ServerPlayer player, float progress) {
        DarkSightPacket packet = progress < THRESHOLD ? EMPTY : scan(player, progress);
        UUID id = player.getUUID();

        if (packet.equals(LAST_SENT.get(id))) return;

        LAST_SENT.put(id, packet);
        MahoragaNetwork.sendDarkSight(player, packet);
    }

    private static DarkSightPacket scan(ServerPlayer player, float progress) {
        double range = Math.min(MAX_RANGE, BASE_RANGE * (progress / THRESHOLD));
        AABB box = player.getBoundingBox().inflate(range);

        List<Integer> hostile = new ArrayList<>();
        List<Integer> passive = new ArrayList<>();
        List<Integer> players = new ArrayList<>();
        List<Integer> elite = new ArrayList<>();

        for (LivingEntity other : player.level().getEntitiesOfClass(LivingEntity.class, box,
                e -> e != player && e.isAlive())) {

            // 血厚的先归类到"精英" —— 它同时可能还是敌对，但金色更值得看见
            if (other.getMaxHealth() >= ELITE_HP) {
                elite.add(other.getId());
            } else if (other instanceof Player) {
                players.add(other.getId());
            } else if (other instanceof Enemy) {
                hostile.add(other.getId());
            } else {
                passive.add(other.getId());
            }
        }

        return new DarkSightPacket(hostile, passive, players, elite);
    }

    /** 掉到 200% 以下 / 玩家下线时把缓存丢掉（不然 UUID 会一直堆着）。 */
    public static void forget(UUID player) {
        LAST_SENT.remove(player);
    }

    /** 现在能看多远（格）—— 0 表示没解锁。给 HUD / 命令描述用。 */
    public static double rangeOf(float progress) {
        if (progress < THRESHOLD) return 0.0D;

        return Math.min(MAX_RANGE, BASE_RANGE * (progress / THRESHOLD));
    }
}
