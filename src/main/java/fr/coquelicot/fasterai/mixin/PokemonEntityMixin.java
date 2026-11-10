package fr.coquelicot.fasterai.mixin;

import com.cobblemon.mod.common.api.riding.behaviour.ActiveRidingContext;
import com.cobblemon.mod.common.api.riding.behaviour.RidingBehaviour;
import com.cobblemon.mod.common.api.riding.behaviour.RidingBehaviours;
import com.cobblemon.mod.common.api.riding.behaviour.RidingController;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.ai.MoveBehaviour;
import fr.coquelicot.fasterai.ai.FarSlowMotion;
import fr.coquelicot.fasterai.config.FasterAIConfig;
import fr.coquelicot.fasterai.perf.FastPathStats;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.ShoulderRidingEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * PokemonEntity.onGround() is asked about ten times per Pokémon per tick (movement, navigation, network sync). Cobblemon
 * writes it with nested lambdas (ifRidingAvailableSupply), which box the answer and go through three closures each
 * time. This is the same sequence of reads and the same call to the riding behaviour, written flat:
 * 1. if a riding behaviour is active and says "turn off on-ground", answer false;
 * 2. if the species cannot walk but can fly, answer false;
 * 3. otherwise Minecraft's own onGround().
 */
@Mixin(PokemonEntity.class)
public abstract class PokemonEntityMixin extends ShoulderRidingEntity implements FarSlowMotion.State {
    protected PokemonEntityMixin(EntityType<? extends ShoulderRidingEntity> type, Level level) {
        super(type, level);
    }

    // ---- far slow motion (optional): last measured distance to the nearest player
    @Unique private float fasterai$nearestPlayerSquared;
    @Unique private long fasterai$nearestPlayerTime = Long.MIN_VALUE / 2;

    @Override
    public float fasterai$nearestPlayerSquared() {
        return fasterai$nearestPlayerSquared;
    }

    @Override
    public long fasterai$nearestPlayerTime() {
        return fasterai$nearestPlayerTime;
    }

    @Override
    public void fasterai$setNearestPlayer(float squared, long time) {
        fasterai$nearestPlayerSquared = squared;
        fasterai$nearestPlayerTime = time;
    }

    /** Verify mode: our answer, compared with Cobblemon's at RETURN (0 none, 1 false, 2 true). */
    @Unique private byte fasterai$onGroundExpected;

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Inject(method = "onGround", at = @At("HEAD"), cancellable = true)
    private void fasterai$flatOnGround(CallbackInfoReturnable<Boolean> cir) {
        if (!FasterAIConfig.INSTANCE.flatOnGround()) return;
        PokemonEntity self = (PokemonEntity) (Object) this;
        boolean result;
        compute:
        {
            RidingController controller = self.getRidingController();
            if (controller != null) {
                ActiveRidingContext context = controller.getContext();
                if (context != null) {
                    RidingBehaviour behaviour = RidingBehaviours.get(context.getBehaviour());
                    if (behaviour.turnOffOnGround(context.getSettings(), context.getState(), self)) {
                        result = false;
                        break compute;
                    }
                }
            }
            MoveBehaviour moving = self.getBehaviour().getMoving();
            if (!moving.getWalk().getCanWalk() && moving.getFly().getCanFly()) {
                result = false;
                break compute;
            }
            result = super.onGround();
        }
        if (FastPathStats.verify) {
            fasterai$onGroundExpected = (byte) (result ? 2 : 1);
            return;
        }
        cir.setReturnValue(result);
    }

    @Inject(method = "onGround", at = @At("RETURN"))
    private void fasterai$verifyOnGround(CallbackInfoReturnable<Boolean> cir) {
        if (fasterai$onGroundExpected == 0) return;
        boolean ours = fasterai$onGroundExpected == 2;
        fasterai$onGroundExpected = 0;
        FastPathStats.onGroundChecked++;
        if (ours != cir.getReturnValueZ()) {
            FastPathStats.onGroundMismatches++;
            FastPathStats.mismatch("onGround", (PokemonEntity) (Object) this);
        }
    }
}
