package com.lostbuildings.world.structure;

/**
 * Which {@code street_*} part a street cell gets, and how far it is turned (PORT #3).
 *
 * <p>This is the whole of the street-tile port: the original picked the tile in
 * {@code LostCityTerrainFeature.generateNormalStreetSection} from four booleans "is the neighbour on
 * this side a road", and everything else about it was engine plumbing. Here the four booleans arrive
 * pre-computed as {@link CityLayout.Cell#neighbourMask()}, so the selection is a pure function of an
 * {@code int} — no world, no Minecraft, and therefore a unit test rather than a screenshot.
 *
 * <p><b>The shipped tiles and their unrotated orientation</b> (read off the JSON; {@code S} is road,
 * {@code b} is pavement, and the part rows are Z, the characters X):
 * <ul>
 *   <li>{@code street_none} — an island of road with no arms at all;</li>
 *   <li>{@code street_end} — one arm, pointing <b>west</b>;</li>
 *   <li>{@code street_straight} — a west–east road;</li>
 *   <li>{@code street_bend} — arms west and <b>north</b>;</li>
 *   <li>{@code street_t} — arms west, east and north (i.e. everything but south);</li>
 *   <li>{@code street_all} — a four-way crossing;</li>
 *   <li>{@code street_full} — a full-width slab with no pavement, used where a cell is entirely
 *       surfaced (kept configurable, not selected by connectivity).</li>
 * </ul>
 * Rotations are clockwise quarter turns, matching {@code CityLayout.Cell.quarterTurns} and
 * {@code engine.Transform.values()}; a clockwise turn takes the west arm to north.
 */
public final class StreetTiles {

	public static final String NONE = "street_none";
	public static final String END = "street_end";
	public static final String STRAIGHT = "street_straight";
	public static final String BEND = "street_bend";
	public static final String T = "street_t";
	public static final String ALL = "street_all";
	public static final String FULL = "street_full";

	/** A tile choice: which part to place and how many clockwise quarter turns to place it with. */
	public record Tile(String part, int quarterTurns) {
	}

	private StreetTiles() {
	}

	/**
	 * The tile for a road-connectivity mask.
	 *
	 * @param mask OR of {@link CityLayout#NORTH}/{@link CityLayout#EAST}/{@link CityLayout#SOUTH}/
	 *             {@link CityLayout#WEST} for every side that continues into another road cell
	 * @return the part name and its clockwise quarter turns; never {@code null}
	 */
	public static Tile forMask(int mask) {
		boolean north = (mask & CityLayout.NORTH) != 0;
		boolean east = (mask & CityLayout.EAST) != 0;
		boolean south = (mask & CityLayout.SOUTH) != 0;
		boolean west = (mask & CityLayout.WEST) != 0;
		int count = (north ? 1 : 0) + (east ? 1 : 0) + (south ? 1 : 0) + (west ? 1 : 0);

		return switch (count) {
			case 0 -> new Tile(NONE, 0);
			// One arm. Base points west, so a clockwise turn walks west -> north -> east -> south.
			case 1 -> new Tile(END, west ? 0 : north ? 1 : east ? 2 : 3);
			case 2 -> {
				if (west && east) {
					yield new Tile(STRAIGHT, 0);
				}
				if (north && south) {
					yield new Tile(STRAIGHT, 1);
				}
				// Base bend is west+north; turning clockwise gives north+east, east+south, south+west.
				if (west && north) {
					yield new Tile(BEND, 0);
				}
				if (north && east) {
					yield new Tile(BEND, 1);
				}
				if (east && south) {
					yield new Tile(BEND, 2);
				}
				yield new Tile(BEND, 3);
			}
			// Base T is missing its south arm; a clockwise turn moves the missing arm to west.
			case 3 -> new Tile(T, !south ? 0 : !west ? 1 : !north ? 2 : 3);
			default -> new Tile(ALL, 0);
		};
	}

	/**
	 * Whether a mask describes a road that carries traffic straight through on one axis. Used by the
	 * kerb decoration to know which two edges are pavement worth putting lamps on.
	 */
	public static boolean isThroughRoad(int mask) {
		boolean westEast = (mask & (CityLayout.WEST | CityLayout.EAST)) == (CityLayout.WEST | CityLayout.EAST);
		boolean northSouth = (mask & (CityLayout.NORTH | CityLayout.SOUTH)) == (CityLayout.NORTH | CityLayout.SOUTH);
		return westEast ^ northSouth;
	}
}
