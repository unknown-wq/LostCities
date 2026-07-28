package com.lostbuildings.world.structure;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Where the abandoned cars stand.
 *
 * <p>The failure modes worth a test here are all invisible to a compiler and expensive to spot in a
 * world. A car is 3–6 blocks long and a street cell writes only inside its own chunk, so the two
 * things that break are (a) a car that is computed by one cell and not by its neighbour, which shows
 * up in game as half a bus meeting nothing at all, and (b) a car block that lands on the kerb ring,
 * which {@code StreetPiece.decorate} then overwrites — leaving a car with a bite out of it and a
 * kerbstone through its roof. Both are asserted directly, against real masks taken from a real
 * {@link CityLayout} plan rather than against hand-picked ones.
 *
 * <p>Every test that samples a population also asserts that the population was non-empty. A
 * boundary test that never happened to see a straddling car would pass for the wrong reason.
 */
class StreetCarsTest {

	private static final int N = CityLayout.NORTH;
	private static final int E = CityLayout.EAST;
	private static final int S = CityLayout.SOUTH;
	private static final int W = CityLayout.WEST;

	private static final long SEED = 0x1057C1715L;
	private static final double DENSITY = StreetCars.DEFAULT_DENSITY;

	/** A city big enough to contain straights, bends, T-junctions and crossroads. */
	private static CityLayout.Plan city(long seed) {
		return CityLayout.plan(seed, 0, 0, new CityLayout.Settings(9, 0.85D, 1, 6, 3, 16));
	}

	private static Map<Long, CityLayout.Cell> streetsByCell(CityLayout.Plan plan) {
		Map<Long, CityLayout.Cell> streets = new HashMap<>();
		for (CityLayout.Cell cell : plan.cellsOf(CityLayout.Role.STREET)) {
			streets.put(key(cell.chunkX(), cell.chunkZ()), cell);
		}
		return streets;
	}

	private static long key(int chunkX, int chunkZ) {
		return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
	}

	// --- the models themselves ---------------------------------------------------------------

	/** The stencils have to fit the lattice they are placed on, or the slot arithmetic is a lie. */
	@Test
	void everyModelFitsItsSlot() {
		int longest = 0;
		Set<Character> known = Set.of(StreetCars.BODY, StreetCars.TRIM, StreetCars.BONNET, StreetCars.BOOT,
				StreetCars.GLASS, StreetCars.WHEEL, StreetCars.GRILLE, StreetCars.CRUSHED, StreetCars.DOOR,
				StreetCars.NOTHING);
		for (StreetCars.Model model : StreetCars.Model.values()) {
			assertEquals(2, model.width(), model + " must be two wide: four lanes fill the carriageway exactly");
			assertTrue(model.height() >= 2, model + " needs a cabin above the chassis");
			assertTrue(model.length() >= 3, model + " is too short to read as a vehicle");
			assertTrue(model.length() <= StreetCars.MAX_LENGTH,
					model + " is longer than MAX_LENGTH, so a cell would stop enumerating it in time");
			longest = Math.max(longest, model.length());
			for (int h = 0; h < model.height(); h++) {
				for (int u = 0; u < model.length(); u++) {
					for (int v = 0; v < model.width(); v++) {
						assertTrue(known.contains(model.at(h, u, v)),
								model + " uses an unknown stencil character '" + model.at(h, u, v) + "'");
					}
				}
			}
		}
		assertEquals(StreetCars.MAX_LENGTH, longest, "MAX_LENGTH must be the longest model, exactly");
		assertTrue(StreetCars.MAX_LENGTH <= StreetCars.SLOT, "a car has to fit inside one slot");
	}

	/** Six models that are actually different shapes, not one shape with six names. */
	@Test
	void theModelsAreDistinctSilhouettes() {
		Set<String> silhouettes = new HashSet<>();
		for (StreetCars.Model model : StreetCars.Model.values()) {
			StringBuilder shape = new StringBuilder();
			for (int h = 0; h < model.height(); h++) {
				for (int u = 0; u < model.length(); u++) {
					for (int v = 0; v < model.width(); v++) {
						shape.append(model.at(h, u, v));
					}
				}
			}
			assertTrue(silhouettes.add(shape.toString()), model + " is a duplicate of an earlier model");
		}
		assertEquals(6, silhouettes.size(), "saloon, hatchback, van, bus, wreck and burnt-out shell");
		assertEquals(6, StreetCars.Model.BUS.length(), "the bus has to be unmistakably the long one");
		assertEquals(3, StreetCars.Model.BUS.height());
		assertTrue(StreetCars.Model.BURNT.burntOut());
		assertFalse(StreetCars.Model.WRECK.burntOut(), "a wreck is crumpled, not charred — they are two models");
	}

	/** The lattice must not line up with the cell grid, or no car ever crosses a seam. */
	@Test
	void theSlotLatticeIsCoprimeWithTheCellSize() {
		assertEquals(1, gcd(StreetCars.SLOT, 16),
				"a slot length sharing a factor with 16 would make every slot boundary a cell boundary");
	}

	private static int gcd(int a, int b) {
		return b == 0 ? a : gcd(b, a % b);
	}

	// --- determinism -------------------------------------------------------------------------

	/** The same seed and the same cell must always produce the same traffic, on any run. */
	@Test
	void sameSeedAndCellAlwaysGivesTheSameCars() {
		int seen = 0;
		for (int cx = -3; cx <= 3; cx++) {
			for (int cz = -3; cz <= 3; cz++) {
				int mask = CityLayout.ALL_SIDES;
				List<StreetCars.Car> first = StreetCars.carsIn(SEED, cx * 16, cz * 16, mask, DENSITY);
				List<StreetCars.Car> second = StreetCars.carsIn(SEED, cx * 16, cz * 16, mask, DENSITY);
				assertEquals(first, second, "cars must be a pure function of (seed, cell, mask)");
				seen += first.size();
			}
		}
		assertTrue(seen > 0, "the sample must actually contain cars");
	}

	/** A different seed has to move the traffic, or the hash is not being consulted. */
	@Test
	void aDifferentSeedGivesDifferentTraffic() {
		List<StreetCars.Car> a = StreetCars.carsIn(SEED, 0, 0, CityLayout.ALL_SIDES, DENSITY);
		List<StreetCars.Car> b = StreetCars.carsIn(SEED + 1, 0, 0, CityLayout.ALL_SIDES, DENSITY);
		assertFalse(a.isEmpty());
		assertFalse(a.equals(b), "the world seed must reach the car lattice");
	}

	/** Density zero is the off switch a datapack can reach for. */
	@Test
	void zeroDensityPlacesNothing() {
		for (int cx = -2; cx <= 2; cx++) {
			assertTrue(StreetCars.carsIn(SEED, cx * 16, 0, CityLayout.ALL_SIDES, 0.0D).isEmpty());
		}
	}

	// --- the cell-boundary problem -----------------------------------------------------------

	/**
	 * The eight columns either side of a connected seam are roadway from <em>both</em> cells' point
	 * of view, whatever else their masks say.
	 *
	 * <p>This is the load-bearing claim of the design. A car straddling a boundary is accepted by the
	 * cell to its west using that cell's mask, and by the cell to its east using a completely
	 * different mask; if the two ever disagreed the world would show half a car. The claim is only
	 * made — and only ever used — on the eight rows the carriageway occupies, which is the only band
	 * a car can straddle a seam in: a car that lies across the seam is by construction an X-axis car,
	 * and an X-axis car always sits on rows {@code 4..11} of its cell.
	 *
	 * <p>Checked over every reciprocal pair of masks rather than over an example, and in both axes,
	 * because the north–south seam is a separate branch of the predicate.
	 */
	@Test
	void aConnectedSeamIsRoadwayFromBothSides() {
		int checked = 0;
		for (int near = 0; near < 16; near++) {
			for (int far = 0; far < 16; far++) {
				// The bit means "my neighbour is a street cell", so only reciprocal pairs occur.
				if ((near & E) == 0 || (far & W) == 0) {
					continue;
				}
				for (int band = 4; band <= 11; band++) {
					for (int world = 16 - StreetCars.REACH; world < 16 + StreetCars.REACH; world++) {
						assertTrue(StreetCars.isRoadNear(world, band, near),
								"west cell (mask " + near + ") denies road at x=" + world + " z=" + band);
						assertTrue(StreetCars.isRoadNear(world - 16, band, far),
								"east cell (mask " + far + ") denies road at x=" + world + " z=" + band);
						// ...and the same seam turned through ninety degrees.
						if ((near & S) != 0 && (far & N) != 0) {
							assertTrue(StreetCars.isRoadNear(band, world, near));
							assertTrue(StreetCars.isRoadNear(band, world - 16, far));
						}
						checked++;
					}
				}
			}
		}
		assertTrue(checked > 1000, "the mask sweep must actually have run");
	}

	/** An unconnected edge has no neighbour, and the predicate must not invent road beyond it. */
	@Test
	void anUnconnectedEdgeIsNeverRoad() {
		for (int mask = 0; mask < 16; mask++) {
			for (int band = 4; band <= 11; band++) {
				for (int out = 1; out <= StreetCars.REACH; out++) {
					assertEquals((mask & E) != 0, StreetCars.isRoadNear(15 + out, band, mask));
					assertEquals((mask & W) != 0, StreetCars.isRoadNear(-out, band, mask));
					assertEquals((mask & S) != 0, StreetCars.isRoadNear(band, 15 + out, mask));
					assertEquals((mask & N) != 0, StreetCars.isRoadNear(band, -out, mask));
				}
			}
		}
	}

	/** The predicate refuses to answer past its reach, and diagonals are always the corner pavement. */
	@Test
	void thePredicateStopsAtItsReach() {
		int all = CityLayout.ALL_SIDES;
		assertTrue(StreetCars.isRoadNear(15 + StreetCars.REACH, 8, all));
		assertFalse(StreetCars.isRoadNear(16 + StreetCars.REACH, 8, all),
				"answering beyond REACH would need the next cell's mask");
		assertFalse(StreetCars.isRoadNear(-StreetCars.REACH - 1, 8, all));
		assertFalse(StreetCars.isRoadNear(-1, -1, all), "a diagonal neighbour's corner is pavement");
		assertFalse(StreetCars.isRoadNear(-1, 2, all), "outside the cell, only the road band counts");
	}

	/**
	 * Two neighbouring street cells must compute <em>the same car</em> for a car that straddles them.
	 *
	 * <p>Run over a real city plan, so the masks are the ones the grid actually produces. Every car
	 * one cell reports is looked up in every other cell it overlaps; if the two disagreed, the world
	 * would show one half of a vehicle and nothing beside it.
	 */
	@Test
	void carsAgreeAcrossEveryCellBoundary() {
		int straddlers = 0;
		for (long seed = 1; seed <= 6; seed++) {
			CityLayout.Plan plan = city(seed);
			Map<Long, CityLayout.Cell> streets = streetsByCell(plan);
			Map<Long, List<StreetCars.Car>> cars = new HashMap<>();
			for (Map.Entry<Long, CityLayout.Cell> entry : streets.entrySet()) {
				CityLayout.Cell cell = entry.getValue();
				cars.put(entry.getKey(), StreetCars.carsIn(seed, cell.chunkX() * 16, cell.chunkZ() * 16,
						cell.neighbourMask(), DENSITY));
			}
			for (Map.Entry<Long, List<StreetCars.Car>> entry : cars.entrySet()) {
				CityLayout.Cell owner = streets.get(entry.getKey());
				for (StreetCars.Car car : entry.getValue()) {
					int firstX = Math.floorDiv(car.minX(), 16);
					int lastX = Math.floorDiv(car.minX() + car.sizeX() - 1, 16);
					int firstZ = Math.floorDiv(car.minZ(), 16);
					int lastZ = Math.floorDiv(car.minZ() + car.sizeZ() - 1, 16);
					for (int cx = firstX; cx <= lastX; cx++) {
						for (int cz = firstZ; cz <= lastZ; cz++) {
							if (cx == owner.chunkX() && cz == owner.chunkZ()) {
								continue;
							}
							straddlers++;
							List<StreetCars.Car> neighbour = cars.get(key(cx, cz));
							assertNotNull(neighbour, "a car reached into cell (" + cx + ", " + cz
									+ ") which is not a street cell at all");
							assertTrue(neighbour.contains(car),
									"cell (" + cx + ", " + cz + ") does not know about " + car
											+ " owned by (" + owner.chunkX() + ", " + owner.chunkZ() + ")");
						}
					}
				}
			}
		}
		assertTrue(straddlers > 20,
				"the sample saw only " + straddlers + " straddling cars — the lattice is not crossing seams");
	}

	/**
	 * The slot sweep a cell does must not miss a car that reaches into it from an earlier slot.
	 *
	 * <p>Checked against brute force: every slot within forty of the cell, on every lane, on both
	 * axes. The sweep bound is the one piece of the enumeration that no other test exercises — a
	 * cell that stopped one slot short would silently drop exactly the cars that straddle one of its
	 * edges, and from inside a single chunk that is invisible.
	 */
	@Test
	void theSlotSweepMissesNothingThatReachesIntoTheCell() {
		int seen = 0;
		for (long seed = 1; seed <= 4; seed++) {
			for (int cx = -2; cx <= 2; cx++) {
				int cellMinX = cx * 16;
				int cellMinZ = 32;
				int mask = CityLayout.ALL_SIDES;
				Set<StreetCars.Car> found = new HashSet<>(
						StreetCars.carsIn(seed, cellMinX, cellMinZ, mask, DENSITY));
				Set<StreetCars.Car> brute = new HashSet<>();
				for (int offset : StreetCars.LANE_OFFSETS) {
					int baseX = Math.floorDiv(cellMinX, StreetCars.SLOT);
					int baseZ = Math.floorDiv(cellMinZ, StreetCars.SLOT);
					for (int step = -40; step <= 40; step++) {
						consider(brute, seed, StreetCars.Axis.X, cellMinZ + offset, baseX + step,
								offset, cellMinX, cellMinZ, mask);
						consider(brute, seed, StreetCars.Axis.Z, cellMinX + offset, baseZ + step,
								offset, cellMinX, cellMinZ, mask);
					}
				}
				assertEquals(brute, found, "the sweep and brute force disagree for cell " + cx);
				seen += found.size();
			}
		}
		assertTrue(seen > 20, "only " + seen + " cars sampled — the comparison proves little");
	}

	private static void consider(Set<StreetCars.Car> out, long seed, StreetCars.Axis axis, int lane,
	                             int slot, int laneOffset, int cellMinX, int cellMinZ, int mask) {
		StreetCars.Car car = StreetCars.candidate(seed, axis, lane, slot, DENSITY, laneOffset);
		if (car != null && StreetCars.overlapsCell(car, cellMinX, cellMinZ)
				&& StreetCars.stands(seed, car, cellMinX, cellMinZ, mask)) {
			out.add(car);
		}
	}

	/** Cars must never reach out of the road network into a lot that has no street piece on it. */
	@Test
	void noCarReachesOffTheRoadNetwork() {
		for (long seed = 1; seed <= 4; seed++) {
			CityLayout.Plan plan = city(seed);
			Map<Long, CityLayout.Cell> streets = streetsByCell(plan);
			for (CityLayout.Cell cell : streets.values()) {
				for (StreetCars.Car car : StreetCars.carsIn(seed, cell.chunkX() * 16, cell.chunkZ() * 16,
						cell.neighbourMask(), DENSITY)) {
					for (int x = car.minX(); x < car.minX() + car.sizeX(); x++) {
						for (int z = car.minZ(); z < car.minZ() + car.sizeZ(); z++) {
							long owner = key(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
							assertTrue(streets.containsKey(owner),
									car + " covers (" + x + ", " + z + "), which is not a street cell");
						}
					}
				}
			}
		}
	}

	// --- staying off the furniture -----------------------------------------------------------

	/**
	 * Not one car block may sit on the kerb ring or the pavement.
	 *
	 * <p>{@code StreetPiece.decorate} rewrites the kerb ring after the tile is stamped, so a car
	 * that overlapped it would be sliced apart; and a car up on the pavement reads as parked in a
	 * shop window. The lamp posts stand on that same ring, so this one assertion covers them too.
	 */
	@Test
	void noCarTouchesTheKerbRingOrThePavement() {
		int columns = 0;
		for (long seed = 1; seed <= 6; seed++) {
			CityLayout.Plan plan = city(seed);
			for (CityLayout.Cell cell : plan.cellsOf(CityLayout.Role.STREET)) {
				int mask = cell.neighbourMask();
				int cellMinX = cell.chunkX() * 16;
				int cellMinZ = cell.chunkZ() * 16;
				for (StreetCars.Car car : StreetCars.carsIn(seed, cellMinX, cellMinZ, mask, DENSITY)) {
					for (int x = car.minX(); x < car.minX() + car.sizeX(); x++) {
						for (int z = car.minZ(); z < car.minZ() + car.sizeZ(); z++) {
							int dx = x - cellMinX;
							int dz = z - cellMinZ;
							if (dx < 0 || dz < 0 || dx > 15 || dz > 15) {
								continue;   // the neighbour's problem, and it asserts the same thing
							}
							columns++;
							assertFalse(StreetDecor.isKerb(dx, dz, mask),
									car + " stands on the kerb at (" + dx + ", " + dz + ")");
							assertTrue(StreetDecor.isRoad(dx, dz, mask),
									car + " stands on pavement at (" + dx + ", " + dz + ")");
						}
					}
				}
			}
		}
		assertTrue(columns > 500, "only " + columns + " car columns checked — the sample is too thin");
	}

	/** Two cars sharing a column would interleave their bodywork into one unreadable lump. */
	@Test
	void carsNeverOverlapEachOther() {
		for (long seed = 1; seed <= 6; seed++) {
			CityLayout.Plan plan = city(seed);
			Map<Long, StreetCars.Car> owners = new HashMap<>();
			for (CityLayout.Cell cell : plan.cellsOf(CityLayout.Role.STREET)) {
				List<StreetCars.Car> cars = StreetCars.carsIn(seed, cell.chunkX() * 16, cell.chunkZ() * 16,
						cell.neighbourMask(), DENSITY);
				for (StreetCars.Car car : cars) {
					for (int x = car.minX(); x < car.minX() + car.sizeX(); x++) {
						for (int z = car.minZ(); z < car.minZ() + car.sizeZ(); z++) {
							// A straddling car is reported by both its cells; only a *different* car
							// on the same column is a collision.
							StreetCars.Car other = owners.put(key(x, z), car);
							if (other != null && !other.equals(car)) {
								fail(car + " overlaps " + other + " at (" + x + ", " + z + ")");
							}
						}
					}
				}
			}
			assertFalse(owners.isEmpty(), "no cars at all in the city for seed " + seed);
		}
	}

	// --- how the traffic reads ---------------------------------------------------------------

	/** A car across the carriageway is a crash, and crashes have to stay rare. */
	@Test
	void carsLieAlongTheRoadExceptForRareCrashes() {
		int along = 0;
		int across = 0;
		for (long seed = 1; seed <= 8; seed++) {
			for (int cx = -4; cx <= 4; cx++) {
				// A straight east-west street: every car on it should be pointing east-west.
				List<StreetCars.Car> cars = StreetCars.carsIn(seed, cx * 16, 0, E | W, DENSITY);
				for (StreetCars.Car car : cars) {
					if (car.axis() == StreetCars.Axis.X) {
						along++;
					} else {
						across++;
					}
				}
			}
		}
		assertTrue(along > 50, "only " + along + " cars along the road — the sample is too thin");
		assertTrue(across > 0, "no crashes at all: the deliberate-crash path is dead code");
		assertTrue(across < along / 6,
				across + " crashes against " + along + " parked cars is far too many");
	}

	/**
	 * The alignment probe has to see past the width of the carriageway.
	 *
	 * <p>The carriageway is eight wide, so a three-block car parked <em>across</em> an east–west
	 * street still has road one block off each end of it. A probe that only looked one block out would
	 * call such a car aligned, the deliberate-crash gate would never fire, and every junction box in
	 * the city would fill up with cars lying sideways.
	 */
	@Test
	void aCarLyingAcrossTheStreetIsNotAligned() {
		for (int z0 = 4; z0 + 3 <= 12; z0++) {
			StreetCars.Car sideways = new StreetCars.Car(StreetCars.Model.HATCHBACK, StreetCars.Axis.Z,
					true, 6, z0, StreetCars.Colour.RED, 0);
			assertFalse(StreetCars.alignedWithRoad(sideways, 0, 0, E | W),
					"a car across the street at z=" + z0 + " must read as a crash, not as parking");
		}
		StreetCars.Car alongTheStreet = new StreetCars.Car(StreetCars.Model.HATCHBACK, StreetCars.Axis.X,
				true, 6, 4, StreetCars.Colour.RED, 0);
		assertTrue(StreetCars.alignedWithRoad(alongTheStreet, 0, 0, E | W));
		// ...and the same two cars on a north-south street swap roles.
		StreetCars.Car turned = new StreetCars.Car(StreetCars.Model.HATCHBACK, StreetCars.Axis.Z,
				true, 6, 6, StreetCars.Colour.RED, 0);
		assertTrue(StreetCars.alignedWithRoad(turned, 0, 0, N | S));
		assertFalse(StreetCars.alignedWithRoad(alongTheStreet, 0, 0, N | S));
	}

	/** Kerbside parking is the common case; the middle of the road is the exception. */
	@Test
	void mostCarsParkAtTheKerb() {
		int kerbside = 0;
		int midlane = 0;
		for (long seed = 1; seed <= 8; seed++) {
			for (int cx = -4; cx <= 4; cx++) {
				for (StreetCars.Car car : StreetCars.carsIn(seed, cx * 16, 0, E | W, DENSITY)) {
					if (car.axis() != StreetCars.Axis.X) {
						continue;
					}
					int lane = Math.floorMod(car.minZ(), 16);
					if (lane == 4 || lane == 10) {
						kerbside++;
					} else {
						midlane++;
					}
				}
			}
		}
		assertTrue(kerbside > 50, "only " + kerbside + " kerbside cars sampled");
		assertTrue(midlane > 0, "nothing ever abandoned mid-lane: that path is dead code");
		assertTrue(midlane < kerbside / 3,
				midlane + " mid-lane cars against " + kerbside + " kerbside is not kerbside parking");
	}

	/**
	 * A street you cannot walk down is worse than an empty one.
	 *
	 * <p>Measured as the share of carriageway cross-sections — one world X, the eight rows of road at
	 * that X — with no clear row at all. Cars are two blocks tall, so a blocked cross-section really
	 * does mean turning back.
	 */
	@Test
	void theCarriagewayStaysWalkable() {
		int sections = 0;
		int blocked = 0;
		int covered = 0;
		int total = 0;
		for (long seed = 1; seed <= 8; seed++) {
			for (int cx = -4; cx <= 4; cx++) {
				int cellMinX = cx * 16;
				List<StreetCars.Car> cars = new ArrayList<>(
						StreetCars.carsIn(seed, cellMinX, 0, E | W, DENSITY));
				for (int x = cellMinX; x < cellMinX + 16; x++) {
					boolean clear = false;
					for (int z = 4; z <= 11; z++) {
						boolean taken = false;
						for (StreetCars.Car car : cars) {
							if (car.covers(x, z)) {
								taken = true;
								break;
							}
						}
						total++;
						if (taken) {
							covered++;
						} else {
							clear = true;
						}
					}
					sections++;
					if (!clear) {
						blocked++;
					}
				}
			}
		}
		assertTrue(total > 2000);
		assertTrue(covered > 0, "no road column is ever under a car — nothing is being placed");
		assertTrue(covered * 100 < total * 40,
				"cars cover " + (covered * 100 / total) + "% of the carriageway; that is a car park");
		assertTrue(blocked * 100 < sections * 3,
				blocked + " of " + sections + " cross-sections are completely blocked");
	}

	/** Colours and models both have to vary, or every street looks like a fleet. */
	@Test
	void modelsAndColoursBothVary() {
		Set<StreetCars.Model> models = new HashSet<>();
		Set<StreetCars.Colour> colours = new HashSet<>();
		for (long seed = 1; seed <= 12; seed++) {
			for (int cx = -6; cx <= 6; cx++) {
				for (StreetCars.Car car : StreetCars.carsIn(seed, cx * 16, 0, E | W, DENSITY)) {
					models.add(car.model());
					colours.add(car.colour());
				}
			}
		}
		assertEquals(StreetCars.Model.values().length, models.size(), "some model is never picked");
		assertEquals(StreetCars.Colour.values().length, colours.size(), "some colour is never picked");
	}

	/** Decay is what makes it a lost city rather than a car park; every flag has to be reachable. */
	@Test
	void everyKindOfDecayHappens() {
		int[] counts = new int[6];
		int cars = 0;
		int[] flags = {StreetCars.RUSTED, StreetCars.SMASHED, StreetCars.WHEELLESS,
				StreetCars.COBWEBBED, StreetCars.DOOR_OPEN};
		for (long seed = 1; seed <= 12; seed++) {
			for (int cx = -6; cx <= 6; cx++) {
				for (StreetCars.Car car : StreetCars.carsIn(seed, cx * 16, 0, E | W, DENSITY)) {
					cars++;
					for (int i = 0; i < flags.length; i++) {
						if (car.has(flags[i])) {
							counts[i]++;
						}
					}
					if (car.decay() == 0) {
						counts[5]++;
					}
				}
			}
		}
		assertTrue(cars > 100);
		for (int i = 0; i < flags.length; i++) {
			assertTrue(counts[i] > 0, "decay flag " + flags[i] + " never occurs");
			assertTrue(counts[i] < cars, "decay flag " + flags[i] + " occurs on every single car");
		}
		assertTrue(counts[5] > 0, "every car is damaged; a few should still be intact");
	}

	// --- the junction rule -------------------------------------------------------------------

	/** One axis owns a junction, and it is decided from nothing but the seed and the chunk. */
	@Test
	void aJunctionIsOwnedByExactlyOneAxis() {
		int x = 0;
		int z = 0;
		for (int cx = -20; cx <= 20; cx++) {
			StreetCars.Axis axis = StreetCars.junctionAxis(SEED, cx, 7);
			assertEquals(axis, StreetCars.junctionAxis(SEED, cx, 7), "the junction owner must be stable");
			if (axis == StreetCars.Axis.X) {
				x++;
			} else {
				z++;
			}
		}
		assertTrue(x > 5 && z > 5, "the junction coin is stuck: " + x + " X against " + z + " Z");
	}

	/** No car may enter a junction box its axis does not own — that is what keeps axes apart. */
	@Test
	void carsOnlyEnterJunctionsTheirAxisOwns() {
		int entered = 0;
		for (long seed = 1; seed <= 6; seed++) {
			CityLayout.Plan plan = city(seed);
			for (CityLayout.Cell cell : plan.cellsOf(CityLayout.Role.STREET)) {
				for (StreetCars.Car car : StreetCars.carsIn(seed, cell.chunkX() * 16, cell.chunkZ() * 16,
						cell.neighbourMask(), DENSITY)) {
					for (int cx = Math.floorDiv(car.minX(), 16);
					     cx <= Math.floorDiv(car.minX() + car.sizeX() - 1, 16); cx++) {
						for (int cz = Math.floorDiv(car.minZ(), 16);
						     cz <= Math.floorDiv(car.minZ() + car.sizeZ() - 1, 16); cz++) {
							if (!StreetCars.entersJunction(car, cx, cz)) {
								continue;
							}
							entered++;
							assertEquals(car.axis(), StreetCars.junctionAxis(seed, cx, cz),
									car + " sits in a junction owned by the other axis");
						}
					}
				}
			}
		}
		assertTrue(entered > 50, "only " + entered + " cars in junctions — the rule is untested");
	}

	// --- geometry of a single car ------------------------------------------------------------

	/** The stencil has to be read the right way round, whichever way the car is pointing. */
	@Test
	void turningACarAroundReversesItsStencil() {
		StreetCars.Car forward = new StreetCars.Car(StreetCars.Model.SALOON, StreetCars.Axis.X, true,
				100, 200, StreetCars.Colour.RED, 0);
		StreetCars.Car reversed = new StreetCars.Car(StreetCars.Model.SALOON, StreetCars.Axis.X, false,
				100, 200, StreetCars.Colour.RED, 0);
		assertEquals(0, forward.noseOffset(100, 200), "a forward car has its nose at the low end");
		assertEquals(3, reversed.noseOffset(100, 200), "a reversed car has its tail at the low end");
		assertEquals(forward.at(100, 200, 1), reversed.at(103, 200, 1), "the bonnet swaps ends");
		assertEquals(4, forward.sizeX());
		assertEquals(2, forward.sizeZ());
	}

	/** A car turned onto the other axis swaps its footprint, not its stencil. */
	@Test
	void aCarOnTheZAxisIsLongInZ() {
		StreetCars.Car car = new StreetCars.Car(StreetCars.Model.BUS, StreetCars.Axis.Z, true,
				40, 60, StreetCars.Colour.YELLOW, 0);
		assertEquals(2, car.sizeX());
		assertEquals(6, car.sizeZ());
		assertTrue(car.covers(41, 65));
		assertFalse(car.covers(42, 65));
		assertFalse(car.covers(41, 66));
		assertEquals(1, car.sideOffset(41, 63));
		assertEquals(0, car.sideOffset(40, 63));
	}

	/** A column outside the car has nothing on it — {@code build} must not paint the road. */
	@Test
	void columnsOutsideTheFootprintAreEmpty() {
		StreetCars.Car car = new StreetCars.Car(StreetCars.Model.SALOON, StreetCars.Axis.X, true,
				0, 0, StreetCars.Colour.BLUE, 0);
		assertEquals(StreetCars.NOTHING, car.at(-1, 0, 0));
		assertEquals(StreetCars.NOTHING, car.at(4, 0, 0));
		assertEquals(StreetCars.NOTHING, car.at(0, 2, 0));
		assertEquals(StreetCars.NOTHING, car.at(0, 0, 5), "nothing above the roof");
	}

	/** A cell with no roads at all cannot hold traffic. */
	@Test
	void anIsolatedCellStillOnlyUsesItsOwnRoadway() {
		for (StreetCars.Car car : StreetCars.carsIn(SEED, 0, 0, 0, DENSITY)) {
			for (int x = car.minX(); x < car.minX() + car.sizeX(); x++) {
				for (int z = car.minZ(); z < car.minZ() + car.sizeZ(); z++) {
					assertTrue(x >= 4 && x <= 11 && z >= 4 && z <= 11,
							car + " left the junction box of a cell with no arms");
				}
			}
		}
	}

	/** A dead end has road on one side only, and nothing may hang off the closed end. */
	@Test
	void aDeadEndDoesNotLeakCarsPastItsStop() {
		for (long seed = 1; seed <= 20; seed++) {
			for (StreetCars.Car car : StreetCars.carsIn(seed, 0, 0, W, StreetCars.DEFAULT_DENSITY)) {
				assertTrue(car.minX() + car.sizeX() - 1 <= 11,
						car + " ran past the end of a west-only dead end");
				assertTrue(car.minZ() >= 4 && car.minZ() + car.sizeZ() - 1 <= 11,
						car + " left the carriageway of a west-only dead end");
			}
		}
		// ...and the same street with the arm on the other side is the mirror image.
		for (long seed = 1; seed <= 20; seed++) {
			for (StreetCars.Car car : StreetCars.carsIn(seed, 0, 0, N, StreetCars.DEFAULT_DENSITY)) {
				assertTrue(car.minZ() + car.sizeZ() - 1 <= 11,
						car + " ran past the end of a north-only dead end");
			}
		}
	}
}
