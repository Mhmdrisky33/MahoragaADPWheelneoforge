package com.mahoraga.skill;

import com.mahoraga.adapt.AdaptationData;
import com.mahoraga.adapt.AdaptationKeys;
import com.mahoraga.adapt.AdaptationManager;
import com.mahoraga.adapt.Track;
import com.mahoraga.entity.WorldSlashProjectile;
import com.mahoraga.handler.WheelHandler;
import com.mahoraga.registry.ModDamageTypes;
import com.mahoraga.registry.ModSounds;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 退魔之剑的「**特殊能力**」—— 右键释放，潜行+右键切换。
 *
 * ==================== 为什么要有这个类（主人 2026-09-14 定） ====================
 *
 * 以前"学来的本事"是散着的：剑气平砍自动挥、末影人的锁是打在怪身上自动生效、
 * 1000% 的必杀也是平砍自动触发 —— 玩家**没有"我主动出招"的感觉**。
 *
 * 现在统一收口到**退魔之剑的右键**：
 * - **右键** = 释放当前选中的能力；
 * - **潜行 + 右键** = 切到下一个已解锁的能力（聊天栏会报"当前是什么"）;
 * - **空手 / 手持别的东西 → 一律不触发**（只有剑才认）。
 *
 * ⚠️ 原来右键是"举剑格挡"，连同 75% 那一档一起**删掉了**（主人说 75% 几乎无敌）。
 * 想减伤还有 {@link GuardSkill}：手持退魔剑 + 潜行 = 减伤 50% + 自动反击。
 *
 * ==================== 解锁条件 ====================
 * 全部挂在**攻击适应**上 —— "从谁身上学来的本事"：
 * - **瞬移**：末影人 攻击适应 ≥ 100%
 * - **空间斩**：**任意**生物 攻击适应 ≥ 1000%（练满一条就算出师）
 */
public final class BladeAbilities {
    private BladeAbilities() {
    }

    /** 一个可释放的特殊能力。 */
    public enum Ability {
        /**
         * **瞬移** —— 学自末影人。
         * 沿准星方向瞬移一段距离，撞墙就停在墙前（会先确认落脚点不卡墙里）。
         */
        TELEPORT("teleport", 40) {
            @Override
            public boolean unlocked(Player player) {
                return AdaptationManager.data(player).get(Track.ATTACK, AdaptationKeys.SPACE)
                        >= AdaptationManager.COMPLETE;
            }

            @Override
            public void cast(Player player) {
                teleport(player);
            }
        },

        /**
         * **空间斩** —— 任意生物的攻击适应练到 1000% 才会。
         * 朝准星方向放一道**远距离的必杀斩**，命中即抹除（和 1000% 平砍那一下同源的伤害）。
         */
        SPACE_CUT("space_cut", 200) {
            @Override
            public boolean unlocked(Player player) {
                AdaptationData data = AdaptationManager.data(player);

                for (String key : data.keys(Track.ATTACK)) {
                    if (key.startsWith("entity:") && data.get(Track.ATTACK, key) >= AdaptationManager.MAX) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public void cast(Player player) {
                spaceCut(player);
            }
        },

        /**
         * **满象 · 喷水** —— 练会"水"这件事就抄会了它：
         * 溺水（防御轨）**或** 浸泡水中（日常轨）任一条满 100%。
         *
         * <p>朝准星喷一道水柱：沿途的生物吃伤害、被推开，身上的火也被浇灭。
         */
        WATER_JET("water_jet", 40) {
            @Override
            public boolean unlocked(Player player) {
                return WaterJet.unlocked(player);
            }

            @Override
            public void cast(Player player) {
                WaterJet.cast(player);
            }
        },

        /**
         * **开** —— **适应火焰**的奖励（着火 / 火焰 / 岩浆任一满 100%）。
         *
         * <p>蓄力半秒 → 射出一颗火球 → 命中点炸开一片火焰。
         * 🔴 半径我们砍到了 **4 格**（原版 15 格，主人："那招破坏力太恐怖了"）。
         */
        FIRE_ARROW("fire_arrow", 60) {
            @Override
            public boolean unlocked(Player player) {
                return FireArrow.unlocked(player);
            }

            @Override
            public void cast(Player player) {
                FireArrow.cast(player);
            }
        },

        /**
         * **伏魔御厨子** —— 群殴适应满 100%（被 5 只以上同时围攻打出来的）。
         *
         * <p>展开那一瞬间，对半径内**所有**生物各来一套「捌」，然后气浪一散就结束 ——
         * 主人要的"零点五秒的领域、不要神龛模型"。
         */
        SHRINE("shrine", 300) {
            @Override
            public boolean unlocked(Player player) {
                return MalevolentShrine.unlocked(player);
            }

            @Override
            public void cast(Player player) {
                MalevolentShrine.cast(player);
            }
        };

        private final String id;
        private final int cooldown;

        Ability(String id, int cooldown) {
            this.id = id;
            this.cooldown = cooldown;
        }

        public String id() {
            return this.id;
        }

        public int cooldown() {
            return this.cooldown;
        }

        public Component display() {
            return Component.translatable("mahoraga.ability." + this.id);
        }

        /** 这个能力现在解锁了吗（**每次现查**，进度掉了就失效）。 */
        public abstract boolean unlocked(Player player);

        /** 真正放出去。 */
        public abstract void cast(Player player);
    }

    /** 每个玩家当前选中的能力 id。 */
    private static final Map<UUID, String> SELECTED = new ConcurrentHashMap<>();
    /** 每个玩家各能力的冷却结束时刻。 */
    private static final Map<UUID, Map<String, Long>> COOLDOWNS = new ConcurrentHashMap<>();
    /** 已经播报过"习得"的能力，避免刷屏。 */
    private static final Map<UUID, Set<String>> ANNOUNCED = new ConcurrentHashMap<>();

    // ==================== 入口：右键 ====================

    /** 右键点一下退魔之剑 —— 潜行是"切换"，否则是"释放"。 */
    public static void use(Player player) {
        if (!WheelHandler.hasWheel(player)) return;

        if (player.isShiftKeyDown()) cycle(player);
        else cast(player);
    }

    /** 切到下一个已解锁的能力。 */
    public static void cycle(Player player) {
        List<Ability> list = unlocked(player);

        if (list.isEmpty()) {
            message(player, Component.translatable("chat.mahoraga.ability.none")
                    .withStyle(ChatFormatting.DARK_GRAY));
            return;
        }

        String current = SELECTED.get(player.getUUID());
        int next = 0;

        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(current)) {
                next = i + 1;
                break;
            }
        }

        Ability picked = list.get(next % list.size());
        SELECTED.put(player.getUUID(), picked.id());

        message(player, Component.translatable("chat.mahoraga.ability.switch", picked.display())
                .withStyle(ChatFormatting.AQUA));
    }

    /** 释放当前选中的能力。 */
    public static void cast(Player player) {
        Ability ability = current(player);

        if (ability == null) {
            message(player, Component.translatable("chat.mahoraga.ability.none")
                    .withStyle(ChatFormatting.DARK_GRAY));
            return;
        }

        long now = player.level().getGameTime();
        long until = cooldownUntil(player, ability);

        if (now < until) {
            message(player, Component.translatable("chat.mahoraga.ability.cooling",
                            ability.display(), (until - now + 19) / 20)
                    .withStyle(ChatFormatting.DARK_GRAY));
            return;
        }

        ability.cast(player);
        COOLDOWNS.computeIfAbsent(player.getUUID(), k -> new ConcurrentHashMap<>())
                .put(ability.id(), now + ability.cooldown());
    }

    /** 当前该放哪个：选中的还在就放它，不在就退回第一个已解锁的。 */
    public static Ability current(Player player) {
        List<Ability> list = unlocked(player);
        if (list.isEmpty()) return null;

        String selected = SELECTED.get(player.getUUID());

        for (Ability ability : list) {
            if (ability.id().equals(selected)) return ability;
        }

        Ability fallback = list.get(0);
        SELECTED.put(player.getUUID(), fallback.id());
        return fallback;
    }

    /** 已经解锁的能力（按枚举顺序）。 */
    public static List<Ability> unlocked(Player player) {
        List<Ability> out = new ArrayList<>();

        for (Ability ability : Ability.values()) {
            if (ability.unlocked(player)) out.add(ability);
        }
        return out;
    }

    private static long cooldownUntil(Player player, Ability ability) {
        Map<String, Long> map = COOLDOWNS.get(player.getUUID());
        if (map == null) return 0L;

        Long until = map.get(ability.id());
        return until == null ? 0L : until;
    }

    // ==================== 「习得」播报 ====================

    /**
     * 每秒查一次 —— 有新解锁的能力就报一句。
     *
     * 第一次见到这个玩家时**先把当前已解锁的全吞掉不报**（否则每次重登都会把
     * 早就学会的能力又念一遍）。
     */
    public static void announceUnlocks(Player player) {
        Set<String> seen = ANNOUNCED.get(player.getUUID());

        if (seen == null) {
            seen = ConcurrentHashMap.newKeySet();

            for (Ability ability : unlocked(player)) seen.add(ability.id());

            ANNOUNCED.put(player.getUUID(), seen);
            return;
        }

        for (Ability ability : unlocked(player)) {
            if (!seen.add(ability.id())) continue;

            player.displayClientMessage(Component.translatable("chat.mahoraga.ability.learned",
                    ability.display()).withStyle(ChatFormatting.LIGHT_PURPLE), false);
        }
    }

    public static void clear(Player player) {
        UUID id = player.getUUID();
        SELECTED.remove(id);
        COOLDOWNS.remove(id);
        ANNOUNCED.remove(id);
    }

    // ==================== 具体能力 ====================

    /** 瞬移能跳多远。 */
    private static final double TELEPORT_RANGE = 16.0D;

    /**
     * **瞬移**：沿准星瞬移。
     *
     * 🔴 不能直接 `eye + look × 距离` —— 撞墙会把人塞进方块里窒息。
     * 所以从远到近试几个距离，挑**第一个"落脚不卡"**的位置。
     */
    private static void teleport(Player player) {
        Level level = player.level();
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        Vec3 origin = player.position();
        Vec3 target = null;

        for (double distance = TELEPORT_RANGE; distance >= 4.0D; distance -= 4.0D) {
            Vec3 end = eye.add(look.scale(distance));
            BlockHitResult hit = level.clip(new ClipContext(eye, end,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));

            Vec3 spot = hit.getType() == HitResult.Type.MISS
                    ? end
                    : hit.getLocation().subtract(look.scale(1.5D));

            // 脚底下那格也要有得站，不然瞬移过去就往下掉
            if (level.noCollision(player, player.getBoundingBox().move(spot.subtract(origin)))) {
                target = spot;
                break;
            }
        }

        if (target == null) {
            message(player, Component.translatable("chat.mahoraga.ability.blocked")
                    .withStyle(ChatFormatting.DARK_GRAY));
            return;
        }

        double fromX = player.getX();
        double fromY = player.getY();
        double fromZ = player.getZ();

        player.teleportTo(target.x, target.y, target.z);
        player.resetFallDistance();

        level.playSound(null, fromX, fromY, fromZ, SoundEvents.ENDERMAN_TELEPORT,
                SoundSource.PLAYERS, 1.0F, 1.0F);
        level.playSound(null, target.x, target.y, target.z, SoundEvents.ENDERMAN_TELEPORT,
                SoundSource.PLAYERS, 1.0F, 1.0F);

        if (level instanceof ServerLevel server) {
            server.sendParticles(ParticleTypes.PORTAL, fromX, fromY + 1.0D, fromZ, 32, 0.5D, 0.5D, 0.5D, 0.5D);
            server.sendParticles(ParticleTypes.PORTAL, target.x, target.y + 1.0D, target.z,
                    32, 0.5D, 0.5D, 0.5D, 0.5D);
        }
    }

    /** 空间斩的刀身多长（格）—— 原模组最长 30，我们取 24：够铺满视野，又不会夸张到扫掉半个区块。 */
    private static final int CUT_LENGTH = 24;
    /** 空间斩飞多快（格 / tick）。 */
    private static final float CUT_SPEED = 2.0F;

    /**
     * **空间斩**：朝准星方向甩出一道**巨大的发光斩击**。
     *
     * 贴图 / 渲染 / 音效**全部照抄原模组的「世界斩」**（见 {@link WorldSlashProjectile}）——
     * 以前那版是用一串末地烛光粒子画条线，主人说"看着像个白色的球"，那根本不算斩击。
     *
     * 潜行时 roll 转 90°，**横劈变竖劈** —— 原版就是这个手感。
     */
    private static void spaceCut(Player player) {
        Level level = player.level();
        if (level.isClientSide) return;

        float roll = (player.isShiftKeyDown() ? 90.0F : 0.0F)
                + (player.getRandom().nextFloat() - 0.5F) * 60.0F;

        level.addFreshEntity(new WorldSlashProjectile(player, roll, CUT_LENGTH, CUT_SPEED));

        // 原模组「世界斩」的原配音效 —— 远古守卫者那一嗓子
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.PLAYERS, 1.0F, 1.0F);
    }

    private static void message(Player player, Component text) {
        player.displayClientMessage(text, true);
    }
}
