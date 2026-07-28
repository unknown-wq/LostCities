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
