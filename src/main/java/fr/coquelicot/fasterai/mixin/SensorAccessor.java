package fr.coquelicot.fasterai.mixin;

import net.minecraft.world.entity.ai.sensing.Sensor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A sensor's countdown to its next scan. */
@Mixin(Sensor.class)
public interface SensorAccessor {
    @Accessor("timeToTick")
    long fasterai$timeToTick();

    @Accessor("timeToTick")
    void fasterai$setTimeToTick(long ticks);
}
