package com.lostbuildings.world.structure;

import java.util.ArrayList;
import java.util.List;

/**
 * The city block grid — a <em>pure function</em> from (world seed, city chunk) to the list of
 * pieces that make up one city.
 *
 * <p><b>Why this class exists.</b> The mod used to generate as a {@code Feature}, which may only
 * write inside the generating chunk ±1 (a 48×48 block window). {@code CellLattice} — the class this
 * one replaces — worked around that by handing out chunk cells exclusively among five candidate
 * origins, which meant a group won <em>one</em> cell in expectation and about a third of firings
 * built nothing at all. Its own javadoc named the real fix and declared it out of scope: "a
 * per-chunk placement grid". This is that grid.
 *
 * <p><b>The grid (wave 2).</b> A city is a square of {@code citySize × citySize} chunk cells centred
 * on the chunk the structure was rolled for. A cell holds a building only when <em>both</em> of its
 * offsets from the centre are even; every other cell is a street. Wave 1 used a checkerboard
 * ({@code (i + j)} even = building), which also kept buildings from touching, but left the street
 * cells touching only at their corners — its own status file called the result "diagonally
 * connected, not orthogonally". Two-apart building cells give the street cells a real orthogonal
 * lattice instead: full street rows and columns with proper intersections, which is the input the
 * shipped {@code street_*} tiles were drawn for.
 *
 * <pre>
 *     B . B . B        B = building cell (both offsets from the centre even)
 *     . . . .          . = street cell
 *     B . B . B        every building is surrounded by street on all four sides,
 *     . . . . .        and the street cells form one connected lattice
 *     B . B . B
 * </pre>
 *
 * <p><b>Density, parks and downtown.</b> Building cells other than the centre survive with
 * probability {@code density}; a surviving cell becomes a park instead of a house with probability
 * {@code parkChance}. The centre cell is unconditional, so a city is never empty — the "empty
 * firing" failure mode of the old lattice cannot happen here. With probability
 * {@code downtownChance} a city is a <em>downtown</em>: the 2×2 quad rooted at its centre cell is
 * given over to one four-quadrant landmark building (PORT #1, IMPROVEMENTS #7).
 *
 * <p><b>Purity.</b> Nothing in this file touches Minecraft: no world reads, no block states, no
 * registries. Rotations are quarter turns, buildings are indices into the configured name list, and
 * the building's <em>kind</em> ({@link BuildingKind}) is a plain layout-level value that
 * {@code BuildingPiece} maps onto the engine's own role enum. That makes the whole layout
 * unit-testable in a plain JVM, and it makes the layout provably deterministic — the only inputs are
 * the seed and the city's chunk coordinates.
 */
public final class CityLayout {

	/** What a cell of the grid is used for. */
	public enum Role {
		/** An ordinary single-chunk building. */
		BUILDING,
		/** A paved cell: one {@code street_*} tile plus kerb decoration. */
		STREET,
		/** A green cell: one {@code park_*} part instead of a house. */
		PARK,
		/** One quadrant of a 2×2 landmark building; {@link Cell#variant()} says which. */
		MULTI_BUILDING
	}

	/**
	 * What a building is <em>for</em> (IMPROVEMENTS #5). Deliberately a layout-level enum: the
	 * engine's own {@code BuildingRole} lives in {@code engine/} and drags Minecraft in with it,
	 * while this file has to stay loadable in a plain JVM. {@code BuildingPiece} maps between them.
	 */
	public enum BuildingKind {
		RESIDENTIAL,
		SHOP,
		LIBRARY,
		TOWER
	}

	/** Neighbour-mask bit for the cell at {@code z - 1}. */
	public static final int NORTH = 1;
	/** Neighbour-mask bit for the cell at {@code x + 1}. */
	public static final int EAST = 2;
	/** Neighbour-mask bit for the cell at {@code z + 1}. */
	public static final int SOUTH = 4;
	/** Neighbour-mask bit for the cell at {@code x - 1}. */
	public static final int WEST = 8;
	/** All four neighbour bits — a full crossroads. */
	public static final int ALL_SIDES = NORTH | EAST | SOUTH | WEST;

	/** Salts keeping the per-cell rolls independent of one another. */
	private static final int SALT_DENSITY = 0x1;
	private static final int SALT_FLOORS = 0x2;
	private static final int SALT_ROTATION = 0x3;
	private static final int SALT_BUILDING = 0x4;
	private static final int SALT_PARK = 0x5;
	private static final int SALT_PARK_KIND = 0x6;
	private static final int SALT_KIND = 0x7;
	private static final int SALT_DOWNTOWN = 0x8;
	private static final int SALT_MULTI = 0x9;

	private CityLayout() {
	}

	/**
	 * Everything the layout needs, with no Minecraft types in sight, so tests can build one
	 * directly. Values are normalised in the canonical constructor rather than trusted, because
	 * they come from a datapack.
	 *
	 * @param citySize           cells per side of the city square (the centre cell always holds a building)
	 * @param density            chance a non-central building cell is actually built, in {@code [0, 1]}
	 * @param minFloors          lowest storey count handed to a building
	 * @param maxFloors          highest storey count handed to a building
	 * @param buildingCount      how many building names the caller configured; indices are modulo this
	 * @param streetWidth        paved width of a street cell's arms, in blocks ({@code 16} = whole cell)
	 * @param parkChance         chance a surviving building cell becomes a park instead
	 * @param parkKindCount      how many park parts the caller configured
	 * @param downtownChance     chance a city gets a 2×2 landmark building at its centre
	 * @param multiBuildingCount how many 2×2 landmark sets the caller configured
	 */
	public record Settings(int citySize, double density, int minFloors, int maxFloors,
	                       int buildingCount, int streetWidth, double parkChance, int parkKindCount,
	                       double downtownChance, int multiBuildingCount) {
		public Settings {
			citySize = Math.max(1, citySize);
			density = Math.clamp(density, 0.0D, 1.0D);
			minFloors = Math.max(0, minFloors);
			maxFloors = Math.max(minFloors, maxFloors);
			buildingCount = Math.max(1, buildingCount);
			streetWidth = Math.clamp(streetWidth, 1, 16);
			parkChance = Math.clamp(parkChance, 0.0D, 1.0D);
			parkKindCount = Math.max(1, parkKindCount);
			downtownChance = Math.clamp(downtownChance, 0.0D, 1.0D);
			multiBuildingCount = Math.max(1, multiBuildingCount);
		}

		/** Wave-1 shaped settings: no parks, no downtown. Kept so old callers and tests still read. */
		public Settings(int citySize, double density, int minFloors, int maxFloors,
		                int buildingCount, int streetWidth) {
			this(citySize, density, minFloors, maxFloors, buildingCount, streetWidth, 0.0D, 1, 0.0D, 1);
		}
	}

	/**
	 * One cell of the grid.
	 *
	 * @param role          what stands here
	 * @param chunkX        absolute chunk X of the cell
	 * @param chunkZ        absolute chunk Z of the cell
	 * @param quarterTurns  clockwise rotation of the building, {@code 0..3} (0 for streets, parks and
	 *                      landmark quadrants — those have to keep their edges aligned)
	 * @param floors        storey count for the building (0 for streets and parks)
	 * @param buildingIndex index into the configured building-name list; for {@link Role#MULTI_BUILDING}
	 *                      it indexes the landmark list instead
	 * @param neighbourMask OR of {@link #NORTH}/{@link #EAST}/{@link #SOUTH}/{@link #WEST}. For a
	 *                      street cell the bits mark orthogonal neighbours that are <em>also</em>
	 *                      street cells — that is exactly the road-connectivity mask the
	 *                      {@code street_*} tile choice needs. For every other role the bits mark any
	 *                      orthogonal neighbour that is an emitted cell of this city.
	 * @param variant       role-dependent extra: the quadrant {@code x * 2 + z} for
	 *                      {@link Role#MULTI_BUILDING}, the park-part index for {@link Role#PARK},
	 *                      {@code 0} otherwise
	 * @param kind          what the building is for; {@link BuildingKind#RESIDENTIAL} for non-buildings
	 */
	public record Cell(Role role, int chunkX, int chunkZ, int quarterTurns, int floors,
	                   int buildingIndex, int neighbourMask, int variant, BuildingKind kind) {
	}

	/**
	 * A whole city: where it is rooted, whether it is a downtown, and every cell it consists of, in
	 * a stable order.
	 */
	public record Plan(int originChunkX, int originChunkZ, boolean downtown, List<Cell> cells) {
		public boolean isEmpty() {
			return cells.isEmpty();
		}

		/** Cells of one role, in plan order — convenience for callers and tests. */
		public List<Cell> cellsOf(Role role) {
			List<Cell> filtered = new ArrayList<>();
			for (Cell cell : cells) {
				if (cell.role() == role) {
					filtered.add(cell);
				}
			}
			return filtered;
		}
	}

	/**
	 * Lay out the city rooted at chunk {@code (originChunkX, originChunkZ)}.
	 *
	 * <p>Pure: the same arguments always produce the same plan, on any JVM and in any order.
	 */
	public static Plan plan(long seed, int originChunkX, int originChunkZ, Settings settings) {
		int size = settings.citySize();
		int half = (size - 1) / 2;

		// Pass 1: decide the role of every cell, so pass 2 can compute neighbour masks against the
		// final grid rather than against a grid that is still being built.
		Role[] roles = new Role[size * size];
		for (int i = 0; i < size; i++) {
			for (int j = 0; j < size; j++) {
				int index = i * size + j;
				int di = i - half;
				int dj = j - half;
				if (((di & 1) | (dj & 1)) != 0) {
					roles[index] = Role.STREET;
					continue;
				}
				int cellX = originChunkX + di;
				int cellZ = originChunkZ + dj;
				// The middle cell is unconditional: a city that rolled every building away would be
				// an "empty firing", the exact failure the old lattice was criticised for.
				if (di == 0 && dj == 0) {
					roles[index] = Role.BUILDING;
					continue;
				}
				if (unit(hash(seed, cellX, cellZ, SALT_DENSITY)) >= settings.density()) {
					roles[index] = null;    // empty lot
					continue;
				}
				roles[index] = unit(hash(seed, cellX, cellZ, SALT_PARK)) < settings.parkChance()
						? Role.PARK : Role.BUILDING;
			}
		}

		// Downtown: the 2x2 quad rooted at the centre cell becomes one landmark building. It eats
		// three street cells; the masks computed below make the surrounding tiles bend around it.
		boolean downtown = settings.downtownChance() > 0.0D
				&& unit(hash(seed, originChunkX, originChunkZ, SALT_DOWNTOWN)) < settings.downtownChance()
				&& half + 1 < size;
		if (downtown) {
			for (int dx = 0; dx <= 1; dx++) {
				for (int dz = 0; dz <= 1; dz++) {
					roles[(half + dx) * size + (half + dz)] = Role.MULTI_BUILDING;
				}
			}
		}

		// Pass 2: emit.
		int multiIndex = (int) Math.floorMod(hash(seed, originChunkX, originChunkZ, SALT_MULTI),
				settings.multiBuildingCount());
		int multiFloors = settings.maxFloors();
		List<Cell> cells = new ArrayList<>(size * size);
		for (int i = 0; i < size; i++) {
			for (int j = 0; j < size; j++) {
				Role role = roles[i * size + j];
				if (role == null) {
					continue;
				}
				int di = i - half;
				int dj = j - half;
				int cellX = originChunkX + di;
				int cellZ = originChunkZ + dj;
				int mask = neighbourMask(roles, size, i, j, role == Role.STREET);
				switch (role) {
					case STREET -> cells.add(new Cell(Role.STREET, cellX, cellZ, 0, 0, 0, mask, 0,
							BuildingKind.RESIDENTIAL));
					case PARK -> {
						int kind = (int) Math.floorMod(hash(seed, cellX, cellZ, SALT_PARK_KIND),
								settings.parkKindCount());
						cells.add(new Cell(Role.PARK, cellX, cellZ, 0, 0, 0, mask, kind,
								BuildingKind.RESIDENTIAL));
					}
					case MULTI_BUILDING -> {
						// Quadrant naming follows the original MultiBuilding table, which is indexed
						// [x][z]: the north-west quadrant is "<name>00", the south-east "<name>11".
						int quadrant = (i - half) * 2 + (j - half);
						cells.add(new Cell(Role.MULTI_BUILDING, cellX, cellZ, 0, multiFloors,
								multiIndex, mask, quadrant, BuildingKind.TOWER));
					}
					case BUILDING -> {
						int floors = floorsFor(seed, cellX, cellZ, di, dj, half, settings);
						int turns = (int) Math.floorMod(hash(seed, cellX, cellZ, SALT_ROTATION), 4L);
						int building = (int) Math.floorMod(hash(seed, cellX, cellZ, SALT_BUILDING),
								settings.buildingCount());
						BuildingKind kind = kindFor(seed, cellX, cellZ, floors, settings);
						cells.add(new Cell(Role.BUILDING, cellX, cellZ, turns, floors, building, mask, 0, kind));
					}
				}
			}
		}
		return new Plan(originChunkX, originChunkZ, downtown, List.copyOf(cells));
	}

	/**
	 * Storey count for one building cell — the block silhouette of IMPROVEMENTS #6.
	 *
	 * <p>The configured range is not rolled flat over the whole city. The closer a cell is to the
	 * centre, the higher its <em>ceiling</em>: at the centre the full {@code [min, max]} range is
	 * available, at the city edge only {@code min}. A quarter therefore reads as a skyline that rises
	 * towards its middle instead of as a field of equally tall boxes, and the result still never
	 * leaves the configured range.
	 *
	 * <p>Pure integer/double arithmetic on the cell's own hash, so it is deterministic and testable.
	 */
	public static int floorsFor(long seed, int cellX, int cellZ, int di, int dj, int half, Settings settings) {
		int span = settings.maxFloors() - settings.minFloors();
		if (span <= 0) {
			return settings.minFloors();
		}
		int ring = Math.max(Math.abs(di), Math.abs(dj));
		double closeness = half <= 0 ? 1.0D : 1.0D - (double) ring / (double) half;
		int ceiling = settings.minFloors() + (int) Math.round(span * Math.clamp(closeness, 0.0D, 1.0D));
		int localSpan = ceiling - settings.minFloors() + 1;
		return settings.minFloors() + (int) Math.floorMod(hash(seed, cellX, cellZ, SALT_FLOORS), localSpan);
	}

	/**
	 * What a building is for (IMPROVEMENTS #5). Anything at the configured maximum height is a
	 * tower; the rest is mostly housing with the odd shop and the rarer library.
	 */
	public static BuildingKind kindFor(long seed, int cellX, int cellZ, int floors, Settings settings) {
		if (floors >= settings.maxFloors() && settings.maxFloors() > settings.minFloors()) {
			return BuildingKind.TOWER;
		}
		long roll = Math.floorMod(hash(seed, cellX, cellZ, SALT_KIND), 10L);
		if (roll < 2L) {
			return BuildingKind.SHOP;
		}
		if (roll < 3L) {
			return BuildingKind.LIBRARY;
		}
		return BuildingKind.RESIDENTIAL;
	}

	/**
	 * Neighbour bits for one cell.
	 *
	 * @param streetsOnly when true only street neighbours count. Street tiles are picked from road
	 *                    connectivity, and on this grid a street's orthogonal neighbours are a mix of
	 *                    houses and roads — counting the houses would turn every straight into a
	 *                    crossroads.
	 */
	private static int neighbourMask(Role[] roles, int size, int i, int j, boolean streetsOnly) {
		int mask = 0;
		if (counts(roles, size, i, j - 1, streetsOnly)) {
			mask |= NORTH;
		}
		if (counts(roles, size, i + 1, j, streetsOnly)) {
			mask |= EAST;
		}
		if (counts(roles, size, i, j + 1, streetsOnly)) {
			mask |= SOUTH;
		}
		if (counts(roles, size, i - 1, j, streetsOnly)) {
			mask |= WEST;
		}
		return mask;
	}

	private static boolean counts(Role[] roles, int size, int i, int j, boolean streetsOnly) {
		if (i < 0 || j < 0 || i >= size || j >= size) {
			return false;
		}
		Role role = roles[i * size + j];
		if (role == null) {
			return false;
		}
		return !streetsOnly || role == Role.STREET;
	}

	/**
	 * Whether the column {@code (dx, dz)} of a 16×16 street cell is paved.
	 *
	 * <p>The paved shape is a cross of two {@code width}-wide arms through the middle of the cell,
	 * so a cell reaches all four of its orthogonal neighbours. {@code width == 16} paves the whole
	 * cell, which is the shipped default and the shape the 16×16 {@code street_*} tiles are laid on;
	 * narrower values are a datapack knob for the tile-less fallback.
	 *
	 * <p>Pure integer arithmetic on purpose — this is the part of {@code StreetPiece} worth testing.
	 */
	public static boolean isStreetColumn(int dx, int dz, int width) {
		if (dx < 0 || dz < 0 || dx > 15 || dz > 15) {
			return false;
		}
		int w = Math.clamp(width, 1, 16);
		int low = (16 - w) / 2;
		int high = low + w - 1;
		boolean alongX = dz >= low && dz <= high;
		boolean alongZ = dx >= low && dx <= high;
		return alongX || alongZ;
	}

	/** SplitMix-style avalanche over (seed, x, z). Stable across runs and JVMs. */
	public static long hash(long seed, int x, int z) {
		return hash(seed, x, z, 0);
	}

	/** As {@link #hash(long, int, int)} but with a salt, so independent rolls stay independent. */
	public static long hash(long seed, int x, int z, int salt) {
		long h = seed
				^ (x * 0x9E3779B97F4A7C15L)
				^ (z * 0xC2B2AE3D27D4EB4FL)
				^ (salt * 0xD6E8FEB86659FD93L);
		h ^= h >>> 30;
		h *= 0xBF58476D1CE4E5B9L;
		h ^= h >>> 27;
		h *= 0x94D049BB133111EBL;
		h ^= h >>> 31;
		return h;
	}

	/** A hash folded into {@code [0, 1)} — the top 53 bits, so the mapping is uniform. */
	public static double unit(long hash) {
		return (hash >>> 11) * 0x1.0p-53;
	}
}
