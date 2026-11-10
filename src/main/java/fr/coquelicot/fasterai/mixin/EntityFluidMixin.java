package fr.coquelicot.fasterai.mixin;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import fr.coquelicot.fasterai.config.FasterAIConfig;
import fr.coquelicot.fasterai.perf.FastPathStats;
import fr.coquelicot.fasterai.world.PaletteFlags;
import fr.coquelicot.fasterai.world.SectionScan;
import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

/**
 * Entity.updateFluidHeightAndDoFluidPushing reads the fluid of every block of the entity's box, for water and again
 * for lava, every tick. When no chunk section around holds any fluid in its palette, the loop can only end with "no
 * fluid": store height 0 and return false, as Minecraft does in that case. Anything else runs the original code.
 */
@Mixin(Entity.class)
public abstract class EntityFluidMixin {
    @Unique private static final Predicate<BlockState> HAS_WATER = s -> s.getFluidState().is(FluidTags.WATER);
    @Unique private static final Predicate<BlockState> HAS_LAVA = s -> s.getFluidState().is(FluidTags.LAVA);
    @Unique private static final Predicate<BlockState> HAS_FLUID = s -> !s.getFluidState().isEmpty();

    @Unique private boolean fasterai$expectNoFluid;

    @Shadow protected Object2DoubleMap<TagKey<Fluid>> fluidHeight;
    @Shadow public abstract Level level();
    @Shadow public abstract AABB getBoundingBox();
    @Shadow public abstract boolean touchingUnloadedChunk();

    @Inject(method = "updateFluidHeightAndDoFluidPushing", at = @At("HEAD"), cancellable = true)
    private void fasterai$noFluidAround(TagKey<Fluid> tag, double speed, CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof PokemonEntity) || !FasterAIConfig.INSTANCE.fluidSectionCheck()) return;
        Level level = level();
        if (level.isClientSide || touchingUnloadedChunk()) return;   // Minecraft's own early exit, left to it
        FastPathStats.fluidCalls++;
        AABB box = getBoundingBox().deflate(0.001);
        // same block range as Minecraft's loop: [floor(min), ceil(max)) on each axis
        int minX = Mth.floor(box.minX), maxX = Mth.ceil(box.maxX) - 1;
        int minY = Mth.floor(box.minY), maxY = Mth.ceil(box.maxY) - 1;
        int minZ = Mth.floor(box.minZ), maxZ = Mth.ceil(box.maxZ) - 1;
        // Minecraft only counts blocks whose fluid is in this tag
        boolean water = tag == FluidTags.WATER, lava = tag == FluidTags.LAVA;
        Predicate<BlockState> match = water ? HAS_WATER : lava ? HAS_LAVA : HAS_FLUID;
        int slot = water ? PaletteFlags.WATER : lava ? PaletteFlags.LAVA : PaletteFlags.ANY_FLUID;
        if (SectionScan.noneCanMatch(level, minX, minY, minZ, maxX, maxY, maxZ, slot, match)) {
            FastPathStats.fluidHits++;
            if (FastPathStats.verify) {           // let Minecraft's loop run, check it at RETURN
                fasterai$expectNoFluid = true;
                return;
            }
            fluidHeight.put(tag, 0.0);
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "updateFluidHeightAndDoFluidPushing", at = @At("RETURN"))
    private void fasterai$verify(TagKey<Fluid> tag, double speed, CallbackInfoReturnable<Boolean> cir) {
        if (!fasterai$expectNoFluid) return;
        fasterai$expectNoFluid = false;
        FastPathStats.fluidChecked++;
        if (cir.getReturnValueZ() || fluidHeight.getDouble(tag) != 0.0) {
            FastPathStats.fluidMismatches++;
            FastPathStats.mismatch("fluid check", (Entity) (Object) this);
        }
    }
}
