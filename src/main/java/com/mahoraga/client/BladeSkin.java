package com.mahoraga.client;

import com.mahoraga.MahoragaMod;
import com.mahoraga.handler.BladeHandler;
import com.mahoraga.registry.ModItems;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomModelData;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * 退魔之剑的「剑刃皮肤」——让剑刃随适应状态变色、并且一呼一吸地亮。
 *
 * ==================== 怎么做到的 ====================
 * 三张贴图 + 一份模型，靠物品的 {@code CustomModelData} 切换：
 *
 * | CustomModelData | 模型 | 剑刃 |
 * |---|---|---|
 * | 0（默认） | `mahoraga_blade` | 原本的黑刃 |
 * | 1 | `mahoraga_blade_fire` | 烧红的橙刃 |
 * | 2 | `mahoraga_blade_frost` | 结霜的冰刃 |
 *
 * **呼吸感**由这里负责：模型里剑刃那 66 个面打了 `tintindex: 0`，
 * 渲染每一帧都会来问这个颜色 —— 于是用时间算一条正弦，亮度在 36%~100% 之间来回走，
 * 看起来就是刃在呼吸。**不用改物品数据、不用发网络包、不占带宽。**
 *
 * ⚠️ 染色是**乘法**，所以只能压暗、不能提亮 —— 这也是为什么
 * 火焰/寒霜必须换贴图（原本的刃是深黑，乘什么颜色都还是黑）。
 *
 * 服务端那边（{@code handler/BladeHandler#updateSkin}）只负责在适应跨过 100% 时
 * 改一下 CustomModelData，其余全在客户端算。
 */
@EventBusSubscriber(modid = MahoragaMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class BladeSkin {
    /** 一次呼吸的长度（毫秒）。 */
    private static final long BREATH_MS = 2600L;
    /** 最暗时保留多少亮度（越低闪得越狠）。 */
    private static final float MIN_BRIGHTNESS = 0.36F;

    private BladeSkin() {
    }

    @SubscribeEvent
    public static void onItemColors(RegisterColorHandlersEvent.Item event) {
        event.register(BladeSkin::bladeColor, ModItems.MAHORAGA_BLADE.get());
    }

    /**
     * @param tintIndex 只有 0 是"剑刃层"（护手那些面没打 tintindex，根本不会走到这里）
     */
    private static int bladeColor(ItemStack stack, int tintIndex) {
        if (tintIndex != 0) return 0xFFFFFFFF;

        int mode = modeOf(stack);
        if (mode == BladeHandler.SKIN_NONE) return 0xFFFFFFFF;          // 没适应 → 原色

        float pulse = brightness();

        if (mode == BladeHandler.SKIN_FIRE) {
            // 偏暖：红满、绿稍降、蓝明显降 → 让橙红更"烫"
            return argb(pulse, 1.00F, 0.92F, 0.80F);
        }
        // 寒霜：偏冷
        return argb(pulse, 0.84F, 0.94F, 1.00F);
    }

    /**
     * 呼吸曲线：一条正弦，回到底再抬起来。
     * 用 `System.nanoTime` 而不是 tick —— 物品栏和世界里的渲染都走这里，
     * 拿 tick 会和游戏暂停/卡顿绑在一起。
     */
    private static float brightness() {
        double phase = (System.nanoTime() / 1_000_000L % BREATH_MS) / (double) BREATH_MS * Math.PI * 2.0;

        return MIN_BRIGHTNESS + (1.0F - MIN_BRIGHTNESS) * (float) ((Math.sin(phase) + 1.0D) / 2.0D);
    }

    private static int argb(float scale, float r, float g, float b) {
        int ri = clamp255(r * scale);
        int gi = clamp255(g * scale);
        int bi = clamp255(b * scale);

        return 0xFF000000 | (ri << 16) | (gi << 8) | bi;
    }

    private static int clamp255(float v) {
        return (int) Math.max(0.0F, Math.min(255.0F, v * 255.0F));
    }

    /** 读物品上的皮肤编号（服务端写进来的）。 */
    public static int modeOf(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_MODEL_DATA, CustomModelData.DEFAULT).value();
    }
}
