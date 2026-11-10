package com.mahoraga.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/**
 * <b>眩晕</b> —— 全身被按住、只剩眼珠子能动。
 *
 * <p>这是「无量空处」砸下来的那一下。抄自原模组的 {@code STUN} 效果，
 * 但原版 Minecraft 没有现成的"不能动"，所以这里用两层保险：
 *
 * <ol>
 *   <li><b>属性修饰符把移动速度乘 0</b>（{@code MULTIPLY_TOTAL -1.0}）——
 *       🔴 用属性而不是"每 tick 清速度"是有讲究的：<b>效果一到期，原版会自动把修饰符摘掉</b>，
 *       生物立刻恢复行动。写成清速度就还得自己盯着"什么时候结束"，漏一次就把怪物永久定住了。</li>
 *   <li><b>每 tick 把水平速度抹掉</b> —— 有些生物（扑击的、被击退的、坐骑）身上已经带着惯性，
 *       光靠速度属性降到 0 拉不回来，这一下负责把它们钉在原地。</li>
 * </ol>
 *
 * <p>⚠️ <b>不拦攻击</b>：范围里的怪还是能挥爪子打你。眩晕要的是"动不了"，
 * 不是"变成靶子"——真的把 AI 全掐了，拍出来反而像卡了 bug。
 */
public class StunEffect extends MobEffect {
    /** 移速修饰符的固定 UUID（属性类改动一律固定 UUID + 可自动摘除）。 */
    private static final ResourceLocation SPEED_MODIFIER = ResourceLocation.fromNamespaceAndPath("mahoraga", "stun_speed");

    public StunEffect() {
        super(MobEffectCategory.HARMFUL, 0x2B0F45);

        this.addAttributeModifier(Attributes.MOVEMENT_SPEED, SPEED_MODIFIER,
                -1.0D, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
    }

    /** 每 tick 都要生效（眩晕不是"每秒掉一次血"那种）。 */
    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        return true;
    }

    @Override
    public boolean applyEffectTick(LivingEntity entity, int amplifier) {
        Vec3 motion = entity.getDeltaMovement();

        // 水平速度清零、**保留垂直**（不然摔下来的时候会悬在半空）
        entity.setDeltaMovement(0.0D, Math.min(motion.y, 0.0D), 0.0D);

        // 不标记的话客户端还在按老速度往前滑，看着像"漂移"
        entity.hurtMarked = true;
        return true;
    }
}
