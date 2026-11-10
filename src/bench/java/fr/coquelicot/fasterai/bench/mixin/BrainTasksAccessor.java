package fr.coquelicot.fasterai.bench.mixin;

import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.sensing.Sensor;
import net.minecraft.world.entity.ai.sensing.SensorType;
import net.minecraft.world.entity.schedule.Activity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.Set;

/** Bench-only: reads a brain's task and sensor tables for the task census. */
@Mixin(Brain.class)
public interface BrainTasksAccessor {
    @Accessor("availableBehaviorsByPriority")
    Map<Integer, Map<Activity, Set<BehaviorControl<?>>>> fasterai$tasks();

    @Accessor("sensors")
    Map<SensorType<?>, Sensor<?>> fasterai$sensors();
}
