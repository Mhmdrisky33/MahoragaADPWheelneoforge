package dev.mahoraga.item;

import dev.mahoraga.data.AdaptData;
import dev.mahoraga.registry.ModAttachments;
import dev.mahoraga.registry.ModItems;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public class WheelItem extends Item {
    public WheelItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            return InteractionResultHolder.success(stack);
        }
        AdaptData data = player.getData(ModAttachments.ADAPT);
        if (data.wheel) {
            player.displayClientMessage(Component.translatable("chat.mahoraga.wheel.already"), true);
            return InteractionResultHolder.fail(stack);
        }
        data.wheel = true;
        stack.shrink(1);

        ItemStack blade = new ItemStack(ModItems.BLADE.get());
        if (!player.getInventory().add(blade)) {
            player.drop(blade, false);
        }
        player.displayClientMessage(Component.translatable("chat.mahoraga.wheel.granted"), false);
        level.playSound(null, player.blockPosition(), SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 1.0F, 0.6F);
        return InteractionResultHolder.consume(stack);
    }
}
