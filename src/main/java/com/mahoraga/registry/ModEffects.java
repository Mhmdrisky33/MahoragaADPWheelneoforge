package com.mahoraga.registry;

import com.mahoraga.MahoragaMod;
import com.mahoraga.effect.EternalBleedingEffect;
import com.mahoraga.effect.StunEffect;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.neoforge.registries.DeferredHolder;

/**
 * 本模组的药水效果。
 *
 * - **永恒流血**（从 Re:Zero 的死亡之神那套搬过来，做成"随机适应库"里的第一条适应）；
 * - **眩晕**（2026-09-15 加）—— 「无量空处」砸下来的那一下。
 */
public final class ModEffects {
    public static final DeferredRegister<MobEffect> EFFECTS =
            DeferredRegister.create(BuiltInRegistries.MOB_EFFECT, MahoragaMod.MOD_ID);

    /**
     * 永恒流血：每多打一下伤口就深一层，**每 3 秒按层数扣一次血**，
     * 而且牛奶、`/effect clear` 一概治不好 —— 只有目标死掉才会清空。
     */
    public static final DeferredHolder<MobEffect, MobEffect> BLEEDING = EFFECTS.register("bleeding",
            () -> new EternalBleedingEffect(MobEffectCategory.HARMFUL, 0x8B0000));

    /**
     * **眩晕** —— 动不了。见 {@link StunEffect}（拿移动速度属性钉死的，效果一到期自动恢复）。
     */
    public static final DeferredHolder<MobEffect, MobEffect> STUN = EFFECTS.register("stun",
            StunEffect::new);

    private ModEffects() {
    }

    public static void register(IEventBus bus) {
        EFFECTS.register(bus);
    }
}
