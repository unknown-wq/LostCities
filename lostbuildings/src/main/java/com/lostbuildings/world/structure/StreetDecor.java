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

	/**
	 * Whether a lamp post stands on this column.
	 *
	 * <p>A lamp needs a kerb to stand on, and its spacing is counted along the road it lights: a kerb
	 * beside an east–west carriageway is spaced on the world X axis, one beside a north–south
	 * carriageway on world Z. Kerbs at the corners of a junction touch both and take the X rule, so
	 * a junction never grows two lamps in the same square.
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
			return Math.floorMod(worldX, spacing) == 0;
		}
		return Math.floorMod(worldZ, spacing) == 0;
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
