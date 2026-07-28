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
 * deterministic) still have to hold, and two more come with the grid: no two buildings share a chunk
 * edge, and a city is never empty. Pure arithmetic — no Minecraft classes are touched, so this runs
 * in a plain JVM.
 */
class CityLayoutTest {

	private static final long SEED = 0x5EEDL;

	private static CityLayout.Settings settings() {
		return new CityLayout.Settings(5, 0.85D, 2, 6, 8, 16);
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

	/** Density 1 fills every building cell of the checkerboard: 13 of a 5x5, plus 12 streets. */
	@Test
	void fullDensityFillsTheCheckerboard() {
		CityLayout.Plan plan = CityLayout.plan(SEED, 0, 0, new CityLayout.Settings(5, 1.0D, 2, 6, 8, 16));
		assertEquals(13, plan.cellsOf(CityLayout.Role.BUILDING).size());
		assertEquals(12, plan.cellsOf(CityLayout.Role.STREET).size());
		assertEquals(25, plan.cells().size());
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
		for (int i = -5; i <= 5; i++) {
			CityLayout.Plan first = CityLayout.plan(SEED, i, -i, settings());
			CityLayout.Plan second = CityLayout.plan(SEED, i, -i, settings());
			assertEquals(first, second, "the same seed and city must give the same plan");
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

	/**
	 * Neighbour masks describe the grid honestly: a street cell in the middle of a full-density city
	 * sees buildings on all four sides, a corner building sees only the two streets inside the city.
	 */
	@Test
	void neighbourMasksMatchTheGrid() {
		CityLayout.Plan plan = CityLayout.plan(SEED, 0, 0, new CityLayout.Settings(5, 1.0D, 2, 6, 8, 16));
		int all = CityLayout.NORTH | CityLayout.EAST | CityLayout.SOUTH | CityLayout.WEST;

		CityLayout.Cell innerStreet = find(plan.cells(), 1, 0);
		assertEquals(CityLayout.Role.STREET, innerStreet.role());
		assertEquals(all, innerStreet.neighbourMask(), "an inner street touches four cells");

		CityLayout.Cell corner = find(plan.cells(), -2, -2);
		assertEquals(CityLayout.Role.BUILDING, corner.role());
		assertEquals(CityLayout.EAST | CityLayout.SOUTH, corner.neighbourMask(),
				"the north-west corner only has neighbours inside the city");
	}

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
		CityLayout.Settings s = new CityLayout.Settings(0, 5.0D, -3, -9, 0, 99);
		assertEquals(1, s.citySize());
		assertEquals(1.0D, s.density());
		assertEquals(0, s.minFloors());
		assertEquals(0, s.maxFloors());
		assertEquals(1, s.buildingCount());
		assertEquals(16, s.streetWidth());
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
}
