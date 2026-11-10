package dev.mahoraga.registry;

import dev.mahoraga.MahoragaMod;
import dev.mahoraga.item.BladeItem;
import dev.mahoraga.item.WheelItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tiers;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MahoragaMod.MODID);

    public static final DeferredItem<Item> WHEEL = ITEMS.register("mahoraga_wheel",
            () -> new WheelItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC).fireResistant()));

    public static final DeferredItem<Item> BLADE = ITEMS.register("mahoraga_blade",
            () -> new BladeItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC).fireResistant()
                    .attributes(SwordItem.createAttributes(Tiers.NETHERITE, 3, -2.4F))));
}
