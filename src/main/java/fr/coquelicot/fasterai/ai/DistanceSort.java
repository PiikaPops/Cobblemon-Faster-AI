package fr.coquelicot.fasterai.ai;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Sorts entities by squared distance to an origin, like list.sort(Comparator.comparingDouble(origin::distanceToSqr)),
 * computing each distance once instead of twice per comparison. Both are stable sorts on the same keys, so the order,
 * ties included, is identical.
 */
public final class DistanceSort {
    private DistanceSort() {}

    private record Keyed(double distance, LivingEntity entity) {}

    private static final Comparator<Keyed> BY_DISTANCE = Comparator.comparingDouble(Keyed::distance);

    public static void sort(List<LivingEntity> list, Entity origin) {
        Keyed[] keyed = new Keyed[list.size()];
        for (int i = 0; i < keyed.length; i++) {
            LivingEntity e = list.get(i);
            keyed[i] = new Keyed(origin.distanceToSqr(e), e);
        }
        Arrays.sort(keyed, BY_DISTANCE);
        for (int i = 0; i < keyed.length; i++) list.set(i, keyed[i].entity());
    }
}
