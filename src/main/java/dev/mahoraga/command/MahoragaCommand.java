package dev.mahoraga.command;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.mahoraga.data.AdaptData;
import dev.mahoraga.event.ModEvents;
import dev.mahoraga.registry.ModAttachments;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public class MahoragaCommand {
    public static void register(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("mahoraga")
                .executes(c -> status(c.getSource()))
                .then(Commands.literal("reset").executes(c -> reset(c.getSource())))
                .then(Commands.literal("set").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("track", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(new String[]{"defense", "attack"}, b))
                                .then(Commands.argument("key", StringArgumentType.string())
                                        .then(Commands.argument("percent", FloatArgumentType.floatArg(0F, 1000F))
                                                .executes(MahoragaCommand::set))))));
    }

    private static int status(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        AdaptData d = p.getData(ModAttachments.ADAPT);
        src.sendSuccess(() -> Component.translatable("command.mahoraga.header"), false);
        if (!d.wheel) {
            src.sendSuccess(() -> Component.translatable("command.mahoraga.noWheel"), false);
            return 0;
        }
        if (d.defense.isEmpty() && d.attack.isEmpty()) {
            src.sendSuccess(() -> Component.translatable("command.mahoraga.empty"), false);
            return 1;
        }
        dump(src, "defense", d.defense);
        dump(src, "attack", d.attack);
        return 1;
    }

    private static void dump(CommandSourceStack src, String track, Map<String, Float> map) {
        for (Map.Entry<String, Float> en : map.entrySet()) {
            Component line = Component.translatable("mahoraga.track." + track)
                    .append(": ").append(ModEvents.displayName(en.getKey()))
                    .append(String.format(" %.0f%%", en.getValue()));
            src.sendSuccess(() -> line, false);
        }
    }

    private static int reset(CommandSourceStack src) throws CommandSyntaxException {
        AdaptData d = src.getPlayerOrException().getData(ModAttachments.ADAPT);
        boolean had = !d.defense.isEmpty() || !d.attack.isEmpty();
        d.defense.clear();
        d.attack.clear();
        src.sendSuccess(() -> Component.translatable(had ? "command.mahoraga.reset" : "command.mahoraga.resetNothing"), false);
        return had ? 1 : 0;
    }

    private static int set(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        String track = StringArgumentType.getString(c, "track");
        if (!track.equals("defense") && !track.equals("attack")) {
            c.getSource().sendFailure(Component.translatable("command.mahoraga.set.badTrack"));
            return 0;
        }
        String key = StringArgumentType.getString(c, "key");
        float pct = FloatArgumentType.getFloat(c, "percent");
        AdaptData d = c.getSource().getPlayerOrException().getData(ModAttachments.ADAPT);
        d.track(track).put(key, pct);
        c.getSource().sendSuccess(() -> Component.literal("OK: " + track + " \"" + key + "\" -> " + pct + "%"), false);
        return 1;
    }
}
