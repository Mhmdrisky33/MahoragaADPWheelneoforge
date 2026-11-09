package com.mahoraga.adapt;

/**
 * 二段跳 / 多段跳 —— 由**持续奔跑**适应解锁。
 *
 * ==================== 规则（主人 2026-09-14 定）====================
 * 每多适应 100%，空中就多允许跳一次：
 *
 * | 持续奔跑适应 | 能跳几段 |
 * |---|---|
 * | < 200% | 1（原版） |
 * | 200% | 2 |
 * | 300% | 3 |
 * | … | … |
 * | 1000% | 10（封顶） |
 *
 * ==================== 为什么要这个类 ====================
 * 适应进度**存在服务端的存档里，客户端读不到**（和"站液体表面"是同一个坑）。
 * 所以服务端每秒把"能跳几段"这个数字通过 {@link com.mahoraga.network.JumpTierPacket}
 * 同步给客户端，客户端存进 {@link #clientTiers}；玩家在空中按跳跃键时，
 * 客户端 {@code client/AirJumpInput} 直接读这个缓存判断还能不能跳。
 *
 * （这个类必须是**两边都能加载**的普通类 —— 网络包在服务端也会被加载，
 *   所以缓存字段不能放进纯客户端的类里。）
 */
public final class AirJump {
    /** 至少要和它一样多才算"解锁了多段跳"。 */
    public static final int MIN_TIERS = 2;
    /** 段数上限（1000% → 10 段）。 */
    public static final int MAX_TIERS = 10;

    /** 客户端缓存：服务端同步过来的段数（0 = 没解锁）。 */
    private static volatile int clientTiers = 0;

    private AirJump() {
    }

    /**
     * 由**持续奔跑**的进度算出能跳几段。
     *
     * @param progress 0.0 ~ 10.0（也就是 0% ~ 1000%）
     * @return 总段数 —— **至少 2**：拿到法轮就自带二段跳（主人 2026-09-14 定的基础奖励）
     */
    public static int tiersFor(float progress) {
        // 底子：有法轮就是二段跳，什么都不用练
        int tiers = MIN_TIERS;

        if (progress >= 2.0F) {
            // 持续奔跑每多 100%，在底子上**再往上加一段**：200% → 3 段，300% → 4 段…
            tiers = Math.max(tiers, (int) (progress + 1.0E-4F) + 1);
        }

        return Math.min(MAX_TIERS, tiers);
    }

    /** 服务端同步用。 */
    public static void setClient(int tiers) {
        clientTiers = tiers;
    }

    /** 客户端读缓存 —— 0 表示没解锁。 */
    public static int clientTiers() {
        return clientTiers;
    }
}
