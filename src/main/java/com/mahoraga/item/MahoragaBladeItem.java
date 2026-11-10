package com.mahoraga.item;

import com.mahoraga.handler.BladeHandler;
import com.mahoraga.skill.BladeAbilities;
import com.mahoraga.skill.MiningAdaptation;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 退魔之剑 —— 魔虚罗身体的一部分。
 *
 * - **无限耐久**：{@link #canBeDepleted()} 恒 false —— 砍人、挖石头都不掉耐久，
 *   也不显示耐久条。（剑本来就是身体的一部分，会磨损本身就说不通。）
 * - 隐藏效果：免死一次（只有免死，没有伤害吸收、不加 buff）。触发后剑会「损坏」，
 *   等下一次适应完成，剑自己会长回完好状态。
 */
public class MahoragaBladeItem extends SwordItem {
    public MahoragaBladeItem(Tier tier, int damage, float speed, Properties properties) {
        super(tier, properties.attributes(SwordItem.createAttributes(tier, damage, speed)));
    }

    // ==================== 万能工具 ====================

    /**
     * **万能工具**：挖什么都按"对口的工具"算。
     *
     * ⚠️ `SwordItem` 默认这三样全是"剑的待遇"（挖得慢、挖石头不掉、创造里左键还挖不动），
     * 想做万能工具必须逐个推翻：
     * - {@link #getDestroySpeed} —— 给一个比钻石镐还快的固定速度；
     * - {@link #isCorrectToolForDrops} —— 任何方块都算"用对了工具"，所以**什么都掉**；
     * - {@link #canAttackBlock} —— 剑默认 `false`，不改的话**创造模式里左键挖不了方块**。
     *
     * 但它的**物品标签仍然是剑**（`data/minecraft/tags/items/swords.json`），
     * 锋利/亡灵杀手这些照样吃。
     */
    @Override
    public float getDestroySpeed(ItemStack stack, BlockState state) {
        return MiningAdaptation.UNIVERSAL_SPEED;
    }


    @Override
    public boolean canAttackBlock(BlockState state, Level level, BlockPos pos, Player player) {
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        if (BladeHandler.isBroken(stack)) {
            tooltip.add(Component.translatable("item.mahoraga.mahoraga_blade.broken")
                    .withStyle(ChatFormatting.DARK_RED));
        } else {
            tooltip.add(Component.translatable("item.mahoraga.mahoraga_blade.intact")
                    .withStyle(ChatFormatting.GOLD));
        }
        tooltip.add(Component.translatable("item.mahoraga.mahoraga_blade.ability")
                .withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("item.mahoraga.mahoraga_blade.tool")
                .withStyle(ChatFormatting.GREEN));
        tooltip.add(Component.translatable("item.mahoraga.mahoraga_blade.execute")
                .withStyle(ChatFormatting.DARK_PURPLE));
        tooltip.add(Component.translatable("item.mahoraga.mahoraga_blade.skin")
                .withStyle(ChatFormatting.RED));
        super.appendHoverText(stack, context, tooltip, flag);
    }

    // ==================== 右键 = 特殊能力 ====================

    /**
     * 右键点一下退魔之剑 = **释放当前选中的「特殊能力」**。
     *
     * - **右键** → 放当前选中的那个；
     * - **潜行 + 右键** → 切到下一个已解锁的（聊天栏会报当前是哪个）;
     * - **空手 / 手持别的东西** → 压根走不到这里（只有剑有这个物品类）。
     *
     * ⚠️ 原来这里是"举剑格挡"，**已经删掉**（含 75% 那一档）。
     * 想减伤还有 {@code skill/GuardSkill}：手持退魔剑 + 潜行 = 减伤 50% + 自动反击。
     *
     * 🔴 判定只放服务端 —— `use` 客户端和服务端都会调一遍，两边都执行会放两次。
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (!level.isClientSide) {
            BladeAbilities.use(player);
            player.swing(hand, true);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
}
