package fr.coquelicot.fasterai.mixin;

import com.cobblemon.mod.common.api.pokemon.evolution.Evolution;
import com.cobblemon.mod.common.api.pokemon.requirement.Requirement;
import com.cobblemon.mod.common.pokemon.FormData;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.requirements.BlocksTraveledRequirement;
import fr.coquelicot.fasterai.config.FasterAIConfig;
import fr.coquelicot.fasterai.perf.FastPathStats;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Pokemon.hasBlocksTraveledRequirement() runs at the end of every Pokémon's travel(), every tick. Cobblemon answers it
 * by sorting the form's evolutions into a new list, flattening their requirements into another and filtering that into
 * a third. The answer is "does any evolution of the form have a BlocksTraveledRequirement": the same sets are read
 * here in place, without the three lists.
 */
@Mixin(value = Pokemon.class, remap = false)
public abstract class PokemonMixin {
    @Shadow public abstract FormData getForm();

    /** Verify mode: our answer, compared with Cobblemon's at RETURN (0 none, 1 false, 2 true). */
    @Unique private byte fasterai$travelExpected;

    @Inject(method = "hasBlocksTraveledRequirement", at = @At("HEAD"), cancellable = true)
    private void fasterai$scanInPlace(CallbackInfoReturnable<Boolean> cir) {
        if (!FasterAIConfig.INSTANCE.travelRequirementScan()) return;
        boolean result = false;
        scan:
        for (Evolution evolution : getForm().getEvolutions()) {
            for (Requirement requirement : evolution.getRequirements()) {
                if (requirement instanceof BlocksTraveledRequirement) {
                    result = true;
                    break scan;
                }
            }
        }
        if (FastPathStats.verify) {
            fasterai$travelExpected = (byte) (result ? 2 : 1);
            return;
        }
        cir.setReturnValue(result);
    }

    @Inject(method = "hasBlocksTraveledRequirement", at = @At("RETURN"))
    private void fasterai$verifyScan(CallbackInfoReturnable<Boolean> cir) {
        if (fasterai$travelExpected == 0) return;
        boolean ours = fasterai$travelExpected == 2;
        fasterai$travelExpected = 0;
        FastPathStats.travelChecked++;
        if (ours != cir.getReturnValueZ()) {
            FastPathStats.travelMismatches++;
            FastPathStats.mismatch("hasBlocksTraveledRequirement of " + this);
        }
    }
}
