package fr.coquelicot.fasterai.bench;

/** Time spent ticking Pokémon entities during the current server tick, measured only while a bench runs. */
public final class EntityTiming {
    public static boolean enabled;
    public static long tickNanos;
    public static int ticked;
    private static long start;
    /** Activity census of the Pokémon ticks (bench diagnostics). */
    public static long cTicks, cStill, cStillGround, cNavigating, cInFluid, cVelocityRest, cAsleep;
    public static double x0, y0, z0;
    private static int depth;

    private EntityTiming() {}

    public static void begin() {
        if (depth++ == 0) start = System.nanoTime();
    }

    public static void end() {
        if (--depth == 0) {
            tickNanos += System.nanoTime() - start;
            ticked++;
        }
        if (depth < 0) depth = 0;
    }

    public static void reset() { tickNanos = 0; ticked = 0; }
}
