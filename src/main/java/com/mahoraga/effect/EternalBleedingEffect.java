package com.mahoraga.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/**
 * 「永恒流血」—— 搬自 Re:Zero（hahueuh）死亡之神那一套。
 *
 * 它本身只是个**标记**：真正的掉血逻辑在
 * {@link com.mahoraga.skill.EternalBleeding#tick}，每 3 秒按"伤口层数"扣一次。
 *
 * "永恒"两个字落在两处：
 * - {@link #getCurativeItems()} 返回空 → 牛奶、蜂蜜这类"治百病"的东西对它无效；
 * - {@code MobEffectEvent.Remove} 里另有一道拦截 → **只要伤口还在，
 *   连 `/effect clear` 都摘不掉**。
 */
public class EternalBleedingEffect extends MobEffect {
    public EternalBleedingEffect(MobEffectCategory category, int color) {
        super(category, color);
    }

    /**
     * 伤害由我们自己按 tick 算，不交给原版的"效果周期"——
     * 那个最短也要 1 秒一跳，而且它只会调用 `applyEffectTick`，控制不了"清无敌帧"这些细节。
     */
    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        return false;
    }
}
