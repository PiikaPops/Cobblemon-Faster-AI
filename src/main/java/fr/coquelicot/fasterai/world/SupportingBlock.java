package fr.coquelicot.fasterai.world;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * Same result as CollisionGetter.findSupportingBlock(entity, box), which runs every tick for every entity on the ground.
 *
 * Minecraft walks a BlockCollisions cursor over the box grown by one block on each side (4x4x4 = 64 blocks for a
 * small mob) and reads every block. The outer blocks only count when they have an oversized collision shape (fences,
 * walls...) or are a moving piston. Here, when a section's palette holds no such state, its outer blocks are skipped
 * without being read, and a section with only air is skipped whole. Every other block gets exactly Minecraft's tests.
 * The answer (closest block to the entity, ties broken by BlockPos order) does not depend on the visiting order.
 */
public final class SupportingBlock {
    private SupportingBlock() {}

    private static final Predicate<BlockState> LARGE_OR_PISTON = s -> s.hasLargeCollisionShape() || s.is(Blocks.MOVING_PISTON);

    public static Optional<BlockPos> find(Level level, Entity entity, AABB box) {
        CollisionContext context = CollisionContext.of(entity);
        VoxelShape entityShape = null;
        // BlockCollisions' cursor bounds
        int x0 = Mth.floor(box.minX - 1.0E-7) - 1, x1 = Mth.floor(box.maxX + 1.0E-7) + 1;
        int y0 = Mth.floor(box.minY - 1.0E-7) - 1, y1 = Mth.floor(box.maxY + 1.0E-7) + 1;
        int z0 = Mth.floor(box.minZ - 1.0E-7) - 1, z1 = Mth.floor(box.maxZ + 1.0E-7) + 1;
        Vec3 origin = entity.position();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) {
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                BlockGetter chunk = level.getChunkForCollisions(cx, cz);
                if (chunk == null) continue;                      // not loaded: Minecraft skips it too
                ChunkAccess access = chunk instanceof ChunkAccess c ? c : null;
                int ax = Math.max(x0, cx << 4), bx = Math.min(x1, (cx << 4) + 15);
                int az = Math.max(z0, cz << 4), bz = Math.min(z1, (cz << 4) + 15);
                for (int y = y0; y <= y1; y++) {
                    boolean outerPossible = true;
                    if (access != null) {
                        int index = access.getSectionIndex(y);
                        LevelChunkSection[] sections = access.getSections();
                        if (index < 0 || index >= sections.length) continue;     // read as air
                        LevelChunkSection section = sections[index];
                        if (section.hasOnlyAir()) continue;                      // air never collides
                        outerPossible = SectionScan.mayHave(section, PaletteFlags.LARGE_OR_PISTON, LARGE_OR_PISTON);
                    }
                    boolean yOuter = y == y0 || y == y1;
                    for (int x = ax; x <= bx; x++) {
                        boolean xOuter = x == x0 || x == x1;
                        for (int z = az; z <= bz; z++) {
                            int type = (xOuter ? 1 : 0) + (yOuter ? 1 : 0) + (z == z0 || z == z1 ? 1 : 0);
                            if (type == 3 || type != 0 && !outerPossible) continue;
                            pos.set(x, y, z);
                            BlockState state = chunk.getBlockState(pos);
                            if (type == 1 && !state.hasLargeCollisionShape() || type == 2 && !state.is(Blocks.MOVING_PISTON)) continue;
                            VoxelShape shape = state.getCollisionShape(level, pos, context);
                            if (shape == Shapes.block()) {
                                if (!box.intersects(x, y, z, x + 1.0, y + 1.0, z + 1.0)) continue;
                            } else {
                                VoxelShape moved = shape.move(x, y, z);
                                if (moved.isEmpty()) continue;
                                if (entityShape == null) entityShape = Shapes.create(box);
                                if (!Shapes.joinIsNotEmpty(moved, entityShape, BooleanOp.AND)) continue;
                            }
                            double distance = pos.distToCenterSqr(origin);
                            if (distance < bestDistance || distance == bestDistance && (best == null || best.compareTo(pos) < 0)) {
                                best = pos.immutable();
                                bestDistance = distance;
                            }
                        }
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** Same result as Entity.isInWall() for an entity with physics, without the stream: the eye box covers 1 to 4 blocks. */
    public static boolean isInWall(Level level, AABB eyeBox) {
        int x0 = Mth.floor(eyeBox.minX), y0 = Mth.floor(eyeBox.minY), z0 = Mth.floor(eyeBox.minZ);
        int x1 = Mth.floor(eyeBox.maxX), y1 = Mth.floor(eyeBox.maxY), z1 = Mth.floor(eyeBox.maxZ);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        VoxelShape boxShape = null;
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    pos.set(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir() || !state.isSuffocating(level, pos)) continue;
                    if (boxShape == null) boxShape = Shapes.create(eyeBox);
                    if (Shapes.joinIsNotEmpty(state.getCollisionShape(level, pos).move(x, y, z), boxShape, BooleanOp.AND)) return true;
                }
            }
        }
        return false;
    }
}
