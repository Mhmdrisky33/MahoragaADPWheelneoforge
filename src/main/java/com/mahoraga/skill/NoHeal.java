package com.mahoraga.skill;

import com.mahoraga.MahoragaMod;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.event.entity.living.LivingHealEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * 「禁止治疗」—— 独特破解之一。
 *
 * 被打上标记的生物在一段时间内**一点血都回不了**：
 * 靠 {@code LivingHealEvent} 直接取消，比给它挂一堆中毒省事得多。
 *
 * ⚠️ 标记存在目标的 **PersistentData** 里（不是玩家身上）——
 * 破解是"打在它身上"的，跟谁打的无关。
 */
@EventBusSubscriber(modid = MahoragaMod.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class NoHeal {
    private static final String KEY = "mahoraga_no_heal";

    private NoHeal() {
    }

    /** 打上"禁止治疗"标记。 */
    public static void mark(LivingEntity target, int ticks) {
        target.getPersistentData().putLong(KEY, target.level().getGameTime() + ticks);
    }

    public static boolean active(LivingEntity target) {
        long until = target.getPersistentData().getLong(KEY);
        return until > 0L && until > target.level().getGameTime();
    }

    @SubscribeEvent
    public static void onHeal(LivingHealEvent event) {
        LivingEntity entity = event.getEntity();

        if (entity.level().isClientSide) return;
        if (!active(entity)) return;

        event.setCanceled(true);
    }
}
