package com.mahoraga.skill;

import com.mahoraga.MahoragaMod;
import com.mahoraga.registry.ModDamageTypes;
import com.mahoraga.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 主动技能：退魔之剑 · 防御反击。
 *
 * **手持退魔之剑 + 蹲下（潜行）** 即自动激活（不需要按键绑定）：
 * - 受到的所有伤害 **减半**；
 * - 被谁打就 **自动反击谁**，反击伤害 = 玩家普通攻击伤害的 **50%**。
 *
 * 和被动的适应系统的边界（主人要求"互不冲突"）：
 * - 反击用**专属伤害类型** {@link ModDamageTypes#COUNTER}，不吃攻击适应的伤害倍率，
 *   也**不会**反过来给攻击适应喂进度 —— 两个系统各算各的；
 * - 反击本身不会再触发反击（防止两个持剑玩家互相弹成死循环）；
 * - 减伤发生在 `LivingHurtEvent`，比适应系统的免疫（`LivingAttackEvent` 里 cancel）晚一步，
 *   完全免疫时压根走不到这里，不会重复算。
 */
@EventBusSubscriber(modid = MahoragaMod.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class GuardSkill {
    /** 反击伤害占"普通攻击伤害"的比例。 */
    private static final float COUNTER_RATIO = 0.5F;
    /** 激活期间受到的伤害倍率。 */
    private static final float DAMAGE_TAKEN_RATIO = 0.5F;

    /** 用来只在"状态发生变化"那一下发提示，不刷屏。 */
    private static final Set<UUID> ANNOUNCED = ConcurrentHashMap.newKeySet();

    private GuardSkill() {
    }

    public static boolean holdsBlade(Player player) {
        return player.getMainHandItem().is(ModItems.MAHORAGA_BLADE.get())
                || player.getOffhandItem().is(ModItems.MAHORAGA_BLADE.get());
    }

    /** 手持退魔之剑 + 潜行 = 激活。 */
    public static boolean isActive(Player player) {
        return holdsBlade(player) && player.isShiftKeyDown();
    }

    // ==================== 减伤 ====================

    @SubscribeEvent
    public static void onHurt(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level().isClientSide) return;
        if (!isActive(player)) return;

        event.setAmount(event.getAmount() * DAMAGE_TAKEN_RATIO);
    }

    // ==================== 自动反击 ====================

    @SubscribeEvent
    public static void onAttacked(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level().isClientSide) return;
        if (!isActive(player)) return;

        DamageSource source = event.getSource();

        // 反击不再触发反击
        if (source.is(ModDamageTypes.COUNTER)) return;

        if (!(source.getEntity() instanceof LivingEntity attacker)) return;
        if (attacker == player || attacker.isRemoved() || !attacker.isAlive()) return;

        float damage = (float) player.getAttributeValue(Attributes.ATTACK_DAMAGE) * COUNTER_RATIO;
        if (damage <= 0.0F) return;

        // 用专属伤害类型：不吃攻击适应倍率，也不喂适应进度
        attacker.hurt(ModDamageTypes.counter(player, attacker), damage);
    }

    // ==================== 激活 / 解除时的提示（只在状态切换时发） ====================

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        

        Player player = event.getEntity();
        if (player.level().isClientSide) return;
        if (player.tickCount % 5 != 0) return;

        boolean active = isActive(player);
        boolean announced = ANNOUNCED.contains(player.getUUID());

        if (active == announced) return;

        if (active) {
            ANNOUNCED.add(player.getUUID());
            player.displayClientMessage(Component.translatable("chat.mahoraga.guard.on")
                    .withStyle(ChatFormatting.AQUA), true);
        } else {
            ANNOUNCED.remove(player.getUUID());
            player.displayClientMessage(Component.translatable("chat.mahoraga.guard.off")
                    .withStyle(ChatFormatting.DARK_GRAY), true);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        ANNOUNCED.remove(event.getEntity().getUUID());
    }
}
