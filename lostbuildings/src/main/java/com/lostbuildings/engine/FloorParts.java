package com.lostbuildings.engine;

import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.List;

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
 * <p>Borrow policy: only buildings the engine already flagged as {@code hybrid} borrow at all. The
 * per-floor borrow chance depends on the floor's role. The <em>ground</em> floor is kept native
 * (almost) always so entrances and doors stay coherent; <em>middle</em> and <em>top</em> floors
 * borrow at a moderate rate for silhouette variety. A borrow picks a random <em>other</em> building
 * and asks it for a part with the same role/floor; if that donor has none we retry a bounded number
 * of times before falling back to the native part, so a floor is never left empty when the native
 * building could fill it and the pick is always deterministic from the passed {@code rand}.
 */
public final class FloorParts {

    private FloorParts() {}

    /**
     * Probability that a hybrid building borrows its <em>ground</em> floor from another building.
     * Kept very low so entrances and door alignment stay coherent.
     */
    private static final float BORROW_CHANCE_GROUND = 0.05f;

    /**
     * Probability that a hybrid building borrows a <em>middle</em> floor from another building.
     * Middle floors are the safest to vary, so this is the highest rate.
     */
    private static final float BORROW_CHANCE_MIDDLE = 0.50f;

    /**
     * Probability that a hybrid building borrows its capping <em>top</em> floor from another
     * building. Moderate: top swaps give the most obvious silhouette variety.
     */
    private static final float BORROW_CHANCE_TOP = 0.45f;

    /**
     * Maximum number of candidate donors tried before giving up and using the native part. Bounds
     * the work per floor so the pick can never loop unbounded when few donors have a matching role.
     */
    private static final int MAX_DONOR_ATTEMPTS = 4;

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
        // The native part is our guaranteed fallback for every path below.
        String nativePart = building.getRandomPart(rand, isTop, isGround, false, floor);

        // Non-hybrid buildings behave exactly like the original stub: native only, no borrowing.
        if (!hybrid) {
            return new Choice(nativePart, null);
        }

        // Decide whether this floor is allowed to borrow, based on its role.
        float borrowChance = isGround ? BORROW_CHANCE_GROUND
                : isTop ? BORROW_CHANCE_TOP
                : BORROW_CHANCE_MIDDLE;
        if (rand.nextFloat() >= borrowChance) {
            return new Choice(nativePart, null);
        }

        // Gather candidate donors: every loaded building except the native one.
        List<Building> donors = new ArrayList<>();
        for (Building candidate : assets.getAllBuildings()) {
            if (candidate != building) {
                donors.add(candidate);
            }
        }
        if (donors.isEmpty()) {
            return new Choice(nativePart, null);
        }

        // Try a bounded number of random donors; accept the first that has a role-matching part.
        int attempts = Math.min(MAX_DONOR_ATTEMPTS, donors.size());
        for (int i = 0; i < attempts; i++) {
            Building donor = donors.get(rand.nextInt(donors.size()));
            String borrowed = donor.getRandomPart(rand, isTop, isGround, false, floor);
            if (borrowed != null) {
                return new Choice(borrowed, donor);
            }
        }

        // No suitable borrow found within the attempt budget: keep the native part.
        return new Choice(nativePart, null);
    }
}
