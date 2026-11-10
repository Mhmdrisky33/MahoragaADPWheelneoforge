package com.mahoraga.registry;

import com.mahoraga.MahoragaMod;
import com.mahoraga.entity.SlashProjectile;
import com.mahoraga.entity.WheelEntity;
import com.mahoraga.entity.WorldSlashProjectile;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.neoforge.registries.DeferredHolder;

public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(BuiltInRegistries.ENTITY_TYPE, MahoragaMod.MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<WheelEntity>> WHEEL = ENTITIES.register("wheel",
            () -> EntityType.Builder.<WheelEntity>of(WheelEntity::new, MobCategory.MISC)
                    .sized(1.0F, 0.5F)
                    .clientTrackingRange(10)
                    .build(ResourceLocation.fromNamespaceAndPath(MahoragaMod.MOD_ID, "wheel")));

    /**
     * 剑气 —— 普通攻击挥出去的那道飞行斩击。
     * 尺寸小、每 tick 同步（飞得快，跟头更新会看着一顿一顿）。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<SlashProjectile>> SLASH = ENTITIES.register("slash",
            () -> EntityType.Builder.<SlashProjectile>of(SlashProjectile::new, MobCategory.MISC)
                    .sized(0.6F, 0.6F)
                    .setUpdateInterval(1)
                    .clientTrackingRange(8)
                    .build(ResourceLocation.fromNamespaceAndPath(MahoragaMod.MOD_ID, "slash")));

    /**
     * 空间斩 —— 退魔之剑右键放出的巨幅斩击（抄原模组的「世界斩」）。
     * 刀身最长 30 格，跟头必须每 tick 同步，追踪范围也给大一点。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<WorldSlashProjectile>> WORLD_SLASH =
            ENTITIES.register("world_slash",
                    () -> EntityType.Builder.<WorldSlashProjectile>of(WorldSlashProjectile::new, MobCategory.MISC)
                            .sized(1.0F, 1.0F)
                            .setUpdateInterval(1)
                            .clientTrackingRange(16)
                            .build(ResourceLocation.fromNamespaceAndPath(MahoragaMod.MOD_ID, "world_slash")));

    private ModEntities() {
    }

    public static void register(IEventBus bus) {
        ENTITIES.register(bus);
    }
}
