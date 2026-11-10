package fr.coquelicot.fasterai.mixin;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import fr.coquelicot.fasterai.ai.FarSlowMotion;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Far slow motion (optional, off by default): a wild Pokémon far from every player sits some ticks out. */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {

    @WrapOperation(method = "tickNonPassenger", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;tick()V"))
    private void fasterai$farSlowMotion(Entity entity, Operation<Void> tick) {
        if (entity instanceof PokemonEntity pokemon && FarSlowMotion.shouldSkip((ServerLevel) (Object) this, pokemon)) {
            FarSlowMotion.sitOut(pokemon);
            return;
        }
        tick.call(entity);
    }
}
