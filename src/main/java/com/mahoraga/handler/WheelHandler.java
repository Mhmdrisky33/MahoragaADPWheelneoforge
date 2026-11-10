package com.mahoraga.handler;

import com.mahoraga.MahoragaMod;
import com.mahoraga.adapt.AdaptationManager;
import com.mahoraga.entity.WheelEntity;
import com.mahoraga.registry.ModItems;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.entries.EmptyLootItem;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.functions.SetItemCountFunction;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;
import net.neoforged.neoforge.event.LootTableLoadEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 让「永久法轮」始终挂在玩家头顶。
 *
 * 真正的标记存在玩家自己的存档里（PersistentData，随角色存档存读），
 * 这里另外维护一份运行期的 UUID 集合 —— 好处是**死亡重生期间**也认得出"这个人有法轮"，
 * 不会因为玩家实体被临时替换就以为要失去法轮。
 *
 * 修复记录：以前只负责"没找到就新生成"，遇到玩家死亡（旧实体被移除、法轮卡在
 * "骑着一个不存在的玩家"的状态）时会留在死亡点。现在改成**找到就拉回来重骑**，
 * 只有真的找不到才新生成。
 */
public final class WheelHandler {
    private static final String TAG = "mahoraga_wheel";
    private static final int CHECK_INTERVAL = 10;
    /** 生成冷却 1 秒，防止来回刷。 */
    private static final int RESPAWN_COOLDOWN = 20;
    /** 检查半径。法轮是贴身的，16 格足够。 */
    private static final double SEARCH_RANGE = 16.0D;

    /** 谁持有法轮（运行期）。 */
    private static final Set<UUID> HOLDERS = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Long> LAST_SPAWN = new ConcurrentHashMap<>();
    /** 重生 / 换维度之后，需要"下一 tick 立刻强制补一只"。 */
    private static final Set<UUID> PENDING = ConcurrentHashMap.newKeySet();

    private WheelHandler() {
    }

    public static boolean hasWheel(UUID id) {
        return HOLDERS.contains(id);
    }

    public static boolean hasWheel(Entity entity) {
        return entity != null && HOLDERS.contains(entity.getUUID());
    }

    public static void grant(Player player) {
        // **只有第一次算"戴上"** —— 重登、死亡重生、再拿一次都不会重演庆祝
        boolean first = !player.getPersistentData().getBoolean(TAG);

        player.getPersistentData().putBoolean(TAG, true);
        HOLDERS.add(player.getUUID());

        BladeHandler.ensureBlade(player);          // 剑是魔虚罗身体的一部分，一起长出来
        AdaptationManager.onWheelGranted(player);  // 底子属性（+5 颗心 / +3 护甲）立刻生效并回满
        ensureWheel(player, true);

        if (first) {
            spin(player);                          // 先让头顶的轮子转起来（配合下面的音效）
            WheelCeremony.play(player);            // 铁砧 + 成就音 + 图腾粒子 + 短暂黑暗 + 三发烟花
        }
    }

    public static void ensureWheel(Player player) {
        ensureWheel(player, false);
    }

    /**
     * <b>把法轮从玩家身上摘掉</b> —— 清标记 + 删掉头顶那只实体。
     *
     * <p>给 {@code /mahoraga wheel reset} 用。主人 2026-09-15 要拍视频，
     * 需要把"获得法轮"的演出<b>重新演一遍</b>（不然一进游戏头顶就有轮子，没法从头拍）。
     *
     * <p>⚠️ 光删实体不够：{@code TAG} 标记还在的话，下一次 {@code onLogin} 又会长回来。
     *
     * <p>🔴 2026-09-15 晚补的两件事（主人报"摘不干净"）：
     * 以前这里只清标记 + 删轮子，于是**血条还是长的、护甲还是厚的、剑还在手上** ——
     * 因为 {@code onPlayerTick} 一开头就 `if (!hasWheel) return`，
     * 那条"每秒重算属性"的逻辑在没法轮时**根本不跑**，加成永远留在身上。
     * 现在把属性加成和退魔之剑一起收掉，摘完就是干净的普通玩家。
     */
    public static void reset(Player player) {
        if (player.level().isClientSide) return;

        player.getPersistentData().putBoolean(TAG, false);
        HOLDERS.remove(player.getUUID());

        List<WheelEntity> wheels = player.level().getEntitiesOfClass(WheelEntity.class,
                player.getBoundingBox().inflate(SEARCH_RANGE), w -> w.isOwnedBy(player));

        for (WheelEntity wheel : wheels) {
            wheel.discard();
        }

        LAST_SPAWN.remove(player.getUUID());
        PENDING.remove(player.getUUID());

        // 把"魔虚罗的底子"一起摘掉：属性加成 + 技能修饰符 + 手上的剑
        AdaptationManager.onWheelReset(player);
        BladeHandler.removeBlade(player);
    }

    private static void ensureWheel(Player player, boolean force) {
        if (player.level().isClientSide) return;

        // 直接把范围内属于我的法轮找出来（按 UUID 认人）——
        // 不查召唤列表，列表被清掉会导致"一清就重生"的疯狂闪烁。
        List<WheelEntity> wheels = player.level().getEntitiesOfClass(WheelEntity.class,
                player.getBoundingBox().inflate(SEARCH_RANGE), w -> w.isOwnedBy(player));

        if (!wheels.isEmpty()) {
            // 只留一只：优先正在骑我的那只，否则编号最小的
            WheelEntity keep = null;

            for (WheelEntity wheel : wheels) {
                if (wheel.getVehicle() == player) {
                    keep = wheel;
                    break;
                }
                if (keep == null || wheel.getId() < keep.getId()) {
                    keep = wheel;
                }
            }

            for (WheelEntity wheel : wheels) {
                if (wheel != keep) wheel.discard();
            }

            keep.snapToOwner(player);          // 位置对不上 / 没骑上 → 拽回来
            LAST_SPAWN.remove(player.getUUID());
            return;
        }

        if (!force) {
            long now = player.level().getGameTime();
            Long last = LAST_SPAWN.get(player.getUUID());

            if (last != null && now - last < RESPAWN_COOLDOWN) return;
            LAST_SPAWN.put(player.getUUID(), now);
        }

        player.level().addFreshEntity(new WheelEntity(player));
    }

    /** 让头顶的法轮转一圈（适应完成时调用）。 */
    public static void spin(Player player) {
        if (player.level().isClientSide) return;

        for (WheelEntity wheel : player.level().getEntitiesOfClass(WheelEntity.class,
                player.getBoundingBox().inflate(SEARCH_RANGE), w -> w.isOwnedBy(player))) {
            wheel.spin();
        }
    }

    @EventBusSubscriber(modid = MahoragaMod.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
    public static final class ForgeEvents {
        private ForgeEvents() {
        }

        /** 上线时把存档里的标记读进内存。 */
        @SubscribeEvent
        public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
            Player player = event.getEntity();

            if (player.getPersistentData().getBoolean(TAG)) {
                HOLDERS.add(player.getUUID());
                ensureWheel(player, true);
            }
        }

        /**
         * 死亡重生 / 换维度：玩家实体是**新建**的，必须把标记带过去，否则一死法轮就没了。
         * 注意这里只搬标记 —— Clone 触发时新玩家还没真正进世界，
         * 这时候生成法轮会挂在一个"不存在的主人"身上，所以生成放到 Respawn 里做。
         */
        @SubscribeEvent
        public static void onClone(PlayerEvent.Clone event) {
            Player from = event.getOriginal();

            if (!from.getPersistentData().getBoolean(TAG)) return;

            Player to = event.getEntity();
            to.getPersistentData().putBoolean(TAG, true);
            HOLDERS.add(to.getUUID());
        }

        /**
         * 重生完成 → 排队"下一 tick 立刻强制补一只"。
         *
         * 为什么不在这里直接生成：重生事件触发时新玩家可能刚站进世界，
         * 这一瞬间造出来的轮子有一定概率挂不上（客户端还没认识这个实体 id）。
         * 推到下一 tick，位置和实体都稳了，一次成功。
         */
        @SubscribeEvent
        public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
            Player player = event.getEntity();

            if (!hasWheel(player)) return;
            LAST_SPAWN.remove(player.getUUID());
            PENDING.add(player.getUUID());
        }

        /** 换维度：新世界这边也排一次队（旧世界那只自己会作废）。 */
        @SubscribeEvent
        public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
            Player player = event.getEntity();

            if (!hasWheel(player)) return;
            LAST_SPAWN.remove(player.getUUID());
            PENDING.add(player.getUUID());
        }

        @SubscribeEvent
        public static void onPlayerTick(PlayerTickEvent.Post event) {
            

            Player player = event.getEntity();
            if (player.level().isClientSide) return;
            if (!hasWheel(player)) {
                PENDING.remove(player.getUUID());
                return;
            }

            boolean pending = PENDING.contains(player.getUUID());

            // 平时 10 tick 查一次；有排队（重生/换维度）就下一 tick 立刻办
            if (!pending && player.tickCount % CHECK_INTERVAL != 0) return;

            ensureWheel(player, pending);
            PENDING.remove(player.getUUID());
        }

        /**
         * 末地城的宝箱里会开出这个物品。
         * 权重 1 : 9 —— 每个宝箱约 10% 概率出（末地城一般十几个宝箱，一趟大概能摸到一两个）。
         * 不能只放这一个条目：那样每个宝箱都必出，等于烂大街。
         */
        @SubscribeEvent
        public static void onLootTableLoad(LootTableLoadEvent event) {
            if (!event.getName().equals(ResourceLocation.fromNamespaceAndPath("minecraft", "chests/end_city_treasure"))) return;

            LootPool pool = LootPool.lootPool()
                    .setRolls(ConstantValue.exactly(1.0F))
                    .add(LootItem.lootTableItem(ModItems.MAHORAGA_WHEEL.get())
                            .setWeight(1)
                            .apply(SetItemCountFunction.setCount(ConstantValue.exactly(1.0F))))
                    .add(EmptyLootItem.emptyItem().setWeight(9))
                    .build();

            event.getTable().addPool(pool);
        }
    }
}
