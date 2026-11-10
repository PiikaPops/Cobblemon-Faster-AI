package fr.coquelicot.fasterai.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import fr.coquelicot.fasterai.ai.FarSlowMotion;
import fr.coquelicot.fasterai.config.FasterAIConfig;
import fr.coquelicot.fasterai.perf.FastPathStats;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * /fasterai optimize [true|false], verify [on|off], stats [reset],
 * slowmotion [off|overload|always|target &lt;ms&gt;|distance &lt;blocks&gt;|interval &lt;ticks&gt;] (permission level 3).
 */
public final class FasterAICommands {
    private FasterAICommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(literal("fasterai").requires(s -> s.hasPermission(3))
                .then(literal("optimize")
                        .executes(c -> reply(c, "Optimisations: " + FasterAIConfig.INSTANCE.enabled))
                        .then(argument("enabled", BoolArgumentType.bool()).executes(c -> {
                            FasterAIConfig.INSTANCE.enabled = BoolArgumentType.getBool(c, "enabled");
                            FasterAIConfig.save();
                            return reply(c, "Optimisations: " + FasterAIConfig.INSTANCE.enabled);
                        })))
                .then(literal("verify")
                        .executes(c -> reply(c, "Verify mode: " + (FastPathStats.verify ? "on" : "off")))
                        .then(literal("on").executes(c -> {
                            FastPathStats.reset();
                            FastPathStats.verify = true;
                            return reply(c, "Verify mode on: each shortcut is checked against the original code (slower). See /fasterai stats.");
                        }))
                        .then(literal("off").executes(c -> {
                            FastPathStats.verify = false;
                            return reply(c, "Verify mode off.\n" + FastPathStats.report());
                        })))
                .then(literal("slowmotion")
                        .executes(c -> reply(c, FarSlowMotion.report()))
                        .then(literal("off").executes(c -> slowMotion(c, false, true)))
                        .then(literal("overload").executes(c -> slowMotion(c, true, true)))
                        .then(literal("always").executes(c -> slowMotion(c, true, false)))
                        .then(literal("target").then(argument("ms", DoubleArgumentType.doubleArg(5, 200)).executes(c -> {
                            FasterAIConfig.INSTANCE.farSlowMotionTargetMspt = DoubleArgumentType.getDouble(c, "ms");
                            FasterAIConfig.save();
                            return reply(c, "Far slow motion steps up above " + FasterAIConfig.INSTANCE.farSlowMotionTargetMspt + " ms per tick");
                        })))
                        .then(literal("distance").then(argument("blocks", IntegerArgumentType.integer(8, 256)).executes(c -> {
                            FasterAIConfig.INSTANCE.farSlowMotionMinDistance = IntegerArgumentType.getInteger(c, "blocks");
                            FasterAIConfig.save();
                            return reply(c, "Far slow motion never applies within " + FasterAIConfig.INSTANCE.farSlowMotionMinDistance + " blocks of a player");
                        })))
                        .then(literal("interval").then(argument("ticks", IntegerArgumentType.integer(2, 20)).executes(c -> {
                            FasterAIConfig.INSTANCE.farSlowMotionMaxInterval = IntegerArgumentType.getInteger(c, "ticks");
                            FasterAIConfig.save();
                            return reply(c, "Far slow motion goes down to 1 tick in " + FasterAIConfig.INSTANCE.farSlowMotionMaxInterval + " at most");
                        }))))
                .then(literal("stats")
                        .executes(c -> reply(c, FastPathStats.report()))
                        .then(literal("reset").executes(c -> { FastPathStats.reset(); return reply(c, "Stats reset."); }))));
    }

    private static int slowMotion(CommandContext<CommandSourceStack> c, boolean on, boolean onlyWhenOverloaded) {
        FasterAIConfig.INSTANCE.farSlowMotion = on;
        FasterAIConfig.INSTANCE.farSlowMotionOnlyWhenOverloaded = onlyWhenOverloaded;
        FasterAIConfig.save();
        return reply(c, !on ? "Far slow motion: off (every Pokémon ticks every tick)"
                : onlyWhenOverloaded ? "Far slow motion: only while the server is overloaded (target " + FasterAIConfig.INSTANCE.farSlowMotionTargetMspt + " ms per tick)"
                : "Far slow motion: always (wild Pokémon beyond " + FasterAIConfig.INSTANCE.farSlowMotionMinDistance + " blocks tick 1 in "
                + FasterAIConfig.INSTANCE.farSlowMotionMaxInterval + ")");
    }

    public static int reply(CommandContext<CommandSourceStack> c, String msg) {
        c.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }
}
