package com.mahoraga.skill;

import com.mahoraga.MahoragaMod;
import com.mahoraga.adapt.AdaptationData;
import com.mahoraga.adapt.AdaptationKeys;
import com.mahoraga.adapt.AdaptationManager;
import com.mahoraga.adapt.Track;
import com.mahoraga.handler.WheelHandler;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * **法轮寄生** —— 把法轮戴到别的生物身上，让它替你挨打、替你积累适应，你躲进它的影子里。
 *
 * ==================== 主人 2026-09-15 定的规矩 ====================
 *
 * 1. **宿主只是"替你适应"** —— 它自己**什么能力都不加**（不加属性、不给效果、不获得适应）。
 *    它纯粹是个"替身 + 适应记录仪"。
 * 2. 你**潜入它的影子**：隐身 + 位置跟着它走 + 不能被怪物选中。
 * 3. **什么时候出来由你决定**（再潜行右键一次）。
 * 4. ⚠️ **宿主一死就把你踢出来**（主人最后改的 —— 本来想"死了也赖着只剩法轮飘着"，
 *    后来改成直接踢出，那就不做"残留法轮"那套了）。
 *
 * ==================== 动画怎么办（1.20.1 的现实） ====================
 *
 * 玩家**用不了 GeckoLib 动画**（玩家渲染是原版的 `PlayerRenderer`），所以：
 * - "消散" = **粒子爆开 + 瞬间隐身**（原版玩家没有透明度控制，只能靠粒子造观感）
 * - "地上的影子" = **在宿主脚下按椭圆撒暗色粒子**
 * 这两样都是纯服务端 `sendParticles`，客户端自动跟上，不用写渲染器、不用发包。
 */
public final class WheelSymbiosis {
    private WheelSymbiosis() {
    }

    /** 宿主实体 id → 寄生者（玩家）UUID。用**实体 id**而不是 UUID —— `Level#getEntity(UUID)` 不存在。 */
    private static final Map<Integer, UUID> HOST_TO_PLAYER = new ConcurrentHashMap<>();
    /** 寄生者（玩家）UUID → 宿主实体 id。 */
    private static final Map<UUID, Integer> PLAYER_TO_HOST = new ConcurrentHashMap<>();

    /** 寻找"感染目标"的范围（格）。 */
    private static final double REACH = 6.0D;

    // ==================== 查询 ====================

    public static boolean isParasitizing(Player player) {
        return PLAYER_TO_HOST.containsKey(player.getUUID());
    }

    /** 这只生物现在是别人的宿主吗？ */
    public static boolean isHost(LivingEntity entity) {
        return HOST_TO_PLAYER.containsKey(entity.getId());
    }

    /** 这只生物的寄生者是谁（不是宿主就返回 null）。 */
    public static UUID playerOf(LivingEntity entity) {
        return HOST_TO_PLAYER.get(entity.getId());
    }

    /** 现在的宿主（可能已经死了/不在了）。 */
    @javax.annotation.Nullable
    public static LivingEntity hostOf(Player player) {
        Integer id = PLAYER_TO_HOST.get(player.getUUID());
        if (id == null) return null;

        Entity found = player.level().getEntity(id);

        return found instanceof LivingEntity living && living.isAlive() ? living : null;
    }

    // ==================== 进入 / 退出 ====================

    /**
     * **尝试寄生（只负责"进入"）** —— 潜行 + 右键生物时调用。
     *
     * 🔴🔴 **为什么这里不再做"再点一次就出来"**（2026-09-15 查了两轮的真凶）：
     * Forge 对"右键实体"会发**两个兄弟事件**（`EntityInteract` + `EntityInteractSpecific`，
     * 都直接继承 `PlayerInteractEvent`，**不是父子**），同一次右键**两个都会到**。
     * 以前这里是"toggle"：第一个事件进去、第二个事件又把你**立刻踢出来** ——
     * 日志里就是 `enter` 紧接着 `leave`，一秒内来回好几次，表现就是"附身不了"。
     *
     * 现在**进和出分成两个互不相干的入口**：
     * - **进** = 潜行 + 右键生物（这里）→ `isParasitizing` 天然幂等，两个事件都来也只进一次
     * - **出** = 潜行 + 右键空处 / 方块（`AdaptationManager#onSymbiosisRelease`）
     *
     * @param clicked 玩家真正右键的那只（没有就传 null，退化成"找最近的"）
     * @return true 表示这次右键已经被用掉了（别再去放特殊能力）
     */
    public static boolean tryEnter(Player player, Level level, @javax.annotation.Nullable Entity clicked) {
        if (player.level().isClientSide) return false;

        // 已经在影子里了 → 这次右键不归我们管（想出来请潜行右键空处）
        if (isParasitizing(player)) return false;

        return tryEnter0(player, level, clicked);
    }

    private static boolean tryEnter0(Player player, Level level, @javax.annotation.Nullable Entity clicked) {
        if (player.level().isClientSide) return false;

        MahoragaMod.LOGGER.info("[mahoraga] symbiosis tryEnter: clicked={}",
                clicked == null ? "null" : clicked.getType().toString());

        if (!WheelHandler.hasWheel(player)) {
            refuse(player, "你身上没有法轮");
            return false;
        }

        LivingEntity target = resolveTarget(player, level, clicked);

        // 🔴 下面这几条以前是**静默 return** —— 玩家在游戏里看到的只有"右键没反应"，
        //    跟当初那把"烧不红的剑"一个毛病。现在每条都给一句反馈，永远别再哑巴失败。
        if (target == null) {
            refuse(player, "附近没有可以附身的生物");
            return false;
        }
        if (target == player) {
            refuse(player, "不能附身自己");
            return false;
        }
        if (target instanceof Player) {
            refuse(player, "不能附身玩家");
            return false;
        }
        if (!target.isAlive()) {
            refuse(player, "它已经死了");
            return false;
        }
        if (isHost(target)) {
            refuse(player, "它已经是别人的宿主了");
            return false;
        }

        enter(player, target);
        return true;
    }

    /**
     * 找附身目标 —— **优先用玩家真正右键的那只**。
     *
     * ⚠️ 以前这里一律用 `getNearestEntity` 找"最近的"，压根不管玩家点的是谁：
     * 对着 A 右键、B 站得更近 → 被附身的是 B。现在改成**点谁附谁**。
     */
    @javax.annotation.Nullable
    private static LivingEntity resolveTarget(Player player, Level level, @javax.annotation.Nullable Entity clicked) {
        if (clicked instanceof LivingEntity living && living != player) return living;

        return level.getNearestEntity(
                LivingEntity.class,
                TargetingConditions.forNonCombat().ignoreLineOfSight(),
                player,
                player.getX(), player.getY(), player.getZ(),
                player.getBoundingBox().inflate(REACH));
    }

    /**
     * 条件不满足 → **写日志 + 聊天栏说一句**。
     *
     * 🔴 以前这里用的是**动作栏**（`displayClientMessage(..., true)`）—— 动作栏有两个毛病：
     *    ① **不落日志**（服务端只记系统聊天），排查时一片空白；② 一闪就没，容易漏看。
     *    所以全套寄生提示统一改成**聊天栏**（`false`）。
     */
    private static void refuse(Player player, String why) {
        MahoragaMod.LOGGER.info("[mahoraga] symbiosis refused: {} ({})",
                why, player.getName().getString());
        player.displayClientMessage(Component.translatable("chat.mahoraga.symbiosis.refused", why)
                .withStyle(net.minecraft.ChatFormatting.GRAY), false);
    }

    private static void enter(Player player, LivingEntity host) {
        int hostId = host.getId();

        HOST_TO_PLAYER.put(hostId, player.getUUID());
        PLAYER_TO_HOST.put(player.getUUID(), hostId);

        // —— 潜入影子 ——
        player.setInvisible(true);
        if (player instanceof ServerPlayer sp) {
            sp.setCamera(host);                                  // 视角交给宿主（看着它替你挨打）
        }

        dissolve(player, true);

        MahoragaMod.LOGGER.info("[mahoraga] symbiosis enter -> {} (id={}) ({})",
                host.getType(), hostId, player.getName().getString());

        player.displayClientMessage(Component.translatable("chat.mahoraga.symbiosis.enter",
                        host.getDisplayName())
                .withStyle(net.minecraft.ChatFormatting.DARK_PURPLE), false);
    }

    /**
     * 退出寄生。
     *
     * @param forced true = 被强行踢出（宿主死了）
     */
    public static void leave(Player player, boolean forced) {
        Integer hostId = PLAYER_TO_HOST.remove(player.getUUID());
        if (hostId != null) HOST_TO_PLAYER.remove(hostId);

        player.setInvisible(false);
        if (player instanceof ServerPlayer sp) {
            sp.setCamera(player);
        }

        dissolve(player, false);

        MahoragaMod.LOGGER.info("[mahoraga] symbiosis leave (forced={}, hostId={}) ({})",
                forced, hostId, player.getName().getString());

        player.displayClientMessage(Component.translatable(forced
                        ? "chat.mahoraga.symbiosis.kicked"
                        : "chat.mahoraga.symbiosis.leave")
                .withStyle(net.minecraft.ChatFormatting.DARK_PURPLE), false);
    }

    /** 宿主死了 → 把寄生者踢出来（主人定的）。 */
    public static void onHostDeath(LivingEntity host) {
        UUID rider = HOST_TO_PLAYER.remove(host.getId());
        if (rider == null) return;

        PLAYER_TO_HOST.remove(rider);

        ServerPlayer player = host.level().getServer() == null
                ? null
                : host.level().getServer().getPlayerList().getPlayer(rider);

        if (player != null) {
            player.setInvisible(false);
            player.setCamera(player);
            dissolve(player, false);

            MahoragaMod.LOGGER.info("[mahoraga] symbiosis host died -> kicked ({})",
                    player.getName().getString());

            player.displayClientMessage(Component.translatable("chat.mahoraga.symbiosis.kicked")
                    .withStyle(net.minecraft.ChatFormatting.RED), false);
        }
    }

    /**
     * 状态自检 —— 给 `/mahoraga symbiosis` 用。
     *
     * 跟当初那把"烧不红的剑"一个套路：**先让状态可见**，不然"附身不了"永远查不出断在哪一环。
     */
    public static void describe(Player player, java.util.function.Consumer<Component> out) {
        out.accept(Component.literal("—— 法轮寄生 · 状态 ——").withStyle(net.minecraft.ChatFormatting.DARK_PURPLE));
        out.accept(Component.literal("持有法轮：" + (WheelHandler.hasWheel(player) ? "是" : "否")));

        Integer hostId = PLAYER_TO_HOST.get(player.getUUID());
        out.accept(Component.literal("寄生中：" + (hostId == null ? "否" : "是")));

        if (hostId != null) {
            Entity found = player.level().getEntity(hostId);
            out.accept(Component.literal("宿主实体 id：" + hostId
                    + " / 实地查找：" + (found == null ? "找不到（会被自动踢出）" : found.getType().toString())));

            if (found instanceof LivingEntity living) {
                out.accept(Component.literal("宿主存活：" + living.isAlive()
                        + " / 名字：" + living.getName().getString()));
            }
        }

        out.accept(Component.literal("隐身中：" + player.isInvisible()));
        out.accept(Component.literal("近处可附身目标：" + (resolveTarget(player, player.level(), null) == null
                ? "无（6 格内没有生物）"
                : resolveTarget(player, player.level(), null).getName().getString())));
    }

    // ==================== 每 tick ====================

    /** 挂在 `AdaptationManager.onPlayerTick` 的"每 tick 必做"区。 */
    public static void tick(Player player) {
        if (player.level().isClientSide) return;
        if (!isParasitizing(player)) return;

        LivingEntity host = hostOf(player);

        if (host == null) {
            leave(player, true);        // 宿主没了（换维度之类）→ 强制退出
            return;
        }

        // 藏在它影子里：位置跟着走
        player.absMoveTo(host.getX(), host.getY(), host.getZ());
        player.setDeltaMovement(0.0D, 0.0D, 0.0D);

        shadow(player, host);
    }

    // ==================== 伤害转移 ====================

    /**
     * 玩家挨打 → 让宿主替他挨这一下。
     *
     * ⚠️ **适应不用在这里记** —— `AdaptationManager.onAttack`（`LivingAttackEvent`）
     *    照常会跑，那一刀已经记在**玩家自己**的进度上了。这里只管把伤害转移过去。
     *
     * ⚠️ 宿主自己**什么也不获得** —— 只挨打，不加属性、不给效果（主人明确定死）。
     *
     * @return true 表示伤害已经转走了（原事件要被取消）
     */
    public static boolean redirect(Player player, LivingIncomingDamageEvent event) {
        LivingEntity host = hostOf(player);
        if (host == null || host.isDeadOrDying()) return false;

        host.invulnerableTime = 0;
        host.hurt(event.getSource(), event.getAmount());

        event.setCanceled(true);
        return true;
    }

    // ==================== 表现：消散 + 影子 ====================

    /** 消散：一蓬烟 + 灵魂粒子（`in` = 钻进去，`false` = 冒出来）。 */
    private static void dissolve(Player player, boolean in) {
        if (!(player.level() instanceof ServerLevel level)) return;

        double x = player.getX();
        double y = player.getY() + player.getBbHeight() * 0.5D;
        double z = player.getZ();

        level.sendParticles(ParticleTypes.SMOKE, x, y, z, 44, 0.45D, 0.6D, 0.45D, 0.02D);
        level.sendParticles(ParticleTypes.SOUL, x, y, z, 24, 0.4D, 0.5D, 0.4D, 0.02D);

        if (in) {
            level.sendParticles(ParticleTypes.LARGE_SMOKE, x, y, z, 8, 0.3D, 0.4D, 0.3D, 0.01D);
        }

        // 进出用同一个音效，靠音调区分（钻进去偏高、冒出来偏低）
        level.playSound(null, player.blockPosition(), SoundEvents.SOUL_ESCAPE.value(),
                SoundSource.PLAYERS, 0.8F, in ? 1.25F : 0.85F);
    }

    /**
     * 地上的影子：在宿主脚下按**椭圆**撒一圈烟。
     *
     * 这就是主人要的"地上的影子" —— 玩家没有透明度可用，
     * 所以用贴地的暗色粒子圈出一个影子来。
     */
    private static void shadow(Player player, LivingEntity host) {
        if (!(host.level() instanceof ServerLevel level)) return;
        if (player.tickCount % 4 != 0) return;              // 别太密

        double rx = host.getBbWidth() * 0.62D + 0.22D;
        double rz = rx * 0.55D;                             // 压扁一点才像"地上的影子"
        double y = host.getY() + 0.04D;
        int count = 12;

        for (int i = 0; i < count; i++) {
            double a = (Math.PI * 2.0D * i) / count;

            level.sendParticles(ParticleTypes.SMOKE,
                    host.getX() + Math.cos(a) * rx,
                    y,
                    host.getZ() + Math.sin(a) * rz,
                    1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    /** 玩家下线 / 换维度时清干净，免得留下脏映射。 */
    public static void forget(Player player) {
        Integer hostId = PLAYER_TO_HOST.remove(player.getUUID());
        if (hostId != null) HOST_TO_PLAYER.remove(hostId);

        player.setInvisible(false);
    }
}
