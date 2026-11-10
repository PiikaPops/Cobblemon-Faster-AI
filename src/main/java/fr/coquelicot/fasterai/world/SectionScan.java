package fr.coquelicot.fasterai.world;

import fr.coquelicot.fasterai.CobblemonFasterAI;
import fr.coquelicot.fasterai.perf.FastPathStats;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.function.Predicate;

/** Palette-level answers to "can any block here match?", without reading the blocks themselves. */
public final class SectionScan {
    private SectionScan() {}

    /**
     * True when no chunk section touching the block box [min, max] (inclusive) has a state matching the predicate in its
     * palette, so no block of the box can match. False means "maybe": the caller runs Minecraft's own block loop.
     * The chunks must be loaded. {@code slot} names the predicate in the per-section cache (see {@link PaletteFlags}).
     */
    public static boolean noneCanMatch(Level level, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                       int slot, Predicate<BlockState> predicate) {
        int minSectionY = level.getMinSection(), maxSectionY = level.getMaxSection() - 1;
        int sy0 = Math.max(minSectionY, SectionPos.blockToSectionCoord(minY));
        int sy1 = Math.min(maxSectionY, SectionPos.blockToSectionCoord(maxY));
        if (sy0 > sy1) return true;
        int cx1 = SectionPos.blockToSectionCoord(maxX), cz1 = SectionPos.blockToSectionCoord(maxZ);
        for (int cx = SectionPos.blockToSectionCoord(minX); cx <= cx1; cx++) {
            for (int cz = SectionPos.blockToSectionCoord(minZ); cz <= cz1; cz++) {
                ChunkAccess chunk = level.getChunk(cx, cz);
                LevelChunkSection[] sections = chunk.getSections();
                for (int sy = sy0; sy <= sy1; sy++) {
                    LevelChunkSection section = sections[sy - minSectionY];
                    if (section.hasOnlyAir()) continue;
                    if (mayHave(section, slot, predicate)) return false;
                }
            }
        }
        return true;
    }

    /**
     * Whether the section's palette holds a state matching the predicate (palettes can hold states no longer placed,
     * so "true" means "maybe"). Cached per section and slot until the next write to it.
     */
    public static boolean mayHave(LevelChunkSection section, int slot, Predicate<BlockState> predicate) {
        PaletteFlags flags = (PaletteFlags) section.getStates();
        byte f = flags.fasterai$flag(slot);
        if (f == PaletteFlags.UNKNOWN) {
            f = section.maybeHas(predicate) ? PaletteFlags.MAYBE : PaletteFlags.NONE;
            flags.fasterai$setFlag(slot, f);
        } else if (FastPathStats.verify) {
            FastPathStats.paletteChecked++;
            byte fresh = section.maybeHas(predicate) ? PaletteFlags.MAYBE : PaletteFlags.NONE;
            if (fresh != f) {
                FastPathStats.paletteMismatches++;
                if (paletteLogs++ < 20) CobblemonFasterAI.LOGGER.warn("[verify] stale palette answer (slot {})", slot);
                f = fresh;
                flags.fasterai$setFlag(slot, f);
            }
        }
        return f == PaletteFlags.MAYBE;
    }

    private static int paletteLogs;
}
