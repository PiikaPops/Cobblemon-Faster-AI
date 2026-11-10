package fr.coquelicot.fasterai.world;

/**
 * Implemented by PalettedContainer (mixin): remembers, per question, whether the palette can hold a matching state.
 * Every write to the container forgets the answers, so a cached answer always describes the current palette.
 */
public interface PaletteFlags {
    byte UNKNOWN = 0, NONE = 1, MAYBE = 2;
    /** Questions (slots); at most 16, 2 bits each. */
    int FIRE_OR_LAVA = 0, WATER = 1, LAVA = 2, ANY_FLUID = 3, LARGE_OR_PISTON = 4;

    byte fasterai$flag(int slot);

    void fasterai$setFlag(int slot, byte value);
}
