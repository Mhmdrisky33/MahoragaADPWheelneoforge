package dev.mahoraga.item;

import dev.mahoraga.registry.ModComponents;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.item.TooltipFlag;

public class BladeItem extends SwordItem {
    public BladeItem(Properties props) {
        super(Tiers.NETHERITE, props);
    }

    public static boolean isBroken(ItemStack stack) {
        return stack.getOrDefault(ModComponents.BROKEN.get(), false);
    }

    public static void setBroken(ItemStack stack, boolean broken) {
        stack.set(ModComponents.BROKEN.get(), broken);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return !isBroken(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(isBroken(stack)
                ? "item.mahoraga.mahoraga_blade.broken"
                : "item.mahoraga.mahoraga_blade.intact").withStyle(ChatFormatting.GRAY));
    }
}
