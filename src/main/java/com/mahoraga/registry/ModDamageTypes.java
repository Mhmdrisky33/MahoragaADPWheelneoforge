package com.mahoraga.registry;

import com.mahoraga.MahoragaMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;

import java.util.function.Supplier;

/**
 * 本模组自己的伤害类型。
 *
 * 为什么非要自建伤害类型：借用玩家的普通攻击源的话，这些附加伤害会
 * (1) 吃到攻击适应的伤害倍率、(2) 反过来给攻击适应喂进度 —— 自己喂自己，越打越强，
 * 主人明确要求两块互不冲突。
 *
 * 注意：1.20.1 的 {@code DamageSources#source(ResourceKey, Entity, Entity)} 是 **private**，
 * 想自建伤害源只能自己 new {@code DamageSource(Holder<DamageType>, Entity, Entity)}。
 *
 * 配套数据文件：
 * - `data/mahoraga/damage_type/counter.json`
 * - `data/mahoraga/damage_type/spell.json`
 * - `data/minecraft/tags/damage_type/bypasses_armor.json`（把 spell 挂进"无视护甲"）
 */
public final class ModDamageTypes {
    /** 退魔之剑的反击伤害。 */
    public static final ResourceKey<DamageType> COUNTER = ResourceKey.create(
            Registries.DAMAGE_TYPE, ResourceLocation.fromNamespaceAndPath(MahoragaMod.MOD_ID, "counter"));

    /**
     * 攻击适应打出来的**法术伤害**：
     * 100% 附带"一半基础伤害"。
     * 已挂进原版 `minecraft:bypasses_armor` 标签 → **无视护甲**（就是主人说的"破甲"）。
     */
    public static final ResourceKey<DamageType> SPELL = ResourceKey.create(
            Registries.DAMAGE_TYPE, ResourceLocation.fromNamespaceAndPath(MahoragaMod.MOD_ID, "spell"));

    /**
     * **真伤** —— 攻击适应 200% 起追加，比法术伤害更彻底。
     *
     * 法术伤害只是"无视护甲"，真伤额外挂进了：
     * `bypasses_enchantments`（保护附魔）`bypasses_resistance`（抗性提升）`bypasses_effects`（伤害吸收）——
     * 也就是**护甲、附魔、药水、吸收全都不算数**，打多少就是多少。
     */
    public static final ResourceKey<DamageType> TRUE_DAMAGE = ResourceKey.create(
            Registries.DAMAGE_TYPE, ResourceLocation.fromNamespaceAndPath(MahoragaMod.MOD_ID, "true_damage"));

    /**
     * **永恒流血**每 3 秒扣的那一下（来自"随机适应库"里的「永恒流血」）。
     * 同样无视护甲（和 Re:Zero 那边直接用 `magic` 源是一个道理）。
     */
    public static final ResourceKey<DamageType> BLEED = ResourceKey.create(
            Registries.DAMAGE_TYPE, ResourceLocation.fromNamespaceAndPath(MahoragaMod.MOD_ID, "bleed"));

    /**
     * **雷电附加伤害** —— 「雷击」适应满 100% 之后每次攻击附带的那一下。
     * 挂进了 `bypasses_armor`（和原版闪电同等待遇：无视护甲）。
     */
    public static final ResourceKey<DamageType> LIGHTNING = ResourceKey.create(
            Registries.DAMAGE_TYPE, ResourceLocation.fromNamespaceAndPath(MahoragaMod.MOD_ID, "lightning"));

    private ModDamageTypes() {
    }

    /** 反击伤害源：直击者是玩家，元凶是攻击者。 */
    public static DamageSource counter(Entity player, Entity attacker) {
        return build(COUNTER, player, attacker, () -> player.damageSources().generic());
    }

    /** 法术伤害源：元凶就是玩家自己（击杀记在玩家头上）。 */
    public static DamageSource spell(Entity player) {
        return build(SPELL, player, player, () -> player.damageSources().indirectMagic(player, player));
    }

    /** 真伤伤害源（无视护甲 / 附魔 / 抗性 / 吸收）。 */
    public static DamageSource trueDamage(Entity player) {
        return build(TRUE_DAMAGE, player, player, () -> player.damageSources().indirectMagic(player, player));
    }

    /** 流血伤害源。 */
    public static DamageSource bleed(Entity player) {
        return build(BLEED, player, player, () -> player.damageSources().indirectMagic(player, player));
    }

    /** 雷电附加伤害源（无视护甲）。 */
    public static DamageSource lightning(Entity player) {
        return build(LIGHTNING, player, player, () -> player.damageSources().indirectMagic(player, player));
    }

    /** 判断这一下是不是我们自己的附加伤害（用来防止自己喂自己、无限递归）。 */
    public static boolean isOwned(DamageSource source) {
        return source.is(COUNTER) || source.is(SPELL) || source.is(TRUE_DAMAGE)
                || source.is(BLEED) || source.is(LIGHTNING);
    }

    /** 拿不到数据文件（万一没加载成功）也不能崩，退回一个近似伤害源。 */
    private static DamageSource build(ResourceKey<DamageType> key, Entity direct, Entity causing,
                                      Supplier<DamageSource> fallback) {
        return direct.level().registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolder(key)
                .<DamageSource>map(holder -> new DamageSource(holder, direct, causing))
                .orElseGet(fallback);
    }
}
