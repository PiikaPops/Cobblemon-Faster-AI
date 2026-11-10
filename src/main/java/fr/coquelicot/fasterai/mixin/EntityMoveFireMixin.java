package fr.coquelicot.fasterai.mixin;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import fr.coquelicot.fasterai.config.FasterAIConfig;
import fr.coquelicot.fasterai.perf.FastPathStats;
import fr.coquelicot.fasterai.world.PaletteFlags;
import fr.coquelicot.fasterai.world.SectionScan;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Entity.move checks getBlockStatesIfLoaded(box).noneMatch(fire or lava) after every move. When the box is loaded and
 * no section around has fire or lava in its palette, the stream can only hold neither: an empty stream gives the same
 * noneMatch. Not loaded: Minecraft returns an empty stream too.
 */
@Mixin(Entity.class)
public abstract class EntityMoveFireMixin {
    @Unique private static final Predicate<BlockState> FIRE_OR_LAVA = s -> s.is(BlockTags.FIRE) || s.is(Blocks.LAVA);

    @Redirect(method = "move", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockStatesIfLoaded(Lnet/minecraft/world/phys/AABB;)Ljava/util/stream/Stream;"))
    private Stream<BlockState> fasterai$noFireAround(Level level, AABB box) {
        if (!((Object) this instanceof PokemonEntity) || !FasterAIConfig.INSTANCE.fireSectionCheck() || level.isClientSide) {
            return level.getBlockStatesIfLoaded(box);
        }
        FastPathStats.fireCalls++;
        int minX = Mth.floor(box.minX), maxX = Mth.floor(box.maxX);
        int minY = Mth.floor(box.minY), maxY = Mth.floor(box.maxY);
        int minZ = Mth.floor(box.minZ), maxZ = Mth.floor(box.maxZ);
        if (!level.hasChunksAt(minX, minY, minZ, maxX, maxY, maxZ)) return Stream.empty();
        if (SectionScan.noneCanMatch(level, minX, minY, minZ, maxX, maxY, maxZ, PaletteFlags.FIRE_OR_LAVA, FIRE_OR_LAVA)) {
            FastPathStats.fireHits++;
            if (FastPathStats.verify) {
                FastPathStats.fireChecked++;
                if (!level.getBlockStatesIfLoaded(box).noneMatch(FIRE_OR_LAVA)) {
                    FastPathStats.fireMismatches++;
                    FastPathStats.mismatch("fire/lava check", (Entity) (Object) this);
                }
                return level.getBlockStatesIfLoaded(box);
            }
            return Stream.empty();
        }
        return level.getBlockStates(box);
    }
}
