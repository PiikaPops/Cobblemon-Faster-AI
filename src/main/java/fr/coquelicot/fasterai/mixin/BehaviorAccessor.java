package fr.coquelicot.fasterai.mixin;

import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** Reads a Behavior's entry conditions (the memories tryStart checks first). */
@Mixin(Behavior.class)
public interface BehaviorAccessor {
    @Accessor("entryCondition")
    Map<MemoryModuleType<?>, MemoryStatus> fasterai$entryCondition();
}
