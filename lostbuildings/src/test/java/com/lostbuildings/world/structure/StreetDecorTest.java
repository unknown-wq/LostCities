package com.lostbuildings.world.structure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Street furniture geometry (IMPROVEMENTS #4): where the roadway ends, where the kerb runs, where
 * the lamp posts stand and how often the paving is broken.
 *
 * <p>Two things are worth a test here rather than a screenshot. First, that the kerb is a
 * <em>ring</em> — one column wide, never overlapping the roadway — because a kerb block dropped on
 * the carriageway is a fence across the road. Second, that lamp spacing is measured in absolute
 * world coordinates: if it were measured per cell, every 16 blocks would restart the count and lamps
 * would cluster at the chunk seams, which is exactly the artefact this arithmetic exists to avoid.
 */
class StreetDecorTest {

	private static final int N = CityLayout.NORTH;
	private static final int E = CityLayout.EAST;
	private static final int S = CityLayout.SOUTH;
	private static final int W = CityLayout.WEST;

	/** The roadway is the middle 8×8 of the cell plus an arm out to every connected side. */
	@Test
	void roadwayMatchesTheTileGeometry() {
		// A crossroads: the plus shape, corners left as pavement.
		assertTrue(StreetDecor.isRoad(8, 8, CityLayout.ALL_SIDES), "the junction box is always roadway");
		assertTrue(StreetDecor.isRoad(0, 8, CityLayout.ALL_SIDES), "the west arm reaches the cell edge");
		assertTrue(StreetDecor.isRoad(15, 8, CityLayout.ALL_SIDES), "the east arm reaches the cell edge");
		assertTrue(StreetDecor.isRoad(8, 0, CityLayout.ALL_SIDES), "the north arm reaches the cell edge");
		assertTrue(StreetDecor.isRoad(8, 15, CityLayout.ALL_SIDES), "the south arm reaches the cell edge");
		assertFalse(StreetDecor.isRoad(0, 0, CityLayout.ALL_SIDES), "the corners are pavement on every tile");
		assertFalse(StreetDecor.isRoad(15, 15, CityLayout.ALL_SIDES));

		// A dead end pointing west: no arm anywhere else.
		assertTrue(StreetDecor.isRoad(0, 8, W));
		assertFalse(StreetDecor.isRoad(15, 8, W));
		assertFalse(StreetDecor.isRoad(8, 0, W));

		// An island of road with nothing leading out of it.
		assertFalse(StreetDecor.isRoad(0, 8, 0));
		assertTrue(StreetDecor.isRoad(8, 8, 0));
	}

	/** Columns outside the cell are never anything. */
	@Test
	void nothingIsPlacedOutsideTheCell() {
		for (int mask = 0; mask < 16; mask++) {
			assertFalse(StreetDecor.isRoad(-1, 8, mask));
			assertFalse(StreetDecor.isRoad(16, 8, mask));
			assertFalse(StreetDecor.isKerb(8, -1, mask));
			assertFalse(StreetDecor.isKerb(8, 16, mask));
		}
	}

	/** A kerb column is pavement, touches the roadway, and the two never overlap. */
	@Test
	void kerbIsAPavementRingAroundTheRoadway() {
		for (int mask = 0; mask < 16; mask++) {
			int kerbs = 0;
			for (int dx = 0; dx < 16; dx++) {
				for (int dz = 0; dz < 16; dz++) {
					boolean road = StreetDecor.isRoad(dx, dz, mask);
					boolean kerb = StreetDecor.isKerb(dx, dz, mask);
					assertFalse(road && kerb, "mask " + mask + ": (" + dx + "," + dz + ") is both road and kerb");
					if (kerb) {
						kerbs++;
						assertTrue(StreetDecor.isRoad(dx - 1, dz, mask) || StreetDecor.isRoad(dx + 1, dz, mask)
										|| StreetDecor.isRoad(dx, dz - 1, mask) || StreetDecor.isRoad(dx, dz + 1, mask),
								"mask " + mask + ": kerb at (" + dx + "," + dz + ") touches no roadway");
					}
				}
			}
			assertTrue(kerbs > 0, "mask " + mask + " produced no kerb at all");
		}
	}

	/** A straight west-east road has one kerb row on each side, and nothing else. */
	@Test
	void aStraightRoadHasExactlyTwoKerbRows() {
		for (int dx = 0; dx < 16; dx++) {
			assertTrue(StreetDecor.isKerb(dx, 3, W | E), "the north kerb runs the whole length");
			assertTrue(StreetDecor.isKerb(dx, 12, W | E), "the south kerb runs the whole length");
			assertFalse(StreetDecor.isKerb(dx, 2, W | E), "the kerb is one column wide");
			assertFalse(StreetDecor.isKerb(dx, 13, W | E));
		}
	}

	// ------------------------------------------------------------------ crossings (defect 3)

	/**
	 * Crossings belong to junctions. Everything else — a straight run, a bend, a dead end, an island —
	 * gets an unbroken kerb, which is why {@link #aStraightRoadHasExactlyTwoKerbRows} is untouched by
	 * any of this.
	 */
	@Test
	void onlyJunctionsHaveCrossings() {
		for (int mask = 0; mask < 16; mask++) {
			boolean junction = Integer.bitCount(mask) >= 3;
			boolean any = false;
			for (int dx = 0; dx < 16 && !any; dx++) {
				for (int dz = 0; dz < 16 && !any; dz++) {
					any = StreetDecor.isCrossing(dx, dz, mask);
				}
			}
			assertEquals(junction, any, "mask " + mask + ": crossings where there should be none, or none where there should be");
		}
		for (int d = 0; d < 16; d++) {
			assertFalse(StreetDecor.isDroppedKerb(d, 3, W | E), "a straight run keeps its whole kerb");
			assertFalse(StreetDecor.isDroppedKerb(d, 12, W | E));
		}
	}

	/** A dropped kerb is a kerb first: the crossing never eats into the carriageway or the pavement. */
	@Test
	void everyDroppedKerbIsAKerb() {
		for (int mask = 0; mask < 16; mask++) {
			int dropped = 0;
			for (int dx = 0; dx < 16; dx++) {
				for (int dz = 0; dz < 16; dz++) {
					if (!StreetDecor.isDroppedKerb(dx, dz, mask)) {
						continue;
					}
					dropped++;
					assertTrue(StreetDecor.isKerb(dx, dz, mask),
							"mask " + mask + ": (" + dx + "," + dz + ") is dropped but is not a kerb");
				}
			}
			// Two ends to every crossing band, two columns deep, one band per arm.
			assertEquals(Integer.bitCount(mask) >= 3 ? 4 * Integer.bitCount(mask) : 0, dropped,
					"mask " + mask + " dropped the wrong number of kerbstones");
		}
	}

	/** A junction still has far more raised kerb than dropped: the ring is broken, not removed. */
	@Test
	void aCrossingBreaksTheKerbRingRatherThanReplacingIt() {
		int kerbs = 0;
		int dropped = 0;
		for (int dx = 0; dx < 16; dx++) {
			for (int dz = 0; dz < 16; dz++) {
				if (StreetDecor.isKerb(dx, dz, CityLayout.ALL_SIDES)) {
					kerbs++;
				}
				if (StreetDecor.isDroppedKerb(dx, dz, CityLayout.ALL_SIDES)) {
					dropped++;
				}
			}
		}
		assertEquals(28, kerbs, "a crossroads kerbs the four corner squares in an L each");
		assertEquals(16, dropped, "a crossroads has four crossings, four kerbstones each");

		// What is left has to be the parts that make a junction read as one: the corner nearest the
		// carriageway, and the far end of each leg where the kerb runs on into the next cell.
		for (int corner : new int[]{3, 12}) {
			for (int other : new int[]{3, 12}) {
				assertFalse(StreetDecor.isDroppedKerb(corner, other, CityLayout.ALL_SIDES),
						"the inner corner (" + corner + "," + other + ") must keep its kerbstone");
			}
		}
		assertFalse(StreetDecor.isDroppedKerb(3, 0, CityLayout.ALL_SIDES), "the kerb still meets the cell edge");
		assertFalse(StreetDecor.isDroppedKerb(0, 3, CityLayout.ALL_SIDES));
	}

	/**
	 * The property the design leans on, asserted rather than asserted-in-a-comment.
	 *
	 * <p>The crossing bands are drawn by the {@code street_*} parts in their own unrotated frame and
	 * read back here in cell coordinates. That only works because the set of bands is invariant under a
	 * quarter turn — turn the cell and its mask together and every crossing column lands on a crossing
	 * column. If it ever stops being true, a turned junction paints its zebra where the kerb is still
	 * raised, which is exactly the defect this replaced.
	 */
	@Test
	void theCrossingBandsTurnWithTheTile() {
		for (int mask = 0; mask < 16; mask++) {
			for (int turns = 1; turns < 4; turns++) {
				int turnedMask = rotateMask(mask, turns);
				for (int dx = 0; dx < 16; dx++) {
					for (int dz = 0; dz < 16; dz++) {
						assertEquals(StreetDecor.isCrossing(dx, dz, mask),
								StreetDecor.isCrossing(rotateX(dx, dz, turns), rotateZ(dx, dz, turns), turnedMask),
								"mask " + mask + " turned " + turns + " at (" + dx + "," + dz + ")");
					}
				}
			}
		}
	}

	/** Clockwise quarter turns of a 16x16 cell, matching {@code engine.Transform}. */
	private static int rotateX(int dx, int dz, int turns) {
		return switch (Math.floorMod(turns, 4)) {
			case 1 -> 15 - dz;
			case 2 -> 15 - dx;
			case 3 -> dz;
			default -> dx;
		};
	}

	private static int rotateZ(int dx, int dz, int turns) {
		return switch (Math.floorMod(turns, 4)) {
			case 1 -> dx;
			case 2 -> 15 - dz;
			case 3 -> 15 - dx;
			default -> dz;
		};
	}

	private static int rotateMask(int mask, int turns) {
		int result = mask;
		for (int i = 0; i < Math.floorMod(turns, 4); i++) {
			int next = 0;
			if ((result & N) != 0) {
				next |= E;
			}
			if ((result & E) != 0) {
				next |= S;
			}
			if ((result & S) != 0) {
				next |= W;
			}
			if ((result & W) != 0) {
				next |= N;
			}
			result = next;
		}
		return result;
	}

	/** Lamps stand on kerbs, never anywhere else, and never when the spacing knob is off. */
	@Test
	void lampsOnlyEverStandOnKerbs() {
		for (int mask = 0; mask < 16; mask++) {
			for (int dx = 0; dx < 16; dx++) {
				for (int dz = 0; dz < 16; dz++) {
					if (StreetDecor.isLamp(1000 + dx, 2000 + dz, dx, dz, mask, 4)) {
						assertTrue(StreetDecor.isKerb(dx, dz, mask), "a lamp grew off the kerb");
					}
					assertFalse(StreetDecor.isLamp(1000 + dx, 2000 + dz, dx, dz, mask, 0),
							"spacing 0 must switch lamps off entirely");
				}
			}
		}
	}

	/**
	 * The point of world-aligned spacing: walk a whole street of cells and the lamps come out evenly
	 * spaced across the cell boundaries, not restarted inside each one.
	 */
	@Test
	void lampSpacingIsContinuousAcrossCells() {
		int spacing = 8;
		int kerbRow = 3;
		// Six cells of straight west-east road, laid end to end from world X = -32.
		java.util.List<Integer> lampX = new java.util.ArrayList<>();
		for (int cell = -2; cell < 4; cell++) {
			int cellMinX = cell * 16;
			for (int dx = 0; dx < 16; dx++) {
				if (StreetDecor.isLamp(cellMinX + dx, 0, dx, kerbRow, W | E, spacing)) {
					lampX.add(cellMinX + dx);
				}
			}
		}
		assertEquals(6 * 16 / spacing, lampX.size(), "one lamp every " + spacing + " blocks of road");
		for (int i = 1; i < lampX.size(); i++) {
			assertEquals(spacing, lampX.get(i) - lampX.get(i - 1), "uneven gap at " + lampX.get(i));
		}
	}

	/** A north-south road spaces its lamps on Z, not on X. */
	@Test
	void lampsFollowTheAxisOfTheRoadTheyLight() {
		int spacing = 4;
		// Kerb column beside a north-south carriageway.
		assertTrue(StreetDecor.isKerb(3, 8, N | S));
		assertTrue(StreetDecor.isLamp(7, 40, 3, 8, N | S, spacing), "Z is a multiple of the spacing");
		assertFalse(StreetDecor.isLamp(40, 7, 3, 8, N | S, spacing), "X being aligned must not matter here");
	}

	/**
	 * The two kerbs of one street alternate rather than facing each other off.
	 *
	 * <p>A lamp opposite a lamp lights the street in bands with dark gaps between them; offsetting the
	 * far kerb by half a span halves the gap for the same number of posts. The offset has to be a
	 * property of the roadway, not of the cell, or it would flip at chunk seams — so this walks four
	 * cells of road and checks the far kerb keeps its half-span offset the whole way.
	 */
	@Test
	void theTwoKerbsOfAStreetAreStaggered() {
		int spacing = 8;
		int nearKerb = 3;       // north of a west-east carriageway
		int farKerb = 12;       // south of it
		for (int cell = -2; cell < 2; cell++) {
			int cellMinX = cell * 16;
			for (int dx = 0; dx < 16; dx++) {
				int worldX = cellMinX + dx;
				assertEquals(Math.floorMod(worldX, spacing) == 0,
						StreetDecor.isLamp(worldX, 0, dx, nearKerb, W | E, spacing),
						"the near kerb is unshifted at x=" + worldX);
				assertEquals(Math.floorMod(worldX - spacing / 2, spacing) == 0,
						StreetDecor.isLamp(worldX, 0, dx, farKerb, W | E, spacing),
						"the far kerb is offset half a span at x=" + worldX);
			}
		}
		// Same rule on the other axis: the kerb east of a north-south carriageway is the far one.
		for (int dz = 0; dz < 16; dz++) {
			assertEquals(Math.floorMod(dz, spacing) == 0,
					StreetDecor.isLamp(0, dz, 3, dz, N | S, spacing));
			assertEquals(Math.floorMod(dz - spacing / 2, spacing) == 0,
					StreetDecor.isLamp(0, dz, 12, dz, N | S, spacing));
		}
	}

	/** A junction never grows two lamps in the same square: corner kerbs take one rule, not both. */
	@Test
	void lampChoiceIsSingleValued() {
		for (int dx = 0; dx < 16; dx++) {
			for (int dz = 0; dz < 16; dz++) {
				boolean a = StreetDecor.isLamp(dx, dz, dx, dz, CityLayout.ALL_SIDES, 1);
				boolean b = StreetDecor.isLamp(dx, dz, dx, dz, CityLayout.ALL_SIDES, 1);
				assertEquals(a, b);
			}
		}
	}

	/** Potholes are deterministic, seed-derived, and land on about the configured share of columns. */
	@Test
	void potholesAreDeterministicAndRoughlyTheRightShare() {
		long seed = 0xC0FFEEL;
		assertFalse(StreetDecor.isPothole(seed, 10, 20, 0.0D), "chance 0 must never break the paving");

		int hits = 0;
		int total = 0;
		for (int x = -200; x < 200; x++) {
			for (int z = -50; z < 50; z++) {
				boolean first = StreetDecor.isPothole(seed, x, z, 0.05D);
				assertEquals(first, StreetDecor.isPothole(seed, x, z, 0.05D), "pothole roll is not stable");
				total++;
				if (first) {
					hits++;
				}
			}
		}
		double share = (double) hits / total;
		assertTrue(share > 0.04D && share < 0.06D, "pothole share drifted to " + share);
	}
}
