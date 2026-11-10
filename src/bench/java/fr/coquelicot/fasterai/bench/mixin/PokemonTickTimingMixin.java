package fr.coquelicot.fasterai.bench.mixin;

import com.cobblemon.mod.common.CobblemonMemories;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import fr.coquelicot.fasterai.bench.EntityTiming;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Bench-only timer around a Pokémon's whole tick (AI, movement, collisions). Off unless a bench runs. */
@Mixin(PokemonEntity.class)
public abstract class PokemonTickTimingMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void fasterai$beginTick(CallbackInfo ci) {
        if (!EntityTiming.enabled) return;
        PokemonEntity self = (PokemonEntity) (Object) this;
        EntityTiming.x0 = self.getX(); EntityTiming.y0 = self.getY(); EntityTiming.z0 = self.getZ();
        EntityTiming.begin();
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void fasterai$endTick(CallbackInfo ci) {
        if (!EntityTiming.enabled) return;
        EntityTiming.end();
        PokemonEntity self = (PokemonEntity) (Object) this;
        if (self.level().isClientSide) return;
        EntityTiming.cTicks++;
        boolean still = self.getX() == EntityTiming.x0 && self.getY() == EntityTiming.y0 && self.getZ() == EntityTiming.z0;
        if (still) EntityTiming.cStill++;
        if (still && self.onGround()) EntityTiming.cStillGround++;
        if (self.getNavigation().isInProgress()) EntityTiming.cNavigating++;
        if (self.isInWater() || self.isInLava()) EntityTiming.cInFluid++;
        Vec3 v = self.getDeltaMovement();
        if (still && self.onGround() && v.x == 0 && v.z == 0) EntityTiming.cVelocityRest++;
        if (self.getBrain().hasMemoryValue(CobblemonMemories.POKEMON_SLEEPING)) EntityTiming.cAsleep++;
    }
}
