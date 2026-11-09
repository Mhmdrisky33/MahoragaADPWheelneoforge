package com.mahoraga.adapt;

import com.mahoraga.MahoragaMod;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.TickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>Boss 解析播报</b> —— 打大怪时聊天栏那一段"逐步看穿"的演出。
 *
 * <h3>时序（主人 2026-09-15 定的）</h3>
 * <pre>
 *   打中它的一瞬间  ┌ 开始解析「凋灵」……
 *                   └ ▸ 扫描中……
 *   1.5 秒后         ┌ ▸ 解析完成：生命 300 ｜ 护甲 4
 *                   └ ✦ 检测到「飞行」能力 —— 已列入优先适应     ← 类型判定得到的能力
 *   2.5 秒后          ▸ 开始针对适应：极高生命 · 飞行
 *   之后每次抽到破解  ✦ 适应出能力「穿甲重击」
 * </pre>
 *
 * <h3>几个设计点</h3>
 * <ul>
 *   <li><b>一只怪只播一次全程</b>（按实体 UUID 记）—— 不然连打十几刀会刷满屏</li>
 *   <li><b>延迟靠 tick 队列</b>，不开线程 —— 和主线程同步，下线/换维度自动失效</li>
 *   <li><b>门槛 100 血</b>，和 {@link AnalyzeTracker#MIN_SPECIAL_HP} 一致</li>
 * </ul>
 */
@EventBusSubscriber(modid = MahoragaMod.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class AnalyzeNarrator {

    /** 第一条延迟出的结果（tick）—— 1.5 秒。 */
    private static final int RESULT_DELAY = 30;
    /** 第二条（针对性说明）—— 2.5 秒。 */
    private static final int TARGET_DELAY = 50;

    /** 某个玩家身上排着的待播报任务。 */
    private static final Map<UUID, List<Pending>> QUEUE = new ConcurrentHashMap<>();

    /** 已经播过全程的目标实体（用 List 保持插入顺序，好做容量裁剪）。 */
    private static final List<UUID> DONE = new ArrayList<>();

    private AnalyzeNarrator() {
    }

    // ==================== 对外入口 ====================

    /**
     * 打中一只生物时调一次（放在攻击分支里）。
     *
     * <p>只有 ≥100 血、且<b>第一次</b>打到它时才走这套演出。
     */
    public static void onAttack(Player player, LivingEntity target) {
        if (player.level().isClientSide) return;
        if (target == null || target == player) return;
        if (target.getMaxHealth() < AnalyzeTracker.MIN_SPECIAL_HP) return;

        UUID id = target.getUUID();

        if (DONE.contains(id)) return;          // 这只已经演过了

        DONE.add(id);

        if (DONE.size() > 256) {
            DONE.remove(0);
        }

        String name = target.getDisplayName().getString();

        // ---- ① 立刻：开始解析 ----
        line(player, Component.translatable("chat.mahoraga.analyze.begin", name)
                .withStyle(ChatFormatting.DARK_AQUA));
        line(player, Component.translatable("chat.mahoraga.analyze.scan")
                .withStyle(ChatFormatting.GRAY));

        // ---- ② 1.5 秒：数值 + 立刻能看出来的能力 ----
        boolean flyer = AnalyzeTracker.canFly(target);
        boolean teleporter = AnalyzeTracker.isTeleporterType(target);

        String entityId = AnalyzeTracker.idOf(target);

        // ★ 类型判定得到的结论**当场写进解析数据** —— 抽签时才知道要优先抽"禁传送"这类破解
        if (flyer) {
            AnalyzeData.of(player).addTag(entityId, AnalyzeTag.FLYER);
        }
        if (teleporter) {
            AnalyzeData.of(player).addTag(entityId, AnalyzeTag.TELEPORT);
        }
        float maxHp = target.getMaxHealth();
        int armor = target.getArmorValue();

        // ★★ 打中这一瞬间把"体检数据"全部抓成快照 —— 1.5 秒后再去读可能已经变了
        //    （血量尤其明显：这段时间里可能被打掉一大截，也可能被它自己回满）
        double attack = attackOf(target);
        float hpBefore = target.getHealth();
        String identity = identityOf(target, maxHp);
        List<String> labels = labelsOf(target);

        schedule(player, RESULT_DELAY, () -> {
            line(player, Component.translatable("chat.mahoraga.analyze.result",
                            num(maxHp), String.valueOf(armor), num(attack) + "（" + attackWord(attack) + "）")
                    .withStyle(ChatFormatting.AQUA));

            // 身份 + 生物标签（主人 2026-09-15 要的："查查生物的标签啊，是不是 boss 啊"）
            List<String> dossier = new ArrayList<>();

            if (identity != null) dossier.add(identity);
            dossier.addAll(labels);

            if (!dossier.isEmpty()) {
                line(player, Component.translatable("chat.mahoraga.analyze.dossier",
                                String.join(" · ", dossier))
                        .withStyle(ChatFormatting.GOLD));
            }

            // 「打不动」探测 —— 完全没掉血 = 无敌 / 锁血 / 受击冷却
            reportResistance(player, target, hpBefore, maxHp);

            if (flyer) {
                line(player, Component.translatable("chat.mahoraga.analyze.flyer")
                        .withStyle(ChatFormatting.LIGHT_PURPLE));
            }

            if (teleporter) {
                line(player, Component.translatable("chat.mahoraga.analyze.teleport")
                        .withStyle(ChatFormatting.LIGHT_PURPLE));
            }
        });

        // ---- ③ 2.5 秒：开始针对适应 ----
        schedule(player, TARGET_DELAY, () -> {
            String traits = traitList(maxHp, armor, attack, flyer, teleporter);

            line(player, Component.translatable("chat.mahoraga.analyze.target", traits)
                    .withStyle(ChatFormatting.GOLD));
        });
    }

    /**
     * **"打不动"探测** —— 拿这 1.5 秒里它掉了多少血，判断有没有锁血 / 无敌 / 高减伤。
     *
     * <p>判据朴素但很准：这一刀<b>打中了</b>（事件都触发了），血却没掉 ——
     * 那就只有三种可能：开着无敌、锁血、或者挨打之后那 20 tick 无敌帧还没过。
     * 三种对玩家来说是同一句话："**现在打它没用**"。
     *
     * <p>⚠️ 用"1.5 秒内掉的血"而不是"这一刀的伤害"：要精确算伤害得跨到
     * {@code LivingDamageEvent} 去，而这里本来就是"边打边说"的演出，粗一点够用。
     */
    private static void reportResistance(Player player, LivingEntity target, float hpBefore, float maxHp) {
        if (!target.isAlive()) return;                       // 被打死了就别提"抵抗"了

        float lost = hpBefore - target.getHealth();

        if (lost <= 0.05F) {
            line(player, Component.translatable("chat.mahoraga.analyze.resist")
                    .withStyle(ChatFormatting.RED));
        } else if (lost / Math.max(1.0F, maxHp) < 0.01F) {
            line(player, Component.translatable("chat.mahoraga.analyze.resist.tough",
                            String.format(Locale.ROOT, "%.1f", lost / Math.max(1.0F, maxHp) * 100.0F))
                    .withStyle(ChatFormatting.RED));
        }
    }

    /** 它的攻击力（没这个属性的生物返回 0）。 */
    private static double attackOf(LivingEntity target) {
        var instance = target.getAttribute(Attributes.ATTACK_DAMAGE);
        return instance == null ? 0.0D : instance.getValue();
    }

    /** 攻击力说成人话。 */
    private static String attackWord(double attack) {
        if (attack >= 20.0D) return "致命";
        if (attack >= 12.0D) return "极高";
        if (attack >= 7.0D) return "偏高";
        if (attack >= 3.0D) return "中等";
        return "偏低";
    }

    /**
     * **它是个什么档次的东西** —— 主人要的"是不是 boss"。
     *
     * <p>原版的 {@code minecraft:bosses} 标签在 1.20.1 里<b>并不存在</b>，
     * 但多数模组会自己加一条带 "boss" 字样的标签，所以先查标签、再按血量分档。
     */
    private static String identityOf(LivingEntity target, float maxHp) {
        if (hasBossTag(target)) return "首领";

        if (maxHp >= 500.0F) return "世界级首领";
        if (maxHp >= 300.0F) return "首领";
        if (maxHp >= 200.0F) return "精英";
        if (maxHp >= 100.0F) return "强敌";
        return null;
    }

    private static boolean hasBossTag(LivingEntity target) {
        return BuiltInRegistries.ENTITY_TYPES.getHolder(target.getType())
                .map(holder -> holder.tags().anyMatch(tag -> tag.location().getPath().contains("boss")))
                .orElse(false);
    }

    /**
     * **生物标签**说成人话（主人要的"查查生物的标签"）。
     *
     * <p>只翻认得出意思的那几个（{@link #TAG_WORDS}）——
     * 原版那堆纯技术标签（{@code fall_damage_immune} 之类）翻出来反而更难懂，宁缺毋滥。
     */
    private static List<String> labelsOf(LivingEntity target) {
        List<String> out = new ArrayList<>();

        BuiltInRegistries.ENTITY_TYPES.getHolder(target.getType()).ifPresent(holder ->
                holder.tags().forEach(tag -> {
                    String word = TAG_WORDS.get(tag.location().getPath());

                    if (word != null && !out.contains(word) && out.size() < 3) out.add(word);
                }));

        return out;
    }

    /** 认得出意思的标签 → 中文。 */
    private static final Map<String, String> TAG_WORDS = Map.ofEntries(
            Map.entry("raiders", "灾厄"),
            Map.entry("skeletons", "骷髅"),
            Map.entry("sensitive_to_smite", "亡灵"),
            Map.entry("sensitive_to_bane_of_arthropods", "节肢"),
            Map.entry("sensitive_to_impaling", "海洋"),
            Map.entry("freeze_immune_entity_types", "免疫冻结"),
            Map.entry("freeze_hurts_extra_types", "惧寒"),
            Map.entry("fall_damage_immune", "免疫摔落"),
            Map.entry("impact_projectiles", "硬壳"),
            Map.entry("powder_snow_walkable_mobs", "能走细雪"),
            Map.entry("axolotl_hunt_targets", "水生猎物"),
            Map.entry("frog_food", "蛙食"),
            Map.entry("non_controlling_rider", "不受控骑乘"),
            Map.entry("beehive_inhabitors", "蜂巢居者"));

    /** 抽到「独特破解」时调 —— 播"适应出能力"。 */
    public static void onCrack(Player player, String crackName) {
        line(player, Component.translatable("chat.mahoraga.analyze.crack", crackName)
                .withStyle(ChatFormatting.LIGHT_PURPLE));
    }

    // ==================== 每 tick 跑队列 ====================

    /** 每 tick 推一次队列（自己挂事件，省得去改 AdaptationManager）。 */
    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (event.player.level().isClientSide) return;

        tick(event.player);
    }

    public static void tick(Player player) {
        List<Pending> list = QUEUE.get(player.getUUID());

        if (list == null || list.isEmpty()) return;

        List<Pending> ready = null;

        for (Pending p : list) {
            p.ticks--;

            if (p.ticks <= 0) {
                if (ready == null) ready = new ArrayList<>();
                ready.add(p);
            }
        }

        if (ready == null) return;

        list.removeAll(ready);

        for (Pending p : ready) {
            try {
                p.action.run();
            } catch (Exception ignored) {
                // 播报失败不该炸掉整个 tick
            }
        }
    }

    /** 玩家下线 → 把队列丢掉。 */
    public static void clear(UUID playerId) {
        QUEUE.remove(playerId);
    }

    // ==================== 内部 ====================

    private static void schedule(Player player, int delay, Runnable action) {
        QUEUE.computeIfAbsent(player.getUUID(), k -> new ArrayList<>())
                .add(new Pending(delay, action));
    }

    private static void line(Player player, Component text) {
        player.displayClientMessage(text, false);
    }

    /**
     * 把这一刀能看出来的特征拼成一句人话。
     *
     * <p>⚠️ 只做<b>当场能判</b>的（血量 / 护甲 / 攻击力 / 类型推断的能力）——
     * "会回血""会闪避"那些要靠打几刀才观察得出来，不在这一句里说。
     */
    private static String traitList(float maxHp, int armor, double attack, boolean flyer, boolean teleporter) {
        List<String> parts = new ArrayList<>();

        if (maxHp >= 400.0F) {
            parts.add("极高生命");
        } else if (maxHp >= 200.0F) {
            parts.add("高生命");
        } else {
            parts.add("厚实生命");
        }

        if (armor >= 15) {
            parts.add("高护甲");
        }

        if (attack >= 15.0D) {
            parts.add("致命攻击");
        } else if (attack >= 7.0D) {
            parts.add("高攻击");
        }

        if (flyer) {
            parts.add("飞行");
        }

        if (teleporter) {
            parts.add("瞬移");
        }

        return String.join(" · ", parts);
    }

    /** 数字去小数（收 double，攻击力那种也是它）。 */
    private static String num(double value) {
        return String.format(Locale.ROOT, "%.0f", value);
    }

    /** 可变倒计时的待办（不能用 record —— 字段是 final 的）。 */
    private static final class Pending {
        private int ticks;
        private final Runnable action;

        private Pending(int ticks, Runnable action) {
            this.ticks = ticks;
            this.action = action;
        }
    }
}
