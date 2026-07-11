package com.lostbuildings.engine;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;

/**
 * Procedural rooftop features layered on top of a finished building.
 *
 * <p>Every building already ends in an authored capping "top" part, but those tops are few, so the
 * skyline reads as repetitive. This pass adds a randomly chosen rooftop <em>feature</em> on top of
 * the existing roof surface — e.g. a parapet ring, an antenna / mast, a water tower, a small rooftop
 * garden, an HVAC cluster, or simply nothing — so buildings gain distinct silhouettes without any
 * new authored parts. It layers on top of whatever the top part already placed rather than replacing
 * it, which keeps it robust.
 *
 * <p>All writes stay inside the building's own 16x16 footprint column and use worldgen-safe flags
 * (never trigger neighbour updates); the pass is fully deterministic from the passed random source
 * and never throws. This is the stub (no-op); the real rooftop generator is layered in here.
 */
public final class Roofs {

    private Roofs() {}

    /**
     * Add a rooftop feature to the building occupying the {@code footprint} x {@code footprint}
     * column that starts at {@code origin} (ground-floor bottom corner) and rises {@code height}
     * blocks. The actual roof surface is found per column by scanning down from the top.
     *
     * @param level     worldgen level
     * @param origin    NW-bottom corner of the building footprint (ground-floor base Y)
     * @param footprint footprint size in blocks (square; 16)
     * @param height    generated building height in blocks above {@code origin}
     * @param rand      random source
     */
    public static void apply(WorldGenLevel level, BlockPos origin, int footprint, int height, RandomSource rand) {
        // Stub: no rooftop features yet.
    }
}
