package com.lostbuildings.world.structure;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the city grid — the class that replaced {@code CellLattice}.
 *
 * <p>The invariants the old lattice test checked (a chunk cell is never claimed twice, the result is
 * deterministic) still have to hold, and the grid adds its own: no two buildings share a chunk edge,
 * a city is never empty, and — new in wave 2 — the street cells form one orthogonally connected
 * lattice, which is the whole reason the {@code street_*} tiles can be picked from a neighbour mask.
 * Pure arithmetic: no Minecraft classes are touched, so this runs in a plain JVM.
 */
class CityLayoutTest {

	private static final long SEED = 0x5EEDL;

	private static CityLayout.Settings settings() {
		return new CityLayout.Settings(5, 0.85D, 2, 6, 8, 16);
	}

	/** Full grid, no parks, no downtown — the shape tests want a deterministic skeleton. */
	private static CityLayout.Settings full(int size) {
		return new CityLayout.Settings(size, 1.0D, 2, 6, 8, 16);
	}

	private static long key(int x, int z) {
		return (((long) x) << 32) ^ (z & 0xffffffffL);
	}

	/** Cells are unique inside a plan: no chunk is ever written by two pieces of the same city. */
	@Test
	void noCellAppearsTwiceInAPlan() {
		for (int cx = -20; cx <= 20; cx++) {
			for (int cz = -20; cz <= 20; cz++) {
				CityLayout.Plan plan = CityLayout.plan(SEED, cx, cz, settings());
				Set<Long> seen = new HashSet<>();
				for (CityLayout.Cell cell : plan.cells()) {
					assertTrue(seen.add(key(cell.chunkX(), cell.chunkZ())),
							"cell " + cell.chunkX() + "," + cell.chunkZ() + " emitted twice");
				}
			}
		}
	}

	/**
	 * The invariant the whole grid exists for: a building's four orthogonal neighbours are never
	 * buildings, so the chunks between houses stay free for streets. {@code CellLattice} could only
	 * approximate this with a five-offset diagonal star, at the cost of most of the mod's output.
	 */
	@Test
	void buildingsNeverTouchOrthogonally() {
		CityLayout.Plan plan = CityLayout.plan(SEED, 3, -7, settings());
		Set<Long> buildings = new HashSet<>();
		for (CityLayout.Cell cell : plan.cellsOf(CityLayout.Role.BUILDING)) {
			buildings.add(key(cell.chunkX(), cell.chunkZ()));
		}
		assertFalse(buildings.isEmpty(), "expected the city to contain buildings");
		for (CityLayout.Cell cell : plan.cellsOf(CityLayout.Role.BUILDING)) {
			assertFalse(buildings.contains(key(cell.chunkX() + 1, cell.chunkZ())), "east neighbour is a building");
			assertFalse(buildings.contains(key(cell.chunkX() - 1, cell.chunkZ())), "west neighbour is a building");
			assertFalse(buildings.contains(key(cell.chunkX(), cell.chunkZ() + 1)), "south neighbour is a building");
			assertFalse(buildings.contains(key(cell.chunkX(), cell.chunkZ() - 1)), "north neighbour is a building");
		}
	}

	/**
	 * The wave-2 fix: every street cell can be walked to from every other one without leaving the
	 * road. Wave 1's checkerboard failed this — its street cells only met at their corners, which its
	 * own status file recorded as "diagonally connected, not orthogonally".
	 */
	@Test
	void streetCellsFormOneConnectedLattice() {
		for (int size : new int[]{5, 7, 9}) {
			List<CityLayout.Cell> streets = CityLayout.plan(SEED, 0, 0, full(size)).cellsOf(CityLayout.Role.STREET);
			Set<Long> remaining = new HashSet<>();
			for (CityLayout.Cell cell : streets) {
				remaining.add(key(cell.chunkX(), cell.chunkZ()));
			}
			assertFalse(remaining.isEmpty(), "size " + size + " produced no streets at all");

			// Flood fill from the first street cell; anything left over is an unreachable island.
			CityLayout.Cell start = streets.getFirst();
			java.util.ArrayDeque<long[]> queue = new java.util.ArrayDeque<>();
			queue.add(new long[]{start.chunkX(), start.chunkZ()});
			remaining.remove(key(start.chunkX(), start.chunkZ()));
			while (!queue.isEmpty()) {
				long[] at = queue.poll();
				int x = (int) at[0];
				int z = (int) at[1];
				for (int[] step : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
					long next = key(x + step[0], z + step[1]);
					if (remaining.remove(next)) {
						queue.add(new long[]{x + step[0], z + step[1]});
					}
				}
			}
			assertTrue(remaining.isEmpty(), "size " + size + " left " + remaining.size() + " unreachable street cells");
		}
	}

	/** A street's mask counts roads, not houses — otherwise every tile would be a crossroads. */
	@Test
	void streetMasksCountOnlyOtherStreets() {
		CityLayout.Plan plan = CityLayout.plan(SEED, 0, 0, full(5));

		// (1, 0) is one cell east of the centre building: a north-south carriageway.
		CityLayout.Cell straight = find(plan.cells(), 1, 0);
		assertEquals(CityLayout.Role.STREET, straight.role());
		assertEquals(CityLayout.NORTH | CityLayout.SOUTH, straight.neighbourMask(),
				"a carriageway between two lots continues only along its own axis");

		// (1, 1) is diagonally off the centre: the crossroads where the two carriageways meet.
		CityLayout.Cell crossing = find(plan.cells(), 1, 1);
		assertEquals(CityLayout.Role.STREET, crossing.role());
		assertEquals(CityLayout.ALL_SIDES, crossing.neighbourMask(), "the junction reaches all four ways");
	}

	/** A building's mask still counts everything around it — it describes its lot, not a road. */
	@Test
	void buildingMasksCountEveryNeighbour() {
		CityLayout.Plan plan = CityLayout.plan(SEED, 0, 0, full(5));
		CityLayout.Cell corner = find(plan.cells(), -2, -2);
		assertEquals(CityLayout.Role.BUILDING, corner.role());
		assertEquals(CityLayout.EAST | CityLayout.SOUTH, corner.neighbourMask(),
				"the north-west corner only has neighbours inside the city");

		CityLayout.Cell centre = find(plan.cells(), 0, 0);
		assertEquals(CityLayout.ALL_SIDES, centre.neighbourMask(), "the centre lot is surrounded by street");
	}

	/**
	 * No empty firings. The old lattice built nothing at all in about a third of its attempts; the
	 * centre cell here is unconditional, so every city has at least one house even at density 0.
	 */
	@Test
	void everyCityHasAtLeastOneBuilding() {
		CityLayout.Settings zeroDensity = new CityLayout.Settings(5, 0.0D, 2, 6, 8, 16);
		for (int cx = -50; cx <= 50; cx += 7) {
			for (int cz = -50; cz <= 50; cz += 11) {
				assertEquals(1, CityLayout.plan(SEED, cx, cz, zeroDensity).cellsOf(CityLayout.Role.BUILDING).size(),
						"at density 0 exactly the centre cell should be built");
				assertFalse(CityLayout.plan(SEED, cx, cz, settings()).cellsOf(CityLayout.Role.BUILDING).isEmpty(),
						"city at " + cx + "," + cz + " built nothing");
			}
		}
	}

	/**
	 * A lot sits on every cell whose offset from the centre is even on both axes, so an
	 * {@code n}-cell city has {@code ((n + 1) / 2)^2} lots and the rest is road.
	 */
	@Test
	void fullDensityFillsEveryOtherCell() {
		for (int size : new int[]{5, 7, 9}) {
			CityLayout.Plan plan = CityLayout.plan(SEED, 0, 0, full(size));
			int lots = 0;
			for (int di = -(size - 1) / 2; di <= (size - 1) / 2; di++) {
				if ((di & 1) == 0) {
					lots++;
				}
			}
			assertEquals(lots * lots, plan.cellsOf(CityLayout.Role.BUILDING).size(), "lots at size " + size);
			assertEquals(size * size, plan.cells().size(), "total cells at size " + size);
			assertEquals(size * size - lots * lots, plan.cellsOf(CityLayout.Role.STREET).size(),
					"street cells at size " + size);
		}
	}

	/** The city is centred on the chunk it was rolled for, and that centre is a building. */
	@Test
	void theCentreCellIsABuilding() {
		CityLayout.Plan plan = CityLayout.plan(SEED, -4, 9, settings());
		boolean found = plan.cellsOf(CityLayout.Role.BUILDING).stream()
				.anyMatch(cell -> cell.chunkX() == -4 && cell.chunkZ() == 9);
		assertTrue(found, "the origin chunk must hold the city's central building");
	}

	/** Worldgen has to be stable: two runs on one seed must produce an identical city. */
	@Test
	void layoutIsDeterministic() {
		CityLayout.Settings s = new CityLayout.Settings(9, 0.85D, 2, 6, 8, 16, 0.25D, 6, 0.3D, 5);
		for (int i = -5; i <= 5; i++) {
			assertEquals(CityLayout.plan(SEED, i, -i, s), CityLayout.plan(SEED, i, -i, s),
					"the same seed and city must give the same plan");
		}
	}

	/** ...and a different seed has to give a different city, or the hash is degenerate. */
	@Test
	void differentSeedsGiveDifferentCities() {
		CityLayout.Plan a = CityLayout.plan(SEED, 0, 0, settings());
		CityLayout.Plan b = CityLayout.plan(SEED + 1, 0, 0, settings());
		assertNotEquals(a.cells(), b.cells());
	}

	/** Every per-cell roll stays inside the range the config asked for. */
	@Test
	void perCellRollsRespectTheConfiguredRanges() {
		CityLayout.Settings s = settings();
		for (int cx = -12; cx <= 12; cx++) {
			for (int cz = -12; cz <= 12; cz++) {
				for (CityLayout.Cell cell : CityLayout.plan(SEED, cx, cz, s).cellsOf(CityLayout.Role.BUILDING)) {
					assertTrue(cell.floors() >= s.minFloors() && cell.floors() <= s.maxFloors(),
							"floors out of range: " + cell.floors());
					assertTrue(cell.quarterTurns() >= 0 && cell.quarterTurns() < 4, "rotation out of range");
					assertTrue(cell.buildingIndex() >= 0 && cell.buildingIndex() < s.buildingCount(),
							"building index out of range");
				}
			}
		}
	}

	/** All four rotations and all eight buildings actually get used — the rolls are not degenerate. */
	@Test
	void allRotationsAndBuildingsAreReachable() {
		boolean[] turns = new boolean[4];
		boolean[] buildings = new boolean[8];
		for (int cx = -30; cx <= 30; cx++) {
			for (int cz = -30; cz <= 30; cz++) {
				for (CityLayout.Cell cell : CityLayout.plan(SEED, cx, cz, settings()).cellsOf(CityLayout.Role.BUILDING)) {
					turns[cell.quarterTurns()] = true;
					buildings[cell.buildingIndex()] = true;
				}
			}
		}
		for (int i = 0; i < turns.length; i++) {
			assertTrue(turns[i], "rotation " + i + " never selected");
		}
		for (int i = 0; i < buildings.length; i++) {
			assertTrue(buildings[i], "building " + i + " never selected");
		}
	}

	// --- wave 2: parks, downtown and the skyline ------------------------------------------------

	/** Parks take roughly the configured share of the lots, and only ever replace a lot. */
	@Test
	void parkShareIsRoughlyTheConfiguredOne() {
		CityLayout.Settings s = new CityLayout.Settings(9, 1.0D, 2, 6, 8, 16, 0.25D, 6, 0.0D, 5);
		int lots = 0;
		int parks = 0;
		for (int cx = -30; cx <= 30; cx += 3) {
			for (int cz = -30; cz <= 30; cz += 3) {
				CityLayout.Plan plan = CityLayout.plan(SEED, cx, cz, s);
				lots += plan.cellsOf(CityLayout.Role.BUILDING).size() + plan.cellsOf(CityLayout.Role.PARK).size();
				parks += plan.cellsOf(CityLayout.Role.PARK).size();
				assertEquals(9 * 9, plan.cells().size(), "parks must replace lots, not remove cells");
			}
		}
		double share = (double) parks / lots;
		assertTrue(share > 0.20D && share < 0.30D, "park share drifted to " + share);
	}

	/** A park picks a part index inside the configured list, and never claims to have floors. */
	@Test
	void parkCellsAreWellFormed() {
		CityLayout.Settings s = new CityLayout.Settings(9, 1.0D, 2, 6, 8, 16, 0.5D, 4, 0.0D, 5);
		for (int cx = -10; cx <= 10; cx++) {
			for (CityLayout.Cell cell : CityLayout.plan(SEED, cx, 0, s).cellsOf(CityLayout.Role.PARK)) {
				assertTrue(cell.variant() >= 0 && cell.variant() < 4, "park part index out of range");
				assertEquals(0, cell.floors());
				assertEquals(0, cell.quarterTurns());
			}
		}
	}

	/** Zero park chance means no parks at all — the knob really does turn it off. */
	@Test
	void zeroParkChanceMeansNoParks() {
		CityLayout.Settings s = new CityLayout.Settings(9, 1.0D, 2, 6, 8, 16, 0.0D, 6, 0.0D, 5);
		for (int cx = -20; cx <= 20; cx++) {
			assertTrue(CityLayout.plan(SEED, cx, 7, s).cellsOf(CityLayout.Role.PARK).isEmpty());
		}
	}

	/**
	 * A downtown city gets exactly four landmark quadrants, they are a 2×2 block rooted at the city
	 * centre, they carry the four distinct quadrant indices, and they agree on storeys and rotation —
	 * which is the entire correctness condition for a multi-chunk building.
	 */
	@Test
	void downtownPlacesOneWellFormedQuad() {
		CityLayout.Settings always = new CityLayout.Settings(9, 1.0D, 2, 6, 8, 16, 0.0D, 6, 1.0D, 5);
		int found = 0;
		for (int cx = -6; cx <= 6; cx++) {
			for (int cz = -6; cz <= 6; cz++) {
				CityLayout.Plan plan = CityLayout.plan(SEED, cx, cz, always);
				assertTrue(plan.downtown(), "downtown chance 1 must always produce a downtown");
				List<CityLayout.Cell> quad = plan.cellsOf(CityLayout.Role.MULTI_BUILDING);
				assertEquals(4, quad.size(), "a landmark is exactly four quadrants");

				Set<Integer> variants = new HashSet<>();
				for (CityLayout.Cell cell : quad) {
					variants.add(cell.variant());
					assertEquals(quad.getFirst().floors(), cell.floors(), "quadrants must share a storey count");
					assertEquals(quad.getFirst().buildingIndex(), cell.buildingIndex(),
							"quadrants must come from the same landmark");
					assertEquals(0, cell.quarterTurns(), "quadrants must not be rotated apart");
					assertTrue(cell.chunkX() >= cx && cell.chunkX() <= cx + 1, "quadrant outside the quad");
					assertTrue(cell.chunkZ() >= cz && cell.chunkZ() <= cz + 1, "quadrant outside the quad");
				}
				assertEquals(Set.of(0, 1, 2, 3), variants, "the four quadrants must be distinct");
				found++;
			}
		}
		assertEquals(13 * 13, found);
	}

	/** No downtown chance, no landmark — and the centre stays an ordinary building. */
	@Test
	void withoutDowntownThereIsNoLandmark() {
		CityLayout.Settings never = new CityLayout.Settings(9, 1.0D, 2, 6, 8, 16, 0.0D, 6, 0.0D, 5);
		for (int cx = -20; cx <= 20; cx++) {
			CityLayout.Plan plan = CityLayout.plan(SEED, cx, -3, never);
			assertFalse(plan.downtown());
			assertTrue(plan.cellsOf(CityLayout.Role.MULTI_BUILDING).isEmpty());
		}
	}

	/**
	 * The skyline of IMPROVEMENTS #6: the ceiling on storeys falls off towards the city edge, so the
	 * outermost ring is always the configured minimum and only the middle can reach the maximum.
	 */
	@Test
	void floorsTaperTowardsTheCityEdge() {
		CityLayout.Settings s = new CityLayout.Settings(9, 1.0D, 2, 6, 8, 16, 0.0D, 6, 0.0D, 5);
		boolean sawMax = false;
		for (int cx = -25; cx <= 25; cx++) {
			for (CityLayout.Cell cell : CityLayout.plan(SEED, cx, 4, s).cellsOf(CityLayout.Role.BUILDING)) {
				int ring = Math.max(Math.abs(cell.chunkX() - cx), Math.abs(cell.chunkZ() - 4));
				if (ring == 4) {
					assertEquals(s.minFloors(), cell.floors(), "the outer ring must be the minimum height");
				}
				sawMax |= cell.floors() == s.maxFloors();
				assertTrue(cell.floors() >= s.minFloors() && cell.floors() <= s.maxFloors());
			}
		}
		assertTrue(sawMax, "nothing ever reached the configured maximum height");
	}

	/** A building's kind is stable, in range, and the tall ones really are towers. */
	@Test
	void buildingKindsAreWellFormed() {
		CityLayout.Settings s = new CityLayout.Settings(9, 1.0D, 2, 6, 8, 16, 0.0D, 6, 0.0D, 5);
		Set<CityLayout.BuildingKind> seen = new HashSet<>();
		for (int cx = -25; cx <= 25; cx++) {
			for (CityLayout.Cell cell : CityLayout.plan(SEED, cx, 11, s).cellsOf(CityLayout.Role.BUILDING)) {
				seen.add(cell.kind());
				if (cell.floors() == s.maxFloors()) {
					assertEquals(CityLayout.BuildingKind.TOWER, cell.kind());
				}
			}
		}
		assertEquals(4, seen.size(), "every building kind should occur somewhere: " + seen);
	}

	// --- pure helpers ---------------------------------------------------------------------------

	private static CityLayout.Cell find(List<CityLayout.Cell> cells, int chunkX, int chunkZ) {
		return cells.stream()
				.filter(cell -> cell.chunkX() == chunkX && cell.chunkZ() == chunkZ)
				.findFirst()
				.orElseThrow(() -> new AssertionError("no cell at " + chunkX + "," + chunkZ));
	}

	/** Width 16 paves the whole street cell; narrower widths pave a centred cross. */
	@Test
	void streetColumnsFormACentredCross() {
		for (int dx = 0; dx < 16; dx++) {
			for (int dz = 0; dz < 16; dz++) {
				assertTrue(CityLayout.isStreetColumn(dx, dz, 16), "width 16 must pave the whole cell");
			}
		}
		// Width 4 -> arms covering 6..9 on each axis.
		assertTrue(CityLayout.isStreetColumn(0, 6, 4), "the X arm reaches the cell edge");
		assertTrue(CityLayout.isStreetColumn(9, 15, 4), "the Z arm reaches the cell edge");
		assertTrue(CityLayout.isStreetColumn(7, 8, 4), "the crossing is paved");
		assertFalse(CityLayout.isStreetColumn(0, 0, 4), "the corner of the cell is not paved");
		assertFalse(CityLayout.isStreetColumn(15, 15, 4), "the corner of the cell is not paved");
	}

	/** Columns outside a cell are never paved, whatever the width. */
	@Test
	void streetColumnsStayInsideTheCell() {
		for (int width = 1; width <= 16; width++) {
			assertFalse(CityLayout.isStreetColumn(-1, 8, width));
			assertFalse(CityLayout.isStreetColumn(16, 8, width));
			assertFalse(CityLayout.isStreetColumn(8, -1, width));
			assertFalse(CityLayout.isStreetColumn(8, 16, width));
		}
	}

	/** Settings normalise nonsense from a datapack instead of propagating it into worldgen. */
	@Test
	void settingsAreNormalised() {
		CityLayout.Settings s = new CityLayout.Settings(0, 5.0D, -3, -9, 0, 99, -1.0D, 0, 7.0D, 0);
		assertEquals(1, s.citySize());
		assertEquals(1.0D, s.density());
		assertEquals(0, s.minFloors());
		assertEquals(0, s.maxFloors());
		assertEquals(1, s.buildingCount());
		assertEquals(16, s.streetWidth());
		assertEquals(0.0D, s.parkChance());
		assertEquals(1, s.parkKindCount());
		assertEquals(1.0D, s.downtownChance());
		assertEquals(1, s.multiBuildingCount());
	}

	/** The hash is stable and salts really do separate the rolls. */
	@Test
	void hashIsStableAndSalted() {
		assertEquals(CityLayout.hash(SEED, 7, -13), CityLayout.hash(SEED, 7, -13));
		assertNotEquals(CityLayout.hash(SEED, 7, -13, 1), CityLayout.hash(SEED, 7, -13, 2));
		for (int i = -100; i <= 100; i++) {
			double u = CityLayout.unit(CityLayout.hash(SEED, i, i * 3, 1));
			assertTrue(u >= 0.0D && u < 1.0D, "unit() out of range: " + u);
		}
	}

	/**
	 * The regression this statistic exists for: a city was generated at y≈49 with everything buried,
	 * because the level was the <em>minimum</em> over samples spanning 144×144 blocks and one deep
	 * spot (ravine, pond, cliff foot) decided the height for the whole settlement.
	 */
	@Test
	void oneDeepSampleDoesNotSinkTheCity() {
		int[] samples = new int[41];
		for (int i = 0; i < 40; i++) {
			samples[i] = 70;
		}
		samples[40] = 20;   // the ravine that used to win

		int level = CityLayout.groundLevelFrom(samples, 6);

		assertTrue(level >= 66, "one deep sample sank the city to y=" + level);
		assertTrue(level <= 72, "level drifted above the terrain: y=" + level);
	}

	/** Half the lots may sit in a hole and the level still lands on the terrain the city stands on. */
	@Test
	void medianSurvivesHalfTheSamplesBeingOutliers() {
		int[] samples = {20, 20, 20, 20, 70, 72, 71, 70, 70};

		int level = CityLayout.groundLevelFrom(samples, 6);

		assertTrue(level >= 66, "median was dragged down to y=" + level);
	}

	/** Snapping is to the nearest storey boundary, not always downward. */
	@Test
	void snapsToNearestStoreyBoundary() {
		assertEquals(72, CityLayout.groundLevelFrom(new int[]{70}, 6));
		assertEquals(66, CityLayout.groundLevelFrom(new int[]{67}, 6));
		assertEquals(66, CityLayout.groundLevelFrom(new int[]{66}, 6));
	}

	/** No samples is the caller's cue to fall back to sea level, not a silent zero. */
	@Test
	void emptySampleSetIsSignalled() {
		assertEquals(Integer.MIN_VALUE, CityLayout.groundLevelFrom(new int[0], 6));
	}

	/** Same samples, same answer — the level is part of the deterministic city description. */
	@Test
	void groundLevelIsDeterministic() {
		int[] a = {64, 70, 68, 71, 65};
		int[] b = {71, 65, 70, 64, 68};   // same multiset, different order

		assertEquals(CityLayout.groundLevelFrom(a, 6), CityLayout.groundLevelFrom(b, 6));
	}
}
