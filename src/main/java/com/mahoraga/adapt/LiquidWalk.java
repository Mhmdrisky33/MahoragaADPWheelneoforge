package com.mahoraga.adapt;

import com.mahoraga.MahoragaMod;
import com.mahoraga.network.MahoragaNetwork;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「站在液体表面」—— 判据缓存 + 客户端同步 + **自动上浮**。
 *
 * ==================== 这套东西是照抄 Re:Zero 的「狮子之心」====================
 * 原实现（`hahueuh/Lakes.java` + 两个 mixin）里有两个必须抄的东西：
 *
 * **① `isEyeInFluid(WATER)` → 不算能站**
 * 眼睛泡在水里就说明"人在水下"，这时候**不能**把水当成实心方块。
 * 少了这一条，你在水底触发了适应，周围的水全变实心 → **卡在水下浮不起来**。
 *
 * **② 泡水时自动上浮（`RISE_SPEED`）**
 * 光有①只会让你"正常游泳"，还是浮不出水面。
 * 所以要每 tick 把垂直速度顶到 0.18，把你**托到水面**，
 * 出水之后 `canStandOnFluid` 才会生效，人自然就站上去了。
 *
 * ==================== 另外两块（人家自己加的，为了性能与不打架）====================
 * - **判据缓存**：`canStandOnFluid` 会被 `LiquidBlock#getCollisionShape` 每帧问很多次，
 *   在里面读 NBT 会把主线程拖垮（"很卡"的根因）→ 服务端每 tick 算一次存起来，mixin 只查表。
 * - **客户端同步**：客户端也要做同一套碰撞预测，而 PersistentData 不同步 →
 *   它以为你会掉进水里、每帧往下拽 → "走不动"。所以要发包告诉它。
 */
@EventBusSubscriber(modid = MahoragaMod.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class LiquidWalk {
    /** 水或岩浆任一条到这个进度就能站上去。 */
    public static final float THRESHOLD = 2.0F;

    /** 位掩码：适应了哪种液体。 */
    public static final int WATER = 1;
    public static final int LAVA = 2;

    /** 泡水时被托起的垂直速度（抄的狮子之心）。 */
    private static final double RISE_SPEED = 0.18D;

    /** 服务端实测的掩码。 */
    private static final Map<UUID, Integer> SERVER = new ConcurrentHashMap<>();
    /** 客户端收到的掩码（只对自己这个本地玩家有意义）。 */
    private static volatile int clientMask = 0;

    private LiquidWalk() {
    }

    // ==================== mixin 入口（必须 O(1)，绝不读 NBT）====================

    /** 玩家现在能站的液体掩码（0 = 站不了）。mixin 热路径专用，O(1)。 */
    public static int currentMask(Player player) {
        if (player.level().isClientSide) {
            return player.isLocalPlayer() ? clientMask : 0;
        }
        return SERVER.getOrDefault(player.getUUID(), 0);
    }

    /** 玩家能不能站**这一种**流体上。 */
    public static boolean matches(Player player, FluidState state) {
        if (state.isEmpty()) return false;

        int mask = currentMask(player);
        if (mask == 0) return false;

        if (state.is(FluidTags.WATER)) return (mask & WATER) != 0;
        if (state.is(FluidTags.LAVA)) return (mask & LAVA) != 0;

        return false;
    }

    // ==================== 每 tick 调一次 ====================

    /**
     * 服务端每 tick：先"托一把"，再更新缓存、变了就同步给客户端。
     *
     * ⚠️ 必须**每 tick** 调（不是每秒）—— 上浮是实时的手感，慢了就浮不上来。
     */
    public static void tick(Player player, AdaptationData data) {
        if (!(player instanceof ServerPlayer server)) return;

        int mask = unlockedMask(data);

        // ① 在水里 → 托到水面（狮子之心的 tickBuoyancy）
        if (mask != 0 && !player.isShiftKeyDown() && !player.isSpectator() && player.isInWater()) {
            Vec3 motion = player.getDeltaMovement();

            if (motion.y < RISE_SPEED) {
                player.setDeltaMovement(motion.x, RISE_SPEED, motion.z);
            }
            player.resetFallDistance();
        }

        // ② 缓存 + 同步
        int active = usableMask(player, mask);
        int had = SERVER.getOrDefault(player.getUUID(), 0);

        if (active == had) return;

        if (active == 0) {
            SERVER.remove(player.getUUID());
        } else {
            SERVER.put(player.getUUID(), active);
        }
        MahoragaNetwork.sendLiquidWalk(server, (byte) active);
    }

    /** 客户端收到同步值。 */
    public static void setClient(int mask) {
        clientMask = mask;
    }

    /**
     * 玩家下线时把记录清掉。
     * ⚠️ 不清的话同一个 UUID 再登进来会被当成"状态没变"→ 永远不再发包 → 重登后失效。
     */
    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        SERVER.remove(event.getEntity().getUUID());
    }

    // ==================== 判据 ====================

    /** 适应解锁了哪些液体（不管当前状态）。 */
    private static int unlockedMask(AdaptationData data) {
        int mask = 0;

        if (data.get(Track.DAILY, AdaptationKeys.DAILY_WATER) >= THRESHOLD) mask |= WATER;
        if (data.get(Track.DAILY, AdaptationKeys.DAILY_LAVA) >= THRESHOLD) mask |= LAVA;

        return mask;
    }

    /**
     * 当前**真正能用**的掩码 —— 除了解锁，还要求：
     * - 没在潜行（潜行 = 主动想下水）
     * - 不是旁观
     * - **眼睛没泡在水/岩浆里**（人在水下时不把液体当实心，否则浮不起来）
     */
    private static int usableMask(Player player, int unlocked) {
        if (unlocked == 0) return 0;
        if (player.isSpectator() || player.isShiftKeyDown()) return 0;

        int mask = unlocked;

        if (player.isEyeInFluid(FluidTags.WATER)) mask &= ~WATER;
        if (player.isEyeInFluid(FluidTags.LAVA)) mask &= ~LAVA;

        return mask;
    }
}
