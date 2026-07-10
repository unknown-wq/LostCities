package com.lostbuildings.world.feature;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Foundation / terrain fitting for a placed building, analog of Lost Cities'
 * {@code LostCityTerrainFeature.fillToGround}. For each column of the building
 * footprint it (1) pillars solid fill down from the building base to the solid
 * ground surface so the house is never left floating, and (2) clears the volume
 * above the base (up to {@code clearHeight}) so intruding terrain / trees do not
 * poke through the house.
 *
 * <p>All writes use worldgen setBlock flags exactly as the sibling {@code desolation}
 * mod does (flag 19 for placed fill, flag 2 for clears — never flag 1, which would
 * trigger neighbour updates during worldgen).
 */
public final class Foundation {

    private Foundation() {}

    /** Default fill block for the pillars under the footprint. */
    private static final BlockState FILL = Blocks.COBBLESTONE.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /**
     * @param level       worldgen level
     * @param origin      NW-bottom corner of the building footprint (building base Y)
     * @param width       footprint size along X (blocks)
     * @param length      footprint size along Z (blocks)
     * @param clearHeight how many blocks above the base to clear of solid terrain
     * @param rand        random source (unused reserved for jittered fill variety)
     */
    public static void build(WorldGenLevel level, BlockPos origin, int width, int length,
                             int clearHeight, RandomSource rand) {
        int baseY = origin.getY();
        int minY = level.getMinY();
        int deepest = Math.max(minY + 1, baseY - 32);

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int dx = 0; dx < width; dx++) {
            for (int dz = 0; dz < length; dz++) {
                int wx = origin.getX() + dx;
                int wz = origin.getZ() + dz;

                // 1) Pillar down: from just below the building base to the first solid
                //    block (or the deepest limit). Border columns and interior columns
                //    are both filled so the house sits on a solid pad.
                int y = baseY - 1;
                cursor.set(wx, y, wz);
                while (y > deepest && isReplaceable(level.getBlockState(cursor))) {
                    level.setBlock(cursor, FILL, 19);
                    y--;
                    cursor.set(wx, y, wz);
                }

                // 2) Clear above: remove any solid terrain that intrudes into the
                //    building volume (e.g. a hillside cutting through the house).
                for (int cy = baseY; cy < baseY + clearHeight; cy++) {
                    cursor.set(wx, cy, wz);
                    BlockState state = level.getBlockState(cursor);
                    if (!state.isAir() && !state.liquid()) {
                        level.setBlock(cursor, AIR, 2);
                    }
                }
            }
        }
    }

    private static boolean isReplaceable(BlockState state) {
        return state.isAir() || state.liquid() || state.canBeReplaced();
    }
}
