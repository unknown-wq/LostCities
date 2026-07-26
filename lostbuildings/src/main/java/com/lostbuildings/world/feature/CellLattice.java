package com.lostbuildings.world.feature;

/**
 * Deterministic ownership lattice over chunk cells — the B5 palliative.
 *
 * <p><b>The bug.</b> A group generated from chunk {@code (0,0)} claims the checkerboard cells
 * {@code {(0,0), (±1,±1)}}. A group generated from chunk {@code (1,1)} claims
 * {@code {(1,1), (0,0), (2,2), (2,0), (0,2)}}. Both claim {@code (0,0)} and {@code (1,1)}, so two
 * buildings get written into the same chunk and interpenetrate. Twelve of the surrounding chunk
 * positions conflict this way, and with the feature firing on average once per 20 chunks that
 * happens often.
 *
 * <p><b>The palliative.</b> Ownership is decided by the <em>cell</em>, not by the group: for a
 * cell {@code (cellX, cellZ)} there are exactly {@link #OFFSETS}{@code .length} groups that could
 * reach it (one per offset), and a hash of {@code (seed, cellX, cellZ)} picks which one of them
 * owns it. Every group evaluates the same pure function and reaches the same answer, so a cell is
 * never claimed twice. No mutable state, no cross-chunk lookups, identical output for a given
 * world seed.
 *
 * <p><b>Known cost.</b> Because each cell has exactly one owner among five symmetric candidates,
 * the expected number of cells a group wins is exactly 1 (and about a third of firings win none).
 * Groups therefore get smaller and sparser than the — broken — previous behaviour. That is
 * inherent to any stateless exclusive scheme on this geometry; restoring density needs the real
 * fix (per-chunk placement grid), which is deliberately out of scope here.
 */
public final class CellLattice {

	/**
	 * Chunk-cell offsets a group may claim relative to its own origin chunk: centre first, then
	 * the four diagonals. All lie inside the FEATURES write window (origin chunk ±1), and no two
	 * share a chunk edge, so buildings touch only at corners and the orthogonal chunks between
	 * them stay free for {@link Streets}.
	 *
	 * <p>The index into this array doubles as the "owner slot" a cell hashes to.
	 */
	public static final int[][] OFFSETS = {
			{0, 0},
			{1, 1}, {1, -1}, {-1, 1}, {-1, -1}
	};

	private CellLattice() {
	}

	/**
	 * Whether the group rooted at chunk {@code (originCX, originCZ)} owns the cell it would reach
	 * through {@code OFFSETS[offsetIndex]}.
	 *
	 * <p>The cell hashes to a single owner slot; the group owns the cell only when that slot is
	 * the very offset it used to get there — i.e. when the winning candidate origin is itself.
	 */
	public static boolean owns(long seed, int originCX, int originCZ, int offsetIndex) {
		int cellX = originCX + OFFSETS[offsetIndex][0];
		int cellZ = originCZ + OFFSETS[offsetIndex][1];
		return ownerSlot(seed, cellX, cellZ) == offsetIndex;
	}

	/** Which of the {@link #OFFSETS} candidate origins owns this cell. Pure in (seed, cell). */
	public static int ownerSlot(long seed, int cellX, int cellZ) {
		return (int) Math.floorMod(hash(seed, cellX, cellZ), OFFSETS.length);
	}

	/** SplitMix-style avalanche over (seed, x, z). Stable across runs and JVMs. */
	public static long hash(long seed, int x, int z) {
		long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
		h ^= h >>> 30;
		h *= 0xBF58476D1CE4E5B9L;
		h ^= h >>> 27;
		h *= 0x94D049BB133111EBL;
		h ^= h >>> 31;
		return h;
	}
}
