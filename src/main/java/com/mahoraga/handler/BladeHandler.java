package com.mahoraga.handler;

import com.mahoraga.MahoragaMod;
import com.mahoraga.adapt.AdaptationData;
import com.mahoraga.adapt.AdaptationKeys;
import com.mahoraga.adapt.AdaptationManager;
import com.mahoraga.adapt.Track;
import com.mahoraga.registry.ModItems;
import com.mahoraga.skill.MiningAdaptation;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.enchantment.Enchantments;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 退魔之剑的隐藏效果：免死一次 + 剑刃会随适应变色。
 *
 * 免死：触发时取消这次死亡 → 回到 1 颗心 → 剑标记为「损坏」（损坏期间不再免死）。
 * 等下一次适应完成调用 {@link #refresh}，剑恢复完好。
 *
 * 剑刃皮肤：见 {@link #updateSkin}。服务端只管在适应跨过 100% 时改一个编号，
 * "一呼一吸"的明暗是客户端 {@code client/BladeSkin} 算的。
 */
public final class BladeHandler {
    private static final String BROKEN_TAG = "mahoraga_blade_broken";
    private static final float REVIVE_HEALTH = 2.0F;   // 1 颗心

    /** 剑生来就带的附魔等级 —— **亡灵杀手 V**（"退魔之剑"的"退魔"就体现在这儿）。 */
    public static final int SMITE_LEVEL = 5;

    /** 剑刃皮肤：原色（还没适应对应伤害）。 */
    public static final int SKIN_NONE = 0;
    /** 剑刃皮肤：烧红（适应了火）。 */
    public static final int SKIN_FIRE = 1;
    /** 剑刃皮肤：结霜（适应了寒冷）。 */
    public static final int SKIN_FROST = 2;

    private BladeHandler() {
    }

    public static boolean isBroken(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getBoolean(BROKEN_TAG);
    }

    private static void setBroken(ItemStack stack, boolean broken) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(BROKEN_TAG, broken));
    }

    private static ItemStack findIntactBlade(Player player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);

            if (stack.is(ModItems.MAHORAGA_BLADE.get()) && !isBroken(stack)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private static boolean hasAnyBlade(Player player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (player.getInventory().getItem(i).is(ModItems.MAHORAGA_BLADE.get())) return true;
        }
        return false;
    }

    /** 造一把新的退魔之剑 —— **自带亡灵杀手 V**。 */
    public static ItemStack newBlade(Player player) {
        ItemStack stack = new ItemStack(ModItems.MAHORAGA_BLADE.get());
        Holder<Enchantment> smite = player.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SMITE);
        EnchantmentHelper.updateEnchantments(stack, enchantments -> enchantments.set(smite, SMITE_LEVEL));
        return stack;
    }

    /**
     * 附魔没到位就补回来（被砂轮磨过、被铁砧洗过、或者别的东西动过）。
     * 它和剑是一体的，不该丢。每 20 tick 跟着 {@link #updateSkin} 顺路查一次，开销可忽略。
     *
     * 🔴 **时运不能用 `stack.enchant()` 加** —— `EnchantmentCategory.DIGGER` 要求
     * `item instanceof DiggerItem`，而退魔之剑是 `SwordItem`，`enchant()` 会**静默失败**。
     * 必须走 {@link EnchantmentHelper#setEnchantments} 直接写 NBT（它不做类别校验）。
     * 所以这里一次性把「亡灵杀手 V + 时运 N」整份重写。
     */
    private static void ensureEnchants(Player player, ItemStack stack) {
        int fortune = MiningAdaptation.fortune(player);

        var enchantmentRegistry = player.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        Holder<Enchantment> smite = enchantmentRegistry.getOrThrow(Enchantments.SMITE);
        Holder<Enchantment> fortuneEnchantment = enchantmentRegistry.getOrThrow(Enchantments.FORTUNE);
        boolean smiteOk = EnchantmentHelper.getItemEnchantmentLevel(smite, stack) >= SMITE_LEVEL;
        boolean fortuneOk = EnchantmentHelper.getItemEnchantmentLevel(fortuneEnchantment, stack) == fortune;

        if (smiteOk && fortuneOk) return;

        EnchantmentHelper.updateEnchantments(stack, enchants -> {
            enchants.set(smite, SMITE_LEVEL);
            if (fortune > 0) enchants.set(fortuneEnchantment, fortune);
            else enchants.removeIf(holder -> holder.is(Enchantments.FORTUNE));
        });
    }

    /** 把剑塞进背包；塞不下就丢在脚边（绝不能凭空消失）。 */
    private static void give(Player player) {
        ItemStack stack = newBlade(player);

        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    /**
     * 和退魔法轮绑定时调用一次：魔虚罗的剑本来就是身体的一部分。
     * 没有剑就长一把出来（有就什么都不做，不会白送第二把）。
     */
    public static void ensureBlade(Player player) {
        if (hasAnyBlade(player)) return;

        give(player);
        // 🔴 2026-09-15 主人要求：**去掉"退魔之剑从体内长出来"这类描述文本**（拍视频不要这些设定说明）。
        //    剑照给，只是不再播报。
    }

    /**
     * 适应完成时调用：
     * - 有损坏的剑 → 修复；
     * - 一把都没有 → 重新长一把（对应"下次适应又会长出来一把新的"）。
     */
    public static void refresh(Player player) {
        boolean repaired = false;

        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);

            if (stack.is(ModItems.MAHORAGA_BLADE.get()) && isBroken(stack)) {
                setBroken(stack, false);
                repaired = true;
            }
        }

        if (repaired) {
            player.displayClientMessage(Component.translatable("chat.mahoraga.blade.restored")
                    .withStyle(ChatFormatting.GOLD), true);
            return;
        }

        if (!hasAnyBlade(player)) {
            give(player);      // 剑回来了，但不播报（同上去掉描述文本）
        }
    }

    /**
     * **把退魔之剑从身上全部收走** —— {@code /mahoraga wheel reset} 用。
     *
     * <p>不收的话，摘了法轮人还是拎着剑站在那儿 ——
     * 剑在设定上是"魔虚罗身体的一部分"，法轮没了它也不该还在
     * （主人 2026-09-15 报的"摘不干净"，这也是其中一样）。
     */
    public static void removeBlade(Player player) {
        if (player.level().isClientSide) return;

        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (player.getInventory().getItem(i).is(ModItems.MAHORAGA_BLADE.get())) {
                player.getInventory().setItem(i, ItemStack.EMPTY);
            }
        }

        if (player.getOffhandItem().is(ModItems.MAHORAGA_BLADE.get())) {
            player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, ItemStack.EMPTY);
        }
    }

    // ==================== 剑刃皮肤 ====================

    /**
     * 每秒扫一遍背包，把剑的皮肤编号刷新成当前该有的样子。
     *
     * 改的是物品的 {@code CustomModelData} —— 原版就支持的模型选择器，
     * 客户端读同一个值去挑模型（0 原色 / 1 火焰 / 2 寒霜）。
     * **值没变就不写**，免得每秒钟白发一次物品同步包。
     */
    public static void updateSkin(Player player) {
        int mode = skinMode(player);

        announceSkin(player, mode);

        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);

            if (stack.is(ModItems.MAHORAGA_BLADE.get())) {
                ensureEnchants(player, stack);
                setSkin(stack, mode);
            }
        }
    }

    /** 上一次播报过的剑刃形态 —— 只在"刚变成火/冰"时报一次。 */
    private static final Map<UUID, Integer> LAST_SKIN = new ConcurrentHashMap<>();

    /**
     * **剑刃变色时在聊天栏报一句**（主人 2026-09-14 报"试了没反应"，根因就是这里原来什么都不说 ——
     * 练成了、看漏了、压根没练成，三件事在游戏里长得一模一样）。
     *
     * 规矩和 `BladeAbilities#announceUnlocks` 一样：**第一次见到玩家只记不报**
     * （否则每次重登都会把已经解锁的重念一遍）；掉回原色（进度衰减）静默。
     */
    private static void announceSkin(Player player, int mode) {
        UUID id = player.getUUID();
        Integer last = LAST_SKIN.get(id);

        if (last == null) {
            LAST_SKIN.put(id, mode);
            return;
        }
        if (last == mode) return;

        LAST_SKIN.put(id, mode);

        // 同时写一行日志 —— 排查"剑到底变没变"时，聊天栏可能被漏看，日志跑不掉
        MahoragaMod.LOGGER.info("[mahoraga] blade skin -> {} ({})", mode,
                player.getName().getString());

        if (mode == SKIN_FIRE) {
            player.displayClientMessage(Component.translatable("chat.mahoraga.blade.skin.fire")
                    .withStyle(ChatFormatting.GOLD), false);
        } else if (mode == SKIN_FROST) {
            player.displayClientMessage(Component.translatable("chat.mahoraga.blade.skin.frost")
                    .withStyle(ChatFormatting.AQUA), false);
        }
    }

    /**
     * 现在该用哪张皮肤。
     *
     * - 火：**火焰 / 着火 / 岩浆** 任意一条适应满 100%（都是"被火搞过"）
     * - 寒冷：**冰冻** 满 100%
     * - 两个都满 → 用进度更高的那个
     *
     * 公开给 {@code skill/HitFx} 用 —— 命中特效的颜色也走同一套判据。
     */
    public static int skinMode(Player player) {
        AdaptationData data = AdaptationManager.data(player);
        float complete = AdaptationManager.COMPLETE;

        float fire = Math.max(data.get(Track.DEFENSE, AdaptationKeys.FIRE),
                Math.max(data.get(Track.DEFENSE, AdaptationKeys.FIRE_IN),
                        data.get(Track.DEFENSE, AdaptationKeys.LAVA)));
        float frost = data.get(Track.DEFENSE, AdaptationKeys.FREEZE);

        boolean hasFire = fire >= complete;
        boolean hasFrost = frost >= complete;

        if (hasFire && hasFrost) return fire >= frost ? SKIN_FIRE : SKIN_FROST;
        if (hasFire) return SKIN_FIRE;
        if (hasFrost) return SKIN_FROST;

        return SKIN_NONE;
    }

    /**
     * 读剑上**实际写着的**皮肤编号（`CustomModelData`）——
     * 诊断用：和 {@link #skinMode} 的结果对照，就能看出断在"判据"还是"写入"。
     */
    public static int skinTag(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_MODEL_DATA, CustomModelData.DEFAULT).value();
    }

    private static void setSkin(ItemStack stack, int mode) {
        if (skinTag(stack) == mode) return; // 没变，别触发同步
        if (mode <= SKIN_NONE) {
            stack.remove(DataComponents.CUSTOM_MODEL_DATA);
        } else {
            stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(mode));
        }
    }

    /**
     * **免死一次** —— 由 {@code AdaptationManager} 的 `LivingDeathEvent` 调。
     *
     * ⚠️ **2026-09-14 搬家说明**：这几条（免死 + 每秒刷新剑刃皮肤）原来挂在本类内部的
     * `@EventBusSubscriber` 嵌套类上。但排查"火刃不变色"时发现：
     * 存档里 `mahoraga_adapt` 的 `def → damage:minecraft:lava` 已经 **414%**（判据早就满足），
     * 而剑的 NBT 里**连 `CustomModelData` 这个键都不存在** ——
     * 说明那套每秒刷新**压根没跑过**（怀疑嵌套类没被自动注册）。
     * 于是统一搬去 `AdaptationManager` —— 它的 tick 已经被证实确实在跑（适应进度一直在涨）。
     */
    public static void trySaveFromDeath(Player player, LivingDeathEvent event) {
        ItemStack blade = findIntactBlade(player);
        if (blade.isEmpty()) return;                     // 没有完好的剑 → 正常死

        event.setCanceled(true);
        player.setHealth(REVIVE_HEALTH);
        setBroken(blade, true);

        player.level().playSound(null, player.blockPosition(), SoundEvents.TOTEM_USE,
                SoundSource.PLAYERS, 1.0F, 1.0F);
        player.displayClientMessage(Component.translatable("chat.mahoraga.blade.saved")
                .withStyle(ChatFormatting.GOLD), false);
    }
}
