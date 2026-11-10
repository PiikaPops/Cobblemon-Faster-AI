package fr.coquelicot.fasterai.ai;

import com.cobblemon.mod.common.api.ai.WrapperLivingEntityTask;
import com.mojang.datafixers.kinds.App;
import com.mojang.datafixers.util.Unit;
import fr.coquelicot.fasterai.CobblemonFasterAI;
import fr.coquelicot.fasterai.mixin.BehaviorAccessor;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.OneShot;
import net.minecraft.world.entity.ai.behavior.declarative.BehaviorBuilder;
import net.minecraft.world.entity.ai.behavior.declarative.MemoryCondition;
import net.minecraft.world.entity.ai.behavior.declarative.Trigger;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds, for a brain task, the memory conditions it checks before doing anything else (its "gate").
 *
 * Two task shapes have a gate that can be read from outside:
 * - a OneShot made by BehaviorBuilder.create from a flat group of present/absent/registered memories: its trigger
 *   returns false, with nothing else run, as soon as one of them does not hold;
 * - a Behavior that keeps Minecraft's hasRequiredMemories: tryStart (final) returns false, with nothing else run, when
 *   its entry conditions do not hold.
 * Cobblemon's WrapperLivingEntityTask only forwards to the task it wraps, so its gate is the wrapped task's.
 * While a gate is closed, trying to start the task is a no-op: {@link BrainGates} skips it until the brain's memories
 * change. Any other task shape has no known gate and always runs as before.
 *
 * BehaviorBuilder's parts are anonymous classes; they are recognised by building one of each at start-up and comparing
 * classes, not by name. If that fails, no OneShot gets a gate.
 */
public final class TaskGates {
    private TaskGates() {}

    /** A task's entry conditions. */
    public static final class Gate {
        final MemoryModuleType<?>[] types;
        final MemoryStatus[] statuses;

        Gate(List<MemoryModuleType<?>> types, List<MemoryStatus> statuses) {
            this.types = types.toArray(new MemoryModuleType<?>[0]);
            this.statuses = statuses.toArray(new MemoryStatus[0]);
        }

        /** False when the task cannot start: one of its memory conditions does not hold. */
        public boolean open(Brain<?> brain) {
            for (int i = 0; i < types.length; i++) {
                if (!brain.checkMemory(types[i], statuses[i])) return false;
            }
            return true;
        }
    }

    private static boolean builderKnown;
    private static Class<?> oneShotClass, memoryNodeClass, constantNodeClass, nodeType;
    private static final Set<Class<?>> groupNodeClasses = new HashSet<>();
    private static Field memoryConditionField;
    private static String hasRequiredMemoriesName;

    private static final ClassValue<Field[]> NODE_FIELDS = new ClassValue<>() {
        @Override
        protected Field[] computeValue(Class<?> type) {
            List<Field> fields = new ArrayList<>();
            for (Field f : type.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && f.getType() == nodeType) {
                    f.setAccessible(true);
                    fields.add(f);
                }
            }
            return fields.toArray(new Field[0]);
        }
    };

    /** Whether a Behavior subclass keeps Minecraft's hasRequiredMemories (so its entry conditions are its whole gate). */
    private static final ClassValue<Boolean> PLAIN_BEHAVIOR = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            if (hasRequiredMemoriesName == null) return false;
            for (Class<?> c = type; c != null && c != Behavior.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals(hasRequiredMemoriesName) && m.getParameterCount() == 1 && m.getReturnType() == boolean.class) return false;
                }
            }
            return true;
        }
    };

    static {
        try {
            hasRequiredMemoriesName = FabricLoader.getInstance().getMappingResolver()
                    .mapMethodName("intermediary", "net.minecraft.class_4097", "method_19546", "(Lnet/minecraft/class_1309;)Z");
        } catch (Throwable t) {
            CobblemonFasterAI.LOGGER.warn("Brain task gates: Behavior tasks left as they are ({})", t.toString());
        }
        try {
            learnBuilderClasses();
            builderKnown = true;
        } catch (Throwable t) {
            CobblemonFasterAI.LOGGER.warn("Brain task gates: BehaviorBuilder tasks left as they are ({})", t.toString());
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void learnBuilderClasses() throws ReflectiveOperationException {
        Field builderNode = null;
        for (Field f : BehaviorBuilder.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (builderNode != null) throw new IllegalStateException("BehaviorBuilder has more than one field");
            builderNode = f;
        }
        if (builderNode == null) throw new IllegalStateException("BehaviorBuilder has no field");
        builderNode.setAccessible(true);
        nodeType = builderNode.getType();

        BehaviorBuilder.Instance in = BehaviorBuilder.instance();
        Trigger<LivingEntity> never = (level, entity, time) -> false;
        App memory = in.present(MemoryModuleType.DUMMY);
        Object memoryNode = builderNode.get(BehaviorBuilder.unbox(memory));
        memoryNodeClass = memoryNode.getClass();
        constantNodeClass = builderNode.get(BehaviorBuilder.unbox(in.point(Unit.INSTANCE))).getClass();
        groupNodeClasses.add(builderNode.get(BehaviorBuilder.unbox(
                in.group(memory).apply(in, a -> never))).getClass());
        groupNodeClasses.add(builderNode.get(BehaviorBuilder.unbox(
                in.group(memory, memory).apply(in, (a, b) -> never))).getClass());
        groupNodeClasses.add(builderNode.get(BehaviorBuilder.unbox(
                in.group(memory, memory, memory).apply(in, (a, b, c) -> never))).getClass());
        groupNodeClasses.add(builderNode.get(BehaviorBuilder.unbox(
                in.group(memory, memory, memory, memory).apply(in, (a, b, c, d) -> never))).getClass());
        oneShotClass = BehaviorBuilder.create(i -> ((BehaviorBuilder.Instance) i).point(never)).getClass();

        for (Field f : memoryNodeClass.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) && MemoryCondition.class.isAssignableFrom(f.getType())) {
                if (memoryConditionField != null) throw new IllegalStateException("memory node has more than one condition");
                memoryConditionField = f;
            }
        }
        if (memoryConditionField == null) throw new IllegalStateException("memory node has no condition");
        memoryConditionField.setAccessible(true);
        if (groupNodeClasses.contains(memoryNodeClass) || groupNodeClasses.contains(constantNodeClass) || memoryNodeClass == constantNodeClass
                || NODE_FIELDS.get(oneShotClass).length != 1) {
            throw new IllegalStateException("unexpected BehaviorBuilder layout");
        }
    }

    /** The gate of a task for this entity, or null when the task has no gate that can be read. */
    public static Gate of(BehaviorControl<?> task, LivingEntity entity) {
        try {
            Object inner = task;
            if (inner instanceof WrapperLivingEntityTask<?> wrapper) {
                if (!wrapper.getClazz().isInstance(entity)) return null;
                inner = wrapper.getTask();
            }
            if (inner instanceof Behavior<?> behavior) return behaviorGate(behavior);
            if (builderKnown && inner instanceof OneShot<?> && inner.getClass() == oneShotClass) return builtGate(inner);
        } catch (Throwable t) {
            // unknown layout: leave the task alone
        }
        return null;
    }

    private static Gate behaviorGate(Behavior<?> behavior) {
        if (!PLAIN_BEHAVIOR.get(behavior.getClass())) return null;
        Map<MemoryModuleType<?>, MemoryStatus> conditions = ((BehaviorAccessor) behavior).fasterai$entryCondition();
        List<MemoryModuleType<?>> types = new ArrayList<>(conditions.size());
        List<MemoryStatus> statuses = new ArrayList<>(conditions.size());
        for (Map.Entry<MemoryModuleType<?>, MemoryStatus> e : conditions.entrySet()) {
            types.add(e.getKey());
            statuses.add(e.getValue());
        }
        return types.isEmpty() ? null : new Gate(types, statuses);
    }

    private static Gate builtGate(Object oneShot) throws IllegalAccessException {
        Object root = NODE_FIELDS.get(oneShotClass)[0].get(oneShot);
        if (root == null || !groupNodeClasses.contains(root.getClass())) return null;
        List<MemoryModuleType<?>> types = new ArrayList<>(4);
        List<MemoryStatus> statuses = new ArrayList<>(4);
        for (Field f : NODE_FIELDS.get(root.getClass())) {
            Object child = f.get(root);
            if (child == null) return null;
            if (child.getClass() == constantNodeClass) continue;          // the function building the trigger
            if (child.getClass() != memoryNodeClass) return null;         // anything else may run code: no gate
            MemoryCondition<?, ?> condition = (MemoryCondition<?, ?>) memoryConditionField.get(child);
            types.add(condition.memory());
            statuses.add(condition.condition());
        }
        return types.isEmpty() ? null : new Gate(types, statuses);
    }
}
