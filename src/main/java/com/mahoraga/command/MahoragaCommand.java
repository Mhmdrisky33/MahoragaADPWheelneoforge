package com.mahoraga.command;

import com.mahoraga.MahoragaMod;
import com.mahoraga.adapt.AnalyzeData;
import com.mahoraga.adapt.AdaptationData;
import com.mahoraga.adapt.AdaptationKeys;
import com.mahoraga.adapt.AdaptationManager;
import com.mahoraga.adapt.Track;
import com.mahoraga.handler.BladeHandler;
import com.mahoraga.handler.WheelCeremony;
import com.mahoraga.handler.WheelHandler;
import com.mahoraga.registry.ModItems;
import com.mahoraga.skill.CrackPool;
import com.mahoraga.skill.FireArrow;
import com.mahoraga.skill.MalevolentShrine;
import com.mahoraga.skill.SukunaCleave;
import com.mahoraga.skill.VoidDomain;
import com.mahoraga.skill.WaterJet;
import com.mahoraga.skill.WheelSymbiosis;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/**
 * /mahoraga —— 查看三套适应的实时进度。
 * 纯诊断用：看不到进度的时候，先跑一句这个，比猜快一百倍。
 *
 *   /mahoraga                          查看状态
 *   /mahoraga reset                    清空全部适应进度
 *   /mahoraga set <轨道> <键> <百分比>  直接设定某一条的进度（调试 / 直接体验某个能力）
 *
 * **防御轨有两类键**（2026-09-14 起）：
 * - `damage:<伤害类型>` → **免伤**进度（例：`damage:minecraft:mob_attack` 近战）
 * - `◆ entity:<生物>`  → 那个生物的**招牌能力**（例：`entity:minecraft:husk` → 饱食度锁满）
 *
 * 例：/mahoraga set attack entity:minecraft:skeleton 100      ← 解锁「剑气」
 *     /mahoraga set defense damage:minecraft:mob_attack 100   ← 免疫所有近战
 *     /mahoraga set defense entity:minecraft:husk 100         ← 招牌能力：饱食度锁满
 *     /mahoraga set daily daily:sprint 200                    ← 解锁「自动跨 1.5 格」
 */
@EventBusSubscriber(modid = MahoragaMod.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class MahoragaCommand {
    private MahoragaCommand() {
    }

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("mahoraga")
                        .executes(MahoragaCommand::status)
                        .then(Commands.literal("status").executes(MahoragaCommand::status))
                        .then(Commands.literal("reset").executes(MahoragaCommand::reset))
                        .then(Commands.literal("ceremony").executes(MahoragaCommand::ceremony))
                        .then(Commands.literal("blade").executes(MahoragaCommand::blade))
                        .then(Commands.literal("symbiosis").executes(MahoragaCommand::symbiosis))
                        .then(Commands.literal("wheel")
                                .then(Commands.literal("reset").executes(MahoragaCommand::wheelReset)))
                        .then(Commands.literal("domain").executes(MahoragaCommand::domain))
                        .then(Commands.literal("cleave").executes(MahoragaCommand::cleave))
                        .then(Commands.literal("water").executes(MahoragaCommand::water))
                        .then(Commands.literal("fuga").executes(MahoragaCommand::fuga))
                        .then(Commands.literal("shrine").executes(MahoragaCommand::shrine))
                        .then(Commands.literal("set")
                                .then(Commands.argument("track", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                new String[]{"defense", "attack", "daily"}, builder))
                                        .then(Commands.argument("key", StringArgumentType.string())
                                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                        KEY_SUGGESTIONS, builder))
                                                .then(Commands.argument("percent", IntegerArgumentType.integer(0, 1000))
                                                        .executes(MahoragaCommand::set))))));
    }

    /**
     * 键的**简称**（`/mahoraga set` 用）。
     *
     * 🔴 **为什么必须给简称**：Brigadier 的 `word()` 类型**不认冒号** ——
     * 人家把 brigadier-1.1.8 反编译出来看过，`isAllowedInUnquotedString` 只放行
     * `0-9 A-Z a-z _ - . +`（字符常量 48-57 / 65-90 / 97-122 / 95 / 45 / 46 / 43），
     * **没有 `:`（58）**。而所有真实的键都是 `damage:` / `entity:` / `daily:` 开头 ——
     * 也就是说老版本这条命令**一个真键都输不进去**（建议列表里那些也点不了）。
     *
     * 现在 key 改成 `string()` 类型，于是两条路都能走：
     * - **打简称**：`/mahoraga set defense fire 100`
     * - **打完整键**（要加引号）：`/mahoraga set defense "damage:minecraft:on_fire" 100`
     */
    private static final String[] KEY_SUGGESTIONS = {
            // 免伤（按伤害类型）
            "mob_attack", "arrow", "trident", "thrown", "fireball",
            "dragon_breath", "sonic_boom", "explosion",
            // 环境伤害
            "fire", "in_fire", "lava", "freeze", "suffocation", "in_wall",
            "lightning",
            // 日常
            "mining", "sprint", "jump", "water", "dark", "cave", "throw", "eat", "enchant", "fishing",
            // 生物招牌能力 / 攻击适应（别的生物名也能直接打）
            "zombie", "skeleton", "phantom", "enderman", "husk", "blaze", "warden", "wither"
    };

    /**
     * 把玩家打的"键"翻译成真正的适应键。三条路：
     *
     * 1. **含冒号** → 原样用（完整键，得带引号）
     * 2. **简称** → 换成完整键（`fire` → `damage:minecraft:on_fire`；`lava` 按轨道分岔）
     * 3. **其它** → 当成生物名（`enderman` → `entity:minecraft:enderman`）
     */
    private static String resolveKey(Track track, String raw) {
        if (raw.indexOf(':') >= 0) return raw;

        String key = raw.toLowerCase(Locale.ROOT);

        // 日常轨：daily:xxx
        if (track == Track.DAILY) {
            return switch (key) {
                case "mine", "dig" -> AdaptationKeys.DAILY_MINING;
                case "run" -> AdaptationKeys.DAILY_SPRINT;
                default -> "daily:" + key;
            };
        }

        // 防御轨：先看看是不是某个"伤害类型"的简称（`lava` 在这里是岩浆伤害）
        if (track == Track.DEFENSE) {
            String damage = switch (key) {
                case "fire", "on_fire" -> AdaptationKeys.FIRE;
                case "in_fire" -> AdaptationKeys.FIRE_IN;
                case "lava" -> AdaptationKeys.LAVA;
                case "freeze", "snow", "powder_snow" -> AdaptationKeys.FREEZE;
                case "lightning", "thunder", "bolt" -> AdaptationKeys.LIGHTNING;
                case "suffocate", "suffocation" -> AdaptationKeys.SUFFOCATION;
                case "wall", "in_wall" -> AdaptationKeys.IN_WALL;
                case "melee", "mob_attack" -> "damage:minecraft:mob_attack";
                case "arrow", "bow" -> "damage:minecraft:arrow";
                case "trident" -> "damage:minecraft:trident";
                case "thrown", "snowball" -> "damage:minecraft:thrown";
                case "fireball" -> "damage:minecraft:fireball";
                case "breath", "dragon_breath" -> "damage:minecraft:dragon_breath";
                case "sonic", "sonic_boom" -> "damage:minecraft:sonic_boom";
                case "explode", "explosion" -> "damage:minecraft:explosion";
                case "drown", "drowning" -> "damage:minecraft:drown";
                case "fall", "falling" -> "damage:minecraft:fall";
                default -> null;
            };

            if (damage != null) return damage;
        }

        // 剩下的当生物名 → entity: 键（攻击适应 / 招牌能力都走它）
        return key.startsWith("minecraft:") ? "entity:" + key : "entity:minecraft:" + key;
    }

    /** /mahoraga set <track> <key> <percent> —— 直接给某一条适应赋值。 */
    private static int set(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();

        String trackName = StringArgumentType.getString(ctx, "track").toLowerCase(Locale.ROOT);
        int percent = IntegerArgumentType.getInteger(ctx, "percent");

        Track track = switch (trackName) {
            case "defense", "def", "防御" -> Track.DEFENSE;
            case "attack", "atk", "攻击" -> Track.ATTACK;
            case "daily", "日常" -> Track.DAILY;
            default -> null;
        };

        if (track == null) {
            ctx.getSource().sendFailure(Component.translatable("command.mahoraga.set.badTrack"));
            return 0;
        }

        // 简称 → 真键（也可以直接打带引号的完整键）
        String key = resolveKey(track, StringArgumentType.getString(ctx, "key"));

        AdaptationManager.setProgress(player, track, key, percent / 100.0F);

        ctx.getSource().sendSuccess(() -> Component.translatable("command.mahoraga.set.done",
                        Component.translatable(AdaptationKeys.trackNameKey(track)),
                        AdaptationKeys.display(key), percent + "%")
                .withStyle(ChatFormatting.GREEN), false);

        return 1;
    }

    /**
     * `/mahoraga blade` —— **"剑刃为什么没变色"的专用诊断**。
     *
     * 主人 2026-09-14 报"适应了岩浆、着火，剑还是没变" —— 有了这一句就能一眼分清是
     * "还没练到 100%" 还是 "练到了但显示没跟上"，不用猜。
     *
     * ⚠️ 顺带说清一件事：**火刃看的是防御轨**（你被火搞过），跟"打什么生物"没关系。
     */
    private static int blade(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayerOrException();

        AdaptationData data = AdaptationManager.data(player);
        int mode = BladeHandler.skinMode(player);

        String skinKey = switch (mode) {
            case BladeHandler.SKIN_FIRE -> "command.mahoraga.blade.skin.fire";
            case BladeHandler.SKIN_FROST -> "command.mahoraga.blade.skin.frost";
            default -> "command.mahoraga.blade.skin.none";
        };

        src.sendSuccess(() -> Component.translatable("command.mahoraga.blade.header")
                .withStyle(ChatFormatting.LIGHT_PURPLE), false);
        src.sendSuccess(() -> Component.translatable("command.mahoraga.blade.current",
                        Component.translatable(skinKey))
                .withStyle(ChatFormatting.GOLD), false);
        src.sendSuccess(() -> Component.translatable("command.mahoraga.blade.fire",
                        pctText(data.get(Track.DEFENSE, AdaptationKeys.FIRE)),
                        pctText(data.get(Track.DEFENSE, AdaptationKeys.FIRE_IN)),
                        pctText(data.get(Track.DEFENSE, AdaptationKeys.LAVA)))
                .withStyle(ChatFormatting.RED), false);
        src.sendSuccess(() -> Component.translatable("command.mahoraga.blade.frost",
                        pctText(data.get(Track.DEFENSE, AdaptationKeys.FREEZE)))
                .withStyle(ChatFormatting.AQUA), false);

        // 剑上**实际**写的编号 —— 和上面「当前形态」对照，就能看出断在哪一环
        ItemStack held = player.getMainHandItem();

        if (held.is(ModItems.MAHORAGA_BLADE.get())) {
            src.sendSuccess(() -> Component.translatable("command.mahoraga.blade.tag",
                            String.valueOf(BladeHandler.skinTag(held)))
                    .withStyle(ChatFormatting.GRAY), false);
        } else {
            src.sendSuccess(() -> Component.translatable("command.mahoraga.blade.noBlade")
                    .withStyle(ChatFormatting.DARK_GRAY), false);
        }

        return 1;
    }

    /** 进度 → "120%" 这种字符串（给诊断命令拼文案用）。 */
    private static String pctText(float value) {
        return ((int) Math.floor(value * 100.0F)) + "%";
    }

    /**
     * **法轮寄生状态自检**（`/mahoraga symbiosis`）。
     *
     * 跟 `/mahoraga blade` 一个套路 —— 把整条链路的每一环都摊开，
     * 免得"附身不了"这种东西又变成一句没有线索的"没反应"。
     */
    private static int symbiosis(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayerOrException();

        WheelSymbiosis.describe(player, line ->
                src.sendSuccess(() -> line, false));

        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayerOrException();

        src.sendSuccess(() -> Component.translatable("command.mahoraga.header")
                .withStyle(ChatFormatting.LIGHT_PURPLE), false);

        boolean has = WheelHandler.hasWheel(player);

        src.sendSuccess(() -> Component.translatable("command.mahoraga.status",
                        Component.translatable(has ? "command.mahoraga.status.yes" : "command.mahoraga.status.no"))
                .withStyle(ChatFormatting.GRAY), false);

        if (!has) {
            src.sendSuccess(() -> Component.translatable("command.mahoraga.noWheel")
                    .withStyle(ChatFormatting.RED), false);
            return 0;
        }

        AdaptationData data = AdaptationManager.data(player);
        boolean any = false;

        for (Track track : Track.values()) {
            // 百分比从高到低排，看得顺眼
            TreeMap<Integer, List<String>> ordered = new TreeMap<>(Comparator.reverseOrder());

            for (String key : data.keys(track)) {
                float value = data.get(track, key);
                int pct = (int) Math.floor(value * 100.0F);

                // 防御轨的 `entity:` 是「生物的招牌能力」而不是免伤 —— 前缀 ◆ 一眼分得开
                boolean signature = track == Track.DEFENSE && key.startsWith("entity:");

                String name = AdaptationKeys.display(key).getString();
                if (signature) name = "◆ " + name;

                String line = name + " " + pct + "%" + stateMark(track, data.mode(track, key));

                if (value >= AdaptationManager.COMPLETE) {
                    line += Component.translatable(signature
                            ? "command.mahoraga.signature" : "command.mahoraga.immune").getString();
                }
                ordered.computeIfAbsent(pct, k -> new ArrayList<>()).add(line);
            }

            if (ordered.isEmpty()) continue;

            any = true;

            List<String> flat = new ArrayList<>();
            ordered.values().forEach(flat::addAll);

            String joined = String.join(" · ", flat);
            Component trackName = Component.translatable(AdaptationKeys.trackNameKey(track));

            src.sendSuccess(() -> Component.translatable("command.mahoraga.line", trackName, joined)
                    .withStyle(ChatFormatting.AQUA), false);
        }

        if (!any) {
            src.sendSuccess(() -> Component.translatable("command.mahoraga.empty")
                    .withStyle(ChatFormatting.DARK_GRAY), false);
        }

        sendAnalyze(src, player);

        return 1;
    }

    /**
     * 解析线的状态 —— 每种打过的生物各一行：
     * 「名字（已看穿的标签）→ 抽到的独特破解」。
     *
     * 标签是"你观察到它有什么毛病"，破解是"你学会了怎么治它"。
     */
    private static void sendAnalyze(CommandSourceStack src, ServerPlayer player) {
        AnalyzeData data = AnalyzeData.of(player);
        List<String> lines = new ArrayList<>();

        for (String id : data.ids()) {
            List<String> tags = CrackPool.tagsOf(data, id).stream()
                    .map(tag -> tag.display().getString()).toList();

            List<String> cracks = data.cracks(id).stream()
                    .map(CrackPool::nameOfKind).toList();

            lines.add(Component.translatable("command.mahoraga.analyze.entry",
                    CrackPool.nameOf(id),
                    tags.isEmpty() ? "?" : String.join("·", tags),
                    cracks.isEmpty() ? "—" : String.join("·", cracks)).getString());
        }

        if (lines.isEmpty()) {
            src.sendSuccess(() -> Component.translatable("command.mahoraga.analyze.none")
                    .withStyle(ChatFormatting.DARK_GRAY), false);
            return;
        }

        src.sendSuccess(() -> Component.translatable("command.mahoraga.analyze.line",
                String.join("  |  ", lines)).withStyle(ChatFormatting.YELLOW), false);
    }

    /**
     * 状态标记 —— 战斗轨和日常轨的机制不一样，说法也分开：
     * - 战斗轨：↑增长中 / =保持期 / ↓衰减中（掉到 0 之前会先播报一次）
     * - 日常轨：↑进行中 / ↓已停下（停下就掉，没有保持期，也不播报）
     */
    private static String stateMark(Track track, int mode) {
        if (track.isDaily()) {
            return switch (mode) {
                case AdaptationData.GROW -> " ↑进行中";
                case AdaptationData.DECAY -> " ↓已停下";
                default -> "";
            };
        }

        return switch (mode) {
            case AdaptationData.GROW -> " ↑增长中";
            case AdaptationData.HOLD -> " =保持期";
            case AdaptationData.DECAY -> " ↓衰减中";
            default -> "";
        };
    }

    private static int reset(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        boolean had = AdaptationManager.resetAll(player);

        ctx.getSource().sendSuccess(() -> Component.translatable(
                        had ? "command.mahoraga.reset" : "command.mahoraga.resetNothing")
                .withStyle(ChatFormatting.GRAY), false);

        return had ? 1 : 0;
    }

    /**
     * <b>摘掉法轮</b> —— 拍视频专用：把"获得法轮"那段演出重置，好从头再演一遍。
     *
     * <p>清标记 + 删头顶实体 + <b>收掉属性加成和退魔之剑</b>（2026-09-15 晚补的：
     * 以前只删轮子，结果"人还是魔虚罗"—— 血条长着、护甲厚着、剑还拎着）。
     * 再拿一次「退魔法轮」就会重新演。
     */
    private static int wheelReset(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();

        WheelHandler.reset(player);

        ctx.getSource().sendSuccess(() -> Component.literal(
                "已摘除退魔法轮 —— 轮子、属性加成、退魔之剑全部收走了，现在是普通玩家。"
                        + "再拿一次「退魔法轮」即可重演召唤。"), false);

        return 1;
    }

    /**
     * `/mahoraga water` —— 直接喷一次水（满象）。
     *
     * <p>它平时要"溺水或泡水适应满 100%"才解锁 —— 调试 / 拍视频时直接用这条。
     */
    private static int water(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();

        WaterJet.cast(player);

        ctx.getSource().sendSuccess(() -> Component.literal(
                "满象 —— 朝准星喷出一道水柱（26 格）。"), false);

        return 1;
    }

    /**
     * `/mahoraga fuga` —— 直接放一记「开」。
     *
     * <p>它是**适应火焰**的奖励；起手蓄力 0.5 秒后火球才飞出去。
     */
    private static int fuga(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();

        FireArrow.cast(player);

        ctx.getSource().sendSuccess(() -> Component.literal(
                "「开」—— 蓄力中，半秒后射出去（爆炸半径 4 格）。"), false);

        return 1;
    }

    /**
     * `/mahoraga shrine` —— 直接展开一次「斩击领域」。
     *
     * <p>它要"被 5 只以上围攻"才练得出来；这条命令跳过练习直接放。
     */
    private static int shrine(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();

        MalevolentShrine.cast(player);

        ctx.getSource().sendSuccess(() -> Component.literal(
                "「斩击领域」—— 半径 15 格内的所有生物，各吃一套连斩。"), false);

        return 1;
    }

    /**
     * `/mahoraga cleave` —— 对着最近的生物甩一套连斩。
     *
     * <p>为什么要有这条：连斩是挂在**百分比伤害**那条破解上的，而且破解对同一只生物
     * **只放一次**（主人定的规矩）—— 想反复看演出，光靠打怪很别扭。这条命令直接放。
     */
    private static int cleave(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();

        net.minecraft.world.entity.LivingEntity target = player.level()
                .getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                        player.getBoundingBox().inflate(12.0D), e -> e != player && e.isAlive())
                .stream().findFirst().orElse(null);

        if (target == null) {
            ctx.getSource().sendFailure(Component.literal("附近 12 格内没有可以下刀的目标。"));
            return 0;
        }

        SukunaCleave.perform(player, target);

        ctx.getSource().sendSuccess(() -> Component.literal(
                "「连斩」—— 对着「" + target.getDisplayName().getString() + "」甩出了一套。"), false);

        return 1;
    }

    /**
     * `/mahoraga domain` —— 直接展开一次「停滞领域」。
     *
     * <p>它平时是**从独特破解池里随机抽**出来的，抽不到就看不到效果 ——
     * 所以留个口子：验证 / 调音效 / 拍视频的时候直接用，不用先赌运气。
     */
    private static int domain(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();

        VoidDomain.open(player);

        ctx.getSource().sendSuccess(() -> Component.literal(
                "领域展开 —— 停滞领域（半径 10 格 · 持续 8 秒）。"), false);

        return 1;
    }

    /**
     * `/mahoraga ceremony` —— 重播一次"第一次戴上法轮"的庆祝动画。
     *
     * 老存档里法轮早就有了，永远走不到自然触发那条路，所以留个口子：
     * 想反复调音效/烟花/黑暗时长就靠它。
     */
    private static int ceremony(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();

        WheelHandler.spin(player);
        WheelCeremony.play(player);

        ctx.getSource().sendSuccess(() -> Component.translatable("command.mahoraga.ceremony")
                .withStyle(ChatFormatting.GOLD), false);

        return 1;
    }
}
