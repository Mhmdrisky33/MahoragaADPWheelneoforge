package com.mahoraga.skill;

import com.mahoraga.handler.BladeHandler;
import com.mahoraga.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobType;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.living.LivingHurtEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 退魔之剑的「**亡灵斩杀**」。
 *
 * 规则：手持**完好的**退魔之剑砍**亡灵**时，如果这一刀下去它剩血不足
 * **最大生命的 1/3**，就直接处决（一刀带走）。凋灵阈值减半（1/6）。
 *
 * ==================== 实现上最关键的一句 ====================
 *
 * 🔴 **绝对不要写成 `event.setCanceled(true)` + `target.kill()`。**
 *
 * 那样先把这次伤害掐掉、再手动杀 —— 结果游戏**根本不知道是谁杀的**：
 * 死亡消息会变成"XXX 死了"，掉落、经验、击杀统计、成就全部按"非玩家击杀"走，
 * 有些模组还会在死亡时反查凶手拿到 null 直接崩。
 *
 * ✅ 正确做法是**把这一刀直接放大到必杀**（`setAmount(maxHealth * 2)`）：
 * 目标还是"被你这一刀砍死的"，其余逻辑原样走。
 *
 * ==================== 其它注意 ====================
 *
 * - 判据是 {@code getMobType() == MobType.UNDEAD}（**1.20.1 没有 `isUndead()`**）。
 * - 调用点必须在 {@code AdaptationManager#onHurt} 的**最末尾** ——
 *   要等攻击倍率、附加伤害（法伤/真伤/百分比）、无视护甲全都算完再判阈值，
 *   早了判出来的"剩余血量"是错的。
 * - 内建 **5 秒冷却**，免得连点鼠标一秒处决一群。
 * - 剑**损坏期间不能处决**（免死已经用过一次了，得等适应完成修复）。
 */
public final class UndeadExecute {
    /** 处决阈值：剩余血量不足最大生命的这个比例 → 处决。 */
    private static final float RATIO = 1.0F / 3.0F;
    /** Boss（凋灵）阈值砍半。 */
    private static final float BOSS_RATIO = 1.0F / 6.0F;
    /** 处决冷却（tick）—— 5 秒。 */
    private static final int COOLDOWN = 100;

    private static final Map<UUID, Long> LAST = new ConcurrentHashMap<>();

    private UndeadExecute() {
    }

    /** 由 {@code AdaptationManager#onHurt} 在最后一步调用。 */
    public static void tryExecute(Player player, LivingEntity victim, LivingHurtEvent event) {
        ItemStack blade = player.getMainHandItem();

        if (!blade.is(ModItems.MAHORAGA_BLADE.get())) return;
        if (BladeHandler.isBroken(blade)) return;

        if (victim.getMobType() != MobType.UNDEAD) return;
        if (victim.isDeadOrDying()) return;

        long now = player.level().getGameTime();
        Long last = LAST.get(player.getUUID());

        if (last != null && now - last < COOLDOWN) return;

        float max = victim.getMaxHealth();
        float threshold = max * (victim instanceof WitherBoss ? BOSS_RATIO : RATIO);

        // 这一刀下去还剩多少 —— 小于 0 说明本来就会死，那不算"处决"
        float remaining = victim.getHealth() - event.getAmount();

        if (remaining <= 0.0F || remaining >= threshold) return;

        victim.invulnerableTime = 0;
        event.setAmount(max * 2.0F);

        LAST.put(player.getUUID(), now);
        effect(player, victim);
    }

    private static void effect(Player player, LivingEntity victim) {
        double x = victim.getX();
        double y = victim.getY() + victim.getBbHeight() * 0.5D;
        double z = victim.getZ();
        double dx = victim.getBbWidth() * 0.4D;
        double dy = victim.getBbHeight() * 0.35D;

        if (player.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, x, y, z, 28, dx, dy, dx, 0.05D);
            level.sendParticles(ParticleTypes.SQUID_INK, x, y, z, 16, dx, dy, dx, 0.05D);
        }

        // 铁砧落地 + 凋灵那副嗓子 —— "退魔"就该是这个动静
        player.level().playSound(null, victim.blockPosition(), SoundEvents.ANVIL_LAND,
                SoundSource.PLAYERS, 0.7F, 0.7F);
        player.level().playSound(null, victim.blockPosition(), SoundEvents.WITHER_HURT,
                SoundSource.PLAYERS, 0.9F, 0.6F);

        player.displayClientMessage(Component.translatable("chat.mahoraga.blade.execute",
                victim.getDisplayName()).withStyle(ChatFormatting.DARK_PURPLE), true);
    }
}
