package fr.coquelicot.fasterai.mixin;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import fr.coquelicot.fasterai.ai.DistanceSort;
import fr.coquelicot.fasterai.config.FasterAIConfig;
import fr.coquelicot.fasterai.perf.FastPathStats;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.sensing.NearestLivingEntitySensor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The sensor sorts nearby entities with Comparator.comparingDouble(entity::distanceToSqr), which computes two distances
 * per comparison. {@link DistanceSort} computes them once, with the same stable sort: same order, ties included.
 */
@Mixin(NearestLivingEntitySensor.class)
public abstract class NearestLivingEntitySensorMixin {

    @Redirect(method = "doTick", at = @At(value = "INVOKE", target = "Ljava/util/List;sort(Ljava/util/Comparator;)V"))
    private void fasterai$sortOnce(List<LivingEntity> list, Comparator<? super LivingEntity> comparator, ServerLevel level, LivingEntity entity) {
        if (!(entity instanceof PokemonEntity) || !FasterAIConfig.INSTANCE.sensorSortKeys() || list.size() < 2) {
            list.sort(comparator);
            return;
        }
        FastPathStats.sortCalls++;
        FastPathStats.sortedEntities += list.size();
        if (FastPathStats.verify) {
            List<LivingEntity> expected = new ArrayList<>(list);
            expected.sort(comparator);
            DistanceSort.sort(list, entity);
            FastPathStats.sortChecked++;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i) != expected.get(i)) {
                    FastPathStats.mismatch("sensor sort", entity);
                    FastPathStats.sortMismatches++;
                    list.clear();
                    list.addAll(expected);
                    break;
                }
            }
            return;
        }
        DistanceSort.sort(list, entity);
    }
}
