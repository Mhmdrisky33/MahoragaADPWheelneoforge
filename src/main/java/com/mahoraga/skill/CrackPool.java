package com.mahoraga.skill;

import com.mahoraga.adapt.AnalyzeData;
import com.mahoraga.adapt.AnalyzeNarrator;
import com.mahoraga.adapt.AnalyzeTag;
import com.mahoraga.adapt.AnalyzeTracker;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 「独特破解池」—— 解析出来的能力就是这么一张张抽出来的。
 *
 * ==================== 抽取规则（主人 2026-09-14 最后拍板） ====================
 *
 * **① 每满 100% 才有一个抽取机会。**
 * 100% / 200% / … / 1000%，一共 10 次。不是每刀抽、也不是每个标签各抽一堆。
 *
 * **② 机会在「窗口」里慢慢变实** —— 概率从 0 线性涨到 100%，到窗口末尾必出：
 * <pre>
 *   第 1 次：窗口 [100, 200]  → 100% 时 0，200% 时必出
 *   第 2 次：窗口 [200, 300]  → 200% 时 0，300% 时必出
 *   … 第 N 次：窗口 [N×100, (N+1)×100]
 * </pre>
 * 所以正常情况下**每 100% 落一个能力**，运气差就往后飘一点，但绝不会永远不出。
 *
 * **③ 一次只出一张**，从**这只怪真正有的那些条件**里挑 ——
 * 卡池 = 已识别标签的池子**并集**（权重相加）。
 * 🔴 所以**不会传送的怪压根抽不到「禁止传送」** —— 它的池子里没有这一条。
 *
 * **④ 学过的不会再出**（同一只怪），每个标签的池子抽干了就跳过。
 *
 * **⑤ 抽完固定**，不是每刀重抽。"适应"的灵魂是可预期的成长。
 */
public final class CrackPool {
    /** 进度上限（百分比）—— 抽签窗口封在这里。 */
    private static final int MAX_PCT = 1000;
    /** 抽签步长 —— 跟 {@link AnalyzeData#DRAW_STEP} 保持一致（50% 一档）。 */
    private static final int STEP = AnalyzeData.DRAW_STEP;

    /**
     * **无量空处**的固定权重 —— 它**挂在所有生物身上**，不挑怪。
     *
     * <p>相比常规条目（60 × 2 = 120 那种）这个数字很小，是故意的：
     * 主人要的是"随机抽出来的"，那就得保持稀有度，抽到才算惊喜。
     */
    private static final int VOID_DOMAIN_WEIGHT = 12;

    /** 「独特破解」的种类。每种都要在 {@link CrackEffects} 里有对应实现。 */
    public enum Kind {
        /** 额外扣一段**按最大生命百分比**算的伤害。 */
        PERCENT_DAMAGE,
        /** 真伤 —— 无视护甲/附魔/抗性/吸收。 */
        TRUE_DAMAGE,
        /** 削减它的**生命上限**。 */
        CUT_MAX_HEALTH,
        /** 削减它的**护甲**。 */
        CUT_ARMOR,
        /** 削减它的**攻击力**。 */
        CUT_DAMAGE,
        /** **禁止治疗** —— 这一段时间它回不了血。 */
        NO_HEAL,
        /** 让它**流血不止**。 */
        BLEED,
        /** **死亡印记** —— 它下一次受到的伤害翻倍。 */
        MARK,
        /** 这一下**直接穿甲**。 */
        PENETRATE,
        /** **禁锢** —— 短时间动不了。 */
        LOCK_MOVE,
        /** **禁止传送**。 */
        LOCK_TELEPORT,
        /** **清除它的召唤物**。 */
        CLEAR_MINION,
        /**
         * **无量空处** —— 展开领域，范围里的东西全部钉住（眩晕）。
         *
         * <p>抄自原模组五条悟那一招（{@code UnlimitedVoid}：封闭领域 + 眩晕 + 失明）。
         * 主人要的就是"让人无法动"这一条 —— 实现在 {@link VoidDomain}。
         */
        VOID_DOMAIN;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public Component display() {
            return Component.translatable("mahoraga.crack." + id());
        }
    }

    /** 标签 → {候选破解: 权重}。顺序 = 从主解法到冷门（LinkedHashMap 保序，便于调参时一眼看懂）。 */
    private static final Map<AnalyzeTag, Map<Kind, Integer>> POOL = new EnumMap<>(AnalyzeTag.class);

    static {
        // 血厚 —— 主解法：百分比伤害（血越多越疼）；备选：削生命上限
        POOL.put(AnalyzeTag.HIGH_HP, pool(
                Kind.PERCENT_DAMAGE, 60,
                Kind.CUT_MAX_HEALTH, 25,
                Kind.TRUE_DAMAGE, 15));

        // 回血快 —— 主解法：禁止治疗
        POOL.put(AnalyzeTag.FAST_HEAL, pool(
                Kind.NO_HEAL, 62,
                Kind.BLEED, 22,
                Kind.MARK, 16));

        // 锁血（怎么打都不掉血）—— 主解法：真伤
        POOL.put(AnalyzeTag.LOCK_BLOOD, pool(
                Kind.TRUE_DAMAGE, 55,
                Kind.PENETRATE, 28,
                Kind.MARK, 17));

        // 甲厚 —— 主解法：穿甲重击
        POOL.put(AnalyzeTag.HIGH_ARMOR, pool(
                Kind.PENETRATE, 60,
                Kind.CUT_ARMOR, 24,
                Kind.TRUE_DAMAGE, 16));

        // 高减伤 —— 主解法：真伤（减伤对它无效）
        POOL.put(AnalyzeTag.HIGH_REDUCTION, pool(
                Kind.TRUE_DAMAGE, 58,
                Kind.CUT_ARMOR, 24,
                Kind.MARK, 18));

        // 会闪避 —— 主解法：禁锢（让它站住）
        POOL.put(AnalyzeTag.DODGE, pool(
                Kind.LOCK_MOVE, 55,
                Kind.LOCK_TELEPORT, 25,
                Kind.TRUE_DAMAGE, 20));

        // 不吃状态（不吃 debuff）—— 主解法：削生命上限（属性削减不是 debuff，照样生效）
        POOL.put(AnalyzeTag.IMMUNE_DEBUFF, pool(
                Kind.CUT_MAX_HEALTH, 45,
                Kind.CUT_DAMAGE, 30,
                Kind.TRUE_DAMAGE, 25));

        // 会传送 —— 主解法：禁止传送
        POOL.put(AnalyzeTag.TELEPORT, pool(
                Kind.LOCK_TELEPORT, 60,
                Kind.LOCK_MOVE, 25,
                Kind.CUT_DAMAGE, 15));

        // 会召唤 —— 主解法：清除召唤物
        POOL.put(AnalyzeTag.SUMMONER, pool(
                Kind.CLEAR_MINION, 60,
                Kind.CUT_DAMAGE, 25,
                Kind.PERCENT_DAMAGE, 15));

        // 阶段无敌 —— 主解法：真伤（无敌帧期间也能压血）
        POOL.put(AnalyzeTag.PHASE, pool(
                Kind.TRUE_DAMAGE, 55,
                Kind.PENETRATE, 25,
                Kind.CUT_MAX_HEALTH, 20));

        // ★ 会飞（2026-09-15 新增）—— 主解法：先把它拽下来（禁锢），不然根本摸不到
        POOL.put(AnalyzeTag.FLYER, pool(
                Kind.LOCK_MOVE, 50,
                Kind.PENETRATE, 30,
                Kind.CUT_MAX_HEALTH, 20));
    }

    /** 自增益池 —— 全是原版效果，抽到就一直给自己挂上。 */
    private static final MobEffect[] BUFFS = {
            MobEffects.MOVEMENT_SPEED, MobEffects.DAMAGE_BOOST, MobEffects.DAMAGE_RESISTANCE,
            MobEffects.REGENERATION, MobEffects.ABSORPTION, MobEffects.DIG_SPEED,
            MobEffects.JUMP, MobEffects.FIRE_RESISTANCE, MobEffects.NIGHT_VISION,
            MobEffects.DOLPHINS_GRACE
    };

    private CrackPool() {
    }

    private static Map<Kind, Integer> pool(Object... pairs) {
        Map<Kind, Integer> map = new LinkedHashMap<>();

        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put((Kind) pairs[i], (Integer) pairs[i + 1]);
        }
        return map;
    }

    /** NBT 里存的名字 → 显示名（存坏了就原样吐回去）。 */
    public static String nameOfKind(String name) {
        try {
            return Kind.valueOf(name).display().getString();
        } catch (IllegalArgumentException e) {
            return name;
        }
    }

    /** `minecraft:zombie` → 「僵尸」这个名字。 */
    public static Component nameOf(String entityId) {
        ResourceLocation id = ResourceLocation.tryParse(entityId);

        if (id == null) return Component.literal(entityId);

        var type = BuiltInRegistries.ENTITY_TYPES.getValue(id);
        return type == null ? Component.literal(entityId) : type.getDescription();
    }

    /** 这条生物当前识别出的所有标签。 */
    public static List<AnalyzeTag> tagsOf(AnalyzeData data, String entityId) {
        List<AnalyzeTag> out = new ArrayList<>();

        for (AnalyzeTag tag : AnalyzeTag.values()) {
            if (data.hasTag(entityId, tag)) out.add(tag);
        }
        return out;
    }

    /**
     * 每次打中这只怪都调一次 —— 该抽就抽。
     *
     * @param pct 这条生物当前的**攻击适应**百分比（100 = 100%）
     */
    public static void advance(Player player, AnalyzeData data, String entityId, int pct) {
        // 血太少的怪不参与（蜘蛛/僵尸那种）
        if (data.maxHealth(entityId) < AnalyzeTracker.MIN_SPECIAL_HP) return;
        if (pct < STEP) return;

        // 一轮最多连抽几次（正常只抽 1 次，卡池干了就直接停）
        for (int guard = 0; guard < 12; guard++) {
            int milestone = data.nextDraw(entityId);

            if (milestone > MAX_PCT) return;          // 10 次抽完，收工
            if (pct < milestone) return;              // 还没到这一档

            double chance = (pct - milestone) / (double) STEP;
            boolean forced = pct >= MAX_PCT;          // 已经到顶 → 别让最后一次永远卡着

            if (!forced && player.getRandom().nextDouble() >= chance) return;

            Kind picked = pick(player, data, entityId);
            if (picked == null) return;               // 池子抽干了

            data.addCrack(entityId, picked.name());
            data.setNextDraw(entityId, milestone + STEP);

            ensureBuff(player, data, entityId);

            AnalyzeNarrator.onCrack(player, picked.display().getString());
        }
    }

    /**
     * 从**这只怪真正有的条件**里按权重抽一张。
     *
     * 权重是**跨标签累加**的 —— 一条破解同时挂在两个标签上，就更容易被抽到，
     * 这本身就放大了"针对性"。
     */
    private static Kind pick(Player player, AnalyzeData data, String entityId) {
        List<String> owned = data.cracks(entityId);
        Map<Kind, Integer> weights = new LinkedHashMap<>();

        for (AnalyzeTag tag : tagsOf(data, entityId)) {
            Map<Kind, Integer> pool = POOL.get(tag);
            if (pool == null) continue;

            // ★ 2026-09-15：「瞬移」和「飞行」的破解**权重 ×2** —— 主人要求优先适应这两个。
            //    理由很实在：会传送 / 会飞的怪你根本摸不到，"先解决够不着的问题"永远是对的。
            int scale = (tag == AnalyzeTag.TELEPORT || tag == AnalyzeTag.FLYER) ? 2 : 1;

            for (Map.Entry<Kind, Integer> entry : pool.entrySet()) {
                if (owned.contains(entry.getKey().name())) continue;

                // ★ 另外把「万能三件套」再抬一手（主人："高呼加破甲，高血量百分比或者砍血量上限"）——
                //    穿甲重击 / 百分比伤害 / 削减生命上限，对什么怪都有效，就是要多出。
                int boost = switch (entry.getKey()) {
                    case PENETRATE, PERCENT_DAMAGE, CUT_MAX_HEALTH -> 3;
                    default -> 2;
                };

                weights.merge(entry.getKey(), entry.getValue() * scale * boost / 2, Integer::sum);
            }
        }

        // ★ **无量空处**：不挑怪的稀有解法 —— 不管你打的是什么生物都有机会抽到
        //   （主人 2026-09-15："搞个独特使用，效果是眩晕，这个也是随机抽出来的"）。
        //   学过就不再出（跟其它破解一个规矩）。
        if (!owned.contains(Kind.VOID_DOMAIN.name())) {
            weights.merge(Kind.VOID_DOMAIN, VOID_DOMAIN_WEIGHT, Integer::sum);
        }

        int total = 0;
        for (int weight : weights.values()) total += weight;
        if (total <= 0) return null;                  // 全学过了

        int roll = player.getRandom().nextInt(total);

        for (Map.Entry<Kind, Integer> entry : weights.entrySet()) {
            roll -= entry.getValue();
            if (roll < 0) return entry.getKey();
        }
        return null;
    }

    /** 自增益：整只怪只抽一次，抽到了就一直带着。 */
    private static void ensureBuff(Player player, AnalyzeData data, String entityId) {
        if (!data.buffs(entityId).isEmpty()) return;

        MobEffect buff = BUFFS[player.getRandom().nextInt(BUFFS.length)];
        ResourceLocation id = BuiltInRegistries.MOB_EFFECTS.getKey(buff);

        if (id != null) data.addBuff(entityId, id.toString());
    }
}
