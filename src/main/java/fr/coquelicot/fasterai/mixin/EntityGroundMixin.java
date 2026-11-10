package fr.coquelicot.fasterai.mixin;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import fr.coquelicot.fasterai.config.FasterAIConfig;
import fr.coquelicot.fasterai.perf.FastPathStats;
import fr.coquelicot.fasterai.world.SupportingBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Two per-tick block lookups around a Pokémon, rewritten without changing their answer (see {@link SupportingBlock}):
 * the supporting block search of checkSupportingBlock, and isInWall.
 */
@Mixin(Entity.class)
public abstract class EntityGroundMixin {
    @Shadow public boolean noPhysics;
    @Shadow private EntityDimensions dimensions;
    @Shadow public abstract Level level();
    @Shadow public abstract Vec3 getEyePosition();

    /** Verify mode: our isInWall answer, to compare with Minecraft's at RETURN (0 none, 1 false, 2 true). */
    @Unique private byte fasterai$inWallExpected;

    @Redirect(method = "checkSupportingBlock", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;findSupportingBlock(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/Optional;"))
    private Optional<BlockPos> fasterai$findSupportingBlock(Level level, Entity entity, AABB box) {
        if (!((Object) this instanceof PokemonEntity) || !FasterAIConfig.INSTANCE.supportingBlockScan() || level.isDebug()) {
            return level.findSupportingBlock(entity, box);
        }
        FastPathStats.supportCalls++;
        Optional<BlockPos> result = SupportingBlock.find(level, entity, box);
        if (FastPathStats.verify) {
            Optional<BlockPos> expected = level.findSupportingBlock(entity, box);
            FastPathStats.supportChecked++;
            if (!expected.equals(result)) {
                FastPathStats.supportMismatches++;
                FastPathStats.mismatch("supporting block (" + result + " instead of " + expected + ")", entity);
            }
            return expected;
        }
        return result;
    }

    @Inject(method = "isInWall", at = @At("HEAD"), cancellable = true)
    private void fasterai$isInWall(CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof PokemonEntity) || !FasterAIConfig.INSTANCE.inWallScan() || noPhysics) return;
        float width = dimensions.width() * 0.8F;
        boolean result = SupportingBlock.isInWall(level(), AABB.ofSize(getEyePosition(), width, 1.0E-6, width));
        FastPathStats.inWallCalls++;
        if (FastPathStats.verify) {
            fasterai$inWallExpected = (byte) (result ? 2 : 1);   // Minecraft's code runs, compared at RETURN
            return;
        }
        cir.setReturnValue(result);
    }

    @Inject(method = "isInWall", at = @At("RETURN"))
    private void fasterai$verifyInWall(CallbackInfoReturnable<Boolean> cir) {
        if (fasterai$inWallExpected == 0) return;
        boolean ours = fasterai$inWallExpected == 2;
        fasterai$inWallExpected = 0;
        FastPathStats.inWallChecked++;
        if (ours != cir.getReturnValueZ()) {
            FastPathStats.inWallMismatches++;
            FastPathStats.mismatch("isInWall", (Entity) (Object) this);
        }
    }
}
