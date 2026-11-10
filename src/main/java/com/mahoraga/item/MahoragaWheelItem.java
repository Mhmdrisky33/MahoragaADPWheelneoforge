package com.mahoraga.item;

import com.mahoraga.handler.WheelHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 退魔法轮（物品）。使用后玩家头顶永久出现法轮。
 * 跨维度、重登、死亡都不会丢（标记写在玩家自身的持久数据里）。
 */
public class MahoragaWheelItem extends Item {
    public MahoragaWheelItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (!level.isClientSide) {
            WheelHandler.grant(player);

            if (!player.getAbilities().instabuild) {
                stack.shrink(1);
            }

            player.displayClientMessage(Component.translatable("chat.mahoraga.wheel.granted")
                    .withStyle(ChatFormatting.GOLD), false);
        }

        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
}
