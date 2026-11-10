package com.mahoraga.skill;

import com.mahoraga.adapt.AdaptationKeys;
import com.mahoraga.adapt.AdaptationManager;
import com.mahoraga.adapt.Track;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 日常适应「**投掷**」的奖励 —— **你丢出去的东西会自己拐弯追人**（主人 2026-09-14 定）。
 *
 * 整套逻辑**照抄 Re:Zero 的加护「射中」**（`ArrowStriking` + `ProjectileTracker` +
 * `mixin/ProjectileMixin`），只把"有没有加护"换成"日常投掷适应够不够"。
 *
 * ==================== 为什么是日常轨 ====================
 *
 * 不靠挨打也不靠打怪，**丢得多了自然就会** —— 和挖矿、奔跑、泡水一个规矩：
 * 每丢一次加一点进度（`AdaptationKeys.DAILY_THROW`），停手 30 秒后开始衰减。
 *
 * ==================== 原版那套怎么做的（照抄的部分） ====================
 *
 * 1. **靠 mixin 挂在 `Projectile#tick` 的尾巴上**（`mixin/ProjectileHomingMixin`）——
 *    投射物不是 `LivingEntity`，收不到 Forge 的 tick 事件；而
 *    Re:Zero 的注释里明确写着"不依赖 `EntityJoinLevelEvent`（那条路一直没验证通）"，所以照抄 mixin。
 * 2. **锁定一个目标就不换了**：`steer` 每 tick 把上一步锁的 id 传回来，目标死了/跑远了才重新找。
 *    不这么做的话，几个目标挨在一起时飞出去的箭会左右乱摆。
 * 3. **只追前方的**（`FORWARD_DOT`）：方向点积太低的目标直接跳过 —— 否则箭会原地掉头。
 * 4. **追踪期间关重力**（`setNoGravity(true)`），并用标记记住"是我们关的"，退出时还回去。
 * 5. **改完速度要 `hasImpulse = true`** —— 让服务端把这一 tick 的位移同步给客户端
 *    （写 `hurtMarked` 是另一回事，位置同步靠这个）。
 *
 * ==================== 我们的数值成长 ====================
 *
 * 照抄之后加了"随进度成长"（主人要求的能力都带成长）：
 * | | 100% | 1000% |
 * |---|---|---|
 * | 转向速率 | 0.18 | 0.45（封顶） |
 * | 搜索半径 | 12 格 | 28 格（封顶） |
 *
 * 另外主人 2026-09-15 追加了「**出手速度加成**」（见 {@link #applySpeedBoost}）——
 * 这条**不设门槛、从 0% 就开始涨**：每 100% 给初速 +10%，1000% 时正好翻倍。
 */
public final class ProjectileHoming {
    private ProjectileHoming() {
    }

    /** 解锁门槛：日常「投掷」适应 100%。 */
    private static final float NEED = 1.0F;

    /** 转向速率：基础值 / 每点进度加多少 / 封顶。 */
    private static final double TURN_BASE = 0.18D;
    private static final double TURN_STEP = 0.030D;
    private static final double TURN_MAX = 0.45D;

    /** 搜索半径（格）：基础值 / 每点进度加多少 / 封顶。 */
    private static final double RANGE_BASE = 12.0D;
    private static final double RANGE_STEP = 1.6D;
    private static final double RANGE_MAX = 28.0D;

    /**
     * **出手速度加成**：每 100% 给初速 +10%，封顶 **2 倍**（1000% 时打满）。
     *
     * 这条**不吃 100% 门槛** —— 从 0% 起就连续往上涨，所以是"投得越多扔得越狠"，
     * 而不是"练满了才突然变快"。
     */
    private static final double SPEED_STEP = 0.10D;
    private static final double SPEED_MAX = 2.0D;

    /** "这一发的初速已经加过了"标记 —— 保证只加一次（见 {@link #applySpeedBoost}）。 */
    private static final String SPEED_TAG = "mahoraga_speed_boosted";

    /**
     * 只追**前方**的目标：飞行方向与"指向目标"的点积低于这个值就跳过。
     * 不卡这一刀的话，箭会掉头去追身后的怪 —— 看着很蠢，也容易撞回自己脸上。
     */
    private static final double FORWARD_DOT = 0.20D;

    /** "重力是我们关的"这个标记（写在实体自己的 PersistentData 上，退出时好还回去）。 */
    private static final String GRAVITY_TAG = "mahoraga_homing_gravity";

    /**
     * **出手速度加成** —— 刚扔/射出去的那一下，按投掷适应的进度把初速放大。
     *
     * 由 `mixin/ProjectileHomingMixin` 注入 `Projectile#tick` 的 **HEAD** 调用，
     * 所以我们是在**原版跑第一帧移动之前**改速度的 —— 等于真的"出手就更快"，
     * 而不是飞出去一帧之后才被补一脚。
     *
     * 🔴 **只加一次**（靠实体自己的 `PersistentData` 打标记）：
     * 每 tick 都乘一遍的话，飞两秒就是天文数字，箭会直接穿出视距。
     *
     * ⚠️ 这是**给玩家的投射物**用的（`getOwner()` 是玩家）——
     * 骷髅射的箭、发射器吐的东西都不受影响。
     */
    public static void applySpeedBoost(Projectile projectile) {
        if (!(projectile.level() instanceof ServerLevel)) return;

        // 只认**第一帧**（HEAD 时 tickCount 还是 0）—— 中途被加载进来的实体不补
        if (projectile.tickCount > 1) return;

        if (projectile.getPersistentData().getBoolean(SPEED_TAG)) return;

        if (!(projectile.getOwner() instanceof Player owner)) return;

        float progress = AdaptationManager.data(owner)
                .get(Track.DAILY, AdaptationKeys.DAILY_THROW);

        double multiplier = speedMultiplier(progress);
        if (multiplier <= 1.0D) return;

        Vec3 motion = projectile.getDeltaMovement();
        if (motion.lengthSqr() < 1.0E-6D) return;               // 还没速度（等下一帧再看）

        projectile.getPersistentData().putBoolean(SPEED_TAG, true);
        projectile.setDeltaMovement(motion.scale(multiplier));
        projectile.hasImpulse = true;                           // 让服务端把这一帧同步给客户端
    }

    /** 速度倍数：`min(2.0, 1 + 进度 × 0.10)` —— 每 100% +10%，1000% 打满 2 倍。 */
    public static double speedMultiplier(float progress) {
        if (progress <= 0.0F) return 1.0D;

        return Math.min(SPEED_MAX, 1.0D + progress * SPEED_STEP);
    }

    /**
     * 每 tick 调一次（mixin 注入 `Projectile#tick` 的 TAIL）。
     *
     * @param lockedId 上一步锁定的目标实体 id，-1 表示还没锁
     * @return 这一 tick 锁着的目标 id（没有就是 -1）—— 调用方存起来，下一步再传回来
     */
    public static int steer(Projectile projectile, int lockedId) {
        // 客户端不掺和：服务端改完速度会同步过去，两边都算会打架
        if (!(projectile.level() instanceof ServerLevel level)) return lockedId;

        // 刚出手的前两 tick 不干预 —— 让它先按你瞄的方向飞出去
        if (projectile.tickCount < 2) return lockedId;

        // 投手必须是个"适应了投掷"的玩家
        if (!(projectile.getOwner() instanceof Player owner)) return release(projectile);

        float progress = AdaptationManager.data(owner)
                .get(Track.DAILY, AdaptationKeys.DAILY_THROW);

        if (progress < NEED) return release(projectile);

        Vec3 motion = projectile.getDeltaMovement();
        double speed = motion.length();

        if (speed < 1.0E-4D) return release(projectile);

        Vec3 heading = motion.normalize();

        LivingEntity target = resolveLocked(level, projectile, owner, lockedId);
        if (target == null) target = findNearest(level, projectile, owner, heading, progress);
        if (target == null) return release(projectile);

        double turn = Math.min(TURN_MAX, TURN_BASE + (progress - NEED) * TURN_STEP);

        Vec3 desired = target.getBoundingBox().getCenter()
                .subtract(projectile.position()).normalize();

        Vec3 steered = heading.add(desired.subtract(heading).scale(turn))
                .normalize()
                .scale(speed);

        projectile.setDeltaMovement(steered);

        // 追踪期间不下坠 —— 不然刚掰正又被重力拽下去，看着就是"追一半掉地上"
        if (!projectile.isNoGravity() && !gravityHeld(projectile)) {
            projectile.setNoGravity(true);
            setGravityHeld(projectile, true);
        }

        projectile.hasImpulse = true;       // 让服务端把这一 tick 的位移同步给客户端
        return target.getId();
    }

    /** 罢手：把重力还回去，返回 -1（表示"没锁任何目标"）。 */
    public static int release(Projectile projectile) {
        if (gravityHeld(projectile)) {
            projectile.setNoGravity(false);
            setGravityHeld(projectile, false);
        }
        return -1;
    }

    /** 上一步锁的那个目标还作不作数。 */
    private static LivingEntity resolveLocked(ServerLevel level, Projectile projectile,
                                              Player owner, int lockedId) {
        if (lockedId < 0) return null;

        Entity entity = level.getEntity(lockedId);

        if (!(entity instanceof LivingEntity living) || !isValidTarget(living, owner)) return null;
        if (living.distanceToSqr(projectile) > RANGE_MAX * RANGE_MAX) return null;

        return living;
    }

    /** 在附近挑一个**在前方**的、最近的敌人。 */
    private static LivingEntity findNearest(ServerLevel level, Projectile projectile, Player owner,
                                            Vec3 heading, float progress) {
        double range = Math.min(RANGE_MAX, RANGE_BASE + (progress - NEED) * RANGE_STEP);
        AABB box = projectile.getBoundingBox().inflate(range);

        LivingEntity best = null;
        double bestSqr = range * range;

        for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, box,
                e -> isValidTarget(e, owner))) {

            double distanceSqr = candidate.distanceToSqr(projectile);
            if (distanceSqr > bestSqr) continue;

            Vec3 toTarget = candidate.getBoundingBox().getCenter().subtract(projectile.position());
            if (toTarget.lengthSqr() < 1.0E-6D) continue;
            if (heading.dot(toTarget.normalize()) < FORWARD_DOT) continue;

            bestSqr = distanceSqr;
            best = candidate;
        }

        return best;
    }

    /** 能追的目标：活的、不是自己人、不是旁观者。 */
    private static boolean isValidTarget(LivingEntity candidate, Player owner) {
        return candidate != owner && candidate.isAlive() && !candidate.isSpectator()
                && !(candidate instanceof Player other && !owner.canHarmPlayer(other));
    }

    private static boolean gravityHeld(Projectile projectile) {
        return projectile.getPersistentData().getBoolean(GRAVITY_TAG);
    }

    private static void setGravityHeld(Projectile projectile, boolean held) {
        projectile.getPersistentData().putBoolean(GRAVITY_TAG, held);
    }
}
