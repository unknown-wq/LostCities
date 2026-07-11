package com.lostbuildings.engine;

import net.minecraft.util.RandomSource;

/**
 * Chooses which part fills a given floor of a building.
 *
 * <p>Normally a building's floors are drawn only from that building's own part list, so every
 * instance of {@code building3} has the same vocabulary of floors. This seam lets a building be a
 * <em>hybrid</em>: for some floors it borrows a role-compatible part (same {@code top/ground/floor}
 * role) from a <em>different</em> building, so the same handful of authored buildings recombine into
 * many novel silhouettes. Because almost every part draws its blocks from the shared <em>style</em>
 * palette (only a few parts carry a local palette), borrowed parts render correctly; the engine also
 * merges the donor's local palette and falls back to the native part if a borrowed part cannot
 * resolve, so hybrids never break a building.
 *
 * <p>This is the stub (native behaviour only); the real hybrid logic is layered in here.
 */
public final class FloorParts {

    private FloorParts() {}

    /**
     * The chosen part for a floor, plus the building it was borrowed from ({@code donor == null}
     * means the native building — no extra palette merge needed).
     */
    public record Choice(String partName, Building donor) {}

    /**
     * Pick the part for one floor.
     *
     * @param assets   loaded assets (to enumerate donor buildings)
     * @param building the native building being generated
     * @param rand     random source
     * @param isTop    this is the capping top floor
     * @param isGround this is the ground floor
     * @param floor    0-based floor index
     * @param hybrid   whether this building instance is allowed to borrow parts from others
     * @return a {@link Choice} (never null); {@code partName} may be null if no part matches
     */
    public static Choice pick(Assets assets, Building building, RandomSource rand,
                              boolean isTop, boolean isGround, int floor, boolean hybrid) {
        // Stub: native behaviour only — draw from the building's own parts, no borrowing.
        return new Choice(building.getRandomPart(rand, isTop, isGround, false, floor), null);
    }
}
