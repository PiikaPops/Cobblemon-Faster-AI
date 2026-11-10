package fr.coquelicot.fasterai.mixin;

import fr.coquelicot.fasterai.ai.BrainGates;
import fr.coquelicot.fasterai.config.FasterAIConfig;
import fr.coquelicot.fasterai.perf.FastPathStats;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.memory.ExpirableValue;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.sensing.Sensor;
import net.minecraft.world.entity.ai.sensing.SensorType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.Optional;

/**
 * Two parts of Brain.tick that run for every mob every tick and almost always end up doing nothing:
 *
 * 1. startEachNonRunningBehavior asks each task of the active activities to start. A task whose memory conditions do
 *    not hold refuses at once; it is skipped until a memory of this brain appears or disappears (see BrainGates).
 * 2. tickSensors counts every sensor down by one, and a sensor only scans when its countdown ends (every 20 ticks for
 *    most). The number of ticks until the first sensor is due is known after each pass: those ticks are skipped whole,
 *    and the countdowns are brought up to date before the next real pass, so every sensor scans on the same tick as
 *    without the mod.
 *
 * Applies to every mob with a brain (Pokémon, villagers, piglins...).
 *
 * Priority above the default: Lithium replaces startEachNonRunningBehavior, and the hook on its getStatus() call may
 * only be applied after that replacement.
 */
@Mixin(value = Brain.class, priority = 1500)
public abstract class BrainMixin<E extends LivingEntity> {
    @Shadow @Final private Map<MemoryModuleType<?>, Optional<? extends ExpirableValue<?>>> memories;
    @Shadow @Final private Map<SensorType<? extends Sensor<? super E>>, Sensor<? super E>> sensors;

    // ------------------------------------------------------------------ task gates

    /** Moves whenever a memory appears, disappears or gets registered: everything a task gate depends on. */
    @Unique private long fasterai$memoryVersion;
    @Unique private int fasterai$memoryCount = -1;
    @Unique private BrainGates fasterai$gates;
    @Unique private boolean fasterai$gating;

    @Inject(method = "setMemoryInternal", at = @At("HEAD"))
    private void fasterai$onSetMemory(MemoryModuleType<?> type, Optional<? extends ExpirableValue<?>> value, CallbackInfo ci) {
        Optional<?> old = memories.get(type);
        if (old != null && old.isPresent() != value.isPresent()) fasterai$memoryVersion++;
        if (fasterai$expiring != null && value.isPresent() && value.get().canExpire()) fasterai$addExpiring(type);
    }

    // ------------------------------------------------------------------ memory expiry

    /**
     * forgetOutdatedMemories walks every memory of the brain, every tick, to count down the few that expire. This is
     * the list of memory types that may hold an expiring value: every type given one through setMemoryInternal since
     * the list was built from a full walk. null = not built yet. Types whose value is gone or no longer expires are
     * dropped when met. Walking this list does to the same values what Minecraft's walk does; the others would have
     * been left untouched.
     */
    @Unique private MemoryModuleType<?>[] fasterai$expiring;
    @Unique private int fasterai$expiringCount;
    @Unique private int fasterai$expiringMemoryCount;

    @Shadow public abstract <U> void eraseMemory(MemoryModuleType<U> type);

    @Unique
    private void fasterai$addExpiring(MemoryModuleType<?> type) {
        MemoryModuleType<?>[] list = fasterai$expiring;
        int n = fasterai$expiringCount;
        for (int i = 0; i < n; i++) if (list[i] == type) return;
        if (n == list.length) fasterai$expiring = list = java.util.Arrays.copyOf(list, n * 2);
        list[n] = type;
        fasterai$expiringCount = n + 1;
    }

    @Inject(method = "forgetOutdatedMemories", at = @At("HEAD"), cancellable = true)
    private void fasterai$forgetExpiringOnly(CallbackInfo ci) {
        if (!FasterAIConfig.INSTANCE.memoryExpiryList()) {
            fasterai$expiring = null;
            return;
        }
        if (fasterai$expiring == null || memories.size() != fasterai$expiringMemoryCount) {
            // build the list from everything the brain holds now; Minecraft's walk runs this tick
            fasterai$expiring = new MemoryModuleType<?>[4];
            fasterai$expiringCount = 0;
            fasterai$expiringMemoryCount = memories.size();
            for (Map.Entry<MemoryModuleType<?>, Optional<? extends ExpirableValue<?>>> entry : memories.entrySet()) {
                if (entry.getValue().isPresent() && entry.getValue().get().canExpire()) fasterai$addExpiring(entry.getKey());
            }
            return;
        }
        if (FastPathStats.verify) {                           // Minecraft's walk runs; the list must cover what it touches
            for (Map.Entry<MemoryModuleType<?>, Optional<? extends ExpirableValue<?>>> entry : memories.entrySet()) {
                if (entry.getValue().isEmpty() || !entry.getValue().get().canExpire()) continue;
                FastPathStats.expiryChecked++;
                boolean listed = false;
                for (int i = 0; i < fasterai$expiringCount && !listed; i++) listed = fasterai$expiring[i] == entry.getKey();
                if (!listed) {
                    FastPathStats.expiryMismatches++;
                    FastPathStats.mismatch("memory expiry list misses " + entry.getKey());
                    fasterai$expiring = null;
                    return;
                }
            }
            return;
        }
        FastPathStats.expiryPasses++;
        for (int i = 0; i < fasterai$expiringCount; ) {
            MemoryModuleType<?> type = fasterai$expiring[i];
            Optional<? extends ExpirableValue<?>> held = memories.get(type);
            if (held == null || held.isEmpty() || !held.get().canExpire()) {
                fasterai$expiring[i] = fasterai$expiring[--fasterai$expiringCount];
                fasterai$expiring[fasterai$expiringCount] = null;
                continue;
            }
            ExpirableValue<?> value = held.get();
            if (value.hasExpired()) eraseMemory(type);
            value.tick();
            i++;
        }
        ci.cancel();
    }

    @Inject(method = "clearMemories", at = @At("HEAD"))
    private void fasterai$onClearMemories(CallbackInfo ci) {
        fasterai$memoryVersion++;
    }

    @Inject(method = "startEachNonRunningBehavior", at = @At("HEAD"))
    private void fasterai$beginStartLoop(ServerLevel level, E entity, CallbackInfo ci) {
        fasterai$gating = FasterAIConfig.INSTANCE.brainTaskGates();
        if (!fasterai$gating) return;
        if (memories.size() != fasterai$memoryCount) {       // a memory was registered behind setMemoryInternal's back
            fasterai$memoryCount = memories.size();
            fasterai$memoryVersion++;
        }
        if (fasterai$gates == null) fasterai$gates = new BrainGates();
        fasterai$gates.beginLoop();
    }

    /** The loop is "if (task.getStatus() == STOPPED) task.tryStart(...)", in Minecraft's version and in Lithium's. */
    @Redirect(method = "startEachNonRunningBehavior", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/behavior/BehaviorControl;getStatus()Lnet/minecraft/world/entity/ai/behavior/Behavior$Status;"))
    private Behavior.Status fasterai$skipClosedGates(BehaviorControl<?> task, ServerLevel level, E entity) {
        if (!fasterai$gating) return task.getStatus();
        return fasterai$gates.status(task, (Brain<?>) (Object) this, entity, fasterai$memoryVersion);
    }

    @Inject(method = "startEachNonRunningBehavior", at = @At("RETURN"))
    private void fasterai$endStartLoop(ServerLevel level, E entity, CallbackInfo ci) {
        if (fasterai$gating) fasterai$gates.checkStayedStopped();
    }

    // ------------------------------------------------------------------ sensors

    /** Coming ticks in which no sensor is due. */
    @Unique private int fasterai$sensorIdle;
    /** Ticks skipped so far, not yet taken off the sensors' countdowns. */
    @Unique private int fasterai$sensorDebt;
    @Unique private int fasterai$sensorCount = -1;
    @Unique private boolean fasterai$sensorsRan;
    /** Verify mode: sum of the countdowns before a pass that should scan nothing, or Long.MIN_VALUE. */
    @Unique private long fasterai$idleSum = Long.MIN_VALUE;

    @Inject(method = "tickSensors", at = @At("HEAD"), cancellable = true)
    private void fasterai$skipIdleSensors(ServerLevel level, E entity, CallbackInfo ci) {
        boolean on = FasterAIConfig.INSTANCE.sensorSchedule();
        if (!on || sensors.size() != fasterai$sensorCount) {
            fasterai$settleSensors();
            fasterai$sensorIdle = 0;
            fasterai$sensorCount = on ? sensors.size() : -1;
            fasterai$sensorsRan = on;
            return;
        }
        if (fasterai$sensorIdle > 0) {
            fasterai$sensorIdle--;
            FastPathStats.sensorSkips++;
            if (FastPathStats.verify) {                       // let the pass run: no sensor may scan in it
                fasterai$settleSensors();
                long sum = 0;
                for (Sensor<? super E> sensor : sensors.values()) sum += ((SensorAccessor) sensor).fasterai$timeToTick();
                fasterai$idleSum = sum;
                return;
            }
            fasterai$sensorDebt++;
            ci.cancel();
            return;
        }
        FastPathStats.sensorPasses++;
        fasterai$settleSensors();
        fasterai$sensorsRan = true;
    }

    @Inject(method = "tickSensors", at = @At("RETURN"))
    private void fasterai$afterSensors(ServerLevel level, E entity, CallbackInfo ci) {
        if (fasterai$idleSum != Long.MIN_VALUE) {
            long sum = 0;
            for (Sensor<? super E> sensor : sensors.values()) sum += ((SensorAccessor) sensor).fasterai$timeToTick();
            FastPathStats.sensorChecked++;
            if (sum != fasterai$idleSum - sensors.size()) {   // a countdown was reset: a sensor scanned
                FastPathStats.sensorMismatches++;
                FastPathStats.mismatch("sensor schedule", entity);
                fasterai$sensorsRan = true;
            }
            fasterai$idleSum = Long.MIN_VALUE;
        }
        if (!fasterai$sensorsRan) return;
        fasterai$sensorsRan = false;
        long first = Long.MAX_VALUE;                          // ticks until the first sensor scans (1 = next tick)
        for (Sensor<? super E> sensor : sensors.values()) {
            first = Math.min(first, Math.max(1L, ((SensorAccessor) sensor).fasterai$timeToTick()));
        }
        fasterai$sensorIdle = first == Long.MAX_VALUE ? 0 : (int) Math.min(first - 1, 1 << 20);
    }

    /** Takes the skipped ticks off every sensor's countdown, as their tick() would have done one by one. */
    @Unique
    private void fasterai$settleSensors() {
        int debt = fasterai$sensorDebt;
        if (debt == 0) return;
        fasterai$sensorDebt = 0;
        for (Sensor<? super E> sensor : sensors.values()) {
            SensorAccessor countdown = (SensorAccessor) sensor;
            countdown.fasterai$setTimeToTick(countdown.fasterai$timeToTick() - debt);
        }
    }
}
