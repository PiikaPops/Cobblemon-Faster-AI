package fr.coquelicot.fasterai.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import fr.coquelicot.fasterai.CobblemonFasterAI;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Server config, stored in config/cobblemon_faster_ai.json. */
public final class FasterAIConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("cobblemon_faster_ai.json");

    public static FasterAIConfig INSTANCE = new FasterAIConfig();

    /** Master switch: false = Cobblemon and Minecraft exactly as without the mod. */
    public boolean enabled = true;

    // ---- each switch below gives the same results as without the mod (checked by /fasterai verify)

    /** Herd leader search: same candidates and same choice, line of sight only checked when it decides. */
    public boolean herdLeaderSearch = true;

    /** Water and lava contact: answer "none" at once when the chunk sections around contain no fluid at all. */
    public boolean fluidSectionCheck = true;

    /** Fire and lava contact while moving: answer "none" at once when the sections around contain neither. */
    public boolean fireSectionCheck = true;

    /** Nearby-entity sensor: sort by distance computed once per entity instead of twice per comparison. */
    public boolean sensorSortKeys = true;

    /** Block under the feet (every tick on the ground): outer blocks skipped when the palette can't make them count. */
    public boolean supportingBlockScan = true;

    /** Suffocation check (isInWall): a plain loop over the 1-4 blocks at eye level instead of a stream. */
    public boolean inWallScan = true;

    /** Brain tasks whose memory conditions do not hold are not asked to start again until a memory changes. */
    public boolean brainTaskGates = true;

    /** Brain sensors: the ticks in which no sensor is due are skipped whole; every sensor still scans on the same tick. */
    public boolean sensorSchedule = true;

    /** Brain memories: only the memories that can expire are counted down each tick, not every memory. */
    public boolean memoryExpiryList = true;

    /** PokemonEntity.onGround(): the same checks without Cobblemon's nested lambdas. */
    public boolean flatOnGround = true;

    /** Pokemon.hasBlocksTraveledRequirement() (every tick): read in place instead of through three temporary lists. */
    public boolean travelRequirementScan = true;

    /** Pokémon move control: when it is waiting, the speed it would not use is not computed. */
    public boolean idleMoveControl = true;

    // ---- the switch below changes behaviour: off by default (see FarSlowMotion)

    /**
     * Wild Pokémon far from every player tick in slow motion (1 tick out of N) to relieve the server.
     * Never applies to owned, battling, busy, hurt or ridden Pokémon.
     */
    public boolean farSlowMotion = false;

    /** true: only while ticks take longer than the target, as much as needed. false: always, at the strongest setting. */
    public boolean farSlowMotionOnlyWhenOverloaded = true;

    /** Tick time (ms) above which the relief steps up; it steps down below 85 % of it. 50 ms is the limit for 20 TPS. */
    public double farSlowMotionTargetMspt = 45.0;

    /** Pokémon closer than this (blocks) to a player are never slowed. */
    public int farSlowMotionMinDistance = 32;

    /** Slowest allowed: 1 tick out of this many (2 to 20). */
    public int farSlowMotionMaxInterval = 4;

    public static void load() {
        try {
            if (Files.exists(PATH)) {
                FasterAIConfig read = GSON.fromJson(Files.readString(PATH), FasterAIConfig.class);
                if (read != null) INSTANCE = read;
            }
            save();
        } catch (Exception e) {
            CobblemonFasterAI.LOGGER.error("Could not read {}, using defaults", PATH, e);
        }
    }

    public static void save() {
        try {
            Files.writeString(PATH, GSON.toJson(INSTANCE));
        } catch (IOException e) {
            CobblemonFasterAI.LOGGER.error("Could not write {}", PATH, e);
        }
    }

    public boolean herdLeaderSearch() { return enabled && herdLeaderSearch; }
    public boolean fluidSectionCheck() { return enabled && fluidSectionCheck; }
    public boolean fireSectionCheck() { return enabled && fireSectionCheck; }
    public boolean sensorSortKeys() { return enabled && sensorSortKeys; }
    public boolean supportingBlockScan() { return enabled && supportingBlockScan; }
    public boolean inWallScan() { return enabled && inWallScan; }
    public boolean brainTaskGates() { return enabled && brainTaskGates; }
    public boolean sensorSchedule() { return enabled && sensorSchedule; }
    public boolean memoryExpiryList() { return enabled && memoryExpiryList; }
    public boolean flatOnGround() { return enabled && flatOnGround; }
    public boolean travelRequirementScan() { return enabled && travelRequirementScan; }
    public boolean idleMoveControl() { return enabled && idleMoveControl; }
    public boolean farSlowMotion() { return enabled && farSlowMotion; }
}
