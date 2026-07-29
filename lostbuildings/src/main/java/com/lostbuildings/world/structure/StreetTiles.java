package com.lostbuildings.world.structure;

import java.util.List;
import java.util.Map;
import java.util.Set;

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
 *   <li>{@code street_all} — a four-way crossing.</li>
 * </ul>
 * Rotations are clockwise quarter turns, matching {@code CityLayout.Cell.quarterTurns} and
 * {@code engine.Transform.values()}; a clockwise turn takes the west arm to north.
 *
 * <p>The two junction tiles ({@code street_t}, {@code street_all}) paint a zebra crossing across
 * every arm, in a character their own local palette defines. Where those bands meet the pavement,
 * {@link StreetDecor#isDroppedKerb} tells {@code StreetPiece} to leave the kerbstone off.
 *
 * <p><b>There is no {@code street_full}.</b> Upstream it is not a mask case at all: {@code FULL} is a
 * value of {@code BuildingInfo.StreetType}, rolled per chunk alongside {@code PARK} and dispatched by
 * {@code LostCityTerrainFeature.generateFullStreetSection} — a whole cell surfaced kerb to kerb, with
 * no connectivity involved. It is also dead upstream: the roll is
 * {@code StreetType.values()[rand.nextInt(0, values().length - 2)]}, and with three values that is
 * {@code nextInt(0, 1)}, which can only return {@code NORMAL}. Nothing in this port carries a street
 * type, and a pavement-less cell would contradict {@link StreetDecor}, whose kerb ring, lamp posts and
 * dropped kerbs are all derived from the mask's road/pavement split. So the constant and
 * {@code parts/street_full.json} were deleted rather than left as a promise the code does not keep.
 */
public final class StreetTiles {

	public static final String NONE = "street_none";
	public static final String END = "street_end";
	public static final String STRAIGHT = "street_straight";
	public static final String BEND = "street_bend";
	public static final String T = "street_t";
	public static final String ALL = "street_all";

	/** Interchangeable redraws of {@link #STRAIGHT} — same geometry, different wear. */
	public static final String STRAIGHT_2 = "street_straight2";
	public static final String STRAIGHT_3 = "street_straight3";

	/**
	 * Every part that may stand in for a family, the family's own part first.
	 *
	 * <p><b>What a variant may and may not change.</b> Neighbouring cells choose their variants
	 * independently, so anything a variant draws on its outer ring has to be what every other member of
	 * every family it can abut draws there: the pavement, the gutter channel, the kerb line and the
	 * lane markings that cross a cell boundary. Only the interior — surface wear, puddles, weeds, the
	 * decoration slices above the road — is free.
	 * {@code StreetTilesTest.everyVariantIsARealPartThatSeamsWithItsFamily} is that rule written down as
	 * an assertion, and it is checked against the shipped JSON rather than against this list.
	 */
	private static final Map<String, List<String>> VARIANTS = Map.of(
			NONE, List.of(NONE),
			END, List.of(END),
			STRAIGHT, List.of(STRAIGHT, STRAIGHT_2, STRAIGHT_3),
			BEND, List.of(BEND),
			T, List.of(T),
			ALL, List.of(ALL));

	/** A tile choice: which part to place and how many clockwise quarter turns to place it with. */
	public record Tile(String part, int quarterTurns) {
	}

	private StreetTiles() {
	}

	/** The tile families, i.e. the parts {@link #forMask(int)} itself can name. */
	public static Set<String> families() {
		return VARIANTS.keySet();
	}

	/**
	 * The parts that may be laid for a family, the family's own part first. An unknown family has no
	 * variants at all.
	 */
	public static List<String> variantsOf(String family) {
		return VARIANTS.getOrDefault(family, List.of());
	}

	/**
	 * The tile for a road-connectivity mask, drawn as the family's own part.
	 *
	 * <p>Equivalent to {@link #forMask(int, long)} with a variant key of {@code 0}: the first entry of
	 * every family's variant list is the family's own part.
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
	 * The tile for a road-connectivity mask, drawn as one of its family's variants.
	 *
	 * <p><b>Why a key rather than a {@code RandomSource}.</b> A cell must draw the same tile every time
	 * its chunk is generated, and neighbouring cells must not agree with it, so the choice cannot come
	 * from the shared per-chunk decoration random. It comes from the same place every other per-cell
	 * decision in this package comes from — {@code CityLayout.hash(worldSeed, cellX, cellZ, salt)} — and
	 * this method only folds that value into the family's variant list. Keeping the fold here rather
	 * than at the call site is what makes it testable without a world, and what keeps the caller from
	 * having to know how many variants a family has.
	 *
	 * @param mask       as {@link #forMask(int)}
	 * @param variantKey any hash of the cell; {@code 0} selects the family's own part
	 */
	public static Tile forMask(int mask, long variantKey) {
		Tile family = forMask(mask);
		List<String> variants = variantsOf(family.part());
		if (variants.size() <= 1) {
			return family;
		}
		int index = (int) Math.floorMod(variantKey, (long) variants.size());
		return new Tile(variants.get(index), family.quarterTurns());
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
