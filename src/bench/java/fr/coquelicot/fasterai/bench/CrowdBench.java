package fr.coquelicot.fasterai.bench;

import com.cobblemon.mod.common.CobblemonBlocks;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.block.PokeSnackBlock;
import com.cobblemon.mod.common.block.entity.PokeSnackBlockEntity;
import com.cobblemon.mod.common.item.components.BaitEffectsComponent;
import com.cobblemon.mod.common.pokemon.Species;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import fr.coquelicot.fasterai.CobblemonFasterAI;
import fr.coquelicot.fasterai.config.FasterAIConfig;
import fr.coquelicot.fasterai.perf.FastPathStats;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.nio.file.Path;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * A crowd of wild Pokémon spread over several sites, each with a fake player in its centre and its chunks force-loaded,
 * plus "hotspot" chunks closed by barrier walls and a roof, holding a dense crowd (100+ Pokémon per chunk).
 * After a warm-up, the optimisations are switched off and on every `block` ticks within the same run, and the whole
 * server tick and the Pokémon ticks are timed per mode.
 *
 * Optional snack fields (/fasterai bench snacks): seasoned Poké Snacks on the natural ground of each site, their
 * random ticks emulated at a chosen speed (fake players do not trigger random ticks), refilled after each bite.
 * When Cobblemon Faster Snacks is installed, its switches follow the same OFF/ON alternation (read by reflection,
 * so this bench does not depend on it).
 */
public final class CrowdBench {
    static CrowdBench current;
    static long tickStart;

    static final int RADIUS_CHUNKS = 5, SPAWN_RADIUS = 56, SITE_SPACING = 1024, WARMUP = 400, SPAWN_PER_TICK = 150;
    static final int[][] HOTSPOT_OFFSETS = {{2, 2}, {-2, -2}, {2, -2}, {-2, 2}};
    static final Set<String> EXCLUDED_LABELS = Set.of("legendary", "mythical", "ultra_beast", "paradox");

    // snack fields, set by /fasterai bench snacks before a crowd run
    static int snacksPerSite = 0, snackSpeed = 20;
    /** Extra fake players per site, next to the first one (/fasterai bench players). */
    static int extraPlayersPerSite = 0;
    /** Distance (blocks, east) from the site centre to its snack field; beyond the crowd it gets its own loaded chunks. */
    static int snackOffset = 60;
    static boolean snackFarm = false;
    static List<ResourceLocation> snackBaits = List.of();

    final CommandSourceStack src;
    final ServerLevel level;
    final int sites, perSite, hotspots, hotspotSize, ticks, block;
    final long seed;
    final String label;
    final Random rng;

    final List<BlockPos> centres = new ArrayList<>();
    final List<ChunkPos> chunks = new ArrayList<>();
    final List<ServerPlayer> fakePlayers = new ArrayList<>();
    final List<PokemonEntity> spawned = new ArrayList<>();
    final List<int[]> jobs = new ArrayList<>();
    final List<long[]> hotspotBoxes = new ArrayList<>();   // minX, minZ (chunk-aligned)
    List<Species> species;
    int stage, tick, waited, nextJob;
    boolean modeOn, savedEnabled;

    final List<BlockPos> snacks = new ArrayList<>();
    final Set<BlockPos> snackSet = new HashSet<>();
    final List<PokemonEntity> snackSpawned = new ArrayList<>();
    final Histogram[] snackTime = {new Histogram(), new Histogram()};
    final long[] snackTicks = new long[2], snackSpawns = new long[2], deferred = new long[2];
    final double[] paddedNanos = new double[2];   // sum of max(50 ms, tick): the wall time the ticks took
    final int snackCountPerSite = snacksPerSite, speed = snackSpeed, offset = snackOffset;
    final boolean farm = snackFarm;
    final List<ResourceLocation> baits = snackBaits;
    Object snacksConfig;                            // FasterSnacksConfig.INSTANCE, if installed
    boolean savedSnacksOpt, savedSnacksEntities;

    final Histogram[] tickTime = {new Histogram(), new Histogram()};
    final Histogram[] pokemonTime = {new Histogram(), new Histogram()};
    final long[] pokemonTicks = new long[2];
    final JsonArray series = new JsonArray();
    /** Every measured tick, in order: [mode, game time, tick microseconds, Pokémon microseconds]. */
    final JsonArray perTick = new JsonArray();
    long wTick, wPokemon, wTicked;
    int wCount;

    CrowdBench(CommandSourceStack src, ServerLevel level, int sites, int perSite, int hotspots, int hotspotSize, int ticks, int block,
               long seed, String label) {
        this.src = src; this.level = level; this.sites = sites; this.perSite = perSite; this.hotspots = Math.min(hotspots, HOTSPOT_OFFSETS.length);
        this.hotspotSize = hotspotSize; this.ticks = ticks; this.block = block; this.seed = seed; this.label = label;
        this.rng = new Random(seed);
    }

    // ------------------------------------------------------------------ driver

    static boolean start(CrowdBench b) {
        if (current != null) return false;
        current = b;
        return true;
    }

    static String status() { return current == null ? "idle" : current.statusLine(); }

    static void cancel() {
        if (current != null) { current.cleanup(); current = null; }
    }

    static void onEndTick(MinecraftServer server) {
        if (current == null) return;
        try {
            if (current.step()) current = null;
        } catch (Throwable t) {
            CobblemonFasterAI.LOGGER.error("[bench] crowd bench failed", t);
            try { current.cleanup(); } catch (Throwable ignored) {}
            current = null;
        }
    }

    String statusLine() {
        return "crowd '" + label + "' stage " + stage + " tick " + tick + "/" + ticks + ", " + spawned.size() + " Pokémon";
    }

    void send(String msg) {
        CobblemonFasterAI.LOGGER.info("[bench] {}", msg);
        if (src != null) src.sendSystemMessage(Component.literal(msg));
    }

    /** @return true when finished */
    boolean step() {
        switch (stage) {
            case 0 -> {
                for (int i = 0; i < sites; i++) {
                    BlockPos c = new BlockPos((i % 4) * SITE_SPACING + 8, 0, (i / 4) * SITE_SPACING + 8);
                    centres.add(c);
                    ChunkPos cc = new ChunkPos(c);
                    for (int dx = -RADIUS_CHUNKS; dx <= RADIUS_CHUNKS; dx++)
                        for (int dz = -RADIUS_CHUNKS; dz <= RADIUS_CHUNKS; dz++) chunks.add(new ChunkPos(cc.x + dx, cc.z + dz));
                }
                if (snacksPerSite > 0 && snackOffset > RADIUS_CHUNKS * 16 - 32) {
                    for (BlockPos c : centres) {
                        ChunkPos fc = new ChunkPos(new BlockPos(c.getX() + snackOffset + 8, 0, c.getZ()));
                        for (int dx = -2; dx <= 3; dx++) for (int dz = -2; dz <= 2; dz++) {
                            ChunkPos k = new ChunkPos(fc.x + dx, fc.z + dz);
                            if (!chunks.contains(k)) chunks.add(k);
                        }
                    }
                }
                for (ChunkPos c : chunks) level.setChunkForced(c.x, c.z, true);
                send("crowd '" + label + "': loading " + chunks.size() + " chunks for " + sites + " sites");
                stage = 1;
            }
            case 1 -> {
                boolean loaded = chunks.stream().allMatch(c -> level.areEntitiesLoaded(c.toLong()) && level.getChunkSource().getChunkNow(c.x, c.z) != null);
                if (++waited % 200 == 0) send("crowd '" + label + "': still loading chunks (" + waited + " ticks)");
                if (loaded || waited > 24000) { build(); stage = 2; }
            }
            case 2 -> {
                int end = Math.min(jobs.size(), nextJob + SPAWN_PER_TICK);
                for (; nextJob < end; nextJob++) spawn(jobs.get(nextJob));
                if (nextJob >= jobs.size()) {
                    send("crowd '" + label + "': " + spawned.size() + " Pokémon spawned, warming up " + WARMUP + " ticks");
                    stage = 3; tick = 0;
                }
            }
            case 3 -> {
                tickSnacks();
                if (++tick >= WARMUP) {
                    savedEnabled = FasterAIConfig.INSTANCE.enabled;
                    snacksConfig = fasterSnacksConfig();
                    if (snacksConfig != null) {
                        savedSnacksOpt = getBool(snacksConfig, "optimizeSnacks");
                        savedSnacksEntities = getBool(snacksConfig, "optimizeEntities");
                    }
                    setMode(false);
                    EntityTiming.reset();
                    EntityTiming.cTicks = EntityTiming.cStill = EntityTiming.cStillGround = EntityTiming.cNavigating = 0;
                    EntityTiming.cInFluid = EntityTiming.cVelocityRest = EntityTiming.cAsleep = 0;
                    EntityTiming.enabled = true;
                    FastPathStats.reset();
                    stage = 4; tick = 0;
                    send("crowd '" + label + "': measuring " + ticks + " ticks, mode switched every " + block + " ticks");
                }
            }
            case 4 -> {
                int m0 = modeOn ? 1 : 0;
                long deferredBefore = snacksDeferred();
                long sn = tickSnacks();
                snackTime[m0].add(sn);
                deferred[m0] += snacksDeferred() - deferredBefore;
                long tickNanos = System.nanoTime() - tickStart;
                paddedNanos[m0] += Math.max(50_000_000L, tickNanos);
                long pk = EntityTiming.tickNanos;
                int n = EntityTiming.ticked;
                EntityTiming.reset();
                int m = modeOn ? 1 : 0;
                tickTime[m].add(tickNanos);
                pokemonTime[m].add(pk);
                pokemonTicks[m] += n;
                wTick += tickNanos; wPokemon += pk; wTicked += n; wCount++;
                JsonArray row = new JsonArray();
                row.add(m); row.add(level.getGameTime()); row.add(tickNanos / 1000); row.add(pk / 1000);
                perTick.add(row);
                if ((tick + 1) % block == 0) { flushWindow(m); setMode(!modeOn); }
                if (++tick >= ticks) { finish(); return true; }
            }
            default -> { return true; }
        }
        return false;
    }

    /** null: alternate OFF/ON every block; TRUE/FALSE: stay in that mode for the whole measure (for profiling). */
    static Boolean fixedMode = null;

    /**
     * Config switches the OFF/ON alternation acts on; empty = the master switch (whole mod off, then on).
     * With names here, the rest of the mod stays on and only these switches are turned off in OFF blocks:
     * it measures what they add on top of the others.
     */
    static List<String> toggled = List.of();

    void setMode(boolean on) {
        if (fixedMode != null) on = fixedMode;
        modeOn = on;
        if (toggled.isEmpty()) {
            FasterAIConfig.INSTANCE.enabled = on;
        } else {
            FasterAIConfig.INSTANCE.enabled = true;
            for (String name : toggled) setBool(FasterAIConfig.INSTANCE, name, on);
        }
        if (snacksConfig != null && toggled.isEmpty()) {
            setBool(snacksConfig, "optimizeSnacks", on);
            setBool(snacksConfig, "optimizeEntities", on);
        }
    }

    // ------------------------------------------------------------------ Cobblemon Faster Snacks (optional)

    static Object fasterSnacksConfig() {
        try {
            return Class.forName("fr.coquelicot.fastersnacks.config.FasterSnacksConfig").getField("INSTANCE").get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean getBool(Object o, String field) {
        try { return o.getClass().getField(field).getBoolean(o); } catch (Throwable t) { return false; }
    }

    static void setBool(Object o, String field, boolean v) {
        try { o.getClass().getField(field).setBoolean(o, v); } catch (Throwable ignored) {}
    }

    /** Snack bites Faster Snacks has pushed to a later tick (its 10 ms lag guard), or 0. */
    static long snacksDeferred() {
        try {
            Field f = Class.forName("fr.coquelicot.fastersnacks.snack.SnackScheduler").getField("deferred");
            return ((Number) f.get(null)).longValue();
        } catch (Throwable t) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ snacks

    /** Emulated random ticks of the snack blocks (vanilla randomTickSpeed 3, times `speed`). @return nanos spent */
    long tickSnacks() {
        if (snacks.isEmpty()) return 0;
        int threshold = 3 * speed;
        long nanos = 0;
        for (BlockPos pos : snacks) {
            if (rng.nextInt(4096) >= threshold) continue;
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof PokeSnackBlock)) continue;
            long t0 = System.nanoTime();
            state.randomTick(level, pos, level.random);
            nanos += System.nanoTime() - t0;
            if (stage == 4) snackTicks[modeOn ? 1 : 0]++;
        }
        return nanos;
    }

    /** Called for every Pokémon a snack attracts (Cobblemon event). */
    void onSnackSpawn(PokemonEntity entity, PokeSnackBlockEntity be) {
        if (!snackSet.contains(be.getBlockPos())) return;
        if (stage == 4) snackSpawns[modeOn ? 1 : 0]++;
        if (farm) entity.discard();
        else snackSpawned.add(entity);
        be.setAmountSpawned(0);   // refill: the bench keeps the same snacks all along
        BlockState state = level.getBlockState(be.getBlockPos());
        if (state.getBlock() instanceof PokeSnackBlock) {
            level.setBlock(be.getBlockPos(), state.setValue(PokeSnackBlock.Companion.getBITES(), 0), 2);
        }
    }

    /** A grid of snacks on the natural ground, east of the site centre, outside the dispersed crowd. */
    void placeSnackField(BlockPos centre) {
        int side = (int) Math.ceil(Math.sqrt(snackCountPerSite));
        int placed = 0;
        for (int i = 0; i < side && placed < snackCountPerSite; i++) {
            for (int j = 0; j < side && placed < snackCountPerSite; j++) {
                int x = centre.getX() + offset + 4 * i, z = centre.getZ() - 2 * side + 4 * j;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos pos = new BlockPos(x, y, z);
                level.setBlock(pos, CobblemonBlocks.POKE_SNACK.defaultBlockState(), 3);
                if (level.getBlockEntity(pos) instanceof PokeSnackBlockEntity be && !baits.isEmpty()) {
                    be.setBaitEffectsComponent(new BaitEffectsComponent(baits));
                }
                snacks.add(pos);
                snackSet.add(pos);
                placed++;
            }
        }
    }

    // ------------------------------------------------------------------ scene

    void build() {
        species = PokemonSpecies.getImplemented().stream()
                .filter(s -> s.getLabels().stream().noneMatch(EXCLUDED_LABELS::contains)).toList();
        for (int i = 0; i < centres.size(); i++) {
            BlockPos c = centres.get(i);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c.getX(), c.getZ());
            FakePlayer fp = FakePlayer.get(level, new GameProfile(UUID.nameUUIDFromBytes(("fasterai-bench-" + i).getBytes()), "fab" + i));
            fp.setPos(c.getX() + 0.5, y, c.getZ() + 0.5);
            fakePlayers.add(fp);
            level.players().add(fp);
            // more players on the same spot: what a busy server costs in "distance to every player" loops
            for (int k = 0; k < extraPlayersPerSite; k++) {
                FakePlayer extra = FakePlayer.get(level, new GameProfile(UUID.nameUUIDFromBytes(("fasterai-bench-" + i + "-" + k).getBytes()), "fax" + i + "x" + k));
                extra.setPos(c.getX() + 0.5 + (k % 5) - 2, y, c.getZ() + 0.5 + (k / 5 % 5) - 2);
                fakePlayers.add(extra);
                level.players().add(extra);
            }
            ChunkPos cc = new ChunkPos(c);
            for (int h = 0; h < hotspots; h++) {
                ChunkPos hc = new ChunkPos(cc.x + HOTSPOT_OFFSETS[h][0], cc.z + HOTSPOT_OFFSETS[h][1]);
                int y0 = buildHotspot(hc);
                for (int k = 0; k < hotspotSize; k++)
                    jobs.add(new int[]{hc.getMinBlockX() + 1 + rng.nextInt(14), y0 + 1, hc.getMinBlockZ() + 1 + rng.nextInt(14)});
            }
            for (int k = 0; k < perSite; k++) {
                int x, z;
                do {
                    double a = rng.nextDouble() * Math.PI * 2, r = Math.sqrt(rng.nextDouble()) * SPAWN_RADIUS;
                    x = c.getX() + (int) Math.round(Math.cos(a) * r);
                    z = c.getZ() + (int) Math.round(Math.sin(a) * r);
                } while (inHotspot(x, z));
                jobs.add(new int[]{x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z});
            }
        }
        if (snackCountPerSite > 0) {
            for (int i = 0; i < centres.size(); i++) {
                BlockPos c = centres.get(i);
                placeSnackField(c);
                if (offset > RADIUS_CHUNKS * 16 - 32) {
                    // a player standing at the field, as on a real snack farm (snacks attract nothing without one near)
                    int fx = c.getX() + offset + 6, fz = c.getZ();
                    int fy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, fx, fz);
                    FakePlayer fp = FakePlayer.get(level, new GameProfile(UUID.nameUUIDFromBytes(("fasterai-snacks-" + i).getBytes()), "fas" + i));
                    fp.setPos(fx + 0.5, fy, fz + 0.5);
                    fakePlayers.add(fp);
                    level.players().add(fp);
                }
            }
        }
        send("crowd '" + label + "': " + species.size() + " species, " + jobs.size() + " Pokémon to spawn, " + hotspotBoxes.size() + " hotspot chunks, "
                + snacks.size() + " snacks (x" + speed + ", " + (farm ? "farm" : "keep") + ", baits " + baits + ")"
                + (fasterSnacksConfig() != null ? ", Faster Snacks installed: alternated with Faster AI" : ""));
    }

    /** Flat grass floor, barrier walls and roof over one chunk. @return the floor height */
    int buildHotspot(ChunkPos hc) {
        int x0 = hc.getMinBlockX(), z0 = hc.getMinBlockZ();
        int y0 = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x0 + 8, z0 + 8) - 1;
        for (int x = x0; x < x0 + 16; x++) for (int z = z0; z < z0 + 16; z++) {
            boolean edge = x == x0 || z == z0 || x == x0 + 15 || z == z0 + 15;
            level.setBlock(new BlockPos(x, y0, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
            for (int y = y0 + 1; y <= y0 + 9; y++) {
                boolean barrier = y == y0 + 9 || edge;
                level.setBlock(new BlockPos(x, y, z), barrier ? Blocks.BARRIER.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
            }
        }
        hotspotBoxes.add(new long[]{x0, z0});
        return y0;
    }

    boolean inHotspot(int x, int z) {
        for (long[] b : hotspotBoxes) if (x >= b[0] - 1 && x <= b[0] + 16 && z >= b[1] - 1 && z <= b[1] + 16) return true;
        return false;
    }

    void spawn(int[] job) {
        Species s = species.get(rng.nextInt(species.size()));
        int lvl = 5 + rng.nextInt(46);
        PokemonEntity e = PokemonProperties.Companion.parse("species=" + s.getResourceIdentifier() + " level=" + lvl, " ", "=").createEntity(level);
        e.moveTo(job[0] + 0.5, job[1], job[2] + 0.5, rng.nextFloat() * 360f, 0f);
        if (level.addFreshEntity(e)) spawned.add(e);
    }

    // ------------------------------------------------------------------ results

    void flushWindow(int mode) {
        JsonObject w = new JsonObject();
        w.addProperty("mode", mode);
        w.addProperty("tickMeanMs", r3(wTick / 1e6 / Math.max(1, wCount)));
        w.addProperty("pokemonUs", r3(wPokemon / 1e3 / Math.max(1, wTicked)));
        w.addProperty("pokemonTicked", wTicked / Math.max(1, wCount));
        w.addProperty("alive", alive());
        series.add(w);
        wTick = wPokemon = wTicked = 0;
        wCount = 0;
    }

    int alive() {
        int n = 0;
        for (PokemonEntity e : spawned) if (e.isAlive() && !e.isRemoved()) n++;
        for (PokemonEntity e : snackSpawned) if (e.isAlive() && !e.isRemoved()) n++;
        return n;
    }

    JsonObject modeJson(int m) {
        JsonObject o = new JsonObject();
        o.add("tickTime", tickTime[m].toJson());
        o.add("pokemonTimePerTick", pokemonTime[m].toJson());
        o.addProperty("pokemonTicks", pokemonTicks[m]);
        o.addProperty("usPerPokemonTick", pokemonTicks[m] == 0 ? 0 : r3(pokemonTime[m].totalNanos() / 1e3 / pokemonTicks[m]));
        o.addProperty("tps", tps(m));
        o.add("snackTimePerTick", snackTime[m].toJson());
        o.addProperty("snackRandomTicks", snackTicks[m]);
        o.addProperty("snackSpawns", snackSpawns[m]);
        o.addProperty("deferredBites", deferred[m]);
        return o;
    }

    void finish() {
        JsonObject o = new JsonObject();
        o.addProperty("type", "crowd");
        o.addProperty("label", label);
        o.addProperty("cobblemonVersion", CobblemonFasterAI.cobblemonVersion());
        o.addProperty("sites", sites);
        o.addProperty("perSite", perSite);
        o.addProperty("hotspotsPerSite", hotspots);
        o.addProperty("hotspotSize", hotspotSize);
        o.addProperty("ticks", ticks);
        o.addProperty("block", block);
        o.addProperty("seed", seed);
        o.addProperty("toggled", toggled.isEmpty() ? "all" : String.join(",", toggled));
        o.addProperty("spawned", spawned.size());
        o.addProperty("aliveAtEnd", alive());
        o.addProperty("snacks", snacks.size());
        o.addProperty("snackSpeed", speed);
        o.addProperty("snackFarm", farm);
        o.addProperty("snackBaits", baits.toString());
        o.addProperty("fasterSnacksAlternated", snacksConfig != null);
        o.add("off", modeJson(0));
        o.add("on", modeJson(1));
        o.add("series", series);
        o.add("perTick", perTick);
        Path file = BenchOutput.write("crowd", label, o);
        send("crowd '" + label + "' done: " + spawned.size() + " spawned, " + alive() + " alive -> " + file);
        send(String.format(Locale.US, "  OFF: tick %s | %.2f us/pokemon tick", tickTime[0].summary(), modeJson(0).get("usPerPokemonTick").getAsDouble()));
        send(String.format(Locale.US, "  ON:  tick %s | %.2f us/pokemon tick", tickTime[1].summary(), modeJson(1).get("usPerPokemonTick").getAsDouble()));
        for (int m = 0; m < 2; m++) {
            send(String.format(Locale.US, "  %s TPS %.2f | snacks %.2f ms/tick, %d random ticks, %d spawns, %d deferred bites",
                    m == 0 ? "OFF" : "ON: ", tps(m), snackTime[m].meanMs(), snackTicks[m], snackSpawns[m], deferred[m]));
        }
        for (String line : FastPathStats.report().split("\n")) send("  STATS " + line);
        brainCensus();
        long ct = Math.max(1, EntityTiming.cTicks);
        send(String.format(Locale.US, "  STATS census of %d Pokémon ticks: still %.1f%%, still on ground %.1f%%, at rest (no horizontal speed) %.1f%%, navigating %.1f%%, in fluid %.1f%%, asleep %.1f%%",
                EntityTiming.cTicks, 100.0 * EntityTiming.cStill / ct, 100.0 * EntityTiming.cStillGround / ct, 100.0 * EntityTiming.cVelocityRest / ct,
                100.0 * EntityTiming.cNavigating / ct, 100.0 * EntityTiming.cInFluid / ct, 100.0 * EntityTiming.cAsleep / ct));
        cleanup();
    }

    /** Which tasks and sensors the spawned Pokémon carry, and how many are in an active activity (log only). */
    void brainCensus() {
        java.util.Map<String, int[]> tasks = new java.util.TreeMap<>();
        java.util.Map<String, Integer> sensors = new java.util.TreeMap<>();
        long total = 0, active = 0;
        int n = 0;
        for (PokemonEntity e : spawned) {
            if (!e.isAlive() || e.isRemoved()) continue;
            n++;
            var brain = e.getBrain();
            var acc = (fr.coquelicot.fasterai.bench.mixin.BrainTasksAccessor) brain;
            for (var byActivity : acc.fasterai$tasks().values()) {
                for (var entry : byActivity.entrySet()) {
                    boolean isActive = brain.isActive(entry.getKey());
                    for (var task : entry.getValue()) {
                        Object inner = task;
                        String prefix = "";
                        if (inner instanceof com.cobblemon.mod.common.api.ai.WrapperLivingEntityTask<?> w) { inner = w.getTask(); prefix = "W:"; }
                        String cls = inner.getClass().getName();
                        String name = prefix + cls.substring(cls.lastIndexOf('.') + 1) + " " + inner.toString().replaceAll("@[0-9a-f]+", "").replaceAll("Lambda/0x[0-9a-f]+", "L");
                        if (name.length() > 230) name = name.substring(0, 230);
                        int[] c = tasks.computeIfAbsent(entry.getKey().getName() + " | " + name, k -> new int[2]);
                        c[0]++; total++;
                        if (isActive) { c[1]++; active++; }
                    }
                }
            }
            for (var s : acc.fasterai$sensors().values()) {
                String cls = s.getClass().getName();
                sensors.merge(cls.substring(cls.lastIndexOf('.') + 1), 1, Integer::sum);
            }
        }
        if (n == 0) return;
        CobblemonFasterAI.LOGGER.info("[bench] BRAIN census over {} Pokémon: {} tasks each on average, {} in an active activity", n, total / n, active / n);
        for (var en : tasks.entrySet()) CobblemonFasterAI.LOGGER.info("[bench] BRAIN task {} x{} (active {})", en.getKey(), en.getValue()[0], en.getValue()[1]);
        for (var en : sensors.entrySet()) CobblemonFasterAI.LOGGER.info("[bench] BRAIN sensor {} x{}", en.getKey(), en.getValue());
    }

    void cleanup() {
        EntityTiming.enabled = false;
        if (stage >= 4) {
            FasterAIConfig.INSTANCE.enabled = savedEnabled;
            for (String name : toggled) setBool(FasterAIConfig.INSTANCE, name, true);
            if (snacksConfig != null) {
                setBool(snacksConfig, "optimizeSnacks", savedSnacksOpt);
                setBool(snacksConfig, "optimizeEntities", savedSnacksEntities);
            }
        }
        for (PokemonEntity e : snackSpawned) if (!e.isRemoved()) e.discard();
        for (BlockPos p : snacks) level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
        for (PokemonEntity e : spawned) if (!e.isRemoved()) e.discard();
        for (ServerPlayer p : fakePlayers) level.players().remove(p);
        for (ChunkPos c : chunks) level.setChunkForced(c.x, c.z, false);
        stage = 99;
    }

    /** Ticks per second the server kept up in this mode: ticks over 50 ms run late, shorter ones wait. */
    double tps(int m) {
        return paddedNanos[m] == 0 ? 0 : r3(tickTime[m].count() * 1e9 / paddedNanos[m]);
    }

    static double r3(double v) { return Math.round(v * 1000.0) / 1000.0; }
}
