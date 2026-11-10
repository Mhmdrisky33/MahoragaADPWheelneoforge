package com.mahoraga.skill;

import com.mahoraga.MahoragaMod;
import com.mahoraga.adapt.AdaptationData;
import com.mahoraga.adapt.AdaptationKeys;
import com.mahoraga.adapt.AdaptationManager;
import com.mahoraga.adapt.Track;
import com.mahoraga.entity.SlashProjectile;
import com.mahoraga.registry.ModDamageTypes;
import com.mahoraga.registry.ModSounds;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「攻击适应」满 100% 之后的专属效果 —— **把对方那套玩意儿用在自己手上**。
 *
 * 主人 2026-09-14 定：只有**有招牌能力**的生物才给，且**适应掉到 100% 以下立刻失效**
 * （所以每一次攻击都要现查进度，不能缓存布尔）。
 *
 * ⚠️ 这里所有追加伤害都走 {@link ModDamageTypes#spell} —— 既穿甲，
 * 又不会回头再喂一次攻击适应（否则自己喂自己，越打越强）。
 */
public final class SignatureAttack {
    private static final Random RANDOM = new Random();
    /** 每个玩家上一次出剑气的 tick（去重用）。 */
    private static final Map<UUID, Long> LAST_SLASH = new ConcurrentHashMap<>();

    /** 强击退的力度。 */
    private static final double KNOCKBACK = 2.2D;
    /** 锁定目标的时长（tick）。 */
    private static final int SPACE_LOCK_TICKS = 40;
    /** 剑气飞行的速度（格/tick）—— 12 tick 的寿命，能扫出去二十来格。 */
    private static final double SLASH_SPEED = 2.0D;

    /** 剑气数量上限（a 一下最多几道）。 */
    private static final int MAX_SLASHES = 5;
    /** 每多这么多「攻击适应」→ 多一道剑气（2.5 = 250%）。 */
    private static final float SLASHES_PER = 2.5F;
    /** 多道剑气之间**扇形散开**的角度（度）。 */
    private static final float SLASH_SPREAD_DEG = 8.0F;
    /** 任意生物的**攻击适应**到这个值也解锁剑气（百分比 / 100）。 */
    private static final float SLASH_ANYWHERE = 2.5F;

    private SignatureAttack() {
    }

    /** 玩家挥出一次攻击时调用（此时目标已被判定）。 */
    public static void apply(Player player, LivingEntity target, AdaptationData data) {
        if (target == player || target.isDeadOrDying()) return;

        String key = AdaptationKeys.attackKey(target);
        int pct = AdaptationManager.percent(data.get(Track.ATTACK, key));

        // —— 随机适应库：攻击适应 100%~200% 之间按概率领悟，200% 起必定
        AdaptationManager.rollRandomAdaptation(player, pct);

        // 领悟过「永恒流血」→ 打谁谁流血（每打一下就深一层）
        if (data.get(Track.ATTACK, AdaptationKeys.RANDOM_BLEEDING) >= AdaptationManager.COMPLETE) {
            EternalBleeding.cut(player, target);
        }

        // 剑气：读远程攻击那两条（骷髅＝攻击侧、幻翼＝防御侧），任一满 100% 就能挥
        slash(player, target, data);

        float progress = data.get(Track.ATTACK, key);
        if (progress < AdaptationManager.COMPLETE) return;

        switch (idOf(target)) {
            // —— 2026-09-14 补的三只常见怪 ——
            case "minecraft:zombie" -> summonReinforcements(player, target, progress);
            case "minecraft:spider", "minecraft:cave_spider" -> venom(target, progress);
            case "minecraft:creeper" -> blast(player, target, progress);

            case "minecraft:husk" -> effect(target, MobEffects.HUNGER, 240, 1);
            case "minecraft:wither_skeleton", "minecraft:wither" -> effect(target, MobEffects.WITHER, 240, 1);
            case "minecraft:stray" -> effect(target, MobEffects.MOVEMENT_SLOWDOWN, 240, 1);
            case "minecraft:blaze" -> target.setRemainingFireTicks(600);      // 无法熄灭的火
            case "minecraft:ghast" -> smallExplosion(player, target);
            case "minecraft:hoglin", "minecraft:ravager" -> knockAway(player, target);
            case "minecraft:enderman" -> SpaceLock.lock(target, SPACE_LOCK_TICKS);
            case "minecraft:shulker" -> effect(target, MobEffects.LEVITATION, 120, 1);
            case "minecraft:guardian" -> effect(target, MobEffects.DIG_SLOWDOWN, 240, 1);
            case "minecraft:elder_guardian" -> effect(target, MobEffects.DIG_SLOWDOWN, 300, 2);
            case "minecraft:vindicator" -> breakShield(target);
            case "minecraft:evoker" -> evokerBurst(player, target);
            case "minecraft:witch" -> randomDebuff(target);
            default -> {
                // 没有招牌能力的生物：只吃通用效果（倍率 + 法伤三档）
            }
        }
    }

    /** 猪灵蛮兵：攻击**无视护甲**（在伤害结算里被换算成"把甲减掉的份额补回来"）。 */
    public static boolean ignoresArmor(Player player, LivingEntity target) {
        AdaptationData data = AdaptationManager.data(player);

        if (data.get(Track.ATTACK, AdaptationKeys.attackKey(target)) < AdaptationManager.COMPLETE) return false;

        return "minecraft:piglin_brute".equals(idOf(target));
    }

    // ==================== 剑气（防风筝） ====================

    /**
     * 剑气：被**远距离攻击**适应满之后，普通攻击会挥出一道**飞行的斩击**。
     *
     * 素材（32×16 的斩击贴图 + 破空音效）和渲染方式**直接搬原模组的「解」**，
     * 实体本体是轻量重写版（原版继承原模组基类 + 14 个内部类，抄不动）。
     *
     * **是普通伤害，不是空间斩**（空间斩是攻击第 4 档那个东西）。
     */
    private static void slash(Player player, LivingEntity primary, AdaptationData data) {
        spawnSlash(player, primary, data);
    }

    /**
     * **挥空**时经网络包调进来的入口 —— 服务端只有在打中实体时才会收到攻击事件，
     * 而剑气是防风筝用的，打不着也必须能甩出去。
     */
    public static void trySwingSlash(Player player) {
        if (player.level().isClientSide) return;

        spawnSlash(player, null, AdaptationManager.data(player));
    }

    /** 真正的出招：数值够 + 这个 tick 还没出过，就甩剑气出去（**数量随后面看适应成长**）。 */
    private static void spawnSlash(Player player, LivingEntity primary, AdaptationData data) {
        if (player.level().isClientSide) return;
        if (!hasSlash(player)) return;
        if (slashedThisTick(player)) return;

        float damage = (float) player.getAttributeValue(Attributes.ATTACK_DAMAGE);
        if (damage <= 0.0F) return;

        int count = slashCount(data, primary);
        Vec3 look = player.getLookAngle();

        for (int i = 0; i < count; i++) {
            // **扇形**：以正前方为中心左右摊开，一路平推过去。1 道时角度偏移就是 0（跟原来一样）。
            float offset = (i - (count - 1) / 2.0F) * SLASH_SPREAD_DEG;
            Vec3 dir = look.yRot((float) Math.toRadians(offset));

            float roll = (RANDOM.nextFloat() - 0.5F) * 60.0F;

            SlashProjectile slash = new SlashProjectile(player, damage, roll, primary);
            slash.setDeltaMovement(dir.scale(SLASH_SPEED));
            player.level().addFreshEntity(slash);
        }

        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                ModSounds.SLASH.get(), SoundSource.PLAYERS, 1.0F, 1.0F);
    }

    /**
     * 这一次挥砍甩出**几道**剑气（主人 2026-09-15 要的"a 一下出更多剑气"）。
     *
     * **每 250% 多一道，最多 5 道**：
     * 250% → 2 道 ｜ 500% → 3 道 ｜ 750% → 4 道 ｜ 1000% → 5 道。低于 250% 恒为 1 道。
     *
     * 看的是哪条适应：
     * - **打中实体** → 看你对**这只生物**的攻击适应（越熟越会打）
     * - **挥空** → 看你**最高的那条**攻击适应
     */
    private static int slashCount(AdaptationData data, @javax.annotation.Nullable LivingEntity primary) {
        float pct;

        if (primary != null) {
            pct = data.get(Track.ATTACK, AdaptationKeys.attackKey(primary));
        } else {
            pct = 0.0F;

            for (String key : data.keys(Track.ATTACK)) {
                pct = Math.max(pct, data.get(Track.ATTACK, key));
            }
        }

        return Mth.clamp(1 + (int) (pct / SLASHES_PER), 1, MAX_SLASHES);
    }

    /**
     * 同一个 tick 只出一发。
     * 「打中实体」和「挥空」是两个入口，但一次挥砍可能同时命中两边 ——
     * 不去重的话会瞬间甩出两道斩击。
     */
    private static boolean slashedThisTick(Player player) {
        long now = player.level().getGameTime();
        Long last = LAST_SLASH.get(player.getUUID());

        if (last != null && last == now) return true;

        LAST_SLASH.put(player.getUUID(), now);
        return false;
    }

    /**
     * 剑气是否已解锁 —— 满足**任一条**就**永久**会了（主人 2026-09-14 放宽过）：
     * - **骷髅**攻击适应满 **100%**（骷髅本来就是靠远程放风筝吃饭的，学它最快）
     * - **幻翼**防御适应满 **100%**
     * - **被投掷物打中**（雪球 / 鸡蛋 / 药水）防御适应满 **100%**（2026-09-15 加的第四路）
     * - **任意生物**攻击适应到 **250%**
     *
     * 🔴 **"永久"两个字是 2026-09-15 晚补的关键**：以前是"现查进度"，于是 ——
     * 练到 300% 剑气出来了，停手 30 秒开始衰减，**掉到 250% 以下剑气当场消失**，
     * 看着就像从来没学会过（主人报的"很多时候攻击适应超过了 250 都没解锁"）。
     * 现在达成的那一刻钉一个 {@link AdaptationKeys#SLASH_UNLOCK}，那个键**永不衰减**
     * （`AdaptationManager#advanceCombat` 里跳过 `unlock:` 前缀）。
     *
     * 这个方法也是**每秒解锁提示**的判据（`AdaptationManager#syncSlashUnlock`）。
     */
    public static boolean hasSlash(Player player) {
        AdaptationData data = AdaptationManager.data(player);

        // 已经学会 → 永久有效（进度掉光也不收回）
        if (data.get(Track.ATTACK, AdaptationKeys.SLASH_UNLOCK) >= AdaptationManager.COMPLETE) return true;

        if (!reachedSlash(data)) return false;

        // 第一次达标 → 当场钉住。**故意把写入放在判定里**：所有调用点都自动获得"永久化"，
        // 不用挨个去接（这个判定一共只有两处调用，但以后加第三处也不会漏）。
        data.set(Track.ATTACK, AdaptationKeys.SLASH_UNLOCK, AdaptationManager.COMPLETE);
        return true;
    }

    /** 四条解锁路径 —— 任一条达标就算学会。 */
    private static boolean reachedSlash(AdaptationData data) {
        if (data.get(Track.ATTACK, AdaptationKeys.BOW_SKELETON) >= AdaptationManager.COMPLETE) return true;
        if (data.get(Track.DEFENSE, AdaptationKeys.FLYING_PHANTOM) >= AdaptationManager.COMPLETE) return true;
        if (data.get(Track.DEFENSE, AdaptationKeys.THROWN) >= AdaptationManager.COMPLETE) return true;

        for (String key : data.keys(Track.ATTACK)) {
            if (key.startsWith("entity:") && data.get(Track.ATTACK, key) >= SLASH_ANYWHERE) return true;
        }
        return false;
    }

    // ==================== 飞行生物：攻击适应满 100% → 直接解锁飞行 ====================

    /**
     * 「**飞行生物**」—— 打过其中任意一种、攻击适应练满 **100%**，就**一直**给创造飞行；
     * **掉到 100% 以下立刻收走**（所以每 tick 现查进度，不能缓存布尔）。
     *
     * 主人 2026-09-14 定："飞行生物的攻击适应直接解锁飞行能力，调到一百以下就没了。"
     */
    private static final Set<String> FLYERS = Set.of(
            "minecraft:phantom", "minecraft:bat", "minecraft:bee", "minecraft:parrot",
            "minecraft:allay", "minecraft:vex", "minecraft:ghast", "minecraft:blaze",
            "minecraft:wither", "minecraft:ender_dragon");

    /** 现在该不该有飞行（现查，不缓存）。 */
    public static boolean hasFlight(Player player) {
        AdaptationData data = AdaptationManager.data(player);

        for (String id : FLYERS) {
            if (data.get(Track.ATTACK, "entity:" + id) >= AdaptationManager.COMPLETE) return true;
        }
        return false;
    }

    /**
     * 每 tick 校准一次（由 {@code AdaptationManager} 调）：
     * 够格 → 给飞行；掉下 100% → 立刻收走。
     *
     * ⚠️ 创造 / 旁观模式本来就能飞，**别去动它**（否则一进创造就被人家的逻辑摁掉）。
     */
    public static void tickFlight(Player player) {
        if (player.isCreative() || player.isSpectator()) return;

        boolean should = hasFlight(player);
        if (player.getAbilities().mayfly == should) return;

        player.getAbilities().mayfly = should;
        if (!should) player.getAbilities().flying = false;

        player.onUpdateAbilities();
    }

    // ==================== 僵尸 / 蜘蛛 / 苦力怕（2026-09-14 补） ====================

    /** 召唤出来的僵尸身上的标记 —— 用来拦住它们别咬主人。 */
    public static final String SERVANT_TAG = "mahoraga_servant";

    private static final Map<UUID, Long> LAST_SUMMON = new ConcurrentHashMap<>();
    /** 招援军的冷却（tick）—— 不然砍十下就攒出一支军队。 */
    private static final int SUMMON_COOLDOWN = 100;
    /** 援军活多久（tick）—— 30 秒后自己消散，不留在世界里碍事。 */
    private static final int SERVANT_LIFE = 600;
    /** 援军的"到期时刻"存在这个键上（`getGameTime() + SERVANT_LIFE`）。 */
    private static final String SERVANT_UNTIL = "mahoraga_servant_until";

    /**
     * **到点的援军自己消散** —— 由 {@code AdaptationManager} 的 `LivingTickEvent` 每 tick 调一次。
     *
     * ⚠️ 本来想用原版 `Mob#setLimitedLife`（唤魔者召恼鬼就是那个），但在这版 1.20.1 上**找不到这个方法**，
     * 所以改成自己记到期时刻。第一句先 `instanceof Zombie` 挡掉绝大部分生物，开销可以忽略。
     */
    public static void tickServants(LivingEntity entity) {
        if (!(entity instanceof Zombie)) return;
        if (!entity.getTags().contains(SERVANT_TAG)) return;

        long until = entity.getPersistentData().getLong(SERVANT_UNTIL);

        if (until > 0L && entity.level().getGameTime() > until) {
            entity.discard();
        }
    }

    /**
     * **僵尸：召唤援军**（主人 2026-09-14："僵尸想不到附带什么"）。
     *
     * 挑这个是因为它**就是僵尸的原版招牌** —— 原版僵尸受伤时会喊着招呼附近的同类一起上。
     * 做成"你砍人的时候顺手从地里叫出几只帮你打"，既贴原版、又和现有能力不重
     * （尸壳管饥饿、凋灵骷髅管凋零、卫道士管破盾，都不是这个）。
     *
     * 三档成长（按那条**攻击适应**的进度）：
     * - **100%** 1 只｜**300%** 2 只｜**600%** 3 只
     *
     * ⚠️ 招出来的僵尸带 {@link #SERVANT_TAG}，由下面的 `ForgeEvents` 拦着**不许把它们的目标设成玩家**
     * —— 不然"援军"转头就咬你。同时用原版的 `setLimitedLife` 让它们到点自己消失
     * （唤魔者召恼鬼用的也是这个，不用我们自己数 tick）。
     */
    private static void summonReinforcements(Player player, LivingEntity target, float progress) {
        if (!(player.level() instanceof ServerLevel level)) return;

        long now = level.getGameTime();
        Long last = LAST_SUMMON.get(player.getUUID());
        if (last != null && now - last < SUMMON_COOLDOWN) return;

        LAST_SUMMON.put(player.getUUID(), now);

        int count = progress >= 6.0F ? 3 : progress >= 3.0F ? 2 : 1;

        for (int i = 0; i < count; i++) {
            Zombie servant = EntityType.ZOMBIE.create(level);
            if (servant == null) continue;

            double angle = player.getRandom().nextDouble() * Math.PI * 2.0D;
            double x = player.getX() + Math.cos(angle) * 1.8D;
            double z = player.getZ() + Math.sin(angle) * 1.8D;

            servant.moveTo(x, player.getY(), z, player.getYRot(), 0.0F);
            servant.getPersistentData().putLong(SERVANT_UNTIL, level.getGameTime() + SERVANT_LIFE);
            servant.addTag(SERVANT_TAG);
            servant.setTarget(target);            // 一出来就奔着它去

            level.addFreshEntity(servant);
            level.sendParticles(ParticleTypes.SOUL, x, player.getY() + 0.6D, z,
                    10, 0.3D, 0.5D, 0.3D, 0.02D);
        }

        level.playSound(null, player.blockPosition(), SoundEvents.ZOMBIE_AMBIENT,
                SoundSource.PLAYERS, 0.8F, 1.35F);
    }

    /**
     * **蜘蛛：攻击带毒**。
     * 100% 中毒 I → 300% II → 600% III。
     */
    private static void venom(LivingEntity target, float progress) {
        int amplifier = progress >= 6.0F ? 2 : progress >= 3.0F ? 1 : 0;

        effect(target, MobEffects.POISON, 120, amplifier);
    }

    /**
     * **苦力怕：攻击带一发小爆炸**。
     *
     * 🔴 **不破坏方块、也不炸到自己** —— 所以**不能用 `Level#explode`**
     * （那个一定会伤到范围内的玩家自己）。改成自己放粒子 + 音效 + 遍历伤害，完全可控。
     *
     * 威力**随适应成长**（主人要求）：100% → 1.5，之后每 100% 涨 0.35，**1000% 封顶 4.5**。
     */
    private static void blast(Player player, LivingEntity target, float progress) {
        if (!(player.level() instanceof ServerLevel level)) return;

        float power = 1.5F + Math.min(3.0F, (progress - AdaptationManager.COMPLETE) * 0.35F);

        double x = target.getX();
        double y = target.getY(0.5D);
        double z = target.getZ();

        // 爆炸的"长相"：一发爆炸粒子 + 一圈烟，够唬人就行
        level.sendParticles(ParticleTypes.EXPLOSION, x, y, z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, x, y, z,
                8 + (int) (power * 3.0F), power * 0.25D, 0.4D, power * 0.25D, 0.02D);
        level.playSound(null, target.blockPosition(), SoundEvents.GENERIC_EXPLODE.value(),
                SoundSource.PLAYERS, 0.6F, 1.35F);

        DamageSource source = ModDamageTypes.spell(player);
        double radius = 2.0D + power * 0.6D;
        float amount = 4.0F + power * 2.0F;

        for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class,
                target.getBoundingBox().inflate(radius), e -> e != player && e.isAlive())) {

            if (victim instanceof Player other && !player.canHarmPlayer(other)) continue;

            victim.invulnerableTime = 0;
            victim.hurt(source, amount);
        }
    }

    // ==================== 各种手段 ====================

    private static void effect(LivingEntity target, Holder<MobEffect> effect, int duration, int amplifier) {
        target.addEffect(new MobEffectInstance(effect, duration, amplifier));
    }

    /** 恶魂：引发一发**小爆炸**（不破坏方块、不伤自己）。 */
    private static void smallExplosion(Player player, LivingEntity target) {
        player.level().explode(player, target.getX(), target.getY(0.5D), target.getZ(),
                1.6F, Level.ExplosionInteraction.NONE);
    }

    /** 疣猪兽 / 劫掠兽：强击退。 */
    private static void knockAway(Player player, LivingEntity target) {
        var push = target.position().subtract(player.position());
        var flat = new net.minecraft.world.phys.Vec3(push.x, 0.0D, push.z);

        if (flat.lengthSqr() < 1.0E-4D) return;

        flat = flat.normalize().scale(KNOCKBACK);
        target.push(flat.x, 0.45D, flat.z);
        target.hurtMarked = true;
    }

    /** 卫道士：随便什么攻击都能**破盾**（对玩家目标而言）。 */
    private static void breakShield(LivingEntity target) {
        if (target instanceof Player blocker && blocker.isBlocking()) {
            blocker.disableShield();
        }
    }

    /**
     * 唤魔者：**大范围伤害**，伤害不高，主要用来清理恼鬼。
     * （顺带把范围内的其他非玩家生物也扫一下，少量。）
     */
    private static void evokerBurst(Player player, LivingEntity target) {
        var source = ModDamageTypes.spell(player);
        double radius = 6.0D;

        if (player.level() instanceof ServerLevel server) {
            server.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
                    target.getX(), target.getY(0.6D), target.getZ(), 24, 2.5D, 1.0D, 2.5D, 0.02D);
        }

        for (LivingEntity victim : player.level().getEntitiesOfClass(LivingEntity.class,
                target.getBoundingBox().inflate(radius), e -> e != player && e.isAlive())) {

            if (victim instanceof Player other && !player.canHarmPlayer(other)) continue;

            float amount = victim instanceof Vex ? 200.0F : 3.0F;   // 恼鬼直接抹掉

            victim.invulnerableTime = 0;
            victim.hurt(source, amount);
        }
    }

    /** 女巫：附带一个**随机负面**。 */
    private static void randomDebuff(LivingEntity target) {
        Holder<MobEffect>[] pool = new Holder[]{
                MobEffects.POISON, MobEffects.MOVEMENT_SLOWDOWN, MobEffects.WEAKNESS,
                MobEffects.DIG_SLOWDOWN, MobEffects.BLINDNESS, MobEffects.HUNGER,
                MobEffects.CONFUSION, MobEffects.UNLUCK
        };
        effect(target, pool[RANDOM.nextInt(pool.length)], 200, 0);
    }

    private static String idOf(Entity entity) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return id == null ? "" : id.toString();
    }

    /** 留着给以后可能用到的判定（免得 import 被 IDE 清掉）。 */
    @SuppressWarnings("unused")
    private static boolean isUndead(LivingEntity entity) {
        return entity.getType().is(EntityTypeTags.UNDEAD);
    }

    // ==================== 僵尸援军的看管 ====================

    @EventBusSubscriber(modid = MahoragaMod.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
    public static final class ForgeEvents {
        private ForgeEvents() {
        }

        /**
         * **召唤出来的僵尸不许把玩家当目标**。
         *
         * 原版僵尸的 AI 就是"看见玩家就上"，不加这一条，"援军"转头先咬主人 ——
         * 那这能力就不是帮手而是添乱。
         *
         * 目标被拦掉之后，它们会去打 {@link #summonReinforcements} 里指定的那只怪，
         * 打完了就傻站着等 30 秒到期消散（`setLimitedLife` 管）。
         */
        @SubscribeEvent
        public static void onTargetChange(LivingChangeTargetEvent event) {
            if (!(event.getEntity() instanceof Mob mob)) return;
            if (!mob.getTags().contains(SERVANT_TAG)) return;
            if (event.getNewAboutToBeSetTarget() instanceof Player) event.setCanceled(true);
        }
    }
}
