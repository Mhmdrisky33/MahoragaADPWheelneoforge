package com.mahoraga.skill;

import com.mahoraga.registry.ModDamageTypes;
import com.mahoraga.registry.ModSounds;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>捌</b> —— 宿傩「御厨子」那一套的落刀式，抄自原模组 {@code ability/shrine/Cleave.java}。
 *
 * <p>原版的完整时序是这样的（这里**一模一样地照搬**）：
 * <pre>
 *   t=0    起手一记破空声
 *   t=1~20 目标身上**每一 tick 炸开一道刀口**（随机落点，跟着打完为止）
 *   t=2~20 每 2 tick 再补一声斩击，一共 10 声 —— 连着挨刀的那个"唰唰唰"
 *   t=25   **收尾落刀**：结算伤害 + 一记闷响（CLEAVE）
 * </pre>
 *
 * <h3>主人要的分工（2026-09-15 原话）</h3>
 * 「特效、音效、代码，你改伤害就好了」——
 * 所以演出一比一照抄，**伤害保留我们自己的那一版**：
 * <b>目标最大生命的 5%</b>（法术伤害，穿甲）。
 * 巧的是原版 {@code DAMAGE_SCALE} 也是 {@code 0.05F}，两边本来就是同一个数。
 *
 * <p>它挂在「独特破解」的 {@code PERCENT_DAMAGE} 上 —— 抽到这一条，
 * 以后打那只怪就会甩出这么一套连斩。
 *
 * <p>⚠️ 延迟用 **tick 队列**（跟 {@code AnalyzeNarrator} 同一套做法），
 * 不开线程：下线 / 换维度 / 存档重载都会自然失效，不会留下"打到一半的刀"。
 */
public final class SukunaCleave {
    /** 连斩段数 —— 每 tick 一道。 */
    private static final int STRIKES = 20;
    /** 打击音效的间隔（tick）。 */
    private static final int SOUND_EVERY = 2;
    /** 打击音效的总次数。 */
    private static final int SOUND_COUNT = 10;
    /** 收尾落刀的延迟（tick）—— 1 秒出头，正好看着刀光打完。 */
    private static final int SETTLE_DELAY = 25;
    /** 收尾伤害 = 目标最大生命的这个比例。 */
    private static final float PERCENT = 0.05F;

    private static final RandomSource RANDOM = RandomSource.create();

    /** 每个玩家身上排着的待办。 */
    private static final Map<UUID, List<Pending>> QUEUE = new ConcurrentHashMap<>();

    private SukunaCleave() {
    }

    /**
     * 甩出一套「捌」（{@code CrackEffects} 抽到百分比伤害时调）。
     *
     * @param owner  施术者（延迟任务挂在他身上跑）
     * @param target 挨刀的那个
     */
    public static void perform(Player owner, LivingEntity target) {
        if (owner.level().isClientSide) return;
        if (!(owner.level() instanceof ServerLevel level)) return;
        if (target.isDeadOrDying()) return;

        // 起手：一声破空
        level.playSound(null, target.getX(), target.getY(), target.getZ(),
                ModSounds.SLASH.get(), SoundSource.PLAYERS, 1.0F, 1.0F);

        // ① 连斩：20 tick，每 tick 在身上随机位置炸一道刀光
        for (int i = 1; i <= STRIKES; i++) {
            schedule(owner, i, () -> {
                if (!target.isDeadOrDying()) slashFx(level, target);
            });
        }

        // ② 打击声：每 2 tick 一声
        for (int i = 1; i <= SOUND_COUNT; i++) {
            schedule(owner, i * SOUND_EVERY, () -> {
                if (!target.isDeadOrDying()) {
                    level.playSound(null, target.getX(), target.getY(), target.getZ(),
                            ModSounds.SLASH.get(), SoundSource.PLAYERS, 1.0F, 1.0F);
                }
            });
        }

        // ③ 收尾落刀
        schedule(owner, SETTLE_DELAY, () -> settle(owner, level, target));
    }

    /**
     * 收尾那一刀 —— <b>伤害是「百分比伤害」那条破解原本的算法</b>：
     * 目标最大生命的 5%，走法术伤害（只穿甲）。
     *
     * <p>⚠️ 打之前必须 `invulnerableTime = 0`：我们是延迟 25 tick 才补的，
     * 这期间目标多半在挨揍，受击无敌帧会把这一下整段吃掉。
     */
    private static void settle(Player owner, ServerLevel level, LivingEntity target) {
        if (owner.level() != level) return;              // 换维度了就别追着打了
        if (!owner.isAlive()) return;
        if (target.isDeadOrDying() || !target.isAlive()) return;

        float amount = target.getMaxHealth() * PERCENT;

        target.invulnerableTime = 0;
        boolean hit = target.hurt(ModDamageTypes.spell(owner), amount);

        if (hit) {
            level.playSound(null, target.getX(), target.getY(), target.getZ(),
                    ModSounds.CLEAVE.get(), SoundSource.PLAYERS, 1.3F, 1.0F);
        }
    }

    /**
     * 一道刀口 —— 落在目标身上**随机的某处**（原版就是这么撒的，所以看着像被乱刀割）。
     *
     * <p>原版用的是它自己画的斩击粒子；我们手上只有原版素材，于是拿
     * **横扫刀光（`SWEEP_ATTACK`）+ 迸溅火星（`CRIT`）** 拼一个等价效果。
     */
    private static void slashFx(ServerLevel level, LivingEntity target) {
        Vec3 center = target.position().add(0.0D, target.getBbHeight() / 2.0D, 0.0D);

        double x = center.x + (RANDOM.nextDouble() - 0.5D) * target.getBbWidth() * 2.0D;
        double y = center.y + (RANDOM.nextDouble() - 0.5D) * target.getBbHeight();
        double z = center.z + (RANDOM.nextDouble() - 0.5D) * target.getBbWidth() * 2.0D;

        level.sendParticles(ParticleTypes.SWEEP_ATTACK, x, y, z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        level.sendParticles(ParticleTypes.CRIT, x, y, z, 4, 0.15D, 0.15D, 0.15D, 0.2D);
    }

    // ==================== 每 tick 推进队列 ====================

    /** 由 {@code AdaptationManager} 每 tick 调一次。 */
    public static void tick(Player player) {
        List<Pending> list = QUEUE.get(player.getUUID());

        if (list == null || list.isEmpty()) return;

        List<Pending> ready = null;

        for (Pending pending : list) {
            pending.ticks--;

            if (pending.ticks <= 0) {
                if (ready == null) ready = new ArrayList<>();
                ready.add(pending);
            }
        }

        if (ready == null) return;

        list.removeAll(ready);

        for (Pending pending : ready) {
            try {
                pending.action.run();
            } catch (Exception ignored) {
                // 一刀崩了不该带崩整个 tick
            }
        }
    }

    /** 玩家下线 → 把没打完的刀丢掉。 */
    public static void clear(UUID playerId) {
        QUEUE.remove(playerId);
    }

    private static void schedule(Player owner, int delay, Runnable action) {
        QUEUE.computeIfAbsent(owner.getUUID(), k -> new ArrayList<>())
                .add(new Pending(delay, action));
    }

    /** 可变倒计时的待办（不能用 record —— 字段是 final 的，减不动）。 */
    private static final class Pending {
        private int ticks;
        private final Runnable action;

        private Pending(int ticks, Runnable action) {
            this.ticks = ticks;
            this.action = action;
        }
    }
}
