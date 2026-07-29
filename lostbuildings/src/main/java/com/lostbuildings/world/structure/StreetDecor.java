package com.lostbuildings.world.structure;

/**
 * Where the street furniture goes (IMPROVEMENTS #4): kerbs, lamp posts and potholes.
 *
 * <p>All of it is pure arithmetic over a street cell's 16×16 columns, so it is unit-testable and —
 * more importantly — <em>world-aligned</em>. Lamp spacing is measured in absolute block coordinates
 * rather than per cell, which is what makes a row of lamps continue across a chunk boundary instead
 * of restarting at every cell and bunching up at the seams.
 *
 * <p>The geometry mirrors the shipped {@code street_*} parts exactly: the roadway is the middle
 * 8 columns of the cell ({@code 4..11}) plus an arm reaching to the edge on every connected side;
 * everything else is pavement. A kerb column is therefore a pavement column that touches the
 * roadway, which is a single ring one block wide — precisely where a kerbstone and a lamp post
 * belong.
 *
 * <p>The tiles raise the pavement one block above the carriageway and sink a gutter channel into the
 * road columns that touch it, so the kerb ring is the step between the two: the half slab
 * {@code StreetPiece} drops here is the middle of a road → kerb → pavement climb rather than a lip on
 * a flat surface. Everything the tiles put on the pavement is deliberately kept <em>off</em> this
 * ring, because {@code StreetPiece.decorate} overwrites it after the tile is stamped.
 *
 * <p>The one exception is a pedestrian crossing: {@link #isCrossing} marks the bands the junction
 * tiles paint across their arms, and {@link #isDroppedKerb} turns the ends of those bands into the
 * gaps in the ring that make a crossing walkable.
 */
public final class StreetDecor {

	/** First and last column of the roadway inside a cell (see {@code street_all.json}). */
	private static final int ROAD_MIN = 4;
	private static final int ROAD_MAX = 11;

	private StreetDecor() {
	}

	/**
	 * Whether the column {@code (dx, dz)} of a street cell carries roadway.
	 *
	 * @param mask the cell's road-connectivity mask (see {@link CityLayout.Cell#neighbourMask()})
	 */
	public static boolean isRoad(int dx, int dz, int mask) {
		if (dx < 0 || dz < 0 || dx > 15 || dz > 15) {
			return false;
		}
		boolean midX = dx >= ROAD_MIN && dx <= ROAD_MAX;
		boolean midZ = dz >= ROAD_MIN && dz <= ROAD_MAX;
		if (midX && midZ) {
			return true;    // the junction box every tile has
		}
		if (midZ && dx < ROAD_MIN) {
			return (mask & CityLayout.WEST) != 0;
		}
		if (midZ && dx > ROAD_MAX) {
			return (mask & CityLayout.EAST) != 0;
		}
		if (midX && dz < ROAD_MIN) {
			return (mask & CityLayout.NORTH) != 0;
		}
		if (midX && dz > ROAD_MAX) {
			return (mask & CityLayout.SOUTH) != 0;
		}
		return false;   // the four corner squares are always pavement
	}

	/** A pavement column that touches the roadway — one block wide, all the way round. */
	public static boolean isKerb(int dx, int dz, int mask) {
		if (dx < 0 || dz < 0 || dx > 15 || dz > 15 || isRoad(dx, dz, mask)) {
			return false;
		}
		return isRoad(dx - 1, dz, mask) || isRoad(dx + 1, dz, mask)
				|| isRoad(dx, dz - 1, mask) || isRoad(dx, dz + 1, mask);
	}

	// ------------------------------------------------------------------ pedestrian crossings

	/** First and last row of a crossing band, counted in from the cell edge it sits nearest. */
	private static final int CROSSING_NEAR = 1;
	private static final int CROSSING_FAR = 2;

	/**
	 * Whether the column {@code (dx, dz)} carries a pedestrian crossing.
	 *
	 * <p><b>Why this lives here and not in {@link StreetTiles}.</b> A crossing is drawn twice: once as
	 * paint, by the {@code street_*} part, in the part's own unrotated frame; and once as geometry, by
	 * {@link #isDroppedKerb} and hence by {@code StreetPiece}, in cell coordinates. Two descriptions
	 * that must agree are a standing invitation to drift apart — unless one of them needs no
	 * translating, which is what this shape is chosen for. The band across an arm is defined
	 * identically for all four arms, so the whole set is invariant under a quarter turn: turning the
	 * north band clockwise (a column at {@code (x, z)} lands at {@code (15 - z, x)}) maps
	 * {@code z ∈ 1..2, x ∈ 3..12} onto {@code x ∈ 13..14, z ∈ 3..12}, which is exactly the east band —
	 * and a tile is only ever turned so that its arms land on the mask's connected sides. So no
	 * rotation arithmetic is needed here, and none of {@code StreetTiles}' is copied.
	 *
	 * <p><b>Only junctions get one.</b> Three arms or four: somewhere traffic stops and turns, and a
	 * pedestrian has to get across it. A straight run and a dead end get nothing (which is why
	 * {@code aStraightRoadHasExactlyTwoKerbRows} is unaffected by any of this), and neither does a
	 * bend, where the two arms are one continuous curve rather than a meeting of streets.
	 *
	 * <p>The band spans the roadway <em>and</em> the kerb column on each side of it, so the two ends
	 * of it are the dropped kerbs.
	 */
	public static boolean isCrossing(int dx, int dz, int mask) {
		if (dx < 0 || dz < 0 || dx > 15 || dz > 15 || !isJunction(mask)) {
			return false;
		}
		boolean acrossX = dx >= ROAD_MIN - 1 && dx <= ROAD_MAX + 1;
		boolean acrossZ = dz >= ROAD_MIN - 1 && dz <= ROAD_MAX + 1;
		return (acrossX && inBand(dz) && (mask & CityLayout.NORTH) != 0)
				|| (acrossX && inBand(15 - dz) && (mask & CityLayout.SOUTH) != 0)
				|| (acrossZ && inBand(dx) && (mask & CityLayout.WEST) != 0)
				|| (acrossZ && inBand(15 - dx) && (mask & CityLayout.EAST) != 0);
	}

	/**
	 * A kerb column that a crossing runs into.
	 *
	 * <p>{@code StreetPiece} lays no kerbstone here and clears the column instead, so the paving stays
	 * flush with the carriageway and the crossing is something you can walk — rather than a painted
	 * strip that ends in a step, which is what the unconditional kerb ring used to produce.
	 */
	public static boolean isDroppedKerb(int dx, int dz, int mask) {
		return isKerb(dx, dz, mask) && isCrossing(dx, dz, mask);
	}

	/** Three arms or more: a place where streets meet rather than a street passing through. */
	private static boolean isJunction(int mask) {
		return Integer.bitCount(mask & CityLayout.ALL_SIDES) >= 3;
	}

	private static boolean inBand(int fromEdge) {
		return fromEdge >= CROSSING_NEAR && fromEdge <= CROSSING_FAR;
	}

	/**
	 * Whether a lamp post stands on this column.
	 *
	 * <p>A lamp needs a kerb to stand on, and its spacing is counted along the road it lights: a kerb
	 * beside an east–west carriageway is spaced on the world X axis, one beside a north–south
	 * carriageway on world Z. Kerbs at the corners of a junction touch both and take the X rule, so
	 * a junction never grows two lamps in the same square.
	 *
	 * <p><b>The two kerbs of one street are staggered.</b> Spacing alone put a lamp on the near kerb
	 * and another directly opposite it on the far one, which reads as a gate every {@code spacing}
	 * blocks rather than as a lit street. The far kerb — the one with the roadway to its north (for an
	 * X-spaced kerb) or to its west (for a Z-spaced one) — is offset by half a span, so the lamps
	 * alternate down the street and light it twice as evenly for the same number of posts. Which kerb
	 * counts as "far" is decided from the roadway alone, so it is the same answer on both sides of a
	 * cell boundary and the alternation carries across cells exactly as the spacing does.
	 *
	 * @param worldX  absolute block X of the column
	 * @param worldZ  absolute block Z of the column
	 * @param dx      column X inside the cell, {@code 0..15}
	 * @param dz      column Z inside the cell, {@code 0..15}
	 * @param mask    the cell's road-connectivity mask
	 * @param spacing blocks between lamps; {@code <= 0} disables them
	 */
	public static boolean isLamp(int worldX, int worldZ, int dx, int dz, int mask, int spacing) {
		if (spacing <= 0 || !isKerb(dx, dz, mask)) {
			return false;
		}
		if (isRoad(dx, dz - 1, mask) || isRoad(dx, dz + 1, mask)) {
			return Math.floorMod(worldX - stagger(isRoad(dx, dz - 1, mask), spacing), spacing) == 0;
		}
		return Math.floorMod(worldZ - stagger(isRoad(dx - 1, dz, mask), spacing), spacing) == 0;
	}

	/** Half a span of offset for the far kerb of a street, and none for the near one. */
	private static int stagger(boolean farKerb, int spacing) {
		return farKerb ? spacing / 2 : 0;
	}

	/**
	 * Whether this roadway column is broken up by a pothole.
	 *
	 * <p>Derived from the world seed and the absolute column, never from a per-chunk random: two
	 * runs on one seed have to break the same paving stones.
	 *
	 * @param chance share of roadway columns that are potholed, in {@code [0, 1]}
	 */
	public static boolean isPothole(long seed, int worldX, int worldZ, double chance) {
		if (chance <= 0.0D) {
			return false;
		}
		return CityLayout.unit(CityLayout.hash(seed, worldX, worldZ, POTHOLE_SALT)) < chance;
	}

	private static final int POTHOLE_SALT = 0x2B;
}
