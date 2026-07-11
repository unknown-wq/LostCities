package com.lostbuildings.engine;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Procedural rooftop features layered on top of a finished building.
 *
 * <p>Every building already ends in an authored capping "top" part, but those tops are few, so the
 * skyline reads as repetitive. This pass rolls <em>one</em> rooftop <em>feature</em> per building
 * from a weighted set and stamps it onto the existing roof surface, so buildings gain distinct
 * silhouettes without any new authored parts. The implemented feature types (with their weights) are:
 *
 * <ul>
 *   <li>{@code NONE} (30) — leave the roof untouched, so plenty of roofs stay plain.</li>
 *   <li>{@code PARAPET} (22) — a low {@code STONE_BRICK_WALL} ring around the roof edge with a lantern
 *       on each corner post (connections corrected against the world, like the engine's own pass).</li>
 *   <li>{@code ANTENNA} (16) — a central {@code IRON_BARS} mast a few blocks tall with a short
 *       cross-arm near the tip and a {@code GLOWSTONE}/{@code END_ROD} beacon on top.</li>
 *   <li>{@code HVAC} (12) — a couple of small clustered {@code IRON_BLOCK}/{@code SMOOTH_STONE} boxes
 *       with the odd {@code HOPPER} on top, reading as rooftop machinery.</li>
 *   <li>{@code GARDEN} (10) — the interior roof surface turned to {@code GRASS_BLOCK} with sparse
 *       short grass / ferns / flowers and the occasional persistent oak-leaf bush.</li>
 *   <li>{@code WATER_TOWER} (7) — a small {@code STONE_BRICKS} tank on {@code OAK_FENCE} legs, capped
 *       with a slab and a {@code WATER_CAULDRON}.</li>
 *   <li>{@code PENTHOUSE} (3) — a small walled {@code STONE_BRICKS} room with a doorway, a slab roof
 *       and a lantern inside.</li>
 * </ul>
 *
 * <p>Worldgen correctness mirrors {@link Weathering}: the roof surface is found per column by scanning
 * down from the top, all writes stay inside the building's own 16x16 footprint column, features only
 * rise a modest amount above the roof (up to ~9 blocks for the antenna) and every write uses the same
 * worldgen-safe flags (notify clients, never trigger neighbour updates) while staying inside the
 * world's build-height range. The pass is fully deterministic from the passed random source and never
 * throws. Running before the weathering pass is intentional: rooftop features get aged too.
 */
public final class Roofs {

    // Worldgen-safe flags: notify clients, do NOT trigger neighbour updates (bit 1 stays off).
    // Kept identical to Weathering.SET_FLAGS / BuildingEngine.SET_FLAGS.
    private static final int SET_FLAGS = Block.UPDATE_CLIENTS;

    // --- feature roll -------------------------------------------------------------------------

    private static final int NONE = 0;
    private static final int PARAPET = 1;
    private static final int ANTENNA = 2;
    private static final int HVAC = 3;
    private static final int GARDEN = 4;
    private static final int WATER_TOWER = 5;
    private static final int PENTHOUSE = 6;

    /** Weights per feature, indexed by the constants above. NONE + subtle share is kept high. */
    private static final int[] WEIGHTS = {30, 22, 16, 12, 10, 7, 3};

    // --- tuning knobs -------------------------------------------------------------------------

    /** Antenna mast height range (blocks above the roof surface), tip beacon adds one more. */
    private static final int ANTENNA_MIN_H = 4;
    private static final int ANTENNA_MAX_H = 8;

    /** Water-tower tank size (square) and leg height. */
    private static final int WATER_TANK_SIZE = 4;
    private static final int WATER_LEG_H = 3;

    /** Garden inset from the footprint edge, and per-cell plant / leaf-bush chances. */
    private static final int GARDEN_INSET = 3;
    private static final float GARDEN_PLANT_CHANCE = 0.55f;
    private static final float GARDEN_BUSH_CHANCE = 0.06f;

    /** HVAC cluster count range and the interior inset the box anchors are kept within. */
    private static final int HVAC_MIN_CLUSTERS = 2;
    private static final int HVAC_INSET = 3;

    /** Penthouse footprint (offset + square size), wall height. */
    private static final int PH_ORIGIN = 3;
    private static final int PH_SIZE = 6;
    private static final int PH_WALL_H = 3;

    // --- reusable block states ----------------------------------------------------------------

    private static final BlockState WALL = Blocks.STONE_BRICK_WALL.defaultBlockState();
    private static final BlockState LANTERN = Blocks.LANTERN.defaultBlockState();
    private static final BlockState IRON_BARS = Blocks.IRON_BARS.defaultBlockState();
    private static final BlockState END_ROD = Blocks.END_ROD.defaultBlockState();
    private static final BlockState GLOWSTONE = Blocks.GLOWSTONE.defaultBlockState();
    private static final BlockState IRON_BLOCK = Blocks.IRON_BLOCK.defaultBlockState();
    private static final BlockState SMOOTH_STONE = Blocks.SMOOTH_STONE.defaultBlockState();
    private static final BlockState SMOOTH_STONE_SLAB = Blocks.SMOOTH_STONE_SLAB.defaultBlockState();
    private static final BlockState STONE_BRICK_SLAB = Blocks.STONE_BRICK_SLAB.defaultBlockState();
    private static final BlockState STONE_BRICKS = Blocks.STONE_BRICKS.defaultBlockState();
    private static final BlockState OAK_FENCE = Blocks.OAK_FENCE.defaultBlockState();
    private static final BlockState HOPPER = Blocks.HOPPER.defaultBlockState();
    private static final BlockState WATER_CAULDRON = Blocks.WATER_CAULDRON.defaultBlockState();
    private static final BlockState GRASS_BLOCK = Blocks.GRASS_BLOCK.defaultBlockState();
    private static final BlockState LEAF_BUSH =
            Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, Boolean.TRUE);

    private Roofs() {
    }

    /**
     * Add a rooftop feature to the building occupying the {@code footprint} x {@code footprint}
     * column that starts at {@code origin} (ground-floor bottom corner) and rises {@code height}
     * blocks. The actual roof surface is found per column by scanning down from the top. All writes
     * stay inside that column and never trigger neighbour updates. Fully guarded — never throws.
     *
     * @param level     worldgen level
     * @param origin    NW-bottom corner of the building footprint (ground-floor base Y)
     * @param footprint footprint size in blocks (square; 16)
     * @param height    generated building height in blocks above {@code origin}
     * @param rand      random source
     */
    public static void apply(WorldGenLevel level, BlockPos origin, int footprint, int height, RandomSource rand) {
        if (footprint <= 0 || height <= 0) {
            return;
        }
        try {
            switch (pickFeature(rand)) {
                case PARAPET -> parapet(level, origin, footprint, height, rand);
                case ANTENNA -> antenna(level, origin, footprint, height, rand);
                case HVAC -> hvac(level, origin, footprint, height, rand);
                case GARDEN -> garden(level, origin, footprint, height, rand);
                case WATER_TOWER -> waterTower(level, origin, footprint, height, rand);
                case PENTHOUSE -> penthouse(level, origin, footprint, height, rand);
                default -> { /* NONE: leave the roof as-is */ }
            }
        } catch (Throwable ignored) {
            // Rooftop features are cosmetic: a failure must never abort or corrupt worldgen.
        }
    }

    /** Roll one feature type from {@link #WEIGHTS}. */
    private static int pickFeature(RandomSource rand) {
        int total = 0;
        for (int w : WEIGHTS) {
            total += w;
        }
        int roll = rand.nextInt(total);
        for (int i = 0; i < WEIGHTS.length; i++) {
            roll -= WEIGHTS[i];
            if (roll < 0) {
                return i;
            }
        }
        return NONE;
    }

    // --- features -----------------------------------------------------------------------------

    /**
     * A low wall ring around the roof edge with a lantern on each corner. Walls are placed raw, then
     * their connections are corrected against the world (via {@link BlockStates#correct}) so the ring
     * reads as a continuous parapet rather than isolated posts.
     */
    private static void parapet(WorldGenLevel level, BlockPos origin, int footprint, int height,
                                RandomSource rand) {
        int max = footprint - 1;
        List<BlockPos> walls = new ArrayList<>();
        for (int x = 0; x < footprint; x++) {
            for (int z = 0; z < footprint; z++) {
                if (x != 0 && x != max && z != 0 && z != max) {
                    continue;   // edge cells only
                }
                int sy = surfaceY(level, origin, x, z, height);
                if (sy < 0) {
                    continue;
                }
                BlockPos base = origin.offset(x, sy + 1, z);
                if (placeIfAir(level, base, WALL)) {
                    walls.add(base);
                    boolean corner = (x == 0 || x == max) && (z == 0 || z == max);
                    if (corner) {
                        placeIfAir(level, base.above(), LANTERN);
                    }
                }
            }
        }
        // Second pass: correct wall connections now that all neighbours exist.
        for (BlockPos pos : walls) {
            BlockState corrected = BlockStates.correct(level, pos, level.getBlockState(pos));
            if (corrected != null && !isOutside(level, pos)) {
                level.setBlock(pos, corrected, SET_FLAGS);
            }
        }
    }

    /**
     * A central vertical iron-bars mast with a short cross-arm near the tip and a glowstone / end-rod
     * beacon on top. The mast stops early if it runs into an obstruction so nothing floats above it.
     */
    private static void antenna(WorldGenLevel level, BlockPos origin, int footprint, int height,
                                RandomSource rand) {
        int cx = footprint / 2;
        int cz = footprint / 2;
        int sy = surfaceY(level, origin, cx, cz, height);
        if (sy < 0) {
            return;
        }
        int mastH = ANTENNA_MIN_H + rand.nextInt(ANTENNA_MAX_H - ANTENNA_MIN_H + 1);
        List<BlockPos> bars = new ArrayList<>();
        int top = sy;   // last successfully placed mast level
        for (int i = 1; i <= mastH; i++) {
            BlockPos p = origin.offset(cx, sy + i, cz);
            if (!placeIfAir(level, p, IRON_BARS)) {
                break;      // obstruction: stop the mast here
            }
            bars.add(p);
            top = sy + i;
        }
        if (top <= sy) {
            return;         // could not raise the mast at all
        }
        // Cross-arm one block below the tip (stays inside the footprint: cx/cz are central).
        int armY = top - 1;
        if (armY > sy) {
            addBar(level, origin, cx - 1, armY, cz, footprint, bars);
            addBar(level, origin, cx + 1, armY, cz, footprint, bars);
            addBar(level, origin, cx, armY, cz - 1, footprint, bars);
            addBar(level, origin, cx, armY, cz + 1, footprint, bars);
        }
        // Beacon on top of the mast.
        placeIfAir(level, origin.offset(cx, top + 1, cz), rand.nextBoolean() ? GLOWSTONE : END_ROD);
        // Correct the bars so the cross-arm connects to the mast.
        for (BlockPos pos : bars) {
            BlockState corrected = BlockStates.correct(level, pos, level.getBlockState(pos));
            if (corrected != null && !isOutside(level, pos)) {
                level.setBlock(pos, corrected, SET_FLAGS);
            }
        }
    }

    private static void addBar(WorldGenLevel level, BlockPos origin, int x, int y, int z, int footprint,
                               List<BlockPos> bars) {
        if (x < 0 || x >= footprint || z < 0 || z >= footprint) {
            return;
        }
        BlockPos p = origin.offset(x, y, z);
        if (placeIfAir(level, p, IRON_BARS)) {
            bars.add(p);
        }
    }

    /**
     * A couple of small clustered machinery boxes with the odd hopper on top. Anchors are kept well
     * inside the footprint so a box never reaches the edge.
     */
    private static void hvac(WorldGenLevel level, BlockPos origin, int footprint, int height,
                             RandomSource rand) {
        int span = footprint - 2 * HVAC_INSET;   // width available for a 2-wide box anchor
        if (span <= 1) {
            return;
        }
        int clusters = HVAC_MIN_CLUSTERS + rand.nextInt(2);
        for (int c = 0; c < clusters; c++) {
            int bx = HVAC_INSET + rand.nextInt(span - 1);
            int bz = HVAC_INSET + rand.nextInt(span - 1);
            int sy = surfaceY(level, origin, bx, bz, height);
            if (sy < 0) {
                continue;
            }
            int w = 1 + rand.nextInt(2);         // 1 or 2 wide
            int boxH = 1 + rand.nextInt(2);      // 1 or 2 tall
            BlockState body = rand.nextBoolean() ? IRON_BLOCK : SMOOTH_STONE;
            for (int dx = 0; dx < w; dx++) {
                for (int dz = 0; dz < w; dz++) {
                    for (int i = 1; i <= boxH; i++) {
                        placeIfAir(level, origin.offset(bx + dx, sy + i, bz + dz), body);
                    }
                }
            }
            if (rand.nextBoolean()) {
                placeIfAir(level, origin.offset(bx, sy + boxH + 1, bz), HOPPER);
            }
        }
    }

    /**
     * Turn the interior roof surface into a small garden: solid roof tops become grass and get sparse
     * short grass / ferns / flowers with the occasional oak-leaf bush. Replacing the top surface with
     * garden dirt is intended; only occluding roof blocks are converted so fences/edges are spared.
     */
    private static void garden(WorldGenLevel level, BlockPos origin, int footprint, int height,
                               RandomSource rand) {
        int min = GARDEN_INSET;
        int max = footprint - 1 - GARDEN_INSET;
        for (int x = min; x <= max; x++) {
            for (int z = min; z <= max; z++) {
                int sy = surfaceY(level, origin, x, z, height);
                if (sy < 0) {
                    continue;
                }
                BlockPos top = origin.offset(x, sy, z);
                BlockState cur = level.getBlockState(top);
                if (!cur.canOcclude()) {
                    continue;   // only carpet a full, solid roof block
                }
                if (!isOutside(level, top)) {
                    level.setBlock(top, GRASS_BLOCK, SET_FLAGS);
                }
                BlockPos above = top.above();
                if (!level.getBlockState(above).isAir()) {
                    continue;
                }
                float r = rand.nextFloat();
                if (r < GARDEN_BUSH_CHANCE) {
                    placeIfAir(level, above, LEAF_BUSH);
                } else if (r < GARDEN_PLANT_CHANCE) {
                    placeIfAir(level, above, pickPlant(rand));
                }
                // otherwise leave bare grass, so the garden reads as patchy
            }
        }
    }

    private static BlockState pickPlant(RandomSource rand) {
        return switch (rand.nextInt(10)) {
            case 0, 1, 2, 3, 4 -> Blocks.SHORT_GRASS.defaultBlockState();
            case 5 -> Blocks.FERN.defaultBlockState();
            case 6 -> Blocks.POPPY.defaultBlockState();
            case 7 -> Blocks.DANDELION.defaultBlockState();
            case 8 -> Blocks.OXEYE_DAISY.defaultBlockState();
            default -> Blocks.CORNFLOWER.defaultBlockState();
        };
    }

    /**
     * A small raised tank on short leg posts near the roof centre: four fence legs carry a solid
     * stone-brick tank capped with a slab and a water cauldron.
     */
    private static void waterTower(WorldGenLevel level, BlockPos origin, int footprint, int height,
                                   RandomSource rand) {
        int size = WATER_TANK_SIZE;
        int x0 = footprint / 2 - size / 2;
        int z0 = footprint / 2 - size / 2;
        if (x0 < 0 || z0 < 0 || x0 + size > footprint || z0 + size > footprint) {
            return;
        }
        int sy = surfaceY(level, origin, footprint / 2, footprint / 2, height);
        if (sy < 0) {
            return;
        }
        int x1 = x0 + size - 1;
        int z1 = z0 + size - 1;
        // Legs at the four tank corners.
        raiseLeg(level, origin, x0, sy, z0);
        raiseLeg(level, origin, x1, sy, z0);
        raiseLeg(level, origin, x0, sy, z1);
        raiseLeg(level, origin, x1, sy, z1);
        // Tank body: two solid layers directly above the legs.
        int tankBase = sy + WATER_LEG_H + 1;
        for (int layer = 0; layer < 2; layer++) {
            for (int dx = 0; dx < size; dx++) {
                for (int dz = 0; dz < size; dz++) {
                    placeIfAir(level, origin.offset(x0 + dx, tankBase + layer, z0 + dz), STONE_BRICKS);
                }
            }
        }
        // Slab cap and a central water cauldron.
        int topY = tankBase + 2;
        for (int dx = 0; dx < size; dx++) {
            for (int dz = 0; dz < size; dz++) {
                placeIfAir(level, origin.offset(x0 + dx, topY, z0 + dz), SMOOTH_STONE_SLAB);
            }
        }
        placeIfAir(level, origin.offset(footprint / 2, topY + 1, footprint / 2), WATER_CAULDRON);
    }

    private static void raiseLeg(WorldGenLevel level, BlockPos origin, int x, int sy, int z) {
        for (int i = 1; i <= WATER_LEG_H; i++) {
            placeIfAir(level, origin.offset(x, sy + i, z), OAK_FENCE);
        }
    }

    /**
     * A small walled room on part of the roof: stone-brick walls (with a doorway), a slab roof and a
     * lantern inside. The room sits well within the footprint so it never fights the edge.
     */
    private static void penthouse(WorldGenLevel level, BlockPos origin, int footprint, int height,
                                  RandomSource rand) {
        int x0 = PH_ORIGIN;
        int z0 = PH_ORIGIN;
        int size = PH_SIZE;
        if (x0 + size > footprint || z0 + size > footprint) {
            return;
        }
        int sy = surfaceY(level, origin, x0 + size / 2, z0 + size / 2, height);
        if (sy < 0) {
            return;
        }
        int max = size - 1;
        int doorDx = size / 2;   // doorway on the south wall (dz == max)
        for (int dx = 0; dx < size; dx++) {
            for (int dz = 0; dz < size; dz++) {
                boolean edge = dx == 0 || dx == max || dz == 0 || dz == max;
                if (!edge) {
                    continue;
                }
                for (int i = 1; i <= PH_WALL_H; i++) {
                    boolean doorway = dz == max && dx == doorDx && i <= 2;
                    if (doorway) {
                        continue;   // leave a 1-wide, 2-tall door gap
                    }
                    placeIfAir(level, origin.offset(x0 + dx, sy + i, z0 + dz), STONE_BRICKS);
                }
            }
        }
        // Slab roof over the whole room.
        int roofY = sy + PH_WALL_H + 1;
        for (int dx = 0; dx < size; dx++) {
            for (int dz = 0; dz < size; dz++) {
                placeIfAir(level, origin.offset(x0 + dx, roofY, z0 + dz), STONE_BRICK_SLAB);
            }
        }
        // A lantern standing on the floor in the middle of the room.
        placeIfAir(level, origin.offset(x0 + size / 2, sy + 1, z0 + size / 2), LANTERN);
    }

    // --- helpers ------------------------------------------------------------------------------

    /**
     * Find the roof surface for a footprint column: scan down from the top and return the local Y of
     * the first solid (non-air) block, or -1 if the column is empty. Mirrors how
     * {@link Weathering}'s roofline pass locates the top of each column.
     */
    private static int surfaceY(WorldGenLevel level, BlockPos origin, int x, int z, int height) {
        for (int y = height - 1; y >= 0; y--) {
            if (!level.getBlockState(origin.offset(x, y, z)).isAir()) {
                return y;
            }
        }
        return -1;
    }

    /** Place {@code state} only if the target cell is air and inside the world's build height. */
    private static boolean placeIfAir(WorldGenLevel level, BlockPos pos, BlockState state) {
        if (isOutside(level, pos)) {
            return false;
        }
        if (!level.getBlockState(pos).isAir()) {
            return false;
        }
        level.setBlock(pos, state, SET_FLAGS);
        return true;
    }

    private static boolean isOutside(WorldGenLevel level, BlockPos pos) {
        return level.isOutsideBuildHeight(pos);
    }
}
