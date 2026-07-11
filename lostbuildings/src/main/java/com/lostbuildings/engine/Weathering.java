package com.lostbuildings.engine;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * Lightweight procedural weathering / ruin pass, applied as the final step of a building's
 * generation. It ages the finished (already intact) structure in place: cracking and mossing a
 * fraction of the stone surfaces, adding sparse overgrowth (vines / cobwebs), knocking jagged
 * gaps into the roofline and dropping a little rubble at the base — so a generated building reads
 * as "lost / abandoned" rather than freshly built.
 *
 * <p>This is intentionally not the original heavyweight damage engine: it is a single self-contained
 * static sweep over the building's own 16x16 footprint column, driven entirely by the passed
 * {@link RandomSource} (so it is reproducible from the world seed) and it only ever writes inside
 * that footprint using the same worldgen-safe flags as {@link BuildingEngine}. It never throws.
 */
public final class Weathering {

    // Worldgen-safe flags: notify clients, do NOT trigger neighbour updates (bit 1 must stay off).
    // Kept identical to BuildingEngine.SET_FLAGS.
    private static final int SET_FLAGS = Block.UPDATE_CLIENTS;

    // --- tuning knobs (kept modest so buildings read as "weathered", not "destroyed") ---

    /** Chance a stone-brick block becomes CRACKED_STONE_BRICKS. */
    private static final float CRACK_CHANCE = 0.09f;
    /** Chance a stone-brick block becomes MOSSY_STONE_BRICKS (rolled after the crack range). */
    private static final float MOSS_CHANCE = 0.07f;
    /** Chance a cobblestone block becomes MOSSY_COBBLESTONE. */
    private static final float COBBLE_MOSS_CHANCE = 0.10f;
    /** Chance a plain stone block crumbles to COBBLESTONE. */
    private static final float STONE_CRUMBLE_CHANCE = 0.04f;

    /** Chance an interior air pocket (next to structure) grows a vine on a wall face. */
    private static final float VINE_CHANCE = 0.03f;
    /** Chance an interior air pocket (next to structure) gets a cobweb. */
    private static final float COBWEB_CHANCE = 0.015f;

    /** Chance the topmost solid block of a roof-band column is knocked out (broken roofline). */
    private static final float CRUMBLE_CHANCE = 0.22f;
    /** Follow-up chance to also remove the block just below a knocked-out top block. */
    private static final float CRUMBLE_FOLLOW_CHANCE = 0.35f;
    /** Height (in blocks, from the top down) of the band eligible for roofline crumbling. */
    private static final int ROOF_BAND = 6;

    /** Expected number of rubble spots dropped around the ground-floor edge per building. */
    private static final int RUBBLE_TRIES = 6;
    /** Chance each rubble try actually converts an eligible ground-edge block. */
    private static final float RUBBLE_CHANCE = 0.35f;

    private static final Direction[] HORIZONTALS =
            {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    private Weathering() {
    }

    /**
     * Weather the building occupying the {@code footprint} x {@code footprint} column that starts at
     * {@code origin} (the bottom corner of the ground floor) and rises {@code height} blocks. All
     * writes stay inside that column; neighbour reads outside it are harmless. Fully guarded — this
     * never throws.
     */
    public static void apply(WorldGenLevel level, BlockPos origin, int footprint, int height, RandomSource rand) {
        if (footprint <= 0 || height <= 0) {
            return;
        }
        try {
            crumbleRoofline(level, origin, footprint, height, rand);
            weatherSurfaces(level, origin, footprint, height, rand);
            dropRubble(level, origin, footprint, rand);
        } catch (Throwable ignored) {
            // Weathering is cosmetic: a failure must never abort or corrupt worldgen.
        }
    }

    /**
     * Knock jagged gaps into the roofline: for each column, find the topmost solid block within the
     * upper {@link #ROOF_BAND} band and occasionally remove it (and sometimes the one below). Removal
     * only — no floating blocks are ever added.
     */
    private static void crumbleRoofline(WorldGenLevel level, BlockPos origin, int footprint, int height,
                                        RandomSource rand) {
        int roofFloor = Math.max(1, height - ROOF_BAND);
        for (int x = 0; x < footprint; x++) {
            for (int z = 0; z < footprint; z++) {
                int topY = -1;
                for (int y = height - 1; y >= roofFloor; y--) {
                    BlockState st = level.getBlockState(origin.offset(x, y, z));
                    if (!st.isAir()) {
                        topY = y;
                        break;
                    }
                }
                if (topY < 0) {
                    continue;   // no solid block in the roof band of this column
                }
                if (rand.nextFloat() >= CRUMBLE_CHANCE) {
                    continue;
                }
                BlockPos top = origin.offset(x, topY, z);
                if (!level.getBlockState(top).isAir()) {
                    level.setBlock(top, BlockStates.AIR, SET_FLAGS);
                }
                // Occasionally eat the block just below too, for a more torn edge.
                if (topY - 1 >= roofFloor && rand.nextFloat() < CRUMBLE_FOLLOW_CHANCE) {
                    BlockPos below = origin.offset(x, topY - 1, z);
                    if (!level.getBlockState(below).isAir()) {
                        level.setBlock(below, BlockStates.AIR, SET_FLAGS);
                    }
                }
            }
        }
    }

    /**
     * Convert a fraction of stone surfaces to their cracked / mossy variants and add sparse
     * overgrowth (vines on walls, cobwebs in corners) throughout the volume.
     */
    private static void weatherSurfaces(WorldGenLevel level, BlockPos origin, int footprint, int height,
                                        RandomSource rand) {
        for (int x = 0; x < footprint; x++) {
            for (int z = 0; z < footprint; z++) {
                for (int y = 0; y < height; y++) {
                    BlockPos pos = origin.offset(x, y, z);
                    BlockState st = level.getBlockState(pos);
                    Block block = st.getBlock();

                    if (st.isAir()) {
                        overgrow(level, pos, rand);
                        continue;
                    }

                    // Material conversions: only transform a block that is exactly this material,
                    // keeping the simple default variant state.
                    if (block == Blocks.STONE_BRICKS) {
                        float r = rand.nextFloat();
                        if (r < CRACK_CHANCE) {
                            set(level, pos, Blocks.CRACKED_STONE_BRICKS.defaultBlockState());
                        } else if (r < CRACK_CHANCE + MOSS_CHANCE) {
                            set(level, pos, Blocks.MOSSY_STONE_BRICKS.defaultBlockState());
                        }
                    } else if (block == Blocks.COBBLESTONE) {
                        if (rand.nextFloat() < COBBLE_MOSS_CHANCE) {
                            set(level, pos, Blocks.MOSSY_COBBLESTONE.defaultBlockState());
                        }
                    } else if (block == Blocks.STONE) {
                        if (rand.nextFloat() < STONE_CRUMBLE_CHANCE) {
                            set(level, pos, Blocks.COBBLESTONE.defaultBlockState());
                        }
                    }
                }
            }
        }
    }

    /**
     * Sparse overgrowth for an air pocket: either hang a vine off an adjacent wall or spin a cobweb
     * in a sheltered corner. Only ever writes the (air) target itself.
     */
    private static void overgrow(WorldGenLevel level, BlockPos pos, RandomSource rand) {
        if (rand.nextFloat() < VINE_CHANCE) {
            BlockState vine = buildVine(level, pos);
            if (vine != null) {
                set(level, pos, vine);
                return;
            }
        }
        if (rand.nextFloat() < COBWEB_CHANCE && hasStructuralNeighbour(level, pos)) {
            set(level, pos, Blocks.COBWEB.defaultBlockState());
        }
    }

    /**
     * Build a vine state attached to the first solid horizontal neighbour of {@code pos}, or null if
     * there is no wall to cling to. Vines attach on the face pointing toward the supporting block.
     */
    private static BlockState buildVine(WorldGenLevel level, BlockPos pos) {
        BlockState vine = Blocks.VINE.defaultBlockState();
        boolean attached = false;
        for (Direction dir : HORIZONTALS) {
            BlockState neighbour = level.getBlockState(pos.relative(dir));
            if (neighbour.canOcclude()) {
                BooleanProperty prop = faceProperty(dir);
                if (prop != null && vine.hasProperty(prop)) {
                    vine = vine.setValue(prop, Boolean.TRUE);
                    attached = true;
                }
            }
        }
        return attached ? vine : null;
    }

    private static BooleanProperty faceProperty(Direction dir) {
        return switch (dir) {
            case NORTH -> PipeBlock.NORTH;
            case EAST -> PipeBlock.EAST;
            case SOUTH -> PipeBlock.SOUTH;
            case WEST -> PipeBlock.WEST;
            default -> null;
        };
    }

    private static boolean hasStructuralNeighbour(WorldGenLevel level, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            if (level.getBlockState(pos.relative(dir)).canOcclude()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Drop a little rubble around the ground-floor edge: convert a couple of eligible edge blocks on
     * the lowest two layers to gravel or a cobblestone slab, hinting at partial collapse. Only
     * existing solid edge blocks are replaced — nothing is added into open air.
     */
    private static void dropRubble(WorldGenLevel level, BlockPos origin, int footprint, RandomSource rand) {
        int max = footprint - 1;
        for (int i = 0; i < RUBBLE_TRIES; i++) {
            if (rand.nextFloat() >= RUBBLE_CHANCE) {
                continue;
            }
            // Pick a random point on the footprint edge.
            int x;
            int z;
            if (rand.nextBoolean()) {
                x = rand.nextInt(footprint);
                z = rand.nextBoolean() ? 0 : max;
            } else {
                x = rand.nextBoolean() ? 0 : max;
                z = rand.nextInt(footprint);
            }
            int y = rand.nextInt(2);   // lowest two layers of the ground floor
            BlockPos pos = origin.offset(x, y, z);
            BlockState st = level.getBlockState(pos);
            if (!st.canOcclude()) {
                continue;   // only rubble-ify an existing solid block, never fill air
            }
            BlockState rubble = rand.nextBoolean()
                    ? Blocks.GRAVEL.defaultBlockState()
                    : Blocks.COBBLESTONE_SLAB.defaultBlockState();
            set(level, pos, rubble);
        }
    }

    private static void set(WorldGenLevel level, BlockPos pos, BlockState state) {
        if (level.getBlockState(pos) != state) {
            level.setBlock(pos, state, SET_FLAGS);
        }
    }
}
