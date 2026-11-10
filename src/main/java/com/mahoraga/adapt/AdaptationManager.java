package com.mahoraga.adapt;

import com.mahoraga.MahoragaMod;
import com.mahoraga.handler.BladeHandler;
import com.mahoraga.handler.WheelHandler;
import com.mahoraga.network.AdaptationHudPacket;
import com.mahoraga.network.MahoragaNetwork;
import com.mahoraga.registry.ModDamageTypes;
import com.mahoraga.registry.ModEffects;
import com.mahoraga.registry.ModItems;
import com.mahoraga.skill.BladeAbilities;
import com.mahoraga.skill.CrackEffects;
import com.mahoraga.skill.CrackPool;
import com.mahoraga.skill.DarkSight;
import com.mahoraga.skill.EatAdaptation;
import com.mahoraga.skill.EternalBleeding;
import com.mahoraga.skill.FireArrow;
import com.mahoraga.skill.FlameStrike;
import com.mahoraga.skill.HitFx;
import com.mahoraga.skill.LightningStrike;
import com.mahoraga.skill.MalevolentShrine;
import com.mahoraga.skill.MiningAdaptation;
import com.mahoraga.skill.ProjectileCounter;
import com.mahoraga.skill.SignatureAttack;
import com.mahoraga.skill.SignatureDefense;
import com.mahoraga.skill.SukunaCleave;
import com.mahoraga.skill.UndeadExecute;
import com.mahoraga.skill.VoidDomain;
import com.mahoraga.skill.WheelSymbiosis;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.event.enchanting.EnchantmentLevelSetEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.ItemFishedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent;
import net.neoforged.neoforge.event.entity.player.PlayerXpEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 三套适应系统的核心。
 *
 * ==================== 两条轨道（2026-09-14 主人定稿）====================
 * 防御 / 攻击 / 药水 是**战斗轨**，日常 是**日常轨** —— 两者规则完全不同，别再混着写。
 *
 * ── 战斗轨（防御 / 攻击 / 药水）—— 有保底、有保持期、会播报 ──
 *   碰到一次 ─▶ 种下种子 ─▶ 随时间自动涨（挨打越多涨得越快）
 *                              │
 *                              ▼ 涨到 150%（0~150 之间绝不衰减 = 保底）
 *                         保持 30 秒（挨打会把这 30 秒续上）
 *                              │
 *                              ▼ 30 秒一过
 *                         开始衰减（播报一次"开始衰减"），一路掉到 0
 *   150% 以上只能靠挨打继续堆（老公式的溢出曲线），最高 1000%。
 *
 * ── 日常轨（挖矿 / 奔跑 / 跳跃 / 泡水 / 泡岩浆 / 黑暗）—— 没有保底、不播报 ──
 *   做这个动作 ─▶ 每次给一份进度（老公式）
 *                    │
 *                    ▼ 动作停下 1 秒
 *                立即衰减（×3 速），不播报，可以一路掉到 0
 *   **没有保底、也不会被推到 150%** —— 一直做就一直涨（最高 1000%），
 *   一停手就开始掉。所以它是"在用时高、不用时归零"的活跃值。
 *
 * ==================== 两轨共通的规则 ====================
 * - **100% = 免疫线**；每跨过一个整数百（100/200/…/1000）= 法轮转圈 + 回 70% 血 + 修剑
 * - 免疫伤害之后**照样继续叠**：被攻击就加进度，不会因为"免伤"就停
 * - **数值涨到哪，效果就涨到哪**：日常加速/急迫/跳跃 10 级，攻击倍率最高 10 倍，
 *   投掷物 100% 弹开 / 200% 反弹 / 之后每 100% 反弹更强
 */
@EventBusSubscriber(modid = MahoragaMod.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class AdaptationManager {
    /** 免疫线：到 100% 就完全适应，再挨这一类伤害直接无效。 */
    public static final float COMPLETE = 1.0F;

    /**
     * 150% —— **战斗轨专用**：自动增长的上限，也是"保持 30 秒"的起点。
     * 0~150% 之间完全不衰减（这就是主人要的"保底"：涨上去的进度不会被吃掉）；
     * 到 150% 后保持 30 秒，之后开始衰减。
     *
     * ⚠️ **日常轨不用它** —— 日常没有保底，停下就直接掉。
     */
    public static final float FLOOR = 1.5F;

    /** 进度上限 1000%。 */
    public static final float MAX = 10.0F;

    /** 攻防适应的基础系数。 */
    private static final float BASE_GAIN = 0.40F;

    /** 溢出段的累积速度倍率（越小越难堆）。 */
    private static final float OVERFLOW_SCALE = 0.3F;

    /** 日常适应：每次动作 / 每秒。 */
    private static final float GAIN_MINING = 0.08F;
    private static final float GAIN_SPRINT = 0.10F;
    private static final float GAIN_JUMP = 0.07F;
    private static final float GAIN_WATER = 0.10F;
    private static final float GAIN_LAVA = 0.14F;
    /** 黑暗适应：待在黑里每秒的加速量（它同样只负责"加速"，进度靠自动增长）。 */
    private static final float GAIN_DARK = 0.14F;

    /** 身处洞穴每秒的进度 —— 比黑暗略慢一点（洞穴太常见，给点分量）。 */
    private static final float GAIN_CAVE = 0.10F;
    /**
     * **雷雨天站在露天**每秒喂给「雷击」适应的进度。
     *
     * 光靠"被闪电劈中"来练这条太看天吃饭了（原版闪电本来就少见、还得正好劈到你），
     * 所以补一条可主动练的路子：雷雨天出门挨淋就行 —— 和"下界自动喂着火"是同一个思路。
     */
    private static final float GAIN_LIGHTNING = 0.14F;

    /**
     * **被群殴**时每秒喂多少给「群殴适应」（还会按围攻数量放大，最多 ×2）。
     *
     * <p>0.08/秒 起手，算上边际递减，实际要**被围住打上二三十秒**才练满 100% ——
     * 这样"解锁伏魔御厨子"才像个成就，而不是路过一群鸡就解锁了。
     */
    private static final float GAIN_SWARM = 0.08F;

    /**
     * **投出去一次**给「投掷」适应多少（日常轨）。
     * 0.10 → 大约丢 10 次解锁「投掷物自动追踪」。
     */
    private static final float GAIN_THROW = 0.10F;

    /** 负面药水效果适应每秒的加速量。 */
    private static final float GAIN_EFFECT = 0.10F;

    // -------------------- 战斗轨参数（防御 / 攻击 / 药水）--------------------

    /** 超过保底之后，停手多久开始衰减（秒）。 */
    private static final int HOLD_SECONDS = 30;

    /**
     * **随时间自动增长的速度**（主人 2026-09-14 明确：适应会随着时间涨，
     * 受伤只是"加速"而已）。
     * 0.0125/秒 → 光挨一下不管它，大约 100 秒爬到 150% 保底线。
     */
    private static final float GROW_PER_SECOND = 0.0125F;
    /** 每挨一次打，加多少加速度。 */
    private static final float BOOST_PER_HIT = 0.20F;
    /** 加速度上限（→ 最高 5 倍速，约 24 秒到保底）。 */
    private static final float BOOST_MAX = 4.0F;
    /** 种下种子时先给一点点进度，好让它立刻出现在列表里、并开始自动涨。 */
    private static final float SEED = 0.01F;

    /** 衰减速度：每秒（战斗轨基准值）。 */
    private static final float DECAY_PER_SECOND = 0.015F;

    // -------------------- 日常轨参数（挖矿 / 奔跑 / 跳跃 / 泡水 / 泡岩浆 / 黑暗）--------------------

    /**
     * 日常轨的**保护期**（秒）：做过一次之后，这 30 秒内**一点都不掉**。
     *
     * 🔴 原来这里是 **1 秒**（`DAILY_ACTIVE_WINDOW = 20` tick），结果就是"一边涨一边掉"——
     * 挖矿 / 跳跃是**事件触发**的（挖一块、跳一下才给一次进度），
     * 那 1 秒在两次动作之间早就过去了，于是刚加进去的进度立刻开始衰减，
     * 表现就是"怎么练都堆不到 100%"（主人 2026-09-14 报的就是这个，跳跃和挖掘都卡在低位）。
     */
    private static final int DAILY_HOLD_SECONDS = 30;
    /** 日常衰减比战斗轨快（主人要的"快涨快掉"）。 */
    private static final float DAILY_DECAY_MULTIPLIER = 3.0F;

    /** 水 / 岩浆 任意一条到这个值 → 能站在液体表面（判据本体在 {@link LiquidWalk}）。 */

    /** 日常奖励的门槛。 */
    /** 跑步到这个值 → 自动跨过 1.5 格的方块。 */
    private static final float SPRINT_STEP = 2.0F;
    /** 跑步到这个值 → 饱食度锁定满格。 */
    private static final float SPRINT_FOOD = 3.0F;
    /** 玩家基础台阶高度 0.6，加这么多 → 一共 1.5 格。 */
    private static final double STEP_HEIGHT_BONUS = 0.9D;
    private static final ResourceLocation STEP_MODIFIER = ResourceLocation.fromNamespaceAndPath("mahoraga", "step_height");

    /** 跳跃到这个值 → **自身重力减少 15%**。 */
    private static final float JUMP_GRAVITY = 1.0F;
    /** 重力削减量（`MULTIPLY_TOTAL -0.15` → 实际是原来的 85%）。 */
    private static final double GRAVITY_CUT = -0.15D;
    private static final ResourceLocation GRAVITY_MODIFIER = ResourceLocation.fromNamespaceAndPath("mahoraga", "low_gravity");

    /** 吃一次给多少进食适应进度。 */
    private static final float GAIN_EAT = 0.12F;
    /** 进食到这个值 → **饱食度锁满**。 */
    private static final float EAT_FOOD_LOCK = 1.0F;
    /** 进食到这个值 → 吃完额外送一个**随机正面效果**（不含漂浮）。 */
    private static final float EAT_BUFF = 2.0F;

    // ==================== 适应供能：每次适应补饱食度 ====================

    /**
     * **每适应一次补几点饱食度**（2 点 = 一个鸡腿）。
     *
     * 主人 2026-09-15 晚加的：跑步适应要"一直在跑"才堆得起来，
     * 可饱食度一空就跑不动 → **越跑越饿、越饿越跑不动、越跑不动越适应不了**的死循环。
     * 补这一口就把它翻成正循环。
     */
    private static final int SATIETY_PER_ADAPT = 2;

    /**
     * 投喂节流：这么多 tick 内**最多补一次**。
     *
     * ⚠️ **必须节流** —— 日常适应是 tick 级触发的（跑步 / 泡水 / 泡岩浆每一 tick 都喂一次进度），
     * 不拦的话 1 秒能进账 20 × 2 = 40 点，直接变成"永久满饱食度"，
     * 那这个奖励就没意义了（等于把饱食度系统删了）。20 tick = **1 秒最多补 2 点**。
     */
    private static final int SATIETY_COOLDOWN = 20;

    /** 「上次补饱食度」的时间戳，写玩家的持久数据（`ForgeData`）。 */
    private static final String TAG_LAST_FEED = "mahoraga_fed_at";

    // ==================== 附魔 / 钓鱼 的门槛与常量 ====================

    /** 在附魔台操作一次给多少进度。 */
    private static final float GAIN_ENCHANT = 0.06F;
    /** 附魔到这个值 → **附魔不再消耗经验等级**。 */
    private static final float ENCHANT_FREE_XP = 1.0F;
    /** 附魔台等级的墙钟节流：这么多 tick 内只记一次进度（换一次物品会刷 3 行）。 */
    private static final int ENCHANT_GAIN_COOLDOWN = 40;
    /** 记"刚在附魔台动过"的时间窗 —— 这段时间里的掉级就算附魔扣的。 */
    private static final long ENCHANT_TABLE_WINDOW = 60L;

    /** 钓上来一次给多少进度。 */
    private static final float GAIN_FISHING = 0.12F;
    /** 钓鱼到这个值 → **鱼竿不消耗耐久**。 */
    private static final float FISHING_NO_DAMAGE = 1.0F;
    /** 鱼获倍数：每这么多进度翻一档（2.0 = 200%）。 */
    private static final float FISHING_PER_TIER = 2.0F;
    /** 钓鱼到这个值 → 每次**额外**多给一件宝藏。 */
    private static final float FISHING_TREASURE = 3.0F;
    /** 宝藏池（钓鱼宝藏表里最值钱的那几样）。 */
    private static final net.minecraft.world.item.Item[] FISHING_TREASURES = {
            Items.NAME_TAG, Items.SADDLE, Items.ENCHANTED_BOOK, Items.NAUTILUS_SHELL,
            Items.LILY_PAD, Items.BOW, Items.FISHING_ROD, Items.BOOK,
    };

    /** 进下界 / 末地时，入场事件喂的那一次适应（就加一次接触的量）。 */
    private static final float GAIN_REALM = 0.30F;

    /** 黑暗适应的判定：照度 ≤ 这个值就算"身处黑暗"。 */
    private static final int DARK_LIGHT_LEVEL = 4;

    /** 播报档位：不满百的三个 + 之后每 100% 一档（法轮转圈 / 反弹强化）。 */
    private static final int[] MILESTONES =
            {25, 50, 75, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000};

    /**
     * 日常效果的档位门槛 —— **数值涨到哪，效果就跟着涨到哪**。
     *
     * 主人 2026-09-14 要求"等级给高点"，所以门槛整体往下压、往密里排：
     * `10 / 25 / 50 / 75 / 100 / 150 / 200 / 300 / 450 / 600` 依次对应 I~X 级。
     *
     * 对比旧表（`25/50/100/200/…/800`）：**100% 时从 III 级提到 V 级**，
     * 也就是同样练到 100%，速度和急迫直接多两档 —— 手感差别很明显。
     *
     * 为什么最高只到 X：原版药水等级的中文/英文名只做到 `enchantment.level.10`，
     * 再高就会把 "enchantment.level.11" 这个键名原样显示出来，很难看。
     * 所以 600% 摸到 X 级封顶，600~1000% 那一段只加衰退缓冲、不再加等级。
     * （X 级的加成是 I 级的 10 倍 —— 速度 I = +20%，速度 X = +200%。）
     */
    private static final int[] EFFECT_THRESHOLDS = {10, 25, 50, 75, 100, 150, 200, 300, 450, 600};

    /** 药水等级最多显示到 X（放大器 0~9）。 */
    private static final int MAX_EFFECT_AMPLIFIER = 9;

    /** 攻击适应的伤害倍率上限。 */
    private static final float MAX_DAMAGE_MULTIPLIER = 10.0F;

    /**
     * **底子** —— 只要拿到法轮就有，跟适应进度无关（主人 2026-09-14 定）：
     * +5 颗心（10 点血）、+3 护甲。
     * 加上法轮自带的二段跳（见 {@link AirJump#tiersFor}），就是"什么都不练也是个怪物"。
     */
    private static final double BASE_HEALTH = 10.0D;
    private static final double BASE_ARMOR = 3.0D;

    /** 每完成一次防御适应给的血量 / 护甲（在底子之上继续叠）。 */
    private static final double HEALTH_PER_ADAPTATION = 8.0D;
    private static final double ARMOR_PER_ADAPTATION = 2.0D;

    /** 适应跨过 100% 的整数倍时回多少血：最大生命的 70%（**分几秒回完**，不是一下加上去）。 */
    private static final float HEAL_ON_MILESTONE = 0.70F;

    /**
     * 跨档回血摊在多少 tick 里回完 —— **3 秒**。
     * 主人 2026-09-15 定：别"啪"一下多出半管血，要看着它涨上去。
     */
    private static final int HEAL_TICKS = 60;

    private static final ResourceLocation HEALTH_MODIFIER = ResourceLocation.fromNamespaceAndPath("mahoraga", "adapt_health");
    private static final ResourceLocation ARMOR_MODIFIER = ResourceLocation.fromNamespaceAndPath("mahoraga", "adapt_armor");
    /** 爆炸击退抗性（适应了爆炸之后挂 100%，纯粹为了**不被崩飞**）。 */
    private static final ResourceLocation EXPLOSION_MODIFIER = ResourceLocation.fromNamespaceAndPath("mahoraga", "blast_resistance");
    /** 上一次告诉客户端的「多段跳额度」—— 用来只在变化时发包（平时安静）。 */
    private static final Map<UUID, Integer> LAST_JUMP_TIERS = new ConcurrentHashMap<>();

    /** 上一次检查时"会不会剑气"—— 用来只在刚解锁的那一刻播报一次。 */
    private static final Map<UUID, Boolean> LAST_SLASH_UNLOCK = new ConcurrentHashMap<>();

    private AdaptationManager() {
    }

    public static AdaptationData data(Player player) {
        return new AdaptationData(player.getPersistentData());
    }

    // ==================== 累积（老公式）====================

    /** 攻击适应从这一刻开始「越练越快」（值 = 300%）。 */
    private static final float ACCELERATE_FROM = 3.0F;
    /** 加速斜率：每多 100%，这一下给的量再多 50%。 */
    private static final float ACCELERATE_STEP = 0.5F;

    /**
     * 一段进度的贡献量。分三段：
     *
     * - **100% 之前**：边际效用递减（越接近满涨得越慢），是"主进度"；
     * - **100% ~ 300%**：换一条更慢的曲线继续堆（原来的溢出曲线，越往上越慢）；
     * - **300% 以上【攻击轨专有】**：**反过来加速** —— 每多 100%，这一下再多给 50%
     *   （300% → ×1.0、400% → ×1.5、500% → ×2.0 … 1000% → ×4.5）。
     *   主人 2026-09-14 要的"从三百开始越来越快"，越接近练满越是滚雪球。
     *
     * 伤害本身的影响一直在：`√(伤害 / 目标血量)` —— **自己打出的伤害越高，这一下涨得越快**，
     * 但只是**开根号**的关系（伤害翻四倍，速度才翻一倍），免得一刀秒杀就瞬间练满。
     *
     * @param accelerate 这条轨道吃不吃"300% 以上越练越快"（现在只有**攻击轨**吃）
     */
    private static float contributionOf(float current, float ratio, float base,
                                        boolean hasFloor, boolean accelerate) {
        if (current >= MAX) return 0.0F;

        float sqrt = (float) Math.sqrt(Math.max(0.0F, ratio));

        if (current < COMPLETE) {
            float value = base * sqrt * (float) Math.pow(1.0F - current, 0.7D);
            // 保底：打不动（伤害 0）也要能慢慢磨
            return hasFloor ? Math.max(value, base * 0.02F) : value;
        }

        // 300% 以上：滚雪球
        if (accelerate && current >= ACCELERATE_FROM) {
            float ramp = 1.0F + ACCELERATE_STEP * (current - ACCELERATE_FROM);
            return base * OVERFLOW_SCALE * sqrt * ramp;
        }

        float left = Math.max(0.0F, 1.0F - (current - COMPLETE) / (MAX - COMPLETE));
        return base * OVERFLOW_SCALE * sqrt * (float) Math.pow(left, 0.7D);
    }

    // ==================== 累积 · 战斗轨（防御 / 攻击 / 药水）====================

    /**
     * 一次"有伤害"的接触。
     *
     * - **保底线以下**：只做两件事 —— 种下种子（让进度开始随时间自动涨）+ 加加速度；
     *   进度本身交给每秒钟的自动增长；
     * - **保底线以上**：自动增长已经到顶了，这一段只能靠挨打继续堆（老公式的溢出曲线），
     *   一路堆到 1000%。
     */
    private static void gainDamage(Player player, AdaptationData data, Track track,
                                   String key, float amount, float maxHealth, float base, long now) {
        seedCombat(player, data, track, key);

        float current = data.get(track, key);
        if (current < FLOOR) return;                       // 下面交给自动增长

        float ratio = Math.max(0.0F, amount / Math.max(1.0F, maxHealth));
        apply(player, data, track, key, current,
                contributionOf(current, ratio, base, true, track == Track.ATTACK));
        refreshHold(data, track, key, now);
    }

    /**
     * **法轮寄生专用**：给一条**防御适应**记一次接触量。
     *
     * 宿主替玩家挨打时调它 —— 用**玩家自己的**血量算比例、记在**玩家自己的**进度上。
     * ⚠️ 宿主本身什么也得不到（主人明确定死的规矩：它只是替身 + 记录仪）。
     */
    public static void gainDamageFor(Player player, AdaptationData data, String key, float amount) {
        gainDamage(player, data, Track.DEFENSE, key,
                amount, player.getMaxHealth(), BASE_GAIN, player.level().getGameTime());
    }

    /**
     * **负面药水效果适应**（防御适应的一种）—— 由 {@link com.mahoraga.skill.PotionAdaptation} 每秒喂进来。
     * 全程静默，不播报不转轮，满了直接被动免疫。
     */
    public static void gainEffect(Player player, String key, long now) {
        gainCombat(player, data(player), Track.DEFENSE, key, GAIN_EFFECT, now);
    }

    /** 一次"无伤害"的战斗轨接触（目前只有药水效果走这里）。 */
    private static void gainCombat(Player player, AdaptationData data, Track track, String key,
                                   float base, long now) {
        seedCombat(player, data, track, key);

        float current = data.get(track, key);
        if (current < FLOOR) return;                       // 保底线以下交给自动增长

        apply(player, data, track, key, current, contributionOf(current, 1.0F, base, false, false));
        refreshHold(data, track, key, now);
    }

    /** 战斗轨的种子：给个起点 + 加加速度 + 标记"开始涨"（进度本身靠每秒自动增长）。 */
    private static void seedCombat(Player player, AdaptationData data, Track track, String key) {
        if (data.get(track, key) <= 0.0F) {
            data.set(track, key, SEED);

            // **第一次接触** → 「开始解析 xxxx」（主人 2026-09-14 定的文案）。
            // 药水适应和招牌能力这两类静默的不报（前者太杂、后者只在满 100% 说一句）。
            if (!isSilent(track, key) && !isQuietUntilComplete(track, key)) {
                player.displayClientMessage(Component.translatable("chat.mahoraga.adapt.begin",
                                AdaptationKeys.display(key))
                        .withStyle(ChatFormatting.GRAY), true);
            }
        }
        data.setBoost(track, key, Math.min(BOOST_MAX, data.boost(track, key) + BOOST_PER_HIT));
        data.setMode(track, key, AdaptationData.GROW);
    }

    // ==================== 累积 · 日常轨（挖矿 / 奔跑 / 跳跃 / 泡水 / 泡岩浆 / 黑暗）====================

    /**
     * 一次日常动作（挖一下 / 跑一秒 / 跳一次 / 泡一秒…）。
     *
     * 和战斗轨不一样：**每做一次就直接给一份进度**，没有"自动增长"这一层；
     * 同时刷新"刚刚还在做"的时间戳 —— 停下超过 1 秒，{@link #advanceDaily} 就开始衰减。
     */
    private static void gainDaily(Player player, AdaptationData data, String key, float base, long now) {
        if (data.get(Track.DAILY, key) <= 0.0F) {
            data.set(Track.DAILY, key, SEED);
        }
        data.setActiveAt(Track.DAILY, key, now);           // 记下"刚刚还在做"
        data.setMode(Track.DAILY, key, AdaptationData.GROW);

        float current = data.get(Track.DAILY, key);
        apply(player, data, Track.DAILY, key, current, contributionOf(current, 1.0F, base, false, false));
    }

    private static void apply(Player player, AdaptationData data, Track track, String key,
                              float current, float contribution) {
        if (contribution <= 0.0F) return;

        float next = Math.min(MAX, current + contribution);
        data.set(track, key, next);
        announce(player, data, track, key, percent(next));

        // 适应一次 → 补一口饱食度（内部带 1 秒节流，见 SATIETY_COOLDOWN）
        feed(player);
    }

    /**
     * **适应供能**：进度每涨一次就补 {@link #SATIETY_PER_ADAPT} 点饱食度。
     *
     * 这是 `apply()` 的**唯一出口** —— 战斗轨（挨打 / 打人 / 中负面药水）和日常轨
     * （挖矿 / 跑步 / 跳跃 / 泡水 / 泡岩浆 / 黑暗 / 洞穴 / 进食 / 附魔 / 钓鱼 / 投掷）
     * 全都从这儿过，所以一处挂上就是"**所有适应**都供能"，不用一根根去接。
     *
     * ⚠️ 两个坑：
     * 1. **必须节流** → {@link #SATIETY_COOLDOWN}。日常轨是 tick 级触发，
     *    不拦就变永久满饱食度；
     * 2. **只认服务端** → `FoodData` 是服务端权威的，客户端改了下一 tick 就被同步回原样
     *    （本项目的老毛病：两边的账要各记一遍或者干脆只在服务端动手）。
     */
    private static void feed(Player player) {
        if (!(player instanceof ServerPlayer)) return;

        long now = player.level().getGameTime();
        var tag = player.getPersistentData();

        if (now - tag.getLong(TAG_LAST_FEED) < SATIETY_COOLDOWN) return;
        tag.putLong(TAG_LAST_FEED, now);

        // eat(点数, 饱和度倍率)：内部会自己 clamp 到 20，吃饱了再喂也不会溢出
        player.getFoodData().eat(SATIETY_PER_ADAPT, 0.1F);
    }

    /** 到了保底之后，每挨一次打就把 30 秒保持期续上。 */
    private static void refreshHold(AdaptationData data, Track track, String key, long now) {
        if (data.get(track, key) < FLOOR) return;

        data.setHoldUntil(track, key, now + HOLD_SECONDS * 20L);
    }

    /** 进度（0.0~10.0）→ 百分比整数。 */
    public static int percent(float progress) {
        return (int) Math.floor(progress * 100.0F);
    }

    // ==================== 播报 ====================

    private static void announce(Player player, AdaptationData data, Track track, String key, int pct) {
        // 药水效果适应全程静默 —— 效果种类太多，一条条播报会把屏幕刷爆
        if (isSilent(track, key)) return;

        int announced = data.announced(track, key);
        boolean changed = false;

        for (int milestone : MILESTONES) {
            if (pct < milestone || announced >= milestone) continue;

            announced = milestone;
            changed = true;

            // 防御轨的「招牌能力」进度（entity: 键）：只在满 100% 那一刻说一句，
            // 25/50/75/200/300… 全不报 —— 免伤那条同时也在涨，两条都报会各响一遍。
            if (isQuietUntilComplete(track, key) && milestone != 100) continue;

            if (milestone == 100) {
                onComplete(player, track, key);
            } else if (milestone > 100) {
                onTier(player, track, key, milestone);
            } else {
                player.displayClientMessage(Component.translatable("chat.mahoraga.adapt.progress",
                                trackName(track), AdaptationKeys.display(key), milestone + "%")
                        .withStyle(ChatFormatting.YELLOW), true);
            }
        }

        if (changed) data.setAnnounced(track, key, announced);
    }

    private static Component trackName(Track track) {
        return Component.translatable(AdaptationKeys.trackNameKey(track));
    }

    /**
     * **完全静默**（一个字都不发）：负面药水效果适应。
     * 效果种类太多，一条条播报会把屏幕刷爆（主人："这个就别显示出来了，太乱了"）。
     */
    private static boolean isSilent(Track track, String key) {
        return key.startsWith(AdaptationKeys.EFFECT_PREFIX);
    }

    /** 是不是「负面药水效果」那条适应（`effect:<药水id>`）。 */
    private static boolean isEffectKey(String key) {
        return key.startsWith(AdaptationKeys.EFFECT_PREFIX);
    }

    /**
     * **只报 100%、中间档不刷屏**：防御轨上那些 `entity:` 键。
     *
     * 它们是"生物的招牌能力"进度 —— 被同一只怪打一下，
     * 免伤那条（`damage:mob_attack`）和它这条会**同时涨**，
     * 两条都报的话 25/50/75 会在屏幕上各出现两遍。
     */
    private static boolean isQuietUntilComplete(Track track, String key) {
        return track == Track.DEFENSE && key.startsWith("entity:");
    }

    /** 跨过 100%：播报 + 法轮转圈（动画+音效）+ 回 70% 血 + 修好 / 长出退魔之剑。 */
    private static void onComplete(Player player, Track track, String key) {
        // 防御轨的 `entity:` 是「招牌能力」（不是免伤）—— 文案和结算都不一样。
        // 不转轮也不回血：同一只怪打你的时候，免伤那条（damage:）几乎同时也会到 100%，
        // 那一下已经转过轮、回过血了，这里再来一次纯属重复。
        if (isQuietUntilComplete(track, key)) {
            player.displayClientMessage(Component.translatable("chat.mahoraga.adapt.signature",
                            AdaptationKeys.display(key))
                    .withStyle(ChatFormatting.AQUA), false);
            return;
        }

        player.displayClientMessage(Component.translatable(completeKey(track),
                        AdaptationKeys.display(key))
                .withStyle(ChatFormatting.GOLD), false);

        spinAndHeal(player);

        // 「独特破解」的抽取**不在这里** —— 现在是"每满 100% 一个机会、窗口内概率递增"，
        // 得靠每次攻击带着实时进度去喂，统一交给 {@link CrackPool#advance}（在 onHurt 里调）。

        // 「剑气」的解锁提示也不在这里 —— 解锁路子有三条（骷髅 100% / 幻翼 100% / 任意生物 250%），
        // 逐个写容易漏，统一交给每秒的 {@link #syncSlashUnlock} 检测后播报。
    }

    /**
     * 「剑气」解锁提示 —— 每秒对比一次"现在会不会剑气"，从"不会"翻成"会"的那一刻报一句。
     *
     * 用状态翻转而不是塞在某个档位上，是因为解锁途径有三条且互相独立
     * （骷髅攻击 100% / 幻翼防御 100% / 任意生物攻击 250%），
     * 挂在任何单独一条路上都会漏掉别的路径。
     */
    private static void syncSlashUnlock(Player player) {
        boolean now = SignatureAttack.hasSlash(player);
        UUID id = player.getUUID();
        Boolean before = LAST_SLASH_UNLOCK.put(id, now);

        if (now && !Boolean.TRUE.equals(before)) {
            player.displayClientMessage(Component.translatable("chat.mahoraga.unlock.slash")
                    .withStyle(ChatFormatting.AQUA), false);
        }
    }

    // ==================== 随机适应库 ====================

    /**
     * 「随机适应库」—— 一组**不靠练、靠打**的适应（主人 2026-09-14 起的概念）。
     *
     * 触发时机：**攻击**某个生物时，看那一条攻击适应的进度：
     *
     * | 进度 | 每次攻击领悟的概率 |
     * |---|---|
     * | < 100% | 没戏 |
     * | 100% ~ 200% | `(进度 − 100) / 100` —— 100% 时是 0，200% 时是 100% |
     * | ≥ 200% | **必定**领悟 |
     *
     * 领悟之后直接给满（100%），并且**永久保留**（`advanceCombat` 里 `random:` 开头的键
     * 直接跳过，不参与衰减 —— 否则掉下 100% 会被反复"重新领悟"，聊天栏刷屏）。
     * 库里目前只有一条（永恒流血），以后往这个数组里加就行。
     */
    private static final String[] RANDOM_POOL = {AdaptationKeys.RANDOM_BLEEDING};

    /** 攻击命中时调一次。 */
    public static void rollRandomAdaptation(Player player, int pct) {
        if (pct < 100) return;

        AdaptationData data = data(player);

        for (String key : RANDOM_POOL) {
            if (data.get(Track.ATTACK, key) >= COMPLETE) continue;         // 已经会了

            double chance = pct >= 200 ? 1.0D : (pct - 100) / 100.0D;
            if (player.getRandom().nextDouble() >= chance) continue;

            data.set(Track.ATTACK, key, COMPLETE);
            data.setMode(Track.ATTACK, key, AdaptationData.HOLD);
            data.setHoldUntil(Track.ATTACK, key, player.level().getGameTime() + HOLD_SECONDS * 20L);

            player.displayClientMessage(Component.translatable(randomUnlockKey(key))
                    .withStyle(ChatFormatting.LIGHT_PURPLE), false);
        }
    }

    private static String randomUnlockKey(String key) {
        return "chat.mahoraga.unlock.random." + key.substring(AdaptationKeys.RANDOM_PREFIX.length());
    }

    /** 跨过 200% / 300% … / 1000%：法轮再转一圈，投掷物反弹的速度和伤害跟着涨一档。 */
    private static void onTier(Player player, Track track, String key, int milestonePct) {
        player.displayClientMessage(Component.translatable("chat.mahoraga.adapt.tier",
                        trackName(track), AdaptationKeys.display(key), milestonePct + "%")
                .withStyle(ChatFormatting.GOLD), false);

        // 攻击轨每上一层都解锁新东西 —— 不单独说一句，玩家根本不知道多了什么
        if (track == Track.ATTACK) {
            String unlock = attackUnlockKey(milestonePct);

            if (unlock != null) {
                player.displayClientMessage(Component.translatable(unlock)
                        .withStyle(ChatFormatting.AQUA), false);
            }
        }

        // 「独特破解」不再挂在具体档位上 —— 改成"每满 100% 一个机会"，
        // 由 {@link CrackPool#advance} 在每次攻击时带着实时进度去判。

        spinAndHeal(player);
    }

    /** 攻击适应跨到这一档解锁了什么（没解锁新东西的档位返回 null）。 */
    private static String attackUnlockKey(int pct) {
        return switch (pct) {
            case 200 -> "chat.mahoraga.unlock.truedamage";
            case 500 -> "chat.mahoraga.unlock.percent";
            case 1000 -> "chat.mahoraga.unlock.oneshot";
            default -> null;
        };
    }

    private static void spinAndHeal(Player player) {
        player.displayClientMessage(Component.translatable("chat.mahoraga.adapt.spin")
                .withStyle(ChatFormatting.AQUA), true);

        WheelHandler.spin(player);
        startFastHeal(player);
        BladeHandler.refresh(player);

        // 头顶的法轮转了，**HUD 上那个也要跟着转 360 度**（主人 2026-09-15 要的）。
        // 序号 +1 之后立刻推一次，不等每秒那趟同步 —— 不然画面比头顶慢半拍，看着像两回事。
        if (player instanceof ServerPlayer server) {
            var tag = server.getPersistentData();
            tag.putInt(TAG_HUD_SPIN, tag.getInt(TAG_HUD_SPIN) + 1);
            syncHud(server, data(server));
        }
    }

    // ==================== 左上角 HUD（法轮 + 正在适应的项目）====================

    /** 法轮"转到第几圈"的计数器，写玩家持久数据（客户端比自己记住的上次序号，不一样就转）。 */
    private static final String TAG_HUD_SPIN = "mahoraga_hud_spin";

    /**
     * 上次推给这人的 HUD 内容签名 —— 写在**他自己**的持久数据里。
     *
     * ⚠️ 别改成服务端静态 Map：那样开一局攒一条，长年不关服会慢慢涨（本项目已有同类疏漏）。
     * 挂玩家身上是自动跟着存档走的，零泄漏。
     */
    private static final String TAG_HUD_SIG = "mahoraga_hud_sig";

    /** HUD 最多列几行 —— 超出的按进度从高到低截掉（主人要"小一点、不挡视野"）。 */
    private static final int HUD_MAX_ROWS = 5;

    /**
     * 把 HUD 数据推给本人 —— **内容或转动序号变了才发**。
     *
     * 挂在每秒那趟 tick 上；"适应完成"时还会立刻补推一次（见 {@link #spinAndHeal}）。
     * `wheel=false` 的空包也照发一次：那是"这人现在没法轮，把 HUD 收回去"的信号。
     */
    public static void syncHud(ServerPlayer player, AdaptationData data) {
        boolean wheel = WheelHandler.hasWheel(player);
        var tag = player.getPersistentData();
        int spin = tag.getInt(TAG_HUD_SPIN);
        List<AdaptationHudPacket.Entry> entries = wheel ? hudEntries(data) : List.of();

        StringBuilder sig = new StringBuilder(wheel ? "on" : "off").append('|').append(spin);

        for (AdaptationHudPacket.Entry entry : entries) {
            sig.append('|').append(entry.key()).append(':').append(entry.percent());
        }

        String now = sig.toString();
        if (now.equals(tag.getString(TAG_HUD_SIG))) return;                 // 没变 → 一个字节都不发

        tag.putString(TAG_HUD_SIG, now);
        MahoragaNetwork.sendHud(player, new AdaptationHudPacket(wheel, entries, spin));
    }

    /**
     * 收集"**正在适应**"的条目：三轨里进度 > 0 的全算，按进度从高到低排，只留前 {@link #HUD_MAX_ROWS} 条。
     *
     * ⚠️ 进度是 0.0~10.0（1000%），`percent()` 转成 0~1000 的整数。
     * 刚种下种子（1%）也算 —— 这是故意的：主人要的就是"**正在**适应什么"，
     * 不然刚开打时列表一直空着，看着像坏了。
     */
    private static List<AdaptationHudPacket.Entry> hudEntries(AdaptationData data) {
        List<AdaptationHudPacket.Entry> all = new ArrayList<>();

        for (Track track : Track.values()) {
            for (String key : data.keys(track)) {
                float value = data.get(track, key);
                if (value <= 0.0F) continue;

                all.add(new AdaptationHudPacket.Entry(key, track.ordinal(), percent(value)));
            }
        }

        all.sort((a, b) -> Integer.compare(b.percent(), a.percent()));
        return all.size() <= HUD_MAX_ROWS ? all : new ArrayList<>(all.subList(0, HUD_MAX_ROWS));
    }

    // ==================== 跨档回血：改成"快速回血" ====================

    /**
     * 排队中的快速回血：`[0]` 还剩多少没回，`[1]` 每 tick 回多少。
     *
     * 主人 2026-09-15 要求：**不要"瞬间多出一条血"，要看着它涨上去**。
     * 所以总量还是最大生命的 70%，但摊到 {@link #HEAL_TICKS}（3 秒）里一 tick 一点地回，
     * 血条会明显往上爬 —— 手感比"啪一下满半管"好得多。
     */
    private static final Map<UUID, float[]> PENDING_HEAL = new ConcurrentHashMap<>();

    /** 开一次快速回血。 */
    private static void startFastHeal(Player player) {
        float total = player.getMaxHealth() * HEAL_ON_MILESTONE;
        if (total <= 0.0F) return;

        PENDING_HEAL.put(player.getUUID(), new float[]{total, total / HEAL_TICKS});
    }

    /** 每 tick 回一点（挂在 `onPlayerTick` 的"每 tick 必做"区）。 */
    private static void tickFastHeal(Player player) {
        float[] task = PENDING_HEAL.get(player.getUUID());
        if (task == null) return;

        float amount = Math.min(task[0], task[1]);

        player.heal(amount);
        task[0] -= amount;

        // 飘几颗爱心，让"在回血"这件事看得见
        if (player.level() instanceof ServerLevel server && player.tickCount % 6 == 0) {
            server.sendParticles(ParticleTypes.HEART, player.getX(), player.getY() + 1.0D, player.getZ(),
                    1, 0.4D, 0.3D, 0.4D, 0.01D);
        }

        // 回完了、或者已经满血 → 收工
        if (task[0] <= 0.0F || player.getHealth() >= player.getMaxHealth()) {
            PENDING_HEAL.remove(player.getUUID());
        }
    }

    /** 每种适应的完成语要说自己的话 —— 日常适应跟"伤害"没有半点关系。 */
    private static String completeKey(Track track) {
        return switch (track) {
            case DEFENSE -> "chat.mahoraga.adapt.complete.defense";
            case ATTACK -> "chat.mahoraga.adapt.complete.attack";
            case DAILY -> "chat.mahoraga.adapt.complete.daily";
        };
    }

    // ==================== 每秒推进 ====================
    //
    //   战斗轨：自动增长 → 保持 30 秒 → 衰减（播报一次）→ 归零
    //   日常轨：还在做动作就一点不掉；停下 1 秒 → 立即衰减（不播报）→ 归零
    //
    //   两套逻辑彻底分开，各走各的 —— 别再往一个方法里塞。

    /**
     * **战斗轨**的状态机，完全由 {@link AdaptationData#mode} 驱动。
     *
     * ⚠️ **为什么不能只靠"数值 < 150 就自动涨"判断**：
     * 那样衰减刚掉 1%，下一 tick 又被自动涨回 150%，两边互相拉扯，
     * 结果永远卡在 150% 掉不下去（模拟里被抓到过）。
     * 所以必须由"种子"（mode=GROW）决定要不要涨：进入衰减后增长就停了，
     * 直到**再次被打到**才重新种下种子。
     */
    private static void advanceCombat(Player player, AdaptationData data, Track track, String key, long now) {
        // 🔴 「随机适应库」的条目是**永久**的，不参与这套状态机。
        //    （2026-09-14 修：以前它跟普通适应一样会衰减到 0，一掉下 100% 就又能"重新领悟"，
        //     于是"你从战斗中领悟了「永恒流血」"这条消息在聊天栏里刷了屏 —— 一次战斗刷了 8 次。）
        // 🔴 「永久解锁」类（`unlock:`）和随机适应库同理 —— **学会了就不会忘**。
        //    2026-09-15：剑气以前是"现查进度"，练到 300% 一出、停手衰减到 250% 以下就当场消失，
        //    主人报的"超过 250% 还是没解锁"就是这个。现在达成那一刻钉一个 `unlock:slash`，之后永不衰减。
        if (key.startsWith(AdaptationKeys.UNLOCK_PREFIX)) return;
        if (key.startsWith(AdaptationKeys.RANDOM_PREFIX)) return;

        float value = data.get(track, key);
        int mode = data.mode(track, key);

        if (value <= 0.0F) return;

        if (mode == AdaptationData.IDLE) {
            // 老存档：有数值但没有状态（上一版把所有东西都清成 IDLE 了）→ 补一个，别让它卡死
            data.setMode(track, key, value < FLOOR ? AdaptationData.GROW : AdaptationData.HOLD);
            data.setHoldUntil(track, key, now + HOLD_SECONDS * 20L);
            return;
        }

        switch (mode) {
            case AdaptationData.GROW -> {
                if (value < FLOOR) {
                    // 随时间自动往上涨（挨打只是加速）
                    float rate = GROW_PER_SECOND * (1.0F + data.boost(track, key));
                    float next = Math.min(FLOOR, value + rate);

                    data.set(track, key, next);
                    announce(player, data, track, key, percent(next));

                    if (next >= FLOOR && data.holdUntil(track, key) <= now) {
                        // 刚爬到 150% —— 从这里开始算 30 秒保持期
                        data.setHoldUntil(track, key, now + HOLD_SECONDS * 20L);
                    }
                } else {
                    // 已经被挨打推过 150% 了 → 进保持期，等 30 秒
                    data.setMode(track, key, AdaptationData.HOLD);

                    if (data.holdUntil(track, key) <= now) {
                        data.setHoldUntil(track, key, now + HOLD_SECONDS * 20L);
                    }
                }
            }

            case AdaptationData.HOLD -> {
                if (now >= data.holdUntil(track, key)) {
                    data.setMode(track, key, AdaptationData.DECAY);
                }
            }

            case AdaptationData.DECAY -> {
                // 本轮衰减的第一次 → 播报一次；这一轮里不会再播（回到 0 才解锁下一轮）。
                // 药水效果适应照旧静默。
                if (!isSilent(track, key) && !data.noticeSent(track, key)) {
                    data.setNoticeSent(track, key, true);

                    player.displayClientMessage(Component.translatable("chat.mahoraga.adapt.decaying",
                                    trackName(track), AdaptationKeys.display(key))
                            .withStyle(ChatFormatting.RED), true);
                }

                float next = value - DECAY_PER_SECOND;

                // 🔴 **药水适应掉到 100% 就钉住，永远不再往下掉**（主人 2026-09-15 报的 bug）。
                //
                //    原来的死循环：适应满 → `PotionAdaptation` 把身上的 buff 摘掉 →
                //    `getActiveEffects()` 里没它了 → `gainEffect` 不再喂进度 → 开始衰减 →
                //    掉下 100% → **免疫失效** → 又被上 buff → 从头再练。
                //    表现就是主人说的"**第一次能适应，第二次就不行了**"。
                //
                //    钉在 100% 之后免疫永久有效，下次中同一个 debuff 直接被挡掉 ——
                //    这才是"适应了"该有的样子：你已经免疫这个毒了，不存在"忘掉"这回事。
                if (isEffectKey(key) && next <= COMPLETE) {
                    data.set(track, key, COMPLETE);
                    data.setMode(track, key, AdaptationData.HOLD);
                    data.setHoldUntil(track, key, Long.MAX_VALUE / 4L);   // 永不过期
                    return;
                }

                if (next <= 0.0F) {
                    data.removeKey(track, key);         // 掉光 → 回 IDLE，要重新接触才会再涨
                    return;
                }

                data.set(track, key, next);
                lowerAnnounced(data, track, key, percent(next));
            }

            default -> {
                // 不会走到
            }
        }
    }

    /**
     * **日常轨**比战斗轨简单得多 —— 因为它没有保底、也没有保持期：
     *
     *   还在做这个动作（1 秒内碰过）→ 一点不掉（进度由 {@link #gainDaily} 每次动作推）
     *   停下了                     → **立即**按 ×3 速衰减，不播报，一路掉到 0
     *
     * 所以日常进度天然是"在用时高、不用时归零"的活跃值。
     */
    private static void advanceDaily(Player player, AdaptationData data, String key, long now) {
        float value = data.get(Track.DAILY, key);
        if (value <= 0.0F) return;

        // **保护期**：这 30 秒内做过这个动作，就一点都不掉（主人 2026-09-14 改的，
        // 之前只保 1 秒，害得挖矿/跳跃"一边涨一边掉"，永远堆不起来）
        if (now - data.activeAt(Track.DAILY, key) <= DAILY_HOLD_SECONDS * 20L) {
            data.setMode(Track.DAILY, key, AdaptationData.GROW);
            return;
        }

        // 保护期过了才开始衰减。
        // ⚠️ 日常**不播报**"开始衰减" —— 种类太多，一条条刷屏太乱（主人明确要求）。
        data.setMode(Track.DAILY, key, AdaptationData.DECAY);

        float next = value - DECAY_PER_SECOND * DAILY_DECAY_MULTIPLIER;

        if (next <= 0.0F) {
            data.removeKey(Track.DAILY, key);               // 掉光 → 清干净，下次从头来
            return;
        }

        data.set(Track.DAILY, key, next);
        lowerAnnounced(data, Track.DAILY, key, percent(next));   // 档位跟着降，方便重新播报
    }

    private static void lowerAnnounced(AdaptationData data, Track track, String key, int pct) {
        int announced = data.announced(track, key);
        if (announced <= 0 || pct >= announced) return;

        int lowered = 0;

        for (int milestone : MILESTONES) {
            if (milestone <= pct) lowered = milestone;
        }
        data.setAnnounced(track, key, lowered);
    }

    // ==================== 事件：防御（挨打）====================

    private static void onAttack(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level().isClientSide) return;
        if (!WheelHandler.hasWheel(player)) return;

        AdaptationData data = data(player);
        DamageSource source = event.getSource();
        long now = player.level().getGameTime();

        // —— 防御适应①：**免伤**看的是「伤害类型」。
        //    僵尸的一刀和尸壳的一刀是同一条（mob_attack），但烈焰人的火球、
        //    末影龙的吐息、监守者的音波各算各的 —— **怪物换招式就得重新适应**。
        String defenseKey = AdaptationKeys.defenseKey(source, player);
        gainDamage(player, data, Track.DEFENSE, defenseKey,
                event.getAmount(), player.getMaxHealth(), BASE_GAIN, now);

        // —— 防御适应②：被生物打时**额外**记一份「这个生物的招牌能力」（静默）。
        //    它不参与免伤，只管解锁专属效果（尸壳 → 饱食度锁满、凋灵骷髅 → 生命上限不被削…）。
        //    远程怪也算 —— 被谁打就是在跟谁交手。
        Entity sourceEntity = source.getEntity();
        if (sourceEntity != null && sourceEntity != player) {
            gainDamage(player, data, Track.DEFENSE, AdaptationKeys.signatureKey(sourceEntity),
                    event.getAmount(), player.getMaxHealth(), BASE_GAIN, now);
        }

        // —— 投掷物：适应够了就挥手弹开 / 反弹（挨打照样继续叠适应）
        ProjectileCounter.tryDeflect(player, source, data, defenseKey);

        // 日常：完全适应了岩浆 → 烧不着
        if (source.is(DamageTypeTags.IS_FIRE)
                && data.get(Track.DAILY, AdaptationKeys.DAILY_LAVA) >= COMPLETE) {
            gainDaily(player, data, AdaptationKeys.DAILY_LAVA, GAIN_LAVA * 0.4F, now);
            event.setCanceled(true);
            player.setRemainingFireTicks(0);
            return;
        }
        // 日常：完全适应了水 → 淹不死
        if (source.is(DamageTypes.DROWN)
                && data.get(Track.DAILY, AdaptationKeys.DAILY_WATER) >= COMPLETE) {
            gainDaily(player, data, AdaptationKeys.DAILY_WATER, GAIN_WATER * 0.4F, now);
            event.setCanceled(true);
            return;
        }
        // 防御：**满 100% 才彻底免疫** —— 在这一步就取消掉，连抖动和屏幕发红都没有。
        // （0~100% 之间的渐进减伤放在 LivingIncomingDamageEvent 里做，那个事件才允许改伤害数值。）
        if (data.get(Track.DEFENSE, defenseKey) >= COMPLETE) {
            event.setCanceled(true);
            swingGuard(player);          // 已经彻底适应 → 顺手挥手拨开（魔虚罗同款动作）
        }
    }

    // ==================== 事件：攻击（打人）====================

    /**
     * 攻击适应用 **AttackEntityEvent**，不用 LivingIncomingDamageEvent。
     * Hurt 事件要求"目标真的掉血了"才触发 —— 空手打、目标免疫、被别的模组拦掉都会漏；
     * AttackEntityEvent 是"玩家挥出一次攻击"就一定有。
     */
    @SubscribeEvent
    public static void onPlayerAttack(AttackEntityEvent event) {
        Player player = event.getEntity();
        if (player.level().isClientSide) return;
        if (!WheelHandler.hasWheel(player)) return;

        Entity target = event.getTarget();
        if (!(target instanceof LivingEntity living) || target == player) return;

        // AttackEntityEvent 拿不到实际伤害（伤害是后面才算的），
        // 用"玩家当前攻击力"和"目标血量"套同一个公式，等价于以前的做法。
        float amount = (float) player.getAttributeValue(Attributes.ATTACK_DAMAGE);

        AdaptationData data = data(player);

        gainDamage(player, data, Track.ATTACK, AdaptationKeys.attackKey(target),
                amount, living.getMaxHealth(), BASE_GAIN, player.level().getGameTime());

        // 解析线：每次挥击都记一笔观察 —— 标签（血厚 / 锁血 / 会传送…）就是这么攒出来的
        AnalyzeTracker.onSwing(player, living);

        // 攻击侧专属效果：对方有招牌能力、且这一条已经满 100% → 把它的能力用在你手上
        SignatureAttack.apply(player, living, data);
    }

    /** 攻击适应的四档：普通 → 法伤 → 真伤 → 极强（靠伤害倍率体现）。 */
    @SubscribeEvent
    public static void onHurt(LivingIncomingDamageEvent event) {
        // NeoForge 1.21.1 合并了旧 LivingAttackEvent / LivingHurtEvent 的入口。
        // 先执行旧攻击阶段，再执行旧受伤阶段，确保免疫逻辑先于渐进减伤。
        onAttack(event);
        if (event.isCanceled()) return;
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide) return;

        DamageSource source = event.getSource();

        // —— 我是挨打的一方：**渐进式减伤**
        // 25% 减掉四分之一、75% 减掉四分之三、100% 才彻底免疫（那一步在 LivingIncomingDamageEvent 就取消了）。
        // （原来的"举剑格挡"连同 75% 那一档已经删掉 —— 右键让给了特殊能力。
        //   想减伤还有 GuardSkill：手持退魔剑 + 潜行 = 减伤 50% + 自动反击，那个是独立事件，不在这里。）
        if (victim instanceof Player holder && WheelHandler.hasWheel(holder)) {
            float defense = data(holder).get(Track.DEFENSE, AdaptationKeys.defenseKey(source, holder));

            if (defense > 0.0F && defense < COMPLETE) {
                event.setAmount(event.getAmount() * (1.0F - defense));
            }

            if (event.getAmount() <= 0.0F) {
                event.setCanceled(true);
                return;
            }

            // ⚠️ 2026-09-18 删：这里原来是「法轮寄生」的伤害转移（让宿主替你挨这一下）。
            //    主人把那个玩法整个删了 —— 现在玩家潜入影子时是**普通人**（法轮在魔虚罗身上），
            //    挨打就自己扛，不转给别人。

            // —— **末影人防御适应满 100%**：被打中的瞬间闪到攻击者背后，反手一刀。
            //    放在寄生转移**之后** —— 伤害已经转给宿主的话就不用再闪了。
            //    ⚠️ 这里显式调用（而不是靠它自己的 @SubscribeEvent）：
            //    `AdaptationManager` 这个类是**实测确认在跑**的事件类，别再赌注册问题。
            SignatureDefense.onCounterHurt(holder, event);
        }

        // 反击伤害 / 我们自己的附加伤害都不吃下面这套 —— 否则自己喂自己，越打越强还无限递归
        if (ModDamageTypes.isOwned(source)) return;

        Entity attacker = source.getEntity();

        if (attacker instanceof Player player && victim != player
                && WheelHandler.hasWheel(player)) {
            AdaptationData data = data(player);
            int pct = percent(data.get(Track.ATTACK, AdaptationKeys.attackKey(victim)));

            float multiplier = damageMultiplier(pct);

            if (multiplier != 1.0F) {
                event.setAmount(event.getAmount() * multiplier);
            }

            applyAttackEffects(player, victim, pct);

            // 命中特效：这条生物的攻击适应 ≥ 100% → **每一刀**都炸一簇粒子，
            // 颜色随「适应的元素（火/冰/无）× 适应档位（每 100% 一档）」递进。
            HitFx.play(player, victim, pct);

            // 「雷击」适应（防御轨）满 100% → 这一刀**附带火焰与雷电**。
            // 注意它看的是**防御轨**的进度 —— 这条是"被雷劈会了"，不是"打谁会的"。
            LightningStrike.onHit(player, victim, data.get(Track.DEFENSE, AdaptationKeys.LIGHTNING));

            // **适应火焰** → 每一刀都带火（着火 / 火焰 / 岩浆任一满 100%）。
            // 2026-09-15 补：这条以前只让**剑刃变色**，攻击上一直没有任何回报（主人报的"不能附加火焰"）。
            FlameStrike.onHit(player, victim, data);

            // 猪灵蛮兵：这一下**无视护甲** —— 把被甲吃掉的那份补回来
            if (SignatureAttack.ignoresArmor(player, victim)) {
                restoreArmorPenalty(victim, event);
            }

            // 解析线：把学会的「独特破解」打出去（每次攻击最多触发一个，各自有冷却）
            CrackEffects.apply(player, victim, AnalyzeTracker.idOf(victim), event.getAmount());

            // 解析线：抽签 —— **每满 100% 一个机会**，窗口内概率从 0 涨到 100%
            // （100%→200% 是第 1 次、200%→300% 是第 2 次…到下一百必出）。
            // 池子只装这只怪**真正有的条件**，所以不会传送的怪抽不到「禁止传送」。
            CrackPool.advance(player, AnalyzeData.of(player), AnalyzeTracker.idOf(victim), pct);

            // ★ Boss 解析播报：打中 ≥100 血的怪时演一段"逐步看穿"（一只怪只演一次）
            AnalyzeNarrator.onAttack(player, victim);

            // 退魔之剑的「亡灵斩杀」—— **必须放最后**：要等攻击倍率、附加伤害、
            // 无视护甲、独特破解全都算完，再判"剩血不足 1/3"才是准的（早了阈值就是错的）。
            UndeadExecute.tryExecute(player, victim, event);
        }
    }

    /**
     * 解析线的**快照点** —— 每次对生物造成伤害后，记下"打之前它多少血 + 这一刀多少伤害 + 它在哪"。
     *
     * 🔴 **必须用 `LivingDamageEvent`，不能用 `LivingIncomingDamageEvent`**：
     * Hurt 那一刻伤害**还没结算**（护甲、附魔都还没算），读到的血量是攻击前的，
     * 拿它去算"实际掉血"会永远得到 0，标签一个都判不出来。
     * Damage 事件是"护甲减伤之后、setHealth 之前"——正好是我们要的那一拍。
     */
    @SubscribeEvent
    public static void onDamageDealt(LivingDamageEvent.Post event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide) return;

        DamageSource source = event.getSource();
        if (ModDamageTypes.isOwned(source)) return;        // 我们自己的补刀不算"观察"

        if (source.getEntity() instanceof Player player && victim != player
                && WheelHandler.hasWheel(player)) {
            AnalyzeTracker.onDamage(player, victim, event.getNewDamage());
        }
    }

    /**
     * 把"被护甲吃掉的那部分"补回来 = 这一下无视护甲。
     *
     * 用原版护甲减伤公式反推：
     *   减伤比例 f = min(20, max(甲/5, 甲 − 伤害/(2 + 韧性/4))) / 25
     * 于是原始伤害 = 当前伤害 / (1 − f)。
     */
    private static void restoreArmorPenalty(LivingEntity victim, LivingIncomingDamageEvent event) {
        double armor = victim.getArmorValue();
        if (armor <= 0.0D) return;

        double toughness = victim.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        double amount = event.getAmount();

        double reduction = Math.min(20.0D,
                Math.max(armor / 5.0D, armor - amount / (2.0D + toughness / 4.0D))) / 25.0D;

        if (reduction <= 0.0D || reduction >= 1.0D) return;

        event.setAmount((float) (amount / (1.0D - reduction)));
    }

    /**
     * 攻击适应跨过档次之后的额外效果（主人 2026-09-14 重排过，**层层累加、不替换**）：
     * - **100%**：附带"一半基础伤害"的**法术伤害**（无视护甲）；
     * - **200%**：追加"一半基础伤害"的**真伤**（护甲 / 附魔 / 抗性 / 吸收全都不认）；
     * - **250%**：解锁**剑气**（判定在 {@link com.mahoraga.skill.SignatureAttack#hasSlash}）；
     * - **500%**：追加 50 点 + 目标**最大生命 20%** 的伤害；
     * - **1000%**：打到直接**无条件秒杀**。
     *
     * 两种附加伤害都走本模组自己的伤害类型，既记在玩家头上，
     * 又不会回头再喂一次攻击适应（否则自己喂自己，越打越强还无限递归）。
     */
    private static void applyAttackEffects(Player player, LivingEntity victim, int pct) {
        if (victim.isDeadOrDying()) return;

        if (pct >= 1000) {
            DamageSource slash = ModDamageTypes.spell(player);

            // 只需要**稳稳打穿血量上限**。原来写成 `maxHealth × 1000 + 1000` 是图省事 ——
            // 20 血的僵尸会打出 21000，数字大到看不懂，而且一点意义都没有。
            victim.invulnerableTime = 0;
            victim.hurt(slash, victim.getMaxHealth() + victim.getAbsorptionAmount() + 100.0F);

            // 🔴 **不要在这里补 `setHealth(0) + die()`**（原来那一版有）。
            //    那等于绕开目标自己的死亡流程 —— Boss 会出现"人还站着、掉落物先掉出来"
            //    （主人 2026-09-14 报的就是这个）。打不打得死，让它自己的逻辑判。
            return;
        }

        float base = (float) player.getAttributeValue(Attributes.ATTACK_DAMAGE);
        if (base < 0.0F) base = 0.0F;

        if (pct >= 100) {                             // 100%：法术伤害
            strike(player, victim, base * 0.5F, false);
        }
        if (pct >= 200) {                             // 200%：追加真伤
            strike(player, victim, base * 0.5F, true);
        }
        if (pct >= 500) {                             // 500%：追加百分比伤害
            strike(player, victim, 50.0F + victim.getMaxHealth() * 0.2F, false);
        }
    }

    /**
     * 补一发附加伤害：先把无敌帧清掉，不然会被目标的受击冷却整段吃掉。
     *
     * @param trueDamage true = 真伤（连附魔/药水/吸收都不认），false = 法术伤害（只穿甲）
     */
    private static void strike(Player player, LivingEntity victim, float amount, boolean trueDamage) {
        if (amount <= 0.0F || victim.isDeadOrDying()) return;

        victim.invulnerableTime = 0;
        victim.hurt(trueDamage ? ModDamageTypes.trueDamage(player) : ModDamageTypes.spell(player), amount);
    }

    // ==================== 挥手挡开 ====================

    /** 挥手挡开的冷却（tick）—— 免得被围殴的时候手臂抽筋。 */
    private static final int GUARD_SWING_COOLDOWN = 8;
    private static final Map<UUID, Long> LAST_GUARD_SWING = new ConcurrentHashMap<>();

    /**
     * **挥手把这一下挡开** —— 魔虚罗挨打时那个动作（主人 2026-09-14 要求玩家也加上）。
     *
     * 两种情况会挥：
     * - **举剑格挡**（尤其按住潜行的时候）：把伤害挡下来；
     * - 这条伤害**已经适应到 100%**（彻底免疫）：顺手拨开。
     *
     * 用的是原版的 {@code LivingEntity#swing} —— 服务端调用会自动广播给周围玩家，
     * 所以别人也看得见你在挥。加了 0.4 秒冷却，连挨几下不会变成抽搐。
     */
    private static void swingGuard(Player player) {
        long now = player.level().getGameTime();
        Long last = LAST_GUARD_SWING.get(player.getUUID());

        if (last != null && now - last < GUARD_SWING_COOLDOWN) return;

        LAST_GUARD_SWING.put(player.getUUID(), now);

        player.swing(InteractionHand.MAIN_HAND, true);
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.SHIELD_BLOCK, SoundSource.PLAYERS, 0.7F, 1.15F);
    }

    // ==================== 挖掘：万能工具 + 秒破 ====================

    /**
     * **挖掘适应满 100% → 退魔之剑秒破除基岩之外的任何方块。**
     *
     * 🔴 为什么不用 `Item#getDestroySpeed` 判断：那个方法**拿不到玩家**，读不了适应进度。
     * `PlayerEvent.BreakSpeed` 是"挖这一下"的钩子，有玩家、客户端服务端都会跑，正好用它顶速度。
     *
     * ⚠️ **客户端也必须走这条路** —— 破坏计时器跑在客户端，两边不一致的话
     * 服务端觉得一帧能挖掉、客户端还在慢慢挖，手感就是"卡着不动"。
     * 客户端的进度由 {@link MiningAdaptation} 缓存（服务端每秒同步过来）。
     *
     * ⚠️ 基岩不用特判：它硬度是 `-1`，原版 `getDestroyProgress` 直接返回 0，再快也挖不动。
     */
    @SubscribeEvent
    public static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        Player player = event.getEntity();

        if (!player.getMainHandItem().is(ModItems.MAHORAGA_BLADE.get())) return;
        if (!MiningAdaptation.isInstant(player)) return;

        event.setNewSpeed(Math.max(event.getNewSpeed(), MiningAdaptation.INSTANT_SPEED));
    }

    /** 攻击倍率（参数是**百分比**，比如 300 表示 300%）。 */
    public static float damageMultiplier(int pct) {
        if (pct < 25) return 1.0F;
        if (pct < 50) return 1.6F;
        if (pct < 100) return 2.5F;

        int tier = Math.min(10, pct / 100);
        return Math.min(MAX_DAMAGE_MULTIPLIER, 4.0F + 0.75F * (tier - 1));
    }

    // ==================== 事件：挖矿 / 跳跃 ====================

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        // BlockEvent.BreakEvent#getPlayer() 的静态类型就是 Player，不能再写 instanceof 模式
        Player player = event.getPlayer();
        if (player == null) return;
        if (player.level().isClientSide) return;
        if (!WheelHandler.hasWheel(player)) return;

        gainDaily(player, data(player), AdaptationKeys.DAILY_MINING, GAIN_MINING,
                player.level().getGameTime());
    }

    // ==================== 日常：投掷（→ 投掷物自动追踪） ====================

    /** 投出去的投射物身上打过的标记 —— 防止同一发被重复计数（区块重载会二次触发加入事件）。 */
    private static final String THROW_COUNTED = "mahoraga_throw_counted";

    /** 「投掷物」的注册名（**不含箭** —— 那是"射"，不是"投"）。 */
    private static final Set<String> THROWN_ITEMS = Set.of(
            "snowball", "egg", "ender_pearl", "potion", "experience_bottle", "trident");

    /**
     * **玩家投出东西 → 日常「投掷」适应 +一点**（主人 2026-09-14 定）。
     *
     * 效果在 {@link com.mahoraga.skill.ProjectileHoming}（照抄 Re:Zero 的加护「射中」）：
     * 投出去的东西会自己拐弯追人。
     *
     * ⚠️ **只认"投"，不认"射"** —— 射箭不算（别把弓箭混进来）。
     */
    @SubscribeEvent
    public static void onProjectileJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof Projectile projectile)) return;
        if (!isThrown(projectile)) return;
        if (!(projectile.getOwner() instanceof Player player)) return;
        if (!WheelHandler.hasWheel(player)) return;

        // 同一发只算一次
        if (projectile.getPersistentData().getBoolean(THROW_COUNTED)) return;

        projectile.getPersistentData().putBoolean(THROW_COUNTED, true);

        gainDaily(player, data(player), AdaptationKeys.DAILY_THROW, GAIN_THROW,
                player.level().getGameTime());
    }

    /** 这一发算不算"**投**出去的东西"。 */
    private static boolean isThrown(Projectile projectile) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(projectile.getType());

        return id != null && THROWN_ITEMS.contains(id.getPath());
    }

    @SubscribeEvent
    public static void onJump(LivingEvent.LivingJumpEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level().isClientSide) return;
        if (!WheelHandler.hasWheel(player)) return;

        gainDaily(player, data(player), AdaptationKeys.DAILY_JUMP, GAIN_JUMP,
                player.level().getGameTime());
    }

    // ==================== 事件：换维度 → 清空 + 入场事件 ====================

    /**
     * 换维度：先清空进度（法轮保留），然后看落到哪个维度 ——
     * **进下界喂一次火焰适应、进末地喂一次空间适应**（主人 2026-09-14 定的入场事件）。
     */
    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        Player player = event.getEntity();

        clearProgress(player);

        if (player.level().isClientSide) return;
        if (!WheelHandler.hasWheel(player)) return;

        if (event.getTo().equals(Level.NETHER)) {
            enterRealm(player, "chat.mahoraga.realm.nether", AdaptationKeys.FIRE, Track.DEFENSE);
        } else if (event.getTo().equals(Level.END)) {
            enterRealm(player, "chat.mahoraga.realm.end", AdaptationKeys.SPACE, Track.ATTACK);
        }
    }

    /** 维度入场事件：说一句话，然后给那条适应"种个种子"，之后它自己随时间涨。 */
    private static void enterRealm(Player player, String messageKey, String key, Track track) {
        player.displayClientMessage(Component.translatable(messageKey)
                .withStyle(ChatFormatting.LIGHT_PURPLE), false);

        AdaptationData data = data(player);

        seedCombat(player, data, track, key);
        apply(player, data, track, key, data.get(track, key), GAIN_REALM);
        refreshHold(data, track, key, player.level().getGameTime());
    }

    @SubscribeEvent
    public static void onSleep(CanPlayerSleepEvent event) {
        clearProgress(event.getEntity());
    }

    /** 死亡重生 / 换维度时把状态带过去（法轮和进度都不该丢）。 */
    @SubscribeEvent
    public static void onClone(PlayerEvent.Clone event) {
        if (event.getOriginal().getPersistentData().contains("mahoraga_adapt", 10)) {
            event.getEntity().getPersistentData().put("mahoraga_adapt",
                    event.getOriginal().getPersistentData().getCompound("mahoraga_adapt").copy());
        }

        // HUD 的两个键也要带过去 —— 不然重生后转动序号归零，
        // 客户端（它还记着旧序号）会误以为"又完成了一次适应"，白白把法轮转一圈。
        var from = event.getOriginal().getPersistentData();

        event.getEntity().getPersistentData().putInt(TAG_HUD_SPIN, from.getInt(TAG_HUD_SPIN));
        event.getEntity().getPersistentData().putString(TAG_HUD_SIG, from.getString(TAG_HUD_SIG));
    }

    /** 命令用：静默清空全部进度，返回"原来有没有东西可清"。 */
    public static boolean resetAll(Player player) {
        AdaptationData data = data(player);

        // 解析数据（标签 / 抽到的破解）也算"有东西可清"
        boolean had = !AnalyzeData.of(player).ids().isEmpty();

        for (Track track : Track.values()) {
            if (!data.keys(track).isEmpty()) had = true;
        }

        data.clear();
        AnalyzeData.of(player).clear();
        applyStats(player, data);
        return had;
    }

    /**
     * 命令用：直接设定某一条适应的进度（0~1000%）。
     *
     * 调试 / 想直接体验某个能力时用，免得为了看一眼剑气先去屠一堆骷髅。
     */
    public static void setProgress(Player player, Track track, String key, float value) {
        AdaptationData data = data(player);
        float clamped = Math.max(0.0F, Math.min(MAX, value));

        data.set(track, key, clamped);
        data.setAnnounced(track, key, 0);
        data.setNoticeSent(track, key, false);

        if (track.isDaily()) {
            // 日常轨：不刷新"还在做"的时间戳的话，下一 tick 就会被当成"停手了"直接衰减
            data.setActiveAt(track, key, player.level().getGameTime());
        } else {
            data.setMode(track, key, clamped >= FLOOR ? AdaptationData.HOLD : AdaptationData.GROW);
            data.setHoldUntil(track, key,
                    player.level().getGameTime() + (clamped >= FLOOR ? HOLD_SECONDS * 20L : 0L));
        }

        applyDailyEffects(player, data);
        applyStats(player, data);
    }

    private static void clearProgress(Player player) {
        if (player.level().isClientSide) return;
        if (!WheelHandler.hasWheel(player)) return;

        AdaptationData data = data(player);
        boolean had = false;

        for (Track track : Track.values()) {
            if (!data.keys(track).isEmpty()) had = true;
        }
        if (!had) return;

        data.clear();
        applyStats(player, data);
        player.displayClientMessage(Component.translatable("chat.mahoraga.adapt.cleared")
                .withStyle(ChatFormatting.GRAY), true);
    }

    // ==================== 每秒：日常动作 / 衰减 / 效果 / 属性 ====================

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        
        Player player = event.getEntity();
        if (player.level().isClientSide) return;

        if (!WheelHandler.hasWheel(player)) return;

        AdaptationData data = data(player);

        // 0) 每 tick 必做 —— 慢一秒就会出问题的那批
        SignatureDefense.tick(player, data);            // 火焰计时 / 饱食度 / 护甲…
        SignatureAttack.tickFlight(player);             // 飞行生物满 100% → 一直有飞行；掉下 100% 立刻收走
        LiquidWalk.tick(player, data);                  // 站液体表面：托一把 + 更新缓存 + 同步客户端
        tickFastHeal(player);                           // 跨档的快速回血（每 tick 回一点，3 秒回完）
        VoidDomain.tick(player);                        // 无量空处：领域里的一切都被钉住
        SukunaCleave.tick(player);                      // 「捌」：还没打完的那套连斩
        FireArrow.tick(player);                         // 「开」：起手蓄力 + 空中飞的火球

        if (player.tickCount % 20 != 0) return;

        long now = player.level().getGameTime();

        // 1) 日常动作 —— 每做一次 / 每持续一秒就给一份进度（顺带刷新"还在做"的时间戳）
        if (player.isSprinting()) gainDaily(player, data, AdaptationKeys.DAILY_SPRINT, GAIN_SPRINT, now);
        if (player.isInWater()) gainDaily(player, data, AdaptationKeys.DAILY_WATER, GAIN_WATER, now);
        if (player.isInLava()) gainDaily(player, data, AdaptationKeys.DAILY_LAVA, GAIN_LAVA, now);
        if (isInDarkness(player)) gainDaily(player, data, AdaptationKeys.DAILY_DARK, GAIN_DARK, now);
        if (isInCave(player)) gainDaily(player, data, AdaptationKeys.DAILY_CAVE, GAIN_CAVE, now);

        // 1.5) **雷雨天站在露天** → 喂「雷击」适应（防御轨，走战斗轨那套种子+加速）
        if (isInThunderstorm(player)) {
            feedDefense(player, data, AdaptationKeys.LIGHTNING, GAIN_LIGHTNING, now);
        }

        // 1.6) **被群殴** —— 身边同时围着 5 只以上敌人时喂「群殴适应」
        //      （练满 100% 解锁「伏魔御厨子」，见 skill/MalevolentShrine）。
        //      围得越多涨得越快：5 只 = 1 倍，10 只及以上封顶 2 倍。
        int swarm = MalevolentShrine.countEnemies(player);

        if (swarm >= MalevolentShrine.SWARM_COUNT) {
            float multiplier = Math.min(2.0F, swarm / (float) MalevolentShrine.SWARM_COUNT);
            feedDefense(player, data, AdaptationKeys.SWARM, GAIN_SWARM * multiplier, now);
        }

        // 2) 负面药水效果 → 战斗轨（静默，满了直接免疫这一类）
        for (MobEffectInstance instance : player.getActiveEffects()) {
            if (instance.getEffect().value().isBeneficial()) continue;

            gainEffect(player, AdaptationKeys.effectKey(instance.getEffect().value()), now);
        }

        // 3) 推进状态机 —— 两条轨道各走各的，别混在一起
        for (Track track : Track.values()) {
            if (track.isDaily()) continue;                 // 日常在下面单独走

            for (String key : data.keys(track)) {
                advanceCombat(player, data, track, key, now);
            }
        }
        for (String key : data.keys(Track.DAILY)) {
            advanceDaily(player, data, key, now);
        }

        // 4) 效果 + 属性
        applyDailyEffects(player, data);
        applyStats(player, data);

        // 5) 多段跳额度 —— 变化时才同步（适应进度在服务端，客户端看不见）
        syncJumpTiers(player, data);

        // 5.5) 挖掘适应 —— 客户端靠它判断"能不能秒破"，值没变不发
        if (player instanceof ServerPlayer server) MiningAdaptation.sync(server);

        // 6) 剑气解锁提示 —— 三条解锁路径统一在这里检测
        syncSlashUnlock(player);

        // 6.5) **左上角 HUD**（法轮图标 + 正在适应的项目列表 + 转动序号）
        //      内容没变、序号也没变的话 syncHud 自己会吞掉，不产生无用流量
        if (player instanceof ServerPlayer hudTarget) syncHud(hudTarget, data);

        // 7) 退魔之剑的「特殊能力」解锁提示（右键那套：瞬移 / 空间斩…）
        BladeAbilities.announceUnlocks(player);

        // 8) **剑刃皮肤（火/冰）+ 附魔维持**
        //    🔴 原来挂在 `BladeHandler` 自己的内部事件类上，但 2026-09-14 查明那段**从来没跑过**
        //    （存档里 def.damage:minecraft:lava 已 414%，剑上却连 CustomModelData 都不存在）。
        //    搬到这里 —— 同一个 tick 里上面那堆适应逻辑一直在正常工作，是可靠的。
        BladeHandler.updateSkin(player);
    }

    /**
     * 玩家死亡 → **退魔之剑替你挡一次**（原来也在 `BladeHandler` 的内部事件类上，一并搬来）。
     */
    @SubscribeEvent
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level().isClientSide) return;
        if (!WheelHandler.hasWheel(player)) return;

        BladeHandler.trySaveFromDeath(player, event);
    }

    // ==================== 已删除：法轮寄生（2026-09-18） ====================
    //
    // 主人原话：「那个下蹲右键给予生物法轮的效果删除了，不需要了，**我们有魔虚罗了**」
    //
    // 所以下面这一整套入口全部撤掉：
    //   · onSymbiontHostDeath      宿主死了把寄生者踢出来
    //   · onSymbiosisInteract      潜行 + 右键生物 → 寄生
    //   · onSymbiosisInteractSpecific  上面那个的"带命中点"兄弟事件
    //   · onSymbiosisRelease       潜行 + 右键空处 → 出来
    //
    // `WheelSymbiosis` 类本身先留着（里面的粒子表现还能参考），但已无人调用。


    // ==================== 「永恒流血」的三个事件 ====================

    /**
     * 每个生物每 tick 都走一遍。
     * 里面**第一句就是查"有没有伤口"**，没伤口的生物一次 NBT 查询就返回 —— 开销可以忽略。
     */
    @SubscribeEvent
    public static void onLivingTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof LivingEntity living)) return;
        EternalBleeding.tick(living);
        SignatureAttack.tickServants(living);   // 僵尸援军 30 秒到点自己消散
    }

    /** 伤口还在，就不许摘掉流血效果 —— 牛奶、`/effect clear` 一律无效，这才叫"永恒"。 */
    @SubscribeEvent
    public static void onBleedRemove(MobEffectEvent.Remove event) {
        if (event.getEffect() != ModEffects.BLEEDING.get()) return;
        if (!EternalBleeding.hasWound(event.getEntity())) return;

        event.setCanceled(true);
    }

    /** 目标死了 → 伤口跟着消失（原版也是这么收尾的）。 */
    @SubscribeEvent
    public static void onBleedTargetDeath(LivingDeathEvent event) {
        EternalBleeding.clear(event.getEntity());
    }

    /**
     * 「持续奔跑」适应每多 100%，空中就允许再跳一次：
     * 200% → 二段跳，300% → 三段，… 1000% → 十段。
     *
     * 客户端读不到适应进度，所以得把这个数字主动告诉他（{@link com.mahoraga.network.JumpTierPacket}）。
     * **数值没变就不发** —— 平时是安静的。
     */
    private static void syncJumpTiers(Player player, AdaptationData data) {
        if (!(player instanceof ServerPlayer server)) return;

        int tiers = AirJump.tiersFor(data.get(Track.DAILY, AdaptationKeys.DAILY_SPRINT));
        UUID id = player.getUUID();
        Integer last = LAST_JUMP_TIERS.get(id);

        if (last != null && last == tiers) return;

        LAST_JUMP_TIERS.put(id, tiers);
        MahoragaNetwork.sendJumpTier(server, tiers);
    }

    /** 照度 ≤ 4 就算黑：洞里、夜里露天都算。 */
    private static boolean isInDarkness(Player player) {
        return player.level().getMaxLocalRawBrightness(player.blockPosition()) <= DARK_LIGHT_LEVEL;
    }

    /**
     * 是不是**在洞穴里**。
     *
     * 判据只用一句话：**头顶看不见天**（{@code canSeeSky} 为 false）就说明上面有方块盖着。
     * 原版这个方法自己带缓存，每秒钟问一次几乎不花钱，比一层层往上扫方块快得多。
     *
     * 另外还要求**在地面线以下**（{@code y <= getSeaLevel()}）—— 不这么卡的话，
     * "站在树荫底下""待在自己家里"都会被算成洞穴，这能力就太廉价了。
     */
    private static boolean isInCave(Player player) {
        var pos = player.blockPosition();

        if (player.level().canSeeSky(pos)) return false;          // 看得见天 → 不是洞穴

        return pos.getY() <= player.level().getSeaLevel();        // 还得在地下
    }

    /**
     * 是不是**正被雷雨浇着**：正在打雷 + 头顶露天。
     *
     * 这是「雷击」适应唯一一条能主动练的路子（另一条是"真被闪电劈中"，太看运气）。
     * 站在屋顶下、洞里、水里都不算 —— 得有"头顶是天"那个暴露感。
     */
    private static boolean isInThunderstorm(Player player) {
        return player.level().isThundering()
                && player.level().canSeeSky(player.blockPosition());
    }

    /**
     * 给**防御轨**喂一次接触（种种子 → 加权 → 续保持期）——
     * 和 {@code enterRealm} 那三行是同一套，只是收成一个方法好复用。
     *
     * 注意它**只加接触量**，进度照样由 {@code advanceCombat} 随时间自己涨 ——
     * 所以"雷雨天站一会"和"真被劈一下"最终都能练满，只是快慢不同。
     */
    private static void feedDefense(Player player, AdaptationData data, String key, float amount, long now) {
        seedCombat(player, data, Track.DEFENSE, key);
        apply(player, data, Track.DEFENSE, key, data.get(Track.DEFENSE, key), amount);
        refreshHold(data, Track.DEFENSE, key, now);
    }

    /** 窒息适应满 100% → 解锁物质穿透（身体占住的方块直接抹掉，于是能穿墙）。 */
    /**
     * 有没有「物质穿透」—— 把身体占住的方块直接抹掉，于是能穿墙（狮子之心那套）。
     *
     * **三条路任一条满 100% 就给**：
     * - {@link AdaptationKeys#SUFFOCATION}（被方块闷住，防御轨）
     * - {@link AdaptationKeys#IN_WALL}（**卡墙**，防御轨）—— 主人 2026-09-14 补的，
     *   之前只认窒息，卡在方块里掉血反而不给穿透，说不通
     * - {@link AdaptationKeys#DAILY_CAVE}（**天天待洞里**，日常轨）
     *
     * 每 tick 现查 —— 日常轨掉下去就会失效，和"黑暗适应满了才给夜视"是同一个规矩。
     */
    public static boolean hasSuffocationPhase(Player player) {
        if (!WheelHandler.hasWheel(player)) return false;

        AdaptationData data = data(player);

        return data.get(Track.DEFENSE, AdaptationKeys.SUFFOCATION) >= COMPLETE
                || data.get(Track.DEFENSE, AdaptationKeys.IN_WALL) >= COMPLETE
                || data.get(Track.DAILY, AdaptationKeys.DAILY_CAVE) >= COMPLETE;
    }

    /** 这一种负面药水效果是否已经适应满（满了就免疫）。 */
    public static boolean hasEffectImmunity(Player player, Holder<MobEffect> effect) {
        if (!WheelHandler.hasWheel(player)) return false;

        return data(player).get(Track.DEFENSE, AdaptationKeys.effectKey(effect.value())) >= COMPLETE;
    }

    private static void applyDailyEffects(Player player, AdaptationData data) {
        float mining = data.get(Track.DAILY, AdaptationKeys.DAILY_MINING);
        float sprint = data.get(Track.DAILY, AdaptationKeys.DAILY_SPRINT);
        float jump = data.get(Track.DAILY, AdaptationKeys.DAILY_JUMP);

        applyByProgress(player, mining, MobEffects.DIG_SPEED);
        applyByProgress(player, sprint, MobEffects.MOVEMENT_SPEED);
        applyByProgress(player, jump, MobEffects.JUMP);

        // 跑步 200% → 自动跨过 1.5 格的方块（玩家基础台阶高度 0.6 + 0.9）
        applyStepHeight(player, sprint >= SPRINT_STEP);

        // 跑步 300% → 饱食度锁定满格
        if (sprint >= SPRINT_FOOD) {
            lockFood(player);
        }

        // 跳跃 100% → **自身重力减少 15%**（顺带把跳跃提升的效果也叠上，飞得更高更飘）
        applyGravity(player, jump >= JUMP_GRAVITY);

        // 进食 100% → 饱食度锁定满格（和"跑步 300%"用的是同一套做法）
        if (data.get(Track.DAILY, AdaptationKeys.DAILY_EAT) >= EAT_FOOD_LOCK) {
            lockFood(player);
        }

        if (data.get(Track.DAILY, AdaptationKeys.DAILY_WATER) >= COMPLETE) {
            player.addEffect(new MobEffectInstance(MobEffects.CONDUIT_POWER, 60, 0, true, false, true));
            player.addEffect(new MobEffectInstance(MobEffects.DOLPHINS_GRACE, 60, 0, true, false, true));
        }
        if (data.get(Track.DAILY, AdaptationKeys.DAILY_LAVA) >= COMPLETE) {
            player.setRemainingFireTicks(0);
        }

        // 黑暗：满 100% → 夜视；到 200% → 开始"透视"周围的生物
        // （轮廓由客户端画，见 skill/DarkSight + client/DarkSightRenderer）
        float dark = data.get(Track.DAILY, AdaptationKeys.DAILY_DARK);

        if (dark >= COMPLETE) {
            player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 400, 0, true, false, true));
        }
        if (player instanceof ServerPlayer server) {
            DarkSight.update(server, dark);

            // 进食适应也要同步给客户端 —— 进食时长两边各算一遍，不同步就会"服务端吃完了客户端还在嚼"
            syncEatSpeed(server, percent(data.get(Track.DAILY, AdaptationKeys.DAILY_EAT)));
        }
    }

    /** 跑步奖励：把台阶高度加成挂上 / 摘掉。 */
    private static void applyStepHeight(Player player, boolean active) {
        AttributeInstance instance = player.getAttribute(Attributes.STEP_HEIGHT);
        if (instance == null) return;

        AttributeModifier existing = instance.getModifier(STEP_MODIFIER);

        if (!active) {
            if (existing != null) instance.removeModifier(STEP_MODIFIER);
            return;
        }
        if (existing != null) return;

        instance.addTransientModifier(new AttributeModifier(STEP_MODIFIER,
                STEP_HEIGHT_BONUS, AttributeModifier.Operation.ADD_VALUE));
    }

    /**
     * 跳跃奖励：把"**重力减少 15%**"挂上 / 摘掉。
     *
     * ⚠️ 用 `NeoForgeMod.ENTITY_GRAVITY`（Forge 加的属性），**不是** `Attributes` 里的 ——
     * 原版 1.20.1 根本没有重力属性，别去 `Attributes` 里找。
     */
    private static void applyGravity(Player player, boolean active) {
        AttributeInstance instance = player.getAttribute(Attributes.GRAVITY);
        if (instance == null) return;

        AttributeModifier existing = instance.getModifier(GRAVITY_MODIFIER);

        if (!active) {
            if (existing != null) instance.removeModifier(GRAVITY_MODIFIER);
            return;
        }
        if (existing != null) return;

        instance.addTransientModifier(new AttributeModifier(GRAVITY_MODIFIER,
                GRAVITY_CUT, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }

    // ==================== 进食适应 ====================

    /** 上次同步给客户端的进食进度，用来"值没变就不发"。 */
    private static final Map<UUID, Integer> LAST_EAT_SYNC = new ConcurrentHashMap<>();

    /**
     * **吃完了** → 给进食适应进度；满 200% 再送一个随机正面效果（不含漂浮）。
     *
     * 用 `LivingEntityUseItemEvent.Finish` 而不是 `Stop`：**Finish 才是"真吃下去了"**
     * （中途松手走 Stop，不能算一次进食）。这个事件只在服务端触发。
     */
    @SubscribeEvent
    public static void onEatFinish(LivingEntityUseItemEvent.Finish event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level().isClientSide) return;
        if (!WheelHandler.hasWheel(player)) return;
        if (event.getItem().getItem().getFoodProperties(event.getItem(), player) == null) return;

        AdaptationData data = data(player);
        long now = player.level().getGameTime();

        gainDaily(player, data, AdaptationKeys.DAILY_EAT, GAIN_EAT, now);

        float eat = data.get(Track.DAILY, AdaptationKeys.DAILY_EAT);
        if (eat < EAT_BUFF) return;

        int pct = percent(eat);
        Holder<MobEffect> buff = EatAdaptation.rollBuff();

        // 满 200% → I 级 10 秒；400% → II 级 20 秒；600%+ → III 级 30 秒
        player.addEffect(new MobEffectInstance(buff,
                EatAdaptation.buffTicks(pct), EatAdaptation.buffAmplifier(pct),
                true, false, true));

        player.displayClientMessage(Component.translatable("chat.mahoraga.eat.buff",
                        Component.translatable(buff.value().getDescriptionId()))
                .withStyle(ChatFormatting.GOLD), true);
    }

    /**
     * **开始吃了** → 按进食适应把时长改短（"越吃越快"）。
     *
     * 🔴 **这里绝对不能按 `isClientSide` 提前 return！**
     * 进食时长**客户端和服务端各算一遍**：光改服务端的话，服务端 11 tick 就吃完了，
     * 客户端还在那儿嚼 32 tick —— 动画和实际对不上。
     * 客户端的进度靠 {@link com.mahoraga.network.EatSpeedPacket} 同步（见 {@code syncEatSpeed}）。
     */
    @SubscribeEvent
    public static void onEatStart(LivingEntityUseItemEvent.Start event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (event.getItem().getItem().getFoodProperties(event.getItem(), player) == null) return;

        int pct;

        if (player.level().isClientSide) {
            pct = EatAdaptation.clientPercent();
        } else {
            pct = WheelHandler.hasWheel(player)
                    ? percent(data(player).get(Track.DAILY, AdaptationKeys.DAILY_EAT))
                    : 0;
        }

        int ticks = EatAdaptation.eatTicks(pct);

        if (ticks < event.getDuration()) {
            event.setDuration(ticks);
        }
    }

    /** 进食速度只在**变化**时同步一次，平时安静（跟挖掘适应一个规矩）。 */
    private static void syncEatSpeed(ServerPlayer player, int percent) {
        Integer last = LAST_EAT_SYNC.get(player.getUUID());

        if (last != null && last.intValue() == percent) return;

        LAST_EAT_SYNC.put(player.getUUID(), percent);
        MahoragaNetwork.sendEatSpeed(player, percent);
    }

    // ==================== 附魔适应 ====================

    /** 上次"在附魔台操作"的 tick，用来判断接下来那一下掉级是不是附魔扣的。 */
    private static final Map<UUID, Long> LAST_TABLE = new ConcurrentHashMap<>();
    /** 附魔进度的墙钟节流。 */
    private static final Map<UUID, Long> LAST_ENCHANT_GAIN = new ConcurrentHashMap<>();

    /**
     * **在附魔台折腾物品** → 给附魔适应进度 + 把附魔台给出的等级抬高。
     *
     * ⚠️ `EnchantmentLevelSetEvent` **不是** `PlayerEvent`（没有玩家字段），
     * 所以只能用它给的方块坐标去**找最近的玩家** —— 附魔台本来就得站在旁边开，够用。
     *
     * ⚠️ 换一次物品会**刷 3 行**（三档附魔各发一次），所以进度加了 40 tick 节流，
     * 但**等级加成每次都给**（不然只会抬一行）。
     */
    @SubscribeEvent
    public static void onEnchantLevelSet(EnchantmentLevelSetEvent event) {
        if (event.getLevel().isClientSide) return;
        if (event.getEnchantLevel() <= 0) return;                  // 空格子/没附魔的档不管

        var pos = event.getPos();

        Player player = event.getLevel().getNearestPlayer(
                pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, 8.0D, false);

        if (player == null) return;
        if (!WheelHandler.hasWheel(player)) return;

        long now = event.getLevel().getGameTime();

        // 记一笔"刚在附魔台动过" —— 紧接着的掉级就是这个附魔扣的
        LAST_TABLE.put(player.getUUID(), now);

        AdaptationData data = data(player);
        float enchant = data.get(Track.DAILY, AdaptationKeys.DAILY_ENCHANT);

        // 进度：40 tick 内只记一次（换物品刷 3 行不算 3 次）
        Long lastGain = LAST_ENCHANT_GAIN.get(player.getUUID());

        if (lastGain == null || now - lastGain >= ENCHANT_GAIN_COOLDOWN) {
            LAST_ENCHANT_GAIN.put(player.getUUID(), now);
            gainDaily(player, data, AdaptationKeys.DAILY_ENCHANT, GAIN_ENCHANT, now);
            enchant = data.get(Track.DAILY, AdaptationKeys.DAILY_ENCHANT);
        }

        // 等级加成：每 100% +1，最多 +10
        int bonus = Math.min(10, percent(enchant) / 100);

        if (bonus > 0) {
            event.setEnchantLevel(event.getOriginalLevel() + bonus);
        }
    }

    /**
     * **满 100% → 附魔不再消耗经验等级**。
     *
     * 原版里附魔的"花费"和"等级"是同一个数（`EnchantmentMenu#clickMenuButton` 直接
     * `giveExperienceLevels(-cost)`），等级抬高了花费自然也涨 —— 所以这条把**扣掉的经验兜回来**，
     * 让"等级加成"变成纯赚。
     *
     * 判据：掉级 + 60 tick 内刚在附魔台动过。**不这么做的话连修装备/铁砧掉级也会被兜**。
     */
    @SubscribeEvent
    public static void onEnchantXpCost(PlayerXpEvent.LevelChange event) {
        Player player = event.getEntity();
        if (player.level().isClientSide) return;
        if (event.getLevels() >= 0) return;                        // 只管掉级

        if (!WheelHandler.hasWheel(player)) return;
        if (data(player).get(Track.DAILY, AdaptationKeys.DAILY_ENCHANT) < ENCHANT_FREE_XP) return;

        Long tableAt = LAST_TABLE.get(player.getUUID());

        if (tableAt == null) return;
        if (player.level().getGameTime() - tableAt > ENCHANT_TABLE_WINDOW) return;

        MahoragaMod.LOGGER.info("[mahoraga] enchant refunded {} levels ({})",
                -event.getLevels(), player.getName().getString());

        event.setLevels(0);                                        // 一分经验都不扣
    }

    // ==================== 钓鱼适应 ====================

    /**
     * **收竿钓上东西** → 给钓鱼适应进度，并按进度加料。
     *
     * 三档效果：
     * - **≥100%** → **鱼竿不消耗耐久**（把这一杆的损耗抹成 0）
     * - **≥200% / 400% / 600%** → 鱼获 **×2 / ×3 / ×4**
     * - **≥300%** → 每次**额外**多给一件宝藏
     */
    @SubscribeEvent
    public static void onItemFished(ItemFishedEvent event) {
        Player player = event.getEntity();
        if (player.level().isClientSide) return;
        if (!WheelHandler.hasWheel(player)) return;

        AdaptationData data = data(player);
        long now = player.level().getGameTime();

        gainDaily(player, data, AdaptationKeys.DAILY_FISHING, GAIN_FISHING, now);

        float fish = data.get(Track.DAILY, AdaptationKeys.DAILY_FISHING);

        // 100% → 鱼竿不掉耐久
        if (fish >= FISHING_NO_DAMAGE) {
            event.damageRodBy(-event.getRodDamage());
        }

        // 每 200% 翻一倍，最多 4 倍：把原掉落**整份复制**（复制的是副本，不会共享 NBT）
        int mult = (int) Math.min(4.0F, 1.0F + fish / FISHING_PER_TIER);

        if (mult > 1) {
            List<ItemStack> base = new ArrayList<>(event.getDrops());

            for (int i = 1; i < mult; i++) {
                for (ItemStack stack : base) {
                    event.getDrops().add(stack.copy());
                }
            }
        }

        // 300% → 额外一件宝藏
        if (fish >= FISHING_TREASURE) {
            event.getDrops().add(new ItemStack(
                    FISHING_TREASURES[player.getRandom().nextInt(FISHING_TREASURES.length)]));
        }
    }

    /** 跑步奖励：饱食度 / 饱和度锁满（和尸壳防御侧同一套做法）。 */
    private static void lockFood(Player player) {
        var food = player.getFoodData();
        food.setFoodLevel(20);
        food.setSaturation(20.0F);
    }

    /**
     * 日常效果的等级 —— **数值涨到哪，等级就跟着涨到哪**（主人 2026-09-14）：
     * 25/50/100/200/…/800 依次对应 I~X 级，一级一档；1000% 那一段只加缓冲不再加等级
     * （X 级已经是原版能正常显示的最高等级，再加就会把翻译键名原样打出来）。
     */
    private static void applyByProgress(Player player, float progress, Holder<MobEffect> effect) {
        int pct = percent(progress);
        int level = 0;

        for (int threshold : EFFECT_THRESHOLDS) {
            if (pct >= threshold) level++;
        }
        if (level <= 0) return;

        int amplifier = Math.min(MAX_EFFECT_AMPLIFIER, level - 1);
        player.addEffect(new MobEffectInstance(effect, 60, amplifier, true, false, true));
    }

    /**
     * 「站液体表面」的判据全在 {@link LiquidWalk} 里 ——
     * 那边做了缓存（性能）、客户端同步（不然走不动）、以及"眼睛在水里就不算能站 + 自动上浮"
     * 这一对（不然在水里触发了能力会卡在水下浮不起来）。
     */

    // ==================== 属性成长 ====================

    /**
     * 刚拿到法轮的那一刻叫一次：把**底子属性**（+5 颗心 / +3 护甲）立刻算上，并回满血。
     *
     * 不这么做的话，血上限是涨了、但当前血还停在旧值，看着像没生效。
     */
    public static void onWheelGranted(Player player) {
        applyStats(player, data(player));
        player.setHealth(player.getMaxHealth());
    }

    /**
     * **摘掉法轮带来的全部"底子"** —— {@code /mahoraga wheel reset} 时调。
     *
     * <p>🔴 为什么不能指望每秒的 {@link #applyStats} 自己收敛：
     * {@code onPlayerTick} 开头第一句就是 `if (!WheelHandler.hasWheel(player)) return;` ——
     * **没法轮的时候这一整套逻辑压根不会跑**，属性修饰符会永远留在身上：
     * 底子的 +10 血 / +3 护甲，外加每次防御适应完成叠上去的成长，还有技能挂的那些
     * （砍生命上限 / 破甲 / 猪灵蛮兵护甲翻倍 / 跑步跨台阶 / 跳跃减重力）。
     *
     * <p>主人的体感就是：「摘了法轮，人却还是魔虚罗」—— 标记清掉、轮子删掉、HUD 收走，
     * 但血条还是长的、护甲还是厚的、剑还在手上。
     */
    public static void onWheelReset(Player player) {
        // ① 底子 + 适应成长（amount 传 0 → setModifier 内部会把修饰符摘掉）
        setModifier(player, Attributes.MAX_HEALTH, HEALTH_MODIFIER, "mahoraga_adapt_health", 0.0D);
        setModifier(player, Attributes.ARMOR, ARMOR_MODIFIER, "mahoraga_adapt_armor", 0.0D);
        setModifier(player, Attributes.KNOCKBACK_RESISTANCE, EXPLOSION_MODIFIER,
                "mahoraga_blast_resist", 0.0D);

        // ② 「独特破解」和招牌能力挂上去的（UUID 都是固定的，见 Skills）
        strip(player, Attributes.MAX_HEALTH, CrackEffects.ID_MAX_HEALTH);
        strip(player, Attributes.ARMOR, CrackEffects.ID_ARMOR);
        strip(player, Attributes.ATTACK_DAMAGE, CrackEffects.ID_ATTACK);
        strip(player, Attributes.ARMOR, SignatureDefense.BRUTE_ARMOR);

        // ③ 日常奖励的（跑步跨台阶 / 跳跃减重力）
        applyStepHeight(player, false);
        applyGravity(player, false);

        // ④ 血上限降回去了，当前血可能超上限 → 压回来，不然会顶着一截"幽灵血"
        if (player.getHealth() > player.getMaxHealth()) {
            player.setHealth(player.getMaxHealth());
        }
    }

    /** 按固定 UUID 摘掉一个修饰符（没有就什么都不做）。 */
    private static void strip(Player player, Holder<Attribute> attribute, ResourceLocation id) {
        AttributeInstance instance = player.getAttribute(attribute);

        if (instance != null && instance.getModifier(id) != null) {
            instance.removeModifier(id);
        }
    }

    private static void applyStats(Player player, AdaptationData data) {
        int done = data.completedCount(Track.DEFENSE);

        // 底子（拿到法轮就有的）+ 每次防御适应完成的成长
        setModifier(player, Attributes.MAX_HEALTH, HEALTH_MODIFIER, "mahoraga_adapt_health",
                BASE_HEALTH + HEALTH_PER_ADAPTATION * done);
        setModifier(player, Attributes.ARMOR, ARMOR_MODIFIER, "mahoraga_adapt_armor",
                BASE_ARMOR + ARMOR_PER_ADAPTATION * done);

        // **爆炸适应满 100% → 爆炸再也推不动你**（主人 2026-09-15）：
        // "这个不是用来减少伤害，是用来防止被爆炸击退的。"
        // 减伤那条早就由免伤逻辑管了（100% 全免），这里只负责"别把我崩飞出去"。
        //
        // 🔴 用的是 `Attributes.KNOCKBACK_RESISTANCE` —— 1.20.1 **没有**
        //    `EXPLOSION_KNOCKBACK_RESISTANCE` 那个属性（那是 1.21 才拆出来的），
        //    这个版本里原版的爆炸推力读的就是通用击退抗性（`1 - 抗性`）。
        //    ⚠️ 副作用：**近战击退也一并免疫**了 —— 1.20.1 没法只挡爆炸那一路。
        //    两种爆炸键任意一条满就算（苦力怕 ／ 自己点的 TNT 是两条不同的伤害类型）。
        boolean blastProof = data.get(Track.DEFENSE, AdaptationKeys.EXPLOSION) >= COMPLETE
                || data.get(Track.DEFENSE, AdaptationKeys.PLAYER_EXPLOSION) >= COMPLETE;

        setModifier(player, Attributes.KNOCKBACK_RESISTANCE, EXPLOSION_MODIFIER,
                "mahoraga_blast_resist", blastProof ? 1.0D : 0.0D);

        if (player.getHealth() > player.getMaxHealth()) {
            player.setHealth(player.getMaxHealth());
        }
    }

    private static void setModifier(Player player, Holder<Attribute> attribute, ResourceLocation id, String name, double amount) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) return;

        AttributeModifier existing = instance.getModifier(id);

        if (existing != null && Math.abs(existing.amount() - amount) < 1.0E-4D) return;

        if (existing != null) instance.removeModifier(id);
        if (amount > 0.0D) {
            instance.addTransientModifier(new AttributeModifier(id, amount,
                    AttributeModifier.Operation.ADD_VALUE));
        }
    }
}
