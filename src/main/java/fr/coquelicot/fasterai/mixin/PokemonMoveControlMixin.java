package fr.coquelicot.fasterai.mixin;

import com.bedrockk.molang.ast.NumberExpression;
import com.cobblemon.mod.common.entity.pokemon.PokemonBehaviourFlag;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.entity.pokemon.ai.PokemonMoveControl;
import com.cobblemon.mod.common.pokemon.ai.MoveBehaviour;
import fr.coquelicot.fasterai.config.FasterAIConfig;
import fr.coquelicot.fasterai.perf.FastPathStats;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.MoveControl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * PokemonMoveControl.tick() starts by working out the Pokémon's speed (a MoLang evaluation, a pose lookup, an
 * attribute read, a square root), whatever it then does. About two ticks in three the control is waiting (no move
 * ordered, not banking) and the speed is never read: in that state the tick only clears the forward and vertical
 * inputs and, when no path is being followed, drops the flying flag of a walker standing on the ground.
 *
 * Here that waiting tick is done directly, skipping the unused speed. Only when the three speed expressions are plain
 * numbers (evaluating them does nothing else), and never in battle, dead, banking or with a move ordered: all of
 * those go through Cobblemon's code.
 */
@Mixin(PokemonMoveControl.class)
public abstract class PokemonMoveControlMixin extends MoveControl {
    @Shadow(remap = false) @Final private PokemonEntity pokemonEntity;
    @Shadow(remap = false) private boolean banking;

    /** Verify mode: 0 none, 1 = flying flag expected unchanged, 2 = flying flag expected cleared. */
    @Unique private byte fasterai$waitExpected;

    protected PokemonMoveControlMixin(Mob mob) {
        super(mob);
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void fasterai$waitingTick(CallbackInfo ci) {
        if (banking || operation != Operation.WAIT || !FasterAIConfig.INSTANCE.idleMoveControl()) return;
        PokemonEntity entity = pokemonEntity;
        if (entity.isDeadOrDying() || entity.isBattling()) return;
        MoveBehaviour moving = entity.getBehaviour().getMoving();
        if (!(moving.getWalk().getWalkSpeed() instanceof NumberExpression) || !(moving.getSwim().getSwimSpeed() instanceof NumberExpression)
                || !(moving.getFly().getFlySpeedHorizontal() instanceof NumberExpression)) return;
        FastPathStats.idleMoveControls++;
        boolean verify = FastPathStats.verify;

        // Cobblemon: a flier that cannot walk and is neither flying nor in liquid is put into flight
        boolean flying = entity.isFlying();
        if (!flying && !entity.isInLiquid() && !entity.canWalk() && entity.canFly()) {
            if (verify) return;                 // rare: left to Cobblemon, nothing to compare
            entity.setFlying(true);
            flying = true;
        }
        // Cobblemon, waiting and no path in progress: a walker on the ground stops flying
        boolean landing = !mob.getNavigation().isInProgress() && mob.onGround() && moving.getWalk().getCanWalk() && flying;
        if (verify) {
            fasterai$waitExpected = (byte) (landing ? 2 : 1);
            return;
        }
        mob.setZza(0.0F);
        mob.yya = 0.0F;
        if (landing) entity.setBehaviourFlag(PokemonBehaviourFlag.FLYING, false);
        ci.cancel();
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void fasterai$verifyWaitingTick(CallbackInfo ci) {
        if (fasterai$waitExpected == 0) return;
        boolean landing = fasterai$waitExpected == 2;
        fasterai$waitExpected = 0;
        FastPathStats.idleMoveChecked++;
        if (mob.zza != 0.0F || mob.yya != 0.0F || operation != Operation.WAIT || landing && pokemonEntity.isFlying()) {
            FastPathStats.idleMoveMismatches++;
            FastPathStats.mismatch("waiting move control", pokemonEntity);
        }
    }
}
