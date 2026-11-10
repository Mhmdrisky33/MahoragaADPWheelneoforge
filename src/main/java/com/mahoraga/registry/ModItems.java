package com.mahoraga.registry;

import com.mahoraga.MahoragaMod;
import com.mahoraga.item.MahoragaBladeItem;
import com.mahoraga.item.MahoragaWheelItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.Tiers;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.neoforge.registries.DeferredHolder;

public final class ModItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(BuiltInRegistries.ITEM, MahoragaMod.MOD_ID);

    /** 退魔法轮：右键使用后，头顶永久出现法轮。 */
    public static final DeferredHolder<Item, Item> MAHORAGA_WHEEL = ITEMS.register("mahoraga_wheel",
            () -> new MahoragaWheelItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)));

    /** 退魔之剑：魔虚罗身体的一部分，可挡一次死亡。 */
    public static final DeferredHolder<Item, Item> MAHORAGA_BLADE = ITEMS.register("mahoraga_blade",
            () -> new MahoragaBladeItem(Tiers.NETHERITE, 3, -2.4F,
                    new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)));

    private ModItems() {
    }

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
    }
}
