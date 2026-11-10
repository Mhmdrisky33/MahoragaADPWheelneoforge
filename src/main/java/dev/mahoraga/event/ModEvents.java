package dev.mahoraga.event;

import dev.mahoraga.data.AdaptData;
import dev.mahoraga.item.BladeItem;
import dev.mahoraga.registry.ModAttachments;
import dev.mahoraga.registry.ModItems;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

public class ModEvents {
    /** Persen adaptasi yang didapat tiap kejadian. */
    public static final float STEP = 10.0F;
    /** Di angka ini musuh/damage sepenuhnya teradaptasi. */
    public static final float FULL = 100.0F;
    /** Batas atas bonus serangan. */
    public static final float ATTACK_CAP = 300.0F;

    public static void addCreative(BuildCreativeModeTabContentsEvent e) {
        if (e.getTabKey() == CreativeModeTabs.COMBAT) {
            e.accept(ModItems.WHEEL);
            e.accept(ModItems.BLADE);
        }
    }

    // ---------------------------------------------------------------- damage
    public static void onIncomingDamage(LivingIncomingDamageEvent e) {
        LivingEntity victim = e.getEntity();
        if (victim.level().isClientSide) return;
        DamageSource src = e.getSource();

        // --- adaptasi serangan: pemain memukul sesuatu
        if (src.getEntity() instanceof ServerPlayer attacker && attacker != victim) {
            AdaptData d = attacker.getData(ModAttachments.ADAPT);
            if (d.wheel) {
                String key = entityKey(victim);
                float before = d.attack.getOrDefault(key, 0F);
                float after = Math.min(ATTACK_CAP, before + STEP);
                d.attack.put(key, after);
                e.setAmount(e.getAmount() * (1F + after / 100F)); // +100% di 100%, +300% di 300%
                report(attacker, "attack", key, before, after);
            }
        }

        // --- adaptasi pertahanan: pemain terkena damage
        if (victim instanceof ServerPlayer player && !src.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            AdaptData d = player.getData(ModAttachments.ADAPT);
            if (!d.wheel) return;

            String key = sourceKey(src);
            float before = d.defense.getOrDefault(key, 0F);

            if (before >= FULL) {
                e.setCanceled(true); // kebal total
                return;
            }
            e.setAmount(e.getAmount() * (1F - before / FULL)); // makin teradaptasi, makin kecil
            float after = Math.min(FULL, before + STEP);
            d.defense.put(key, after);
            report(player, "defense", key, before, after);
        }
    }

    private static void report(ServerPlayer p, String track, String key, float before, float after) {
        Component name = displayName(key);
        if (before < FULL && after >= FULL) {
            p.displayClientMessage(Component.translatable("chat.mahoraga.adapt.complete." + track, name), false);
            onAdaptationComplete(p);
        } else if (after != before) {
            p.displayClientMessage(Component.translatable("chat.mahoraga.adapt.progress",
                    Component.translatable("mahoraga.track." + track), name,
                    String.format("%.0f%%", after)), true);
        }
    }

    /** Roda berputar: pulihkan HP ke 70% dan perbaiki semua Blade. */
    private static void onAdaptationComplete(ServerPlayer p) {
        float target = p.getMaxHealth() * 0.7F;
        if (p.getHealth() < target) p.setHealth(target);
        p.level().playSound(null, p.blockPosition(), SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 1F, 1.4F);
        p.displayClientMessage(Component.translatable("chat.mahoraga.adapt.spin"), false);

        boolean restored = false;
        Inventory inv = p.getInventory();
        for (ItemStack s : inv.items) restored |= restore(s);
        for (ItemStack s : inv.offhand) restored |= restore(s);
        if (restored) p.displayClientMessage(Component.translatable("chat.mahoraga.blade.restored"), false);
    }

    private static boolean restore(ItemStack s) {
        if (s.is(ModItems.BLADE.get()) && BladeItem.isBroken(s)) {
            BladeItem.setBroken(s, false);
            return true;
        }
        return false;
    }

    // ----------------------------------------------------------------- death
    /** Blade of Exorcism yang utuh menahan satu kematian. */
    public static void onDeath(LivingDeathEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || p.level().isClientSide) return;
        if (e.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return; // /kill & void

        Inventory inv = p.getInventory();
        ItemStack blade = ItemStack.EMPTY;
        for (ItemStack s : inv.items) {
            if (s.is(ModItems.BLADE.get()) && !BladeItem.isBroken(s)) { blade = s; break; }
        }
        if (blade.isEmpty()) {
            for (ItemStack s : inv.offhand) {
                if (s.is(ModItems.BLADE.get()) && !BladeItem.isBroken(s)) { blade = s; break; }
            }
        }
        if (blade.isEmpty()) return;

        BladeItem.setBroken(blade, true);
        e.setCanceled(true);
        p.setHealth(p.getMaxHealth() * 0.5F);
        p.removeAllEffects();
        p.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 100, 4));
        p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 1));
        p.level().broadcastEntityEvent(p, (byte) 35); // animasi totem
        p.displayClientMessage(Component.translatable("chat.mahoraga.blade.saved"), false);
    }

    // --------------------------------------------------------------- helpers
    public static String entityKey(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }

    /** Kunci adaptasi: tipe penyerang jika ada (non-pemain), kalau tidak tipe damage. */
    public static String sourceKey(DamageSource src) {
        Entity attacker = src.getEntity();
        if (attacker != null && !(attacker instanceof Player)) {
            return entityKey(attacker);
        }
        String path = src.typeHolder().unwrapKey().map(k -> k.location().getPath()).orElse("generic");
        return "damage:" + path;
    }

    public static Component displayName(String key) {
        if (key.startsWith("damage:")) {
            String path = key.substring(7);
            return Component.translatableWithFallback("mahoraga.damage." + path, path);
        }
        ResourceLocation rl = ResourceLocation.tryParse(key);
        if (rl != null && BuiltInRegistries.ENTITY_TYPE.containsKey(rl)) {
            return BuiltInRegistries.ENTITY_TYPE.get(rl).getDescription();
        }
        return Component.literal(key);
    }
}
