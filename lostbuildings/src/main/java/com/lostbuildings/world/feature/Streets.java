package com.lostbuildings.world.feature;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.List;

/**
 * Lightweight streets between the buildings of a group (PORT-PLAN §11b, preferred option).
 *
 * <p>This is NOT a port of the Lost Cities city/street/highway engine (that whole system was
 * cut in Phase 1). It simply connects the already-placed building footprints with a narrow
 * paved path laid on the natural terrain surface: for each consecutive pair of sites an
 * L-shaped (Manhattan) route is walked, and every column of the route that falls in the gap
 * between footprints has its surface block replaced with {@link Blocks#STONE_BRICKS} and the
 * two blocks above cleared. Columns inside a building footprint are skipped so the road never
 * carves through a house; liquid columns are skipped so roads do not float over water.
 *
 * <p>Height follows terrain per column via {@code getHeightmapPos(WORLD_SURFACE_WG, ...)}.
 * All writes use worldgen setBlock flag 19 (notify clients, no neighbour updates), exactly as
 * the sibling {@code desolation} mod does.
 */
public final class Streets {

    private Streets() {}

    /** Road surface material — reads clearly as a paved street, distinct from cobblestone foundations. */
    private static final BlockState STREET = Blocks.STONE_BRICKS.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    /** Half-width of the path: 1 -> a 3-wide street. */
    private static final int HALF_WIDTH = 1;

    /**
     * Connect the given building sites (NW-bottom corners of each footprint) with streets.
     * Sites are chained in placement order, yielding a connected road network for the group.
     *
     * @param level     worldgen level
     * @param sites     NW-bottom corners of each placed building footprint
     * @param footprint footprint size in blocks (square)
     * @param rand      random source (reserved; unused for now)
     */
    public static void connect(WorldGenLevel level, List<BlockPos> sites, int footprint, RandomSource rand) {
        if (sites.size() < 2) {
            return;
        }
        int half = footprint / 2;
        for (int i = 0; i < sites.size() - 1; i++) {
            BlockPos a = sites.get(i);
            BlockPos b = sites.get(i + 1);
            int ax = a.getX() + half, az = a.getZ() + half;
            int bx = b.getX() + half, bz = b.getZ() + half;
            // L-shaped route: X leg at z=az, then Z leg at x=bx.
            layLegX(level, ax, bx, az, sites, footprint);
            layLegZ(level, az, bz, bx, sites, footprint);
        }
    }

    /** Lay a road segment along X from x0..x1 at fixed z, HALF_WIDTH blocks each side (widened on z). */
    private static void layLegX(WorldGenLevel level, int x0, int x1, int z, List<BlockPos> sites, int footprint) {
        int lo = Math.min(x0, x1), hi = Math.max(x0, x1);
        for (int x = lo; x <= hi; x++) {
            for (int w = -HALF_WIDTH; w <= HALF_WIDTH; w++) {
                paveColumn(level, x, z + w, sites, footprint);
            }
        }
    }

    /** Lay a road segment along Z from z0..z1 at fixed x, HALF_WIDTH blocks each side (widened on x). */
    private static void layLegZ(WorldGenLevel level, int z0, int z1, int x, List<BlockPos> sites, int footprint) {
        int lo = Math.min(z0, z1), hi = Math.max(z0, z1);
        for (int z = lo; z <= hi; z++) {
            for (int w = -HALF_WIDTH; w <= HALF_WIDTH; w++) {
                paveColumn(level, x + w, z, sites, footprint);
            }
        }
    }

    /** Pave one column: replace the terrain surface block with street and clear the air above. */
    private static void paveColumn(WorldGenLevel level, int x, int z, List<BlockPos> sites, int footprint) {
        if (insideAnyFootprint(x, z, sites, footprint)) {
            return; // never carve a road through a building footprint
        }
        BlockPos ground = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE_WG, new BlockPos(x, 0, z));
        BlockPos surface = ground.below();
        BlockState existing = level.getBlockState(surface);
        if (existing.liquid()) {
            return; // don't build roads over water/lava surfaces
        }
        level.setBlock(surface, STREET, 19);
        // Clear grass / snow layers / flowers sitting on top of the new road surface.
        for (int cy = 0; cy < 2; cy++) {
            BlockPos above = ground.above(cy);
            BlockState s = level.getBlockState(above);
            if (!s.isAir() && !s.liquid()) {
                level.setBlock(above, AIR, 19);
            }
        }
    }

    private static boolean insideAnyFootprint(int x, int z, List<BlockPos> sites, int footprint) {
        for (BlockPos p : sites) {
            if (x >= p.getX() && x < p.getX() + footprint
                    && z >= p.getZ() && z < p.getZ() + footprint) {
                return true;
            }
        }
        return false;
    }
}
