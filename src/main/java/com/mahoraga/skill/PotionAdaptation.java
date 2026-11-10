package com.mahoraga.skill;

import com.mahoraga.MahoragaMod;
import com.mahoraga.adapt.AdaptationManager;
import com.mahoraga.handler.WheelHandler;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import java.util.ArrayList;

/**
 * **负面药水效果适应**（防御适应的一种，主人 2026-09-14 加）。
 *
 * 中了负面效果（中毒 / 缓慢 / 挖掘疲劳 / 虚弱 / 失明 / 反胃 / 饥饿 / 漂浮 / 黑暗…）
 * 就会开始适应；满 100% 之后**直接免疫这一类负面效果**。
 *
 * ⚠️ 这一类**全程静默**：不播报、不转轮、不回血 —— 效果种类太多，
 * 一条条播报会把屏幕刷爆（主人原话"这个就别显示出来了，太乱了，直接被动就可以了"）。
 * 想看进度就用 `/mahoraga`，那里照样列出来。
 */
@EventBusSubscriber(modid = MahoragaMod.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class PotionAdaptation {
    private PotionAdaptation() {
    }

    /** 已经适应满的效果 → 压根不让它上身。 */
    @SubscribeEvent
    public static void onApplicable(MobEffectEvent.Applicable event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level().isClientSide) return;

        Holder<MobEffect> effect = event.getEffectInstance().getEffect();

        if (!isHarmful(effect)) return;
        if (!AdaptationManager.hasEffectImmunity(player, effect.value())) return;

        event.setResult(Event.Result.DENY);
    }

    /** 免疫之前中的、或者别人给的 → 立刻摘掉。 */
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {

        Player player = event.getEntity();
        if (player.level().isClientSide) return;
        if (player.tickCount % 10 != 0) return;
        if (!WheelHandler.hasWheel(player)) return;

        // 拷一份再遍历：removeEffect 会改原集合
        for (MobEffectInstance instance : new ArrayList<>(player.getActiveEffects())) {
            Holder<MobEffect> effect = instance.getEffect();

            if (!isHarmful(effect)) continue;
            if (AdaptationManager.hasEffectImmunity(player, effect)) {
                player.removeEffect(effect);
            }
        }
    }

    private static boolean isHarmful(Holder<MobEffect> effect) {
        return !effect.value().isBeneficial();
    }
}
