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
 * <p><b>The grid.</b> A city is a square of {@code citySize × citySize} chunk cells centred on the
 * chunk the structure was rolled for. Cells alternate like a checkerboard: cells whose local
 * {@code (i + j)} is even are <em>building</em> cells, the rest are <em>street</em> cells. Two
 * buildings therefore never share a chunk edge — every orthogonal neighbour of a building is a
 * street cell, which is exactly the "orthogonal cells stay free for streets" invariant that
 * {@code CellLattice} could only approximate with its five-offset diagonal star.
 *
 * <p><b>Density.</b> Building cells other than the centre survive with probability
 * {@code density}. The centre cell is unconditional, so a city is never empty — the "empty firing"
 * failure mode of the old lattice cannot happen here.
 *
 * <p><b>Purity.</b> Nothing in this file touches Minecraft: no world reads, no block states, no
 * registries. Rotations are quarter turns, buildings are indices into the configured name list.
 * That makes the whole layout unit-testable in a plain JVM, and it makes the layout provably
 * deterministic — the only inputs are the seed and the city's chunk coordinates.
 */
public final class CityLayout {

	/** What a cell of the grid is used for. Parks/bridges join this enum in wave 2. */
	public enum Role {
		BUILDING,
		STREET
	}

	/** Neighbour-mask bit for the cell at {@code z - 1}. */
	public static final int NORTH = 1;
	/** Neighbour-mask bit for the cell at {@code x + 1}. */
	public static final int EAST = 2;
	/** Neighbour-mask bit for the cell at {@code z + 1}. */
	public static final int SOUTH = 4;
	/** Neighbour-mask bit for the cell at {@code x - 1}. */
	public static final int WEST = 8;

	/** Salts keeping the four per-cell rolls independent of one another. */
	private static final int SALT_DENSITY = 0x1;
	private static final int SALT_FLOORS = 0x2;
	private static final int SALT_ROTATION = 0x3;
	private static final int SALT_BUILDING = 0x4;

	private CityLayout() {
	}

	/**
	 * Everything the layout needs, with no Minecraft types in sight, so tests can build one
	 * directly. Values are normalised in the canonical constructor rather than trusted, because
	 * they come from a datapack.
	 *
	 * @param citySize      cells per side of the city square (odd values put a building in the middle)
	 * @param density       chance a non-central building cell is actually built, in {@code [0, 1]}
	 * @param minFloors     lowest storey count handed to a building
	 * @param maxFloors     highest storey count handed to a building
	 * @param buildingCount how many building names the caller configured; indices are modulo this
	 * @param streetWidth   paved width of a street cell's arms, in blocks ({@code 16} = whole cell)
	 */
	public record Settings(int citySize, double density, int minFloors, int maxFloors,
	                       int buildingCount, int streetWidth) {
		public Settings {
			citySize = Math.max(1, citySize);
			density = Math.clamp(density, 0.0D, 1.0D);
			minFloors = Math.max(0, minFloors);
			maxFloors = Math.max(minFloors, maxFloors);
			buildingCount = Math.max(1, buildingCount);
			streetWidth = Math.clamp(streetWidth, 1, 16);
		}
	}

	/**
	 * One cell of the grid.
	 *
	 * @param role          what stands here
	 * @param chunkX        absolute chunk X of the cell
	 * @param chunkZ        absolute chunk Z of the cell
	 * @param quarterTurns  clockwise rotation of the building, {@code 0..3} (0 for streets)
	 * @param floors        storey count for the building (0 for streets)
	 * @param buildingIndex index into the configured building-name list (0 for streets)
	 * @param neighbourMask OR of {@link #NORTH}/{@link #EAST}/{@link #SOUTH}/{@link #WEST} for every
	 *                      orthogonal neighbour that is itself an emitted cell of this city. Wave 2
	 *                      picks street tiles and building facades from this.
	 */
	public record Cell(Role role, int chunkX, int chunkZ, int quarterTurns, int floors,
	                   int buildingIndex, int neighbourMask) {
	}

	/** A whole city: where it is rooted and every cell it consists of, in a stable order. */
	public record Plan(int originChunkX, int originChunkZ, List<Cell> cells) {
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

		// Pass 1: decide occupancy, so pass 2 can compute neighbour masks against the final grid.
		boolean[] occupied = new boolean[size * size];
		Role[] roles = new Role[size * size];
		for (int i = 0; i < size; i++) {
			for (int j = 0; j < size; j++) {
				int index = i * size + j;
				if (((i + j) & 1) != 0) {
					roles[index] = Role.STREET;
					occupied[index] = true;
					continue;
				}
				roles[index] = Role.BUILDING;
				// The middle cell is unconditional: a city that rolled every building away would be
				// an "empty firing", the exact failure the old lattice was criticised for.
				boolean centre = (i == half && j == half);
				int cellX = originChunkX + i - half;
				int cellZ = originChunkZ + j - half;
				occupied[index] = centre || unit(hash(seed, cellX, cellZ, SALT_DENSITY)) < settings.density();
			}
		}

		// Pass 2: emit.
		List<Cell> cells = new ArrayList<>(size * size);
		for (int i = 0; i < size; i++) {
			for (int j = 0; j < size; j++) {
				int index = i * size + j;
				if (!occupied[index]) {
					continue;
				}
				int cellX = originChunkX + i - half;
				int cellZ = originChunkZ + j - half;
				int mask = neighbourMask(occupied, size, i, j);
				if (roles[index] == Role.STREET) {
					cells.add(new Cell(Role.STREET, cellX, cellZ, 0, 0, 0, mask));
					continue;
				}
				int floorSpan = settings.maxFloors() - settings.minFloors() + 1;
				int floors = settings.minFloors() + (int) Math.floorMod(hash(seed, cellX, cellZ, SALT_FLOORS), floorSpan);
				int turns = (int) Math.floorMod(hash(seed, cellX, cellZ, SALT_ROTATION), 4L);
				int building = (int) Math.floorMod(hash(seed, cellX, cellZ, SALT_BUILDING), settings.buildingCount());
				cells.add(new Cell(Role.BUILDING, cellX, cellZ, turns, floors, building, mask));
			}
		}
		return new Plan(originChunkX, originChunkZ, List.copyOf(cells));
	}

	private static int neighbourMask(boolean[] occupied, int size, int i, int j) {
		int mask = 0;
		if (isOccupied(occupied, size, i, j - 1)) {
			mask |= NORTH;
		}
		if (isOccupied(occupied, size, i + 1, j)) {
			mask |= EAST;
		}
		if (isOccupied(occupied, size, i, j + 1)) {
			mask |= SOUTH;
		}
		if (isOccupied(occupied, size, i - 1, j)) {
			mask |= WEST;
		}
		return mask;
	}

	private static boolean isOccupied(boolean[] occupied, int size, int i, int j) {
		if (i < 0 || j < 0 || i >= size || j >= size) {
			return false;
		}
		return occupied[i * size + j];
	}

	/**
	 * Whether the column {@code (dx, dz)} of a 16×16 street cell is paved.
	 *
	 * <p>The paved shape is a cross of two {@code width}-wide arms through the middle of the cell,
	 * so a cell reaches all four of its orthogonal neighbours. {@code width == 16} paves the whole
	 * cell, which is the shipped default and the only shape that leaves the road network of a
	 * checkerboard city corner-connected; narrower values are a datapack knob.
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
