package fr.coquelicot.fasterai.ai;

import fr.coquelicot.fasterai.perf.FastPathStats;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;

import java.util.Arrays;
import java.util.IdentityHashMap;

/**
 * Per-brain memo for Brain.startEachNonRunningBehavior, which asks every task of the active activities to start, every
 * tick. Most of them answer "no" from their memory conditions alone (see {@link TaskGates}). For each position in that
 * loop this keeps the task seen there, its gate, and the brain's memory version at which the gate was last found
 * closed. As long as the version has not moved, the gate is still closed and the task is skipped without being
 * touched: asking it would have changed nothing.
 *
 * The memory version moves whenever a memory appears, disappears or gets registered (BrainMixin), which is all a gate
 * depends on.
 */
public final class BrainGates {
    private static final TaskGates.Gate NO_GATE = new TaskGates.Gate(java.util.List.of(), java.util.List.of());

    private BehaviorControl<?>[] tasks = new BehaviorControl<?>[24];
    private TaskGates.Gate[] gates = new TaskGates.Gate[24];
    private long[] closedAt = new long[24];
    /** Gates already worked out for this brain's tasks, for when the loop order changes (activity switch). */
    private IdentityHashMap<BehaviorControl<?>, TaskGates.Gate> known;
    private int index;
    /** Verify mode: a task whose gate was closed and which was asked anyway; it must not have started. */
    private BehaviorControl<?> mustStayStopped;
    private LivingEntity mustStayStoppedOwner;

    public void beginLoop() {
        index = 0;
    }

    /**
     * Stands in for task.getStatus() inside the loop: the loop only tries to start a task whose status is STOPPED, so
     * answering RUNNING skips it.
     */
    public Behavior.Status status(BehaviorControl<?> task, Brain<?> brain, LivingEntity entity, long memoryVersion) {
        checkStayedStopped();
        int i = index++;
        if (i >= tasks.length) grow();
        TaskGates.Gate gate;
        if (tasks[i] != task) {
            gate = lookUp(task, entity);
            tasks[i] = task;
            gates[i] = gate;
            closedAt[i] = -1;
        } else {
            gate = gates[i];
        }
        if (gate == NO_GATE) return task.getStatus();

        FastPathStats.gateCalls++;
        boolean verify = FastPathStats.verify;
        if (closedAt[i] == memoryVersion) {
            FastPathStats.gateHits++;
            if (!verify) return Behavior.Status.RUNNING;
            FastPathStats.gateChecked++;
            if (gate.open(brain)) {                      // the memo says closed, the memories say open
                FastPathStats.gateMismatches++;
                FastPathStats.mismatch("task gate memo", entity);
                closedAt[i] = -1;
            }
            return askAnyway(task, entity);
        }
        Behavior.Status status = task.getStatus();
        if (status != Behavior.Status.STOPPED) return status;
        if (gate.open(brain)) return status;
        closedAt[i] = memoryVersion;
        return verify ? askAnyway(task, entity) : Behavior.Status.RUNNING;
    }

    /** Verify mode: the task is asked as without the mod; with its gate closed it must refuse. */
    private Behavior.Status askAnyway(BehaviorControl<?> task, LivingEntity entity) {
        Behavior.Status status = task.getStatus();
        if (status == Behavior.Status.STOPPED) {
            mustStayStopped = task;
            mustStayStoppedOwner = entity;
        }
        return status;
    }

    /** Called before the next task and at the end of the loop. */
    public void checkStayedStopped() {
        BehaviorControl<?> task = mustStayStopped;
        if (task == null) return;
        mustStayStopped = null;
        FastPathStats.gateChecked++;
        if (task.getStatus() != Behavior.Status.STOPPED) {
            FastPathStats.gateMismatches++;
            FastPathStats.mismatch("task gate (a task with a closed gate started: " + task.debugString() + ")", mustStayStoppedOwner);
        }
        mustStayStoppedOwner = null;
    }

    private TaskGates.Gate lookUp(BehaviorControl<?> task, LivingEntity entity) {
        if (known == null) known = new IdentityHashMap<>();
        TaskGates.Gate gate = known.get(task);
        if (gate == null) {
            gate = TaskGates.of(task, entity);
            if (gate == null) gate = NO_GATE;
            known.put(task, gate);
        }
        return gate;
    }

    private void grow() {
        int size = tasks.length * 2;
        tasks = Arrays.copyOf(tasks, size);
        gates = Arrays.copyOf(gates, size);
        closedAt = Arrays.copyOf(closedAt, size);
    }
}
