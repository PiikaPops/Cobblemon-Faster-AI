package fr.coquelicot.fasterai.ai;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import fr.coquelicot.fasterai.config.FasterAIConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.player.Player;

import java.util.List;
import java.util.Locale;

/**
 * Optional, off by default: the one part of this mod that changes what Pokémon do.
 *
 * A wild Pokémon far from every player only gets one tick out of N. It makes the same decisions and moves by the same
 * rules, in slow motion: nothing is approximated, time just passes slower for it. Its age for despawning still counts
 * in real ticks. Pokémon that are owned, in battle, busy, hurt, burning, ridden, leashed, falling or freshly spawned
 * always tick normally, and so does everything within the distance.
 *
 * By default it only engages while the server is overloaded: once a second the average tick time is compared with the
 * target; above it, the relief goes up one step (first only the farthest Pokémon at half speed, then closer ones,
 * then slower), and it goes back down when the server has room again. Without this, an overloaded server slows
 * everything for everyone; with it, the slowdown is put on the Pokémon nobody is next to.
 */
public final class FarSlowMotion {
    private FarSlowMotion() {}

    /** Distance steps (blocks) before the interval starts to grow. */
    private static final int[] DISTANCES = {96, 80, 64, 48, 40, 32, 24, 16};
    /** Ticks between two measures of a Pokémon's distance to the nearest player. */
    private static final int DISTANCE_REFRESH = 10;
    private static final int WINDOW = 20;

    // current relief, set once a second by the controller
    private static int interval = 1;
    private static float distanceSquared = Float.MAX_VALUE;
    private static int level;
    private static int distance;

    private static long tickStart, windowNanos;
    private static int windowTicks;
    private static double lastMspt;
    public static long skippedTicks, fullTicks;

    /** Implemented by PokemonEntity (mixin): squared distance to the nearest player, and when it was measured. */
    public interface State {
        float fasterai$nearestPlayerSquared();

        long fasterai$nearestPlayerTime();

        void fasterai$setNearestPlayer(float squared, long time);
    }

    public static void onStartTick() {
        tickStart = System.nanoTime();
    }

    public static void onEndTick() {
        FasterAIConfig config = FasterAIConfig.INSTANCE;
        if (!config.farSlowMotion()) {
            level = 0;
            interval = 1;
            windowTicks = 0;
            windowNanos = 0;
            return;
        }
        windowNanos += System.nanoTime() - tickStart;
        if (++windowTicks < WINDOW) return;
        lastMspt = windowNanos / 1e6 / windowTicks;
        windowNanos = 0;
        windowTicks = 0;

        int minDistance = Math.max(8, config.farSlowMotionMinDistance);
        int maxInterval = Math.max(2, Math.min(20, config.farSlowMotionMaxInterval));
        int distanceSteps = 1;                                   // the minimum distance itself
        for (int d : DISTANCES) if (d > minDistance) distanceSteps++;
        int maxLevel = distanceSteps + maxInterval - 2;

        if (!config.farSlowMotionOnlyWhenOverloaded) level = maxLevel;
        else if (lastMspt > config.farSlowMotionTargetMspt) level = Math.min(maxLevel, level + 1);
        else if (lastMspt < config.farSlowMotionTargetMspt * 0.85) level = Math.max(0, level - 1);
        if (level > maxLevel) level = maxLevel;

        if (level == 0) {
            interval = 1;
            return;
        }
        if (level < distanceSteps) {                             // farthest first, at half speed
            distance = DISTANCES[level - 1];
            interval = 2;
        } else {
            distance = minDistance;
            interval = 2 + (level - distanceSteps);
        }
        distanceSquared = (float) distance * distance;
    }

    /** True when this Pokémon sits this tick out. */
    public static boolean shouldSkip(ServerLevel world, PokemonEntity pokemon) {
        int n = interval;
        if (n <= 1) return false;
        long time = world.getGameTime();
        if ((time + pokemon.getId()) % n == 0 || !isFar(world, pokemon, time) || !mayBeSlowed(pokemon)) {
            fullTicks++;
            return false;
        }
        skippedTicks++;
        return true;
    }

    /** What still happens in a tick sat out: the Pokémon keeps ageing in real time (Cobblemon's despawner reads it). */
    public static void sitOut(PokemonEntity pokemon) {
        pokemon.setTicksLived(pokemon.getTicksLived() + 1);
    }

    private static boolean isFar(ServerLevel world, PokemonEntity pokemon, long time) {
        State state = (State) pokemon;
        if (time - state.fasterai$nearestPlayerTime() >= DISTANCE_REFRESH) {
            List<? extends Player> players = world.players();
            double x = pokemon.getX(), y = pokemon.getY(), z = pokemon.getZ();
            float nearest = Float.MAX_VALUE;
            for (int i = 0, count = players.size(); i < count; i++) {
                Player player = players.get(i);
                float dx = (float) (player.getX() - x), dy = (float) (player.getY() - y), dz = (float) (player.getZ() - z);
                float squared = dx * dx + dy * dy + dz * dz;
                if (squared < nearest) nearest = squared;
            }
            state.fasterai$setNearestPlayer(nearest, time);
        }
        return state.fasterai$nearestPlayerSquared() > distanceSquared;
    }

    private static boolean mayBeSlowed(PokemonEntity pokemon) {
        if (pokemon.getOwnerUUID() != null || !pokemon.getPokemon().isWild() || pokemon.getTethering() != null) return false;
        if (pokemon.getTicksLived() < 40 || !pokemon.isAlive() || pokemon.deathTime > 0) return false;
        if (pokemon.hurtTime > 0 || pokemon.getLastHurtByMob() != null || pokemon.isOnFire()) return false;
        if (pokemon.isBattling() || pokemon.isBusy() || pokemon.getBeamMode() != 0 || pokemon.getQueuedToDespawn()) return false;
        if (pokemon.isVehicle() || pokemon.isPassenger() || pokemon.isLeashed()) return false;
        if (pokemon.getBrain().hasMemoryValue(MemoryModuleType.ATTACK_TARGET)) return false;
        // falling: neither standing, swimming nor flying
        return pokemon.onGround() || pokemon.isInWater() || pokemon.isFlying();
    }

    public static String report() {
        FasterAIConfig config = FasterAIConfig.INSTANCE;
        if (!config.farSlowMotion()) return "far slow motion: off";
        String state = level == 0 ? "not engaged"
                : "level " + level + ": wild Pokémon beyond " + distance + " blocks tick 1 in " + interval;
        long total = skippedTicks + fullTicks;
        return String.format(Locale.ROOT, "far slow motion: %s, %s (%.1f ms per tick over the last second, target %.0f), ticks sat out: %d%s",
                config.farSlowMotionOnlyWhenOverloaded ? "when overloaded" : "always", state, lastMspt, config.farSlowMotionTargetMspt,
                skippedTicks, total == 0 ? "" : String.format(Locale.ROOT, " (%.1f%%)", 100.0 * skippedTicks / total));
    }

    public static void resetStats() {
        skippedTicks = fullTicks = 0;
    }
}
