package fr.coquelicot.fasterai.mixin;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.ai.ToleratedHerdLeader;
import fr.coquelicot.fasterai.ai.HerdLeaderSearch;
import fr.coquelicot.fasterai.config.FasterAIConfig;
import fr.coquelicot.fasterai.perf.FastPathStats;
import kotlin.Pair;
import net.minecraft.world.entity.ai.memory.NearestVisibleLivingEntities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Runs the herd leader search in a cheaper order with the same result: see {@link HerdLeaderSearch}. */
@Mixin(targets = "com.cobblemon.mod.common.api.ai.config.task.FindHerdLeaderTaskConfig$Companion", remap = false)
public abstract class FindHerdLeaderMixin {

    @Inject(method = "getBestHerdLeader", at = @At("HEAD"), cancellable = true)
    private void fasterai$cheaperSearch(Pair<? extends PokemonEntity, ToleratedHerdLeader> currentLeader, NearestVisibleLivingEntities nearbyEntities,
                                        PokemonEntity entity, CallbackInfoReturnable<PokemonEntity> cir) {
        if (!FasterAIConfig.INSTANCE.herdLeaderSearch() || FastPathStats.verify) return;
        FastPathStats.herdCalls++;
        Object result = HerdLeaderSearch.find(currentLeader, nearbyEntities, entity);
        if (result == HerdLeaderSearch.FALLBACK) return;
        FastPathStats.herdHits++;
        cir.setReturnValue((PokemonEntity) result);
    }

    /** Verify mode: Cobblemon's search ran (its raycasts filled the cache); ours must pick the same leader. */
    @Inject(method = "getBestHerdLeader", at = @At("RETURN"))
    private void fasterai$verify(Pair<? extends PokemonEntity, ToleratedHerdLeader> currentLeader, NearestVisibleLivingEntities nearbyEntities,
                                 PokemonEntity entity, CallbackInfoReturnable<PokemonEntity> cir) {
        if (!FastPathStats.verify || !FasterAIConfig.INSTANCE.herdLeaderSearch()) return;
        Object result = HerdLeaderSearch.find(currentLeader, nearbyEntities, entity);
        if (result == HerdLeaderSearch.FALLBACK) return;
        FastPathStats.herdChecked++;
        if (result != cir.getReturnValue()) {
            FastPathStats.herdMismatches++;
            FastPathStats.mismatch("herd leader", entity);
        }
    }
}
