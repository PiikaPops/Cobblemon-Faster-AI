package fr.coquelicot.fasterai.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.NearestVisibleLivingEntities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.function.Predicate;

@Mixin(NearestVisibleLivingEntities.class)
public interface NearestVisibleLivingEntitiesAccessor {
    /** Nearby living entities, closest first (what findAll walks). */
    @Accessor("nearbyEntities")
    List<LivingEntity> fasterai$nearby();

    /** Line-of-sight test, computed once per entity and cached in this memory. */
    @Accessor("lineOfSightTest")
    Predicate<LivingEntity> fasterai$lineOfSight();
}
