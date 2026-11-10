package fr.coquelicot.fasterai.bench;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.cooking.PokeSnackSpawnPokemonEvent;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import fr.coquelicot.fasterai.CobblemonFasterAI;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static fr.coquelicot.fasterai.command.FasterAICommands.reply;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * Benchmark build only (gradlew build -Pbench):
 * /fasterai bench crowd &lt;sites&gt; &lt;perSite&gt; &lt;hotspots&gt; &lt;hotspotSize&gt; &lt;ticks&gt; &lt;block&gt; &lt;seed&gt; &lt;label&gt;
 * /fasterai bench snacks &lt;perSite&gt; &lt;speed&gt; &lt;keep|farm&gt; &lt;baits|none&gt; (or "snacks off"): snack fields for the next crowd runs
 * /fasterai bench mode alternate|on|off, /fasterai bench status|cancel
 * It force-loads chunks, builds barrier boxes and snacks, spawns and removes Pokémon: test worlds only.
 */
public final class BenchInit implements ModInitializer {
    @Override
    public void onInitialize() {
        ServerTickEvents.START_SERVER_TICK.register(s -> CrowdBench.tickStart = System.nanoTime());
        ServerTickEvents.END_SERVER_TICK.register(CrowdBench::onEndTick);
        CobblemonEvents.POKE_SNACK_SPAWN_POKEMON_POST.subscribe(Priority.LOWEST, (Consumer<PokeSnackSpawnPokemonEvent.Post>) e -> {
            CrowdBench b = CrowdBench.current;
            if (b != null) b.onSnackSpawn(e.getPokemonEntity(), e.getPokeSnackBlockEntity());
        });
        CommandRegistrationCallback.EVENT.register((d, r, e) -> d.register(literal("fasterai").requires(s -> s.hasPermission(3))
                .then(literal("bench")
                        .then(literal("status").executes(c -> reply(c, CrowdBench.status())))
                        .then(literal("mode")
                                .then(literal("alternate").executes(c -> { CrowdBench.fixedMode = null; return reply(c, "Bench mode: alternate"); }))
                                .then(literal("on").executes(c -> { CrowdBench.fixedMode = true; return reply(c, "Bench mode: always ON"); }))
                                .then(literal("off").executes(c -> { CrowdBench.fixedMode = false; return reply(c, "Bench mode: always OFF"); })))
                        .then(literal("players").then(argument("extraPerSite", IntegerArgumentType.integer(0, 200)).executes(c -> {
                            CrowdBench.extraPlayersPerSite = IntegerArgumentType.getInteger(c, "extraPerSite");
                            return reply(c, "Fake players per site: 1 + " + CrowdBench.extraPlayersPerSite);
                        })))
                        .then(literal("toggle")
                                .then(literal("all").executes(c -> { CrowdBench.toggled = List.of(); return reply(c, "Bench alternates the whole mod"); }))
                                .then(argument("switches", StringArgumentType.greedyString()).executes(c -> {
                                    List<String> names = new ArrayList<>();
                                    for (String s : StringArgumentType.getString(c, "switches").split("[ ,]+")) {
                                        try {
                                            if (fr.coquelicot.fasterai.config.FasterAIConfig.class.getField(s).getType() != boolean.class) throw new NoSuchFieldException();
                                        } catch (NoSuchFieldException notASwitch) {
                                            return reply(c, "Not a config switch: " + s);
                                        }
                                        names.add(s);
                                    }
                                    CrowdBench.toggled = List.copyOf(names);
                                    return reply(c, "Bench alternates only " + names + " (the rest of the mod stays on)");
                                })))
                        .then(literal("snacks")
                                .then(literal("off").executes(c -> { CrowdBench.snacksPerSite = 0; return reply(c, "Snack fields: off"); }))
                                .then(literal("offset").then(argument("blocks", IntegerArgumentType.integer(40, 400)).executes(c -> {
                                    CrowdBench.snackOffset = IntegerArgumentType.getInteger(c, "blocks");
                                    return reply(c, "Snack fields " + CrowdBench.snackOffset + " blocks east of each site centre");
                                })))
                                .then(argument("perSite", IntegerArgumentType.integer(1, 256))
                                .then(argument("speed", IntegerArgumentType.integer(1, 1000))
                                .then(argument("spawned", StringArgumentType.word())
                                .then(argument("baits", StringArgumentType.greedyString()).executes(BenchInit::snacks))))))
                        .then(literal("cancel").executes(c -> { CrowdBench.cancel(); return reply(c, "Bench cancelled."); }))
                        .then(literal("crowd")
                                .then(argument("sites", IntegerArgumentType.integer(1, 16))
                                .then(argument("perSite", IntegerArgumentType.integer(0, 5000))
                                .then(argument("hotspots", IntegerArgumentType.integer(0, 4))
                                .then(argument("hotspotSize", IntegerArgumentType.integer(0, 1000))
                                .then(argument("ticks", IntegerArgumentType.integer(1, 1000000))
                                .then(argument("block", IntegerArgumentType.integer(1, 100000))
                                .then(argument("seed", LongArgumentType.longArg())
                                .then(argument("label", StringArgumentType.word()).executes(BenchInit::crowd)))))))))))));
        CobblemonFasterAI.LOGGER.warn("Benchmark build: /fasterai bench is available. It spawns and removes Pokémon and builds blocks: test worlds only.");
    }

    private static int snacks(CommandContext<CommandSourceStack> c) {
        String spawned = StringArgumentType.getString(c, "spawned");
        if (!spawned.equals("keep") && !spawned.equals("farm")) return reply(c, "spawned must be keep or farm");
        List<ResourceLocation> baits = new ArrayList<>();
        String raw = StringArgumentType.getString(c, "baits");
        if (!raw.equalsIgnoreCase("none")) {
            for (String s : raw.split(",")) {
                ResourceLocation id = ResourceLocation.tryParse(s.trim());
                if (id == null) return reply(c, "Bad bait id: " + s);
                baits.add(id);
            }
        }
        CrowdBench.snacksPerSite = IntegerArgumentType.getInteger(c, "perSite");
        CrowdBench.snackSpeed = IntegerArgumentType.getInteger(c, "speed");
        CrowdBench.snackFarm = spawned.equals("farm");
        CrowdBench.snackBaits = List.copyOf(baits);
        return reply(c, "Snack fields: " + CrowdBench.snacksPerSite + " per site, random ticks x" + CrowdBench.snackSpeed
                + ", attracted Pokémon " + (CrowdBench.snackFarm ? "removed at once (farm)" : "kept") + ", baits " + baits);
    }

    private static int crowd(CommandContext<CommandSourceStack> c) {
        CrowdBench b = new CrowdBench(c.getSource(), c.getSource().getLevel(),
                IntegerArgumentType.getInteger(c, "sites"), IntegerArgumentType.getInteger(c, "perSite"),
                IntegerArgumentType.getInteger(c, "hotspots"), IntegerArgumentType.getInteger(c, "hotspotSize"),
                IntegerArgumentType.getInteger(c, "ticks"), IntegerArgumentType.getInteger(c, "block"),
                LongArgumentType.getLong(c, "seed"), StringArgumentType.getString(c, "label"));
        return reply(c, CrowdBench.start(b) ? "Crowd bench started." : "A bench is already running: " + CrowdBench.status());
    }
}
