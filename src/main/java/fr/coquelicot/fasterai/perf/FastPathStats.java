package fr.coquelicot.fasterai.perf;

import fr.coquelicot.fasterai.CobblemonFasterAI;
import net.minecraft.world.entity.Entity;

/**
 * How often each shortcut answers on its own ("hit") versus hands over to Minecraft's code, and, in verify mode, how
 * often its answer differed from the original code's. Plain counters, written from the server thread only; read by
 * /fasterai stats.
 */
public final class FastPathStats {
    private FastPathStats() {}

    /**
     * Verify mode (/fasterai verify on): each shortcut also runs the original code and compares; the original answer
     * is the one used. Slower than running without the mod: for checking, not for production.
     */
    public static boolean verify;

    public static long herdCalls, herdHits;
    public static long fluidCalls, fluidHits;
    public static long fireCalls, fireHits;
    public static long sortCalls, sortedEntities;
    public static long supportCalls, inWallCalls;
    public static long gateCalls, gateHits;
    public static long sensorPasses, sensorSkips;
    public static long expiryPasses, idleMoveControls;

    public static long herdChecked, herdMismatches;
    public static long paletteChecked, paletteMismatches;
    public static long fluidChecked, fluidMismatches;
    public static long fireChecked, fireMismatches;
    public static long sortChecked, sortMismatches;
    public static long supportChecked, supportMismatches;
    public static long inWallChecked, inWallMismatches;
    public static long gateChecked, gateMismatches;
    public static long sensorChecked, sensorMismatches;
    public static long expiryChecked, expiryMismatches;
    public static long onGroundChecked, onGroundMismatches;
    public static long travelChecked, travelMismatches;
    public static long idleMoveChecked, idleMoveMismatches;
    private static int mismatchLogs;

    public static void reset() {
        herdCalls = herdHits = fluidCalls = fluidHits = fireCalls = fireHits = 0;
        sortCalls = sortedEntities = supportCalls = inWallCalls = 0;
        gateCalls = gateHits = sensorPasses = sensorSkips = expiryPasses = idleMoveControls = 0;
        herdChecked = herdMismatches = paletteChecked = paletteMismatches = 0;
        fluidChecked = fluidMismatches = fireChecked = fireMismatches = sortChecked = sortMismatches = 0;
        supportChecked = supportMismatches = inWallChecked = inWallMismatches = 0;
        gateChecked = gateMismatches = sensorChecked = sensorMismatches = expiryChecked = expiryMismatches = 0;
        onGroundChecked = onGroundMismatches = travelChecked = travelMismatches = idleMoveChecked = idleMoveMismatches = 0;
        fr.coquelicot.fasterai.ai.FarSlowMotion.resetStats();
        mismatchLogs = 0;
    }

    /** Logs the first mismatches (at most 20 per reset), so a difference can be looked at. */
    public static void mismatch(String what, Entity entity) {
        if (mismatchLogs++ < 20) {
            CobblemonFasterAI.LOGGER.warn("[verify] {} differs for {} at {}", what, entity, entity.blockPosition());
        }
    }

    public static void mismatch(String what) {
        if (mismatchLogs++ < 20) CobblemonFasterAI.LOGGER.warn("[verify] {} differs", what);
    }

    public static String report() {
        String s = "herd leader: " + rate(herdHits, herdCalls)
                + "\nfluid check: " + rate(fluidHits, fluidCalls)
                + "\nfire/lava check: " + rate(fireHits, fireCalls)
                + "\nsupporting block: " + supportCalls + ", in wall: " + inWallCalls
                + "\nsensor sorts: " + sortCalls + " (" + (sortCalls == 0 ? 0 : sortedEntities / sortCalls) + " entities avg)"
                + "\nbrain tasks skipped (closed gate): " + rate(gateHits, gateCalls)
                + "\nsensor passes skipped: " + rate(sensorSkips, sensorSkips + sensorPasses)
                + "\nshort memory expiry passes: " + expiryPasses + ", waiting move controls: " + idleMoveControls;
        long checked = herdChecked + paletteChecked + fluidChecked + fireChecked + sortChecked + supportChecked + inWallChecked
                + gateChecked + sensorChecked + expiryChecked + onGroundChecked + travelChecked + idleMoveChecked;
        if (verify || checked > 0) {
            s += "\nverify mismatches: herd " + herdMismatches + "/" + herdChecked
                    + ", palette cache " + paletteMismatches + "/" + paletteChecked
                    + ", fluid " + fluidMismatches + "/" + fluidChecked
                    + ", fire " + fireMismatches + "/" + fireChecked
                    + ", sort " + sortMismatches + "/" + sortChecked
                    + ", supporting block " + supportMismatches + "/" + supportChecked
                    + ", in wall " + inWallMismatches + "/" + inWallChecked
                    + ", task gates " + gateMismatches + "/" + gateChecked
                    + ", sensor schedule " + sensorMismatches + "/" + sensorChecked
                    + ", memory expiry " + expiryMismatches + "/" + expiryChecked
                    + ", on ground " + onGroundMismatches + "/" + onGroundChecked
                    + ", travel requirement " + travelMismatches + "/" + travelChecked
                    + ", waiting move control " + idleMoveMismatches + "/" + idleMoveChecked;
        }
        return s + '\n' + fr.coquelicot.fasterai.ai.FarSlowMotion.report();
    }

    private static String rate(long hits, long calls) {
        return hits + "/" + calls + (calls == 0 ? "" : String.format(" (%.1f%%)", 100.0 * hits / calls));
    }
}
