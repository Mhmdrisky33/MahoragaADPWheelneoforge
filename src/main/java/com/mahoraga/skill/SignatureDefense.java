package com.mahoraga.skill;

import com.mahoraga.MahoragaMod;
import com.mahoraga.adapt.AdaptationData;
import com.mahoraga.adapt.AdaptationKeys;
import com.mahoraga.adapt.AdaptationManager;
import com.mahoraga.adapt.Track;
import com.mahoraga.handler.WheelHandler;
import com.mahoraga.registry.ModDamageTypes;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.living.LivingHurtEvent;
import net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent;
import net.neoforged.neoforge.event.entity.living.LivingUseTotemEvent;
import net.neoforged.neoforge.event.entity.living.ShieldBlockEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.UUID;

/**
 * 「防御适应」满 100% 之后的专属效果 —— **对方折磨你的那个机制，被你锁死**。
 *
 * 🔴 关键原则（主人纠偏）：**不是"免疫那个 debuff"，而是"锁住那项资源本身"。**
 * 比如尸壳给你饥饿 → 不是免疫饥饿效果（那玩意儿药水适应已经管了），
 * 而是**饱食度 / 饱和度永远不掉**。
 *
 * ⚠️ 所有效果都**现查进度**，所以适应一掉到 100% 以下立刻失效 —— 不需要额外清理。
 */
@EventBusSubscriber(modid = MahoragaMod.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class SignatureDefense {
    /** 猪灵蛮兵：护甲翻倍的倍率修饰符。`/mahoraga wheel reset` 摘属性要用，所以是 public。 */
    public static final UUID BRUTE_ARMOR = UUID.fromString("2f47c1a9-6b0e-4d38-9d21-5c8e7a304f61");
    /** 短距瞬移的冷却用的假物品。 */
    private static final int TELEPORT_COOLDOWN = 20;
    private static final double TELEPORT_RANGE = 8.0D;

    private SignatureDefense() {
    }

    // ==================== 每 tick 的被动 ====================

    /**
     * 每 tick 调用（不是每秒）—— 火焰、饱食度这些必须实时，慢了就会被打到一次。
     */
    public static void tick(Player player, AdaptationData data) {
        boolean brute = false;

        for (String key : data.keys(Track.DEFENSE)) {
            if (!key.startsWith("entity:")) continue;
            if (data.get(Track.DEFENSE, key) < AdaptationManager.COMPLETE) continue;

            switch (key.substring("entity:".length())) {
                case "minecraft:husk" -> lockFood(player);
                case "minecraft:wither_skeleton", "minecraft:wither" -> keepMaxHealth(player);
                case "minecraft:blaze" -> cleatFire(player);
                case "minecraft:piglin_brute" -> brute = true;
                case "minecraft:vindicator" -> keepShield(player);
                default -> {
                }
            }
        }

        // 没在持有时记得把护甲翻倍摘掉（不然适应掉下来了还一直双倍）
        setBruteArmor(player, brute);
    }

    /** 尸壳：饱食度 / 饱和度永远不减。 */
    private static void lockFood(Player player) {
        var food = player.getFoodData();
        food.setFoodLevel(20);
        food.setSaturation(20.0F);
    }

    /** 凋灵骷髅 / 凋灵：生命上限永不被削 —— 把任何"减上限"的修饰符摘掉。 */
    private static void keepMaxHealth(Player player) {
        AttributeInstance instance = player.getAttribute(Attributes.MAX_HEALTH);
        if (instance == null) return;

        for (AttributeModifier modifier : instance.getModifiers().stream().toList()) {
            if (modifier.getAmount() < 0.0D) {
                instance.removeModifier(modifier.getId());
            }
        }
    }

    /** 烈焰人：火焰计时永远为 0 —— 什么都点不着你。 */
    private static void cleatFire(Player player) {
        player.setRemainingFireTicks(0);
        player.clearFire();
    }

    /** 猪灵蛮兵：护甲翻倍。 */
    private static void setBruteArmor(Player player, boolean active) {
        AttributeInstance instance = player.getAttribute(Attributes.ARMOR);
        if (instance == null) return;

        AttributeModifier existing = instance.getModifier(BRUTE_ARMOR);

        if (!active) {
            if (existing != null) instance.removeModifier(BRUTE_ARMOR);
            return;
        }
        if (existing != null) return;

        instance.addTransientModifier(new AttributeModifier(BRUTE_ARMOR, "mahoraga_brute_armor",
                1.0D, AttributeModifier.Operation.MULTIPLY_TOTAL));
    }

    /** 卫道士：盾牌永不被破 —— 不停地清掉破盾冷却。 */
    private static void keepShield(Player player) {
        player.getCooldowns().removeCooldown(Items.SHIELD);
    }

    // ==================== 击退 ====================

    /** 疣猪兽 / 劫掠兽 / 恶魂：**你不会被击退**。 */
    @SubscribeEvent
    public static void onKnockback(LivingKnockBackEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level().isClientSide) return;
        if (!WheelHandler.hasWheel(player)) return;

        AdaptationData data = AdaptationManager.data(player);

        for (String key : new String[]{
                "entity:minecraft:hoglin", "entity:minecraft:ravager", "entity:minecraft:ghast"}) {
            if (data.get(Track.DEFENSE, key) >= AdaptationManager.COMPLETE) {
                event.setStrength(0.0F);
                event.setRatioX(0.0D);
                event.setRatioZ(0.0D);
                return;
            }
        }
    }

    // ==================== 盾牌 ====================

    /** 卫道士：盾牌连耐久都不掉。 */
    @SubscribeEvent
    public static void onShieldBlock(ShieldBlockEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!hasComplete(player, "minecraft:vindicator")) return;

        event.setShieldTakesDamage(false);
    }

    // ==================== 不死图腾 ====================

    /**
     * 唤魔者：**无视不死图腾** —— 玩家打过的目标，用图腾也救不回来。
     *
     * 唤魔者的招牌就是"不死"（它掉不死图腾），所以适应它之后，
     * 你打的目标即使掏出图腾也会被直接掐掉。
     */
    @SubscribeEvent
    public static void onUseTotem(LivingUseTotemEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide) return;

        if (entity.getLastHurtByMob() instanceof Player killer && hasComplete(killer, "minecraft:evoker")) {
            event.setCanceled(true);
        }
    }

    // ==================== 末影人：短距瞬移 ====================

    /**
     * 末影人防御侧：**你能短距瞬移**。
     *
     * 触发方式（人家自己定的，随时可以改）：**按住潜行 + 空手右键点方块**，
     * 朝视线方向最多瞬移 8 格（会自动找落点，撞墙不动）。
     */
    @SubscribeEvent
    public static void onTeleport(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        if (player.level().isClientSide) return;
        if (!player.isShiftKeyDown()) return;
        if (!player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty()) return;
        if (!hasComplete(player, "minecraft:enderman")) return;
        if (player.getCooldowns().isOnCooldown(Items.ENDER_PEARL)) return;

        Vec3 look = player.getLookAngle();

        for (double distance = TELEPORT_RANGE; distance >= 1.0D; distance -= 0.5D) {
            Vec3 target = player.position().add(look.scale(distance));

            if (!player.level().noCollision(player, player.getBoundingBox().move(target.subtract(player.position())))) {
                continue;
            }

            if (player instanceof ServerPlayer server) {
                server.teleportTo(server.serverLevel(), target.x, target.y, target.z,
                        player.getYRot(), player.getXRot());
            }
            player.resetFallDistance();
            player.setDeltaMovement(Vec3.ZERO);
            player.getCooldowns().addCooldown(Items.ENDER_PEARL, TELEPORT_COOLDOWN);

            if (player.level() instanceof ServerLevel level) {
                level.sendParticles(ParticleTypes.PORTAL,
                        target.x, target.y + 0.5D, target.z, 24, 0.3D, 0.5D, 0.3D, 0.2D);
            }
            player.level().playSound(null, target.x, target.y, target.z,
                    SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.0F);
            return;
        }
    }

    // ==================== 末影人：受击闪烁反击 ====================

    /** 反击冷却（tick）—— 2 秒。 */
    private static final int COUNTER_COOLDOWN = 40;
    /** 落点离攻击者多远（沿着它背后方向）。 */
    private static final double COUNTER_BACK = 1.6D;
    /** 反击伤害 = 玩家普通攻击力 × 这个倍率。 */
    private static final float COUNTER_RATIO = 1.0F;

    /** 各玩家上次反击的时间，用来压冷却。 */
    private static final java.util.Map<UUID, Long> LAST_COUNTER = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * **末影人防御适应满 100% → 被打中的瞬间闪到攻击者背后，反手一刀。**
     *
     * 跟「防御反击」（`GuardSkill`，要**手持退魔剑 + 潜行**）不是一个东西：
     * 这个是**被动**的 —— 不持剑、不潜行，唯一条件是末影人那条**防御适应满 100%**。
     *
     * 反击走专属伤害类型 {@link ModDamageTypes#COUNTER} —— 不吃攻击倍率，
     * 也**不会**反过来给攻击适应喂进度（和 `GuardSkill` 一个规矩）。
     *
     * ⚠️ 由 `AdaptationManager#onHurt` **显式调用**，不是靠自己的 `@SubscribeEvent` ——
     *    这个项目被"事件类没注册"坑过一次，能确定在跑的路就别再赌。
     */
    public static void onCounterHurt(Player player, LivingHurtEvent event) {
        if (player.level().isClientSide) return;
        if (!complete(player, "minecraft:enderman")) return;

        DamageSource source = event.getSource();

        if (ModDamageTypes.isOwned(source)) return;                     // 我们自己的附加伤害不触发
        if (!(source.getEntity() instanceof LivingEntity attacker)) return;
        if (attacker == player || attacker.isRemoved() || !attacker.isAlive()) return;
        if (!attacker.canAttack(player)) return;                        // 别对友军 / 自己的宠物出手

        long now = player.level().getGameTime();
        Long last = LAST_COUNTER.get(player.getUUID());

        if (last != null && now - last < COUNTER_COOLDOWN) return;
        LAST_COUNTER.put(player.getUUID(), now);

        // —— 先算好落点和朝向（背后），再一次性传送，免得先落地再转头那一下被看到 ——
        Vec3 from = player.position();
        Vec3 dest = behindOf(player, attacker);

        double dx = attacker.getX() - dest.x;
        double dz = attacker.getZ() - dest.z;
        double dy = attacker.getEyeY() - (dest.y + player.getEyeHeight());

        float yaw = (float) (Mth.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        float pitch = (float) (-(Mth.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * (180.0D / Math.PI)));

        if (player instanceof ServerPlayer sp) {
            sp.teleportTo(sp.serverLevel(), dest.x, dest.y, dest.z, yaw, pitch);
        } else {
            player.teleportTo(dest.x, dest.y, dest.z);
        }

        player.resetFallDistance();
        player.setDeltaMovement(Vec3.ZERO);
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.setYHeadRot(yaw);

        // —— 反手一刀 ——
        float damage = (float) player.getAttributeValue(Attributes.ATTACK_DAMAGE) * COUNTER_RATIO;

        player.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);

        if (damage > 0.0F) {
            attacker.invulnerableTime = 0;
            attacker.hurt(ModDamageTypes.counter(player, attacker), damage);
        }

        // —— 表现：原地和落点各一蓬传送粒子 + 末影人那声"叮" ——
        if (player.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.PORTAL,
                    from.x, from.y + 1.0D, from.z, 24, 0.4D, 0.5D, 0.4D, 0.25D);
            level.sendParticles(ParticleTypes.PORTAL,
                    dest.x, dest.y + 1.0D, dest.z, 24, 0.4D, 0.5D, 0.4D, 0.25D);
        }

        player.level().playSound(null, dest.x, dest.y, dest.z,
                SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.2F);
    }

    /**
     * 攻击者**背后**的落点。
     *
     * 沿它朝向的反方向由远到近试，挑第一个**不卡墙**的位置；
     * 都不行就退回它脚边半格 —— 总比把玩家塞进方块里窒息强。
     */
    private static Vec3 behindOf(Player player, LivingEntity attacker) {
        Vec3 back = attacker.getLookAngle().scale(-1.0D).normalize();

        for (double d = COUNTER_BACK; d >= 0.4D; d -= 0.4D) {
            Vec3 candidate = attacker.position().add(back.scale(d));

            if (player.level().noCollision(player,
                    player.getBoundingBox().move(candidate.subtract(player.position())))) {
                return candidate;
            }
        }

        return attacker.position().add(back.scale(0.4D));
    }

    // ==================== 工具 ====================

    /** 这一条防御适应是不是已经满了（顺带查法轮）。 */
    private static boolean hasComplete(Player player, String entityId) {
        if (player.level().isClientSide) return false;
        if (!WheelHandler.hasWheel(player)) return false;

        return AdaptationManager.data(player).get(Track.DEFENSE, "entity:" + entityId)
                >= AdaptationManager.COMPLETE;
    }

    /** 给别的地方用的：某个生物 id 的防御适应是不是满了。 */
    public static boolean complete(Player player, String entityId) {
        return hasComplete(player, entityId);
    }
}
