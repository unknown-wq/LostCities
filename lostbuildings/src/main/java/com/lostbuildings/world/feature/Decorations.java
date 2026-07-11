package com.lostbuildings.world.feature;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.List;

/**
 * Sparse "yard" decoration scattered into the empty ground of a building group so a settlement
 * reads as lived-in rather than eight bare boxes on flat dirt (PORT-PLAN §11b flavour pass — NOT
 * a port of any Lost Cities decorator). It runs AFTER {@link Streets}, over the chunk cells of the
 * 3x3 write window that no building occupies, and drops a handful of props per free cell: the odd
 * fence-and-lantern lamp post, small tufts of grass / fern / flowers, and the occasional leaf bush.
 *
 * <p>Everything is guarded to stay correct during worldgen:
 * <ul>
 *   <li>Cells holding a building footprint are skipped entirely, so props never touch a house.</li>
 *   <li>Each prop only lands on a natural {@link Blocks#GRASS_BLOCK}/{@link Blocks#DIRT} surface, so
 *       streets ({@code STONE_BRICKS}) and foundations ({@code COBBLESTONE}) are left alone, and
 *       water/lava columns are skipped.</li>
 *   <li>All positions lie inside a cell of the 3x3 window, so nothing writes outside the FEATURES
 *       {@code blockStateWriteRadius}.</li>
 *   <li>All writes use worldgen setBlock flag 19 (notify clients, no neighbour updates) exactly as
 *       {@link Foundation} and {@link Streets} do; the method never throws.</li>
 * </ul>
 */
public final class Decorations {

    private Decorations() {}

    private static final BlockState LAMP_POST = Blocks.OAK_FENCE.defaultBlockState();
    private static final BlockState LAMP_LIGHT = Blocks.LANTERN.defaultBlockState();
    private static final BlockState LEAF_BUSH =
            Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, Boolean.TRUE);

    /** Worldgen-safe setBlock flag: notify clients, no neighbour updates (mirrors Streets/Foundation). */
    private static final int SET_FLAGS = 19;

    /**
     * Scatter yard props into the free cells of the 3x3 window around (centerCX, centerCZ).
     *
     * @param level     worldgen level
     * @param occupied  NW corners of every placed building footprint (each a 16x16 chunk)
     * @param footprint footprint size in blocks (square, = 16)
     * @param centerCX  origin chunk X
     * @param centerCZ  origin chunk Z
     * @param rand      random source
     */
    public static void scatter(WorldGenLevel level, List<BlockPos> occupied, int footprint,
                               int centerCX, int centerCZ, RandomSource rand) {
        try {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int cellX = (centerCX + dx) << 4;
                    int cellZ = (centerCZ + dz) << 4;
                    if (isOccupiedCell(cellX, cellZ, occupied)) {
                        continue; // never decorate a building's chunk
                    }
                    decorateCell(level, cellX, cellZ, footprint, occupied, rand);
                }
            }
        } catch (Exception e) {
            // Decoration is pure flavour; never let it break generation.
        }
    }

    /** Drop a sparse handful of props somewhere inside one free 16x16 cell. */
    private static void decorateCell(WorldGenLevel level, int cellX, int cellZ, int footprint,
                                     List<BlockPos> occupied, RandomSource rand) {
        // Occasional lamp post near the middle of the cell (path-side prop).
        if (rand.nextInt(3) == 0) {
            int lx = cellX + 3 + rand.nextInt(footprint - 6);
            int lz = cellZ + 3 + rand.nextInt(footprint - 6);
            placeLampPost(level, lx, lz, occupied, footprint);
        }

        // A few plants / bushes: sparse, so gaps still read as open ground.
        int props = rand.nextInt(6);
        for (int i = 0; i < props; i++) {
            int px = cellX + rand.nextInt(footprint);
            int pz = cellZ + rand.nextInt(footprint);
            placePlant(level, px, pz, occupied, footprint, rand);
        }
    }

    /** A fence pillar topped with a lantern, if the column is clear natural ground. */
    private static void placeLampPost(WorldGenLevel level, int x, int z,
                                      List<BlockPos> occupied, int footprint) {
        if (insideAnyFootprint(x, z, occupied, footprint)) {
            return;
        }
        BlockPos ground = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE_WG, new BlockPos(x, 0, z));
        if (!isNaturalSurface(level, ground.below())) {
            return;
        }
        if (!level.getBlockState(ground).isAir()
                || !level.getBlockState(ground.above()).isAir()
                || !level.getBlockState(ground.above(2)).isAir()) {
            return; // don't clip into overhangs / trees
        }
        level.setBlock(ground, LAMP_POST, SET_FLAGS);
        level.setBlock(ground.above(), LAMP_POST, SET_FLAGS);
        level.setBlock(ground.above(2), LAMP_LIGHT, SET_FLAGS);
    }

    /** A single tuft of grass / flower, or the odd leaf bush, on natural ground. */
    private static void placePlant(WorldGenLevel level, int x, int z,
                                   List<BlockPos> occupied, int footprint, RandomSource rand) {
        if (insideAnyFootprint(x, z, occupied, footprint)) {
            return;
        }
        BlockPos ground = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE_WG, new BlockPos(x, 0, z));
        if (!isNaturalSurface(level, ground.below())) {
            return;
        }
        if (!level.getBlockState(ground).isAir()) {
            return; // something (a street clear, a tree) is already there
        }
        level.setBlock(ground, pickPlant(rand), SET_FLAGS);
    }

    private static BlockState pickPlant(RandomSource rand) {
        return switch (rand.nextInt(10)) {
            case 0, 1, 2, 3 -> Blocks.SHORT_GRASS.defaultBlockState();
            case 4 -> Blocks.FERN.defaultBlockState();
            case 5 -> Blocks.POPPY.defaultBlockState();
            case 6 -> Blocks.DANDELION.defaultBlockState();
            case 7 -> Blocks.CORNFLOWER.defaultBlockState();
            case 8 -> Blocks.OXEYE_DAISY.defaultBlockState();
            default -> LEAF_BUSH; // occasional low shrub of foliage
        };
    }

    /** True only for the vanilla dirt/grass a settlement grows on — not streets, foundations or water. */
    private static boolean isNaturalSurface(WorldGenLevel level, BlockPos solid) {
        BlockState s = level.getBlockState(solid);
        return s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT);
    }

    /** A cell is "occupied" if its NW corner is the corner of a placed building footprint. */
    private static boolean isOccupiedCell(int cellX, int cellZ, List<BlockPos> occupied) {
        for (BlockPos p : occupied) {
            if (p.getX() == cellX && p.getZ() == cellZ) {
                return true;
            }
        }
        return false;
    }

    private static boolean insideAnyFootprint(int x, int z, List<BlockPos> occupied, int footprint) {
        for (BlockPos p : occupied) {
            if (x >= p.getX() && x < p.getX() + footprint
                    && z >= p.getZ() && z < p.getZ() + footprint) {
                return true;
            }
        }
        return false;
    }
}
