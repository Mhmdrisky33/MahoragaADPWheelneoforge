package com.mahoraga.skill;

import com.mahoraga.registry.ModEffects;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>领域展开 —— 无量空处</b>。
 *
 * <p>抄自原模组的 {@code UnlimitedVoid}（五条悟那招）：原版是「封闭领域 + 眩晕 + 失明 + 持续伤脑」。
 * 主人要的是**眩晕**这一条，于是这里保留"领域"和"让它动不了"，
 * 失明改成粒子表现（屏幕全黑在单人测试里看不出好看的地方，紫色星空更帅）。
 *
 * <p>长这样：
 * <pre>
 *   打中目标的一瞬间，以**你**为圆心展开一个半径 10 格的领域
 *   ▸ 里面所有生物被「眩晕」钉住 —— 动不了，只能挨打
 *   ▸ 领域持续 8 秒，边界上翻涌着末地传送门那种紫色星尘
 * </pre>
 *
 * <p>三个设计选择：
 * <ul>
 *   <li><b>中心钉在展开那一刻的位置</b>，不是跟着你走 —— 领域是"铺在地上的一块地"，
 *       跟着人跑就变成光环了，没有领域感。</li>
 *   <li><b>状态效果每 40 tick 刷一次</b>而不是给足 8 秒：这样一来，
 *       走出领域的生物会**在 2 秒内自己恢复**，符合"离开领域就没事了"的直觉。</li>
 *   <li>🔴 <b>免疫状态效果的 Boss 也得定住</b>：末影龙、凋灵这类 {@code addEffect} 会返回 false，
 *       这时改用**物理定身**（每 tick 抹掉它的速度）—— 属性免疫挡不住这个。</li>
 * </ul>
 *
 * <p>由 {@code AdaptationManager} 每 tick 调 {@link #tick} 推进（本项目唯一实测可靠的 tick 入口）。
 */
public final class VoidDomain {
    /** 领域半径（格）。 */
    private static final double RADIUS = 10.0D;
    /** 持续时长（tick）—— 8 秒。 */
    private static final int DURATION = 160;
    /** 眩晕每次刷新的时长（tick）—— 2 秒，走出领域后最多 2 秒就恢复。 */
    private static final int STUN_REFRESH = 40;
    /** 每隔几 tick 撒一次粒子。 */
    private static final int PARTICLE_INTERVAL = 4;

    /** 谁正开着领域（运行期状态，不存档）。 */
    private static final Map<UUID, Active> ACTIVE = new ConcurrentHashMap<>();

    private VoidDomain() {
    }

    /** 一次领域展开：中心 + 结束时刻。 */
    private record Active(Vec3 center, long until) {
    }

    /**
     * 展开领域（{@code CrackEffects} 抽到这一条时调）。
     *
     * <p>中心取**玩家当前站的位置**——之后你冲出去打架也不会把它带走。
     */
    public static void open(Player player) {
        if (player.level().isClientSide) return;

        long now = player.level().getGameTime();

        ACTIVE.put(player.getUUID(), new Active(player.position(), now + DURATION));

        player.displayClientMessage(Component.translatable("chat.mahoraga.domain.void")
                .withStyle(ChatFormatting.DARK_PURPLE), false);

        if (player.level() instanceof ServerLevel level) {
            Vec3 at = player.position();

            // 低频轰鸣 —— 原模组那声"领域压下来"的感觉
            level.playSound(null, at.x, at.y, at.z, SoundEvents.ELDER_GUARDIAN_CURSE,
                    SoundSource.PLAYERS, 1.6F, 0.55F);

            // 开场：脚下炸开一圈灵魂火 + 中心一记强光
            level.sendParticles(ParticleTypes.SCULK_SOUL, at.x, at.y + 0.4D, at.z,
                    90, RADIUS * 0.45D, 0.6D, RADIUS * 0.45D, 0.06D);
            level.sendParticles(ParticleTypes.FLASH, at.x, at.y + 1.0D, at.z,
                    3, 1.5D, 1.0D, 1.5D, 0.0D);
        }
    }

    /** 现在开着领域吗（诊断用）。 */
    public static boolean isOpen(Player player) {
        return ACTIVE.containsKey(player.getUUID());
    }

    /** 立刻收掉领域（`/mahoraga reset` 之类的地方可以调）。 */
    public static void close(Player player) {
        ACTIVE.remove(player.getUUID());
    }

    /**
     * 每 tick 推进一次（{@code AdaptationManager} 调）。
     *
     * <p>干三件事：给范围内的生物刷新眩晕 → 撒边界粒子 → 到点收工。
     */
    public static void tick(Player player) {
        if (player.level().isClientSide) return;

        Active active = ACTIVE.get(player.getUUID());
        if (active == null) return;

        long now = player.level().getGameTime();

        if (now >= active.until()) {
            ACTIVE.remove(player.getUUID());

            if (player.level() instanceof ServerLevel level) {
                level.playSound(null, active.center().x, active.center().y, active.center().z,
                        SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 0.6F);
            }
            return;
        }

        if (!(player.level() instanceof ServerLevel level)) return;

        // ---- ① 范围内的生物：钉住 ----
        AABB box = new AABB(active.center(), active.center()).inflate(RADIUS);
        double r2 = RADIUS * RADIUS;

        for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, box)) {
            if (victim == player || !victim.isAlive()) continue;
            if (victim.position().distanceToSqr(active.center()) > r2) continue;

            boolean applied = victim.addEffect(new MobEffectInstance(ModEffects.STUN.get(),
                    STUN_REFRESH, 0, false, false, false));

            // 🔴 免疫状态效果的（末影龙 / 凋灵…）走物理定身 —— 属性免疫挡不住动速度
            if (!applied) {
                victim.setDeltaMovement(0.0D, Math.min(victim.getDeltaMovement().y, 0.0D), 0.0D);
                victim.hurtMarked = true;
            }
        }

        // ---- ② 边界粒子：末地传送门那种紫色星尘 ----
        if (now % PARTICLE_INTERVAL != 0) return;

        Vec3 at = active.center();

        level.sendParticles(ParticleTypes.REVERSE_PORTAL, at.x, at.y + 1.2D, at.z,
                26, RADIUS * 0.75D, 1.6D, RADIUS * 0.75D, 0.02D);
        level.sendParticles(ParticleTypes.END_ROD, at.x, at.y + 0.6D, at.z,
                6, RADIUS * 0.6D, 0.8D, RADIUS * 0.6D, 0.01D);
    }
}
