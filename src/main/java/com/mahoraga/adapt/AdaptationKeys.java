package com.mahoraga.adapt;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.core.registries.BuiltInRegistries;

import javax.annotation.Nullable;

/**
 * 适应的「键」。一共四类前缀：
 *
 * - `damage:<伤害类型>` —— **防御轨的免伤进度**。2026-09-14 主人改的规矩：
 *   以前近战按**生物种类**记（适应僵尸 = 所有僵尸的伤害全免），打 Boss 太无敌
 *   （适应一遍就能站着不动）。现在一律按**伤害类型**：怪物换招式 = 换伤害类型 = 要重新适应。
 * - `entity:<生物注册名>` —— **生物独有的「招牌能力」进度**（防御轨 + 攻击轨都用）。
 *   防御轨上它**不再参与免伤**，只负责解锁专属效果；
 *   攻击轨上它管着攻击倍率和攻击侧专属效果。
 * - `daily:*` —— 日常适应。
 * - `effect:<药水id>` / `random:*` —— 负面药水适应 / 随机适应库。
 */
public final class AdaptationKeys {
    public static final String DAILY_MINING = "daily:mining";
    public static final String DAILY_SPRINT = "daily:sprint";
    public static final String DAILY_JUMP = "daily:jump";
    public static final String DAILY_WATER = "daily:water";
    public static final String DAILY_LAVA = "daily:lava";
    /** 身处黑暗（照度 ≤ 4）→ 适应出夜视。 */
    public static final String DAILY_DARK = "daily:dark";

    /**
     * **进食**（吃任何能吃的东西）→ 适应出「**吃得更快**」。
     *
     * 主人 2026-09-15 定：
     * - 吃一次给一份进度；
     * - 满 **100%** → **饱食度永久锁满**；
     * - 满 **200%** → 每次吃完额外获得一个**随机正面药水效果**（⚠️ **不含漂浮**）。
     *
     * "越吃越快"是**全程连续**的（每 100% 一档，最快 3 倍速），见 {@code skill/EatAdaptation}。
     */
    public static final String DAILY_EAT = "daily:eat";

    /**
     * **附魔**（在附魔台折腾物品）→ 适应出「**附魔台给出的等级更高**」。
     *
     * 主人 2026-09-15 定：
     * - 每 100% 给附魔台的**等级 +1**（最多 +10）→ 更容易出多个附魔 / 出高级附魔；
     * - 满 **100%** → **附魔不再消耗经验等级**（原版里花费和等级是同一个数，这里把扣掉的兜回来）。
     *
     * 触发用 `EnchantmentLevelSetEvent`（Forge），所以"越附越熟"是**在附魔台前操作**练出来的。
     */
    public static final String DAILY_ENCHANT = "daily:enchant";

    /**
     * **钓鱼**（真的把东西钓上来）→ 适应出「**鱼获翻倍 + 鱼竿不掉耐久 + 钓到宝藏**」。
     *
     * 主人 2026-09-15 定；触发用 `ItemFishedEvent`（Forge，收竿那一刻）。
     */
    public static final String DAILY_FISHING = "daily:fishing";

    /**
     * **投掷东西**（雪球 / 鸡蛋 / 末影珍珠 / 药水 / 经验瓶）→ 适应出「**投掷物自动追踪**」。
     *
     * 主人 2026-09-14 定：这一条不靠挨打、也不靠打怪，**丢得多了自然就会** ——
     * 所以它是日常轨（`gainDaily`），和挖矿/奔跑那几条一个规矩。
     *
     * 效果在 {@code skill/ProjectileHoming}：你投出去的东西会自己拐弯追最近的敌人。
     * ⚠️ **射箭不算**（那是"射"不是"投"，别把弓箭混进来）。
     */
    public static final String DAILY_THROW = "daily:throw";

    /**
     * 身处**洞穴**（头顶看不见天 + 在地面线以下）→ 适应出「物质穿透」，
     * 也就是狮子之心那套"把身体占住的方块直接抹掉、于是能穿墙"。
     *
     * 和 {@link #SUFFOCATION}（窒息，防御轨）是**两条独立的路**，任一条满 100% 就给穿透。
     */
    public static final String DAILY_CAVE = "daily:cave";

    /**
     * 窒息伤害的键（防御适应）。适应满 100% 之后解锁"物质穿透"——
     * 身体占着的方块会被直接抹掉，于是能穿过墙（抄的狮子之心那套）。
     */
    public static final String SUFFOCATION = "damage:minecraft:suffocation";

    /**
     * **卡墙**（整个人被挤进方块里、每 tick 掉血）—— 和窒息是**两条**伤害类型，
     * 但效果都该是"物质穿透"（主人 2026-09-14 报"卡墙不会适应出穿透"，就是漏了这条）。
     */
    public static final String IN_WALL = "damage:minecraft:in_wall";

    /**
     * 环境「着火」。进下界时自动喂一次的就是这一条
     * （下界最典型的就是身上烧起来）。
     */
    public static final String FIRE = "damage:minecraft:on_fire";

    /** 火焰类伤害的另外两条（"火焰"「岩浆」）—— 剑刃外观用它们判断"适应了火"。 */
    public static final String FIRE_IN = "damage:minecraft:in_fire";
    public static final String LAVA = "damage:minecraft:lava";

    /**
     * 寒冷（细雪冻伤）。适应满 100% 之后剑刃会变成寒霜。
     */
    public static final String FREEZE = "damage:minecraft:freeze";

    /**
     * **雷击**（原版闪电劈中的那条伤害类型）。
     *
     * 适应满 100% 之后，你的**每次攻击都会附带火焰与雷电** ——
     * 实现在 {@code skill/LightningStrike}。
     *
     * 练它有三条路：
     * 1. **被雷劈**（这就是本来的伤害类型，走防御轨自动累）；
     * 2. **雷雨天站在露天** —— 每 20 tick 喂一次，不然这玩意太难遇到；
     * 3. `/mahoraga set defense lightning 100`。
     */
    public static final String LIGHTNING = "damage:minecraft:lightning_bolt";

    /**
     * 投掷物（雪球 / 鸡蛋 / 药水 / 末影珍珠）。
     *
     * <p>⚠️ 2026-09-15 新增 —— 主人要求<b>剑气多一条解锁路</b>：被投掷物打中也能适应出来。
     */
    public static final String THROWN = "damage:minecraft:thrown";

    /**
     * **爆炸**（苦力怕 / TNT / 火球…）—— 原版的爆炸伤害分两条键。
     *
     * <p>适应满 100% 之后除了免伤，还会额外做一件事（主人 2026-09-15 要的）：
     * <b>爆炸再也推不动你</b>—— 走的是原版的「爆炸击退抗性」属性，
     * 跟减伤是两码事（那条走免伤逻辑，这条纯粹是不想被崩飞）。
     */
    public static final String EXPLOSION = "damage:minecraft:explosion";

    /** 玩家自己引爆的那些（床、打火石点 TNT）—— 和上一个分开记，但防击退时两条都算。 */
    public static final String PLAYER_EXPLOSION = "damage:minecraft:player_explosion";

    /**
     * **溺水**（防御轨）—— 满 100% 解锁「满象」的喷水（见 {@code skill/WaterJet}）。
     *
     * <p>和日常轨的 {@code daily:water}（浸泡水中）**任一条满 100%** 都算 ——
     * 被水淹过和一直泡在水里，学会的是同一件事。主人 2026-09-15 定。
     */
    public static final String DROWN = "damage:minecraft:drown";

    /**
     * **群殴适应**（防御轨）—— 被 **5 只以上**生物同时围攻时累积，
     * 满 100% 解锁「伏魔御厨子」的领域（见 {@code skill/MalevolentShrine}）。
     *
     * <p>⚠️ 这个键**不以 `damage:` 开头**，所以它**不参与免伤**（免伤是按伤害类型查表的），
     * 它纯粹是个"进度容器"。战斗轨那套（种子 → 自动增长 → 30 秒保持 → 衰减）照常适用。
     *
     * <p>主人 2026-09-15 的原话：「增加一个适应群殴，就是有超过五只生物同时围攻的时候适应，
     * 达到一百可以解锁伏魔御厨子的领域」。
     */
    public static final String SWARM = "swarm";


    /**
     * 「空间适应」—— 主人随口起的名，**其实就是末影人那一条**，机制和别的一模一样。
     * 效果：攻击锁定目标（无法改变坐标、无法传送）；防御侧能短距瞬移。
     */
    public static final String SPACE = "entity:minecraft:enderman";

    /** 专属适应里"剑气"的判定用：攻击适应这两个都是远程放风筝的，练满都给剑气。 */
    public static final String BOW_SKELETON = "entity:minecraft:skeleton";
    public static final String FLYING_PHANTOM = "entity:minecraft:phantom";

    /**
     * 负面药水效果的键（也算防御适应的一种）。
     * 这一类**全程静默**：不播报、不转轮、不刷屏，满了直接被动免疫。
     */
    public static final String EFFECT_PREFIX = "effect:";

    /**
     * 「随机适应库」里的适应 —— **不是练出来的，是打出来的**。
     *
     * 攻击某生物时，那条攻击适应到 **100%~200%** 之间按概率领悟一条、**200% 起必定**领悟。
     * 库里目前只有一条，以后往 {@code AdaptationManager.RANDOM_POOL} 里加就行。
     */
    public static final String RANDOM_PREFIX = "random:";

    /** 库 #1：**永恒流血**（搬自 Re:Zero 的死亡之神）—— 打谁谁流血，而且治不好。 */
    public static final String RANDOM_BLEEDING = RANDOM_PREFIX + "bleeding";

    /**
     * 「**永久解锁**」类条目 —— 一旦达成**永不衰减**（`advanceCombat` 里直接跳过，同 `random:`）。
     *
     * 为什么需要这一类：主人的原话是"**解锁**"，可解锁类的东西（剑气）以前是**现查进度** ——
     * 练到 300% 剑气出来了，停手 30 秒开始衰减，掉到 250% 以下 → **剑气当场消失**，
     * 看着就像"从来没学会过"（2026-09-15 报的"超过 250% 还是没解锁"就是这个）。
     *
     * 道具是**学会了就学会了**，不是"暂时会一下"。以后再有同类（"满 X% 就永久会"）往这儿挂。
     */
    public static final String UNLOCK_PREFIX = "unlock:";

    /** 学会**剑气**（三条路径任一达成后，钉在这个键上）。 */
    public static final String SLASH_UNLOCK = UNLOCK_PREFIX + "slash";

    public static String effectKey(MobEffect effect) {
        ResourceLocation id = BuiltInRegistries.MOB_EFFECT.getKey(effect);
        return EFFECT_PREFIX + (id == null ? "unknown" : id.toString());
    }

    private AdaptationKeys() {
    }

    /**
     * **防御适应的键 —— 一律按「伤害类型」记。**
     *
     * 🔴 2026-09-14 主人的改动：以前是"近战按生物种类 / 投掷物按投掷物本身"，
     * 结果适应一只僵尸就等于免疫**所有**近战，打 Boss 时站在那儿不动都行 —— 太变态。
     *
     * 现在按伤害类型分：
     * | 打过来的东西 | 伤害类型 | 记成 |
     * |---|---|---|
     * | 僵尸 / 尸壳 / 卫道士的近战 | `mob_attack` | **同一条**（都是近战，一起适应） |
     * | 骷髅射的箭 | `arrow` | 弓箭一条 |
     * | 三叉戟 | `trident` | 单独一条 |
     * | 雪球 / 鸡蛋 | `thrown` | 投掷物一条 |
     * | 烈焰人火球 | `fireball` | 单独一条 |
     * | 末影龙吐息 | `dragon_breath` | 单独一条 |
     * | 监守者音波 | `sonic_boom` | 单独一条 |
     *
     * **所以 Boss 换一招你就得重新适应一招** —— 这正是主人要的
     * （"如果怪物拿出新的招式，伤害类型不同还是要适应"）。
     *
     * 生物独有的招牌能力**不走这里** —— 那条在 {@link #signatureKey}。
     *
     * @param victim 挨打的人（保留参数是为了调用点统一，现在判据里用不到）
     */
    public static String defenseKey(DamageSource source, @Nullable Entity victim) {
        return damageKey(source);
    }

    /**
     * **生物「招牌能力」的键**（`entity:<注册名>`）。
     *
     * 免伤已经不看它了，它只负责回答一个问题：**你摸透了这个生物的哪一手？**
     * - 防御轨 → {@code skill/SignatureDefense}（尸壳 → 饱食度锁满、凋灵骷髅 → 生命上限不被削…）
     * - 攻击轨 → {@code skill/SignatureAttack}（打尸壳 → 谁饿、打末影人 → 空间锁定…）
     *
     * 顺带也是"剑气解锁"的判据之一（骷髅攻击 100% / 幻翼防御 100%）。
     */
    public static String signatureKey(Entity entity) {
        return entityKey(entity.getType());
    }

    /** 攻击适应的键 —— **仍然是按生物种类**（"掌握某个生物的弱点"这件事本来就该按生物）。 */
    public static String attackKey(Entity target) {
        return entityKey(target.getType());
    }

    /**
     * 这一下是不是"投掷物打过来的"。
     *
     * 判据取直击者是不是投掷物；另外叠一个原版的 {@code is_projectile} 伤害标签兜底
     * （模组投掷物只要挂了这个标签也能认出来）。
     */
    public static boolean isProjectile(DamageSource source, @Nullable Entity direct, @Nullable Entity victim) {
        if (direct == null || direct == victim) return false;
        if (direct instanceof Projectile) return true;

        return source.is(DamageTypeTags.IS_PROJECTILE);
    }

    private static String entityKey(EntityType<?> type) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        return "entity:" + (id == null ? "unknown" : id.toString());
    }

    private static String damageKey(DamageSource source) {
        String id = source.typeHolder().unwrapKey()
                .map(key -> key.location().toString()).orElse("unknown");
        return "damage:" + id;
    }

    public static String trackNameKey(Track track) {
        return "mahoraga.track." + track.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** 把键变成能读的名字。 */
    public static Component display(@Nullable String key) {
        if (key == null) return Component.literal("?");

        if (key.startsWith("entity:")) {
            ResourceLocation id = ResourceLocation.tryParse(key.substring(7));
            EntityType<?> type = id == null ? null : BuiltInRegistries.ENTITY_TYPE.getValue(id);
            return type == null ? Component.literal(key.substring(7)) : type.getDescription();
        }
        if (key.startsWith("damage:")) {
            String id = key.substring("damage:".length());

            // 原版伤害类型我们都有翻译（`mahoraga.damage.<名字>`）
            if (id.startsWith("minecraft:")) {
                return Component.translatable("mahoraga.damage." + id.substring("minecraft:".length()));
            }

            // 🔴 模组加的伤害类型没得翻 —— 直接把注册名亮出来。
            //    以前是硬拼成 `mahoraga.damage.legendary_monsters:clouds`，查不到就原样印到聊天栏，
            //    看着像 bug（日志里那句"已完全适应「mahoraga.damage.legendary_monsters:clouds」"就是这么来的）。
            return Component.literal(id);
        }
        if (key.startsWith("daily:")) {
            return Component.translatable("mahoraga.daily." + key.substring(6));
        }
        if (key.startsWith(RANDOM_PREFIX)) {
            return Component.translatable("mahoraga.random." + key.substring(RANDOM_PREFIX.length()));
        }
        if (key.startsWith(UNLOCK_PREFIX)) {
            return Component.translatable("mahoraga.unlock.key." + key.substring(UNLOCK_PREFIX.length()));
        }
        // 「群殴适应」这种没有前缀的独立键（不以 damage:/entity:/daily: 开头）
        if (key.equals("swarm")) {
            return Component.translatable("mahoraga.defense.swarm");
        }
        if (key.startsWith(EFFECT_PREFIX)) {
            ResourceLocation id = ResourceLocation.tryParse(key.substring(EFFECT_PREFIX.length()));
            MobEffect effect = id == null ? null : BuiltInRegistries.MOB_EFFECT.getValue(id);
            return effect == null
                    ? Component.literal(key.substring(EFFECT_PREFIX.length()))
                    : Component.translatable(effect.getDescriptionId());
        }
        return Component.literal(key);
    }
}
