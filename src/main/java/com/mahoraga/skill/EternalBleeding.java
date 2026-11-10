package com.mahoraga.skill;

import com.mahoraga.registry.ModDamageTypes;
import com.mahoraga.registry.ModEffects;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

import java.util.UUID;

/**
 * 「永恒流血」的核心 —— 搬自 Re:Zero（hahueuh）的死亡之神（`DeathGod`）。
 *
 * ==================== 原版怎么做的 ====================
 * 打一下就开一道口子（`cut`），**再打一下就深一层**；
 * 伤口记在目标身上的 NBT 里（谁打的 + 几层）；
 * 每隔几秒按"层数 ^ 指数"扣一次血；牛奶和 `/effect clear` 都摘不掉；
 * 目标一死，伤口跟着消失。
 *
 * ==================== 我们简化掉的两处 ====================
 * - 原版还要按"施加者离目标多近"来放大强度、另外有个技能专门用来"缝合伤口" ——
 *   我们只留核心（打得越勤越痛、治不好），够狠也够简单；
 * - 层数上限写死 5 层（原版走配置文件）。
 */
public final class EternalBleeding {
    private static final String ROOT = "mahoraga_bleed";
    private static final String LEVEL = "level";
    private static final String SOURCE = "source";

    /** 最多叠几层。 */
    private static final int MAX_LEVEL = 5;
    /** 每 3 秒扣一次血。 */
    private static final int INTERVAL_TICKS = 60;
    /** 每层基础伤害 × 层数^指数 —— 5 层时约 2 × 8.1 ≈ 16 点/次。 */
    private static final float DAMAGE_PER_LEVEL = 2.0F;
    private static final double DAMAGE_EXPONENT = 1.3D;
    /** 效果的持续时间；每 2 秒续一次（续不上说明伤口没了）。 */
    private static final int EFFECT_TICKS = 160;

    private EternalBleeding() {
    }

    // ==================== 施加 ====================

    /**
     * 给目标开一道口子（叠一层）。
     *
     * 换个人打他就**重新从 1 层算** —— 和原版一致（伤口是按"谁砍的"分开记的）。
     */
    public static void cut(LivingEntity attacker, LivingEntity victim) {
        if (victim == attacker || !victim.isAlive()) return;

        int level = Math.min(MAX_LEVEL, level(victim) + 1);

        store(victim, level, attacker.getUUID());
        refresh(victim, level);
    }

    /** 这个人身上还有没有伤口。 */
    public static boolean hasWound(LivingEntity entity) {
        return level(entity) > 0;
    }

    private static int level(LivingEntity entity) {
        CompoundTag data = entity.getPersistentData();

        return data.contains(ROOT) ? data.getCompound(ROOT).getInt(LEVEL) : 0;
    }

    private static void store(LivingEntity victim, int level, UUID source) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(LEVEL, level);
        tag.putUUID(SOURCE, source);
        victim.getPersistentData().put(ROOT, tag);
    }

    private static void refresh(LivingEntity victim, int level) {
        victim.addEffect(new MobEffectInstance(ModEffects.BLEEDING, EFFECT_TICKS,
                Math.max(0, level - 1), false, true, true));
    }

    // ==================== 每 tick ====================

    /**
     * 每个生物每 tick 都会走这里（挂在 `LivingTickEvent` 上）。
     *
     * ⚠️ 这是**高频路径**：世界里几百上千个生物每 tick 都会进来一次。
     * 所以第一句就是最便宜的判断 —— 一次药水表查询，**99.9% 的生物在这里就返回了**，
     * 只有真在流血的那几个才会往下走。
     */
    public static void tick(LivingEntity victim) {
        if (victim.level().isClientSide) return;
        if (!victim.hasEffect(ModEffects.BLEEDING)) return;

        int level = level(victim);

        // 效果还在、伤口却没了（正常情况下会被 Remove 事件拦住，这里是兜底）
        if (level <= 0) {
            victim.removeEffect(ModEffects.BLEEDING);
            return;
        }

        if (victim.level() instanceof ServerLevel server) {
            server.sendParticles(ParticleTypes.FALLING_LAVA,
                    victim.getX(), victim.getY() + victim.getBbHeight() * 0.6D, victim.getZ(),
                    level, victim.getBbWidth() * 0.35D, 0.15D, victim.getBbWidth() * 0.35D, 0.0D);
        }

        // 续期 —— "永恒"不能自己走完
        if (victim.tickCount % 40 == 0) {
            refresh(victim, level);
        }

        if (victim.tickCount % INTERVAL_TICKS != 0) return;

        float damage = (float) (DAMAGE_PER_LEVEL * Math.pow(level, DAMAGE_EXPONENT));
        if (damage <= 0.0F) return;

        victim.invulnerableTime = 0;                 // 不然会被受击冷却吃掉
        victim.hurt(sourceOf(victim), damage);
    }

    /** 找出发起人（记不到就用"一般魔法"兜底，绝不崩）。 */
    private static DamageSource sourceOf(LivingEntity victim) {
        CompoundTag data = victim.getPersistentData();

        if (data.contains(ROOT) && data.getCompound(ROOT).hasUUID(SOURCE)
                && victim.level() instanceof ServerLevel server && server.getServer() != null) {

            var player = server.getServer().getPlayerList()
                    .getPlayer(data.getCompound(ROOT).getUUID(SOURCE));

            if (player != null) return ModDamageTypes.bleed(player);
        }
        return victim.damageSources().magic();
    }

    /** 伤口清空 + 摘掉效果（目标死掉、或者效果自己没了的时候调）。 */
    public static void clear(LivingEntity victim) {
        victim.getPersistentData().remove(ROOT);
        victim.removeEffect(ModEffects.BLEEDING);
    }

    /** 调试用：谁砍的。 */
    public static UUID source(LivingEntity entity) {
        CompoundTag data = entity.getPersistentData();

        return data.contains(ROOT) && data.getCompound(ROOT).hasUUID(SOURCE)
                ? data.getCompound(ROOT).getUUID(SOURCE) : null;
    }
}
