package fr.coquelicot.fasterai.ai;

import com.cobblemon.mod.common.CobblemonMemories;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.storage.StoreCoordinates;
import com.cobblemon.mod.common.api.storage.party.PartyStore;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Species;
import com.cobblemon.mod.common.pokemon.ai.HerdBehaviour;
import com.cobblemon.mod.common.pokemon.ai.ToleratedHerdLeader;
import com.cobblemon.mod.common.util.ResourceLocationExtensionsKt;
import fr.coquelicot.fasterai.mixin.NearestVisibleLivingEntitiesAccessor;
import kotlin.Pair;
import net.minecraft.ResourceLocationException;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.NearestVisibleLivingEntities;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Same answer as FindHerdLeaderTaskConfig.getBestHerdLeader, with its checks in a cheaper order.
 *
 * Cobblemon walks findAll(filter), which runs the filter and then a line-of-sight raycast on every nearby entity, and
 * only then asks the herd config whether that entity can lead. Here, for each entity in the same order:
 * 1. the species is compared to the tolerated leaders' species (an entity of another species can't match them);
 * 2. Cobblemon's filter and HerdBehaviour.bestMatchLeader run, unchanged;
 * 3. if the entity would become the new best leader, the line of sight is checked, through the same cached test.
 * Steps 1-2 only read state, and an entity that would not replace the current best changes nothing whether it is
 * visible or not, so the chosen leader is the same. The raycasts skipped are the ones whose answer was not used.
 */
public final class HerdLeaderSearch {
    private HerdLeaderSearch() {}

    /** Returned when the reordered search can't be proven equal (a "random" species): Cobblemon's code runs. */
    public static final Object FALLBACK = new Object();

    public static Object find(Pair<? extends PokemonEntity, ToleratedHerdLeader> currentLeader, NearestVisibleLivingEntities nearbyEntities,
                              PokemonEntity entity) {
        HerdBehaviour herd = entity.getBehaviour().getHerd();
        List<ToleratedHerdLeader> leaders = herd.getToleratedLeaders();
        PokemonEntity currentLeaderEntity = currentLeader == null ? null : currentLeader.getFirst();
        if (leaders.isEmpty()) return currentLeaderEntity;            // bestMatchLeader can only answer null

        List<Species> allowedSpecies = new ArrayList<>(leaders.size());   // null: any species may match
        for (ToleratedHerdLeader leader : leaders) {
            PokemonProperties props = leader.getPokemon();
            String name = props.getSpecies();
            if (name == null) { allowedSpecies = null; break; }
            if (name.equals("random")) return FALLBACK;                // matches() would draw a random species
            Species species;
            try {
                species = PokemonSpecies.getByIdentifier(ResourceLocationExtensionsKt.asIdentifierDefaultingNamespace(name, "cobblemon"));
            } catch (ResourceLocationException e) {
                continue;                                              // matches() returns false for this leader
            }
            if (species == null) { allowedSpecies = null; break; }     // unknown species: matches() skips the check
            allowedSpecies.add(species);
        }

        NearestVisibleLivingEntitiesAccessor visible = (NearestVisibleLivingEntitiesAccessor) nearbyEntities;
        Predicate<LivingEntity> lineOfSight = visible.fasterai$lineOfSight();
        ToleratedHerdLeader currentDefinition = currentLeader == null ? null : currentLeader.getSecond();
        PokemonEntity bestLeader = null;
        int bestTier = currentDefinition != null ? currentDefinition.getTier() + 1 : 0;
        float bestDistance = Float.MAX_VALUE;

        for (LivingEntity candidate : visible.fasterai$nearby()) {
            if (!(candidate instanceof PokemonEntity possible) || candidate.equals(currentLeader)) continue;
            if (allowedSpecies != null && !allowedSpecies.contains(possible.getPokemon().getSpecies())) continue;
            // Cobblemon's filter (FindHerdLeaderTaskConfig.Companion.getBestHerdLeader$lambda$1)
            StoreCoordinates<?> coords = possible.getPokemon().getStoreCoordinates().get();
            if ((coords != null ? coords.getStore() : null) instanceof PartyStore) continue;
            if (possible.getHerdSize() >= possible.getBehaviour().getHerd().getMaxSize()) continue;
            if (possible.getBrain().hasMemoryValue(CobblemonMemories.POKEMON_DROWSY)) continue;

            ToleratedHerdLeader match = herd.bestMatchLeader(entity, possible);
            if (match == null) continue;
            boolean alreadyALeader = possible.getBrain().hasMemoryValue(CobblemonMemories.HERD_SIZE);
            int currentLeaderHerdTier = currentLeaderEntity != null ? currentLeaderEntity.getHerdTier() : entity.getHerdTier();
            if (currentLeaderHerdTier >= match.getTier()) continue;
            float distance = entity.distanceTo(possible);
            if (match.getTier() >= bestTier && (!(distance > bestDistance) || alreadyALeader || match.getTier() != bestTier)) {
                if (!lineOfSight.test(possible)) continue;
                bestLeader = possible;
                bestDistance = distance;
                bestTier = match.getTier();
            }
        }
        return bestLeader != null ? bestLeader : currentLeaderEntity;
    }
}
