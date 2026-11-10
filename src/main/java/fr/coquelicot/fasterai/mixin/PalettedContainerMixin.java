package fr.coquelicot.fasterai.mixin;

import fr.coquelicot.fasterai.world.PaletteFlags;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Cache of palette answers for SectionScan. All writes to a container go through set(int, T), getAndSet(int, T) or
 * read(buf) (the palette only grows or gets replaced inside them), so clearing the answers there keeps them true.
 * copy() and recreate() build new containers, which start with no answer.
 */
@Mixin(PalettedContainer.class)
public abstract class PalettedContainerMixin implements PaletteFlags {
    /** 2 bits per slot, 0 = UNKNOWN: a new container knows nothing. */
    @Unique private int fasterai$flags;

    @Override
    public byte fasterai$flag(int slot) {
        return (byte) ((fasterai$flags >>> (slot * 2)) & 3);
    }

    @Override
    public void fasterai$setFlag(int slot, byte value) {
        fasterai$flags = (fasterai$flags & ~(3 << (slot * 2))) | ((value & 3) << (slot * 2));
    }

    @Unique
    private void fasterai$forget() {
        fasterai$flags = 0;
    }

    @Inject(method = "set(ILjava/lang/Object;)V", at = @At("HEAD"))
    private void fasterai$onSet(int index, Object value, CallbackInfo ci) {
        fasterai$forget();
    }

    @Inject(method = "getAndSet(ILjava/lang/Object;)Ljava/lang/Object;", at = @At("HEAD"))
    private void fasterai$onGetAndSet(int index, Object value, CallbackInfoReturnable<Object> cir) {
        fasterai$forget();
    }

    @Inject(method = "read", at = @At("HEAD"))
    private void fasterai$onRead(FriendlyByteBuf buf, CallbackInfo ci) {
        fasterai$forget();
    }
}
