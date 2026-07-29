package com.lostbuildings.world.feature;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the worldgen write-window invariant: nothing this mod derives from a feature origin may
 * resolve outside the generating chunk ±{@link WorldGenBounds#WRITE_RADIUS}.
 *
 * <p>Pure arithmetic — no Minecraft classes are touched, so this runs in a plain JVM.
 */
class WorldGenBoundsTest {

	/** A handful of origin chunks including negatives, where {@code >>} and {@code /} disagree. */
	private static final int[] CHUNKS = {0, 1, 7, -1, -12, -11, 30, -257};

	@Test
	void chunkOfRoundsTowardsNegativeInfinity() {
		assertEquals(0, WorldGenBounds.chunkOf(0));
		assertEquals(0, WorldGenBounds.chunkOf(15));
		assertEquals(1, WorldGenBounds.chunkOf(16));
		// -17/16 == -1 in Java, but the block at -17 lives in chunk -2. Getting this wrong is
		// exactly how a west-facing neighbour probe silently escaped the window.
		assertEquals(-2, WorldGenBounds.chunkOf(-17));
		assertEquals(-1, WorldGenBounds.chunkOf(-16));
		assertEquals(-1, WorldGenBounds.chunkOf(-1));
	}

	@Test
	void writeWindowIsTheGeneratingChunkPlusMinusOne() {
		for (int cx : CHUNKS) {
			for (int cz : CHUNKS) {
				for (int dx = -3; dx <= 3; dx++) {
					for (int dz = -3; dz <= 3; dz++) {
						boolean expected = Math.abs(dx) <= 1 && Math.abs(dz) <= 1;
						assertEquals(expected, WorldGenBounds.holdsChunk(cx + dx, cz + dz, cx, cz),
								"chunk offset " + dx + "," + dz);
					}
				}
			}
		}
	}

	/**
	 * The crash, reduced to arithmetic: a biome probe sitting on the minimum block edge of the outer
	 * chunk resolves two chunks out, because {@code BiomeManager} subtracts 2 before quantising.
	 */
	@Test
	void biomeProbeOnAChunkMinimumEdgeIsRejected() {
		for (int cx : CHUNKS) {
			int outerMin = (cx - WorldGenBounds.WRITE_RADIUS) << 4;   // first block of the outer chunk
			assertTrue(WorldGenBounds.holdsBlock(outerMin, outerMin, cx, cx),
					"the column itself is inside the window");
			assertFalse(WorldGenBounds.holdsBiomeSample(outerMin, outerMin, cx, cx),
					"but a biome probe there reaches into chunk " + (cx - 2));

			int outerMax = ((cx + WorldGenBounds.WRITE_RADIUS) << 4) + 15;   // last block of the outer chunk
			assertTrue(WorldGenBounds.holdsBlock(outerMax, outerMax, cx, cx));
			assertFalse(WorldGenBounds.holdsBiomeSample(outerMax, outerMax, cx, cx),
					"the fuzzy +5 corner reaches into chunk " + (cx + 2));
		}
	}

	/**
	 * Regression witness for the shipped bug: probes expressed as ±16 <em>blocks</em> from the raw
	 * origin are unsafe whenever the origin sits on a chunk border, which {@code InSquarePlacement}
	 * makes happen for 2 of every 16 columns.
	 */
	@Test
	void blockOffsetProbesFromTheRawOriginAreUnsafe() {
		int cx = -12;
		int originAtLowEdge = cx << 4;
		assertFalse(WorldGenBounds.holdsBiomeSample(originAtLowEdge - 16, originAtLowEdge, cx, cx),
				"origin on the chunk's low edge, probed -16 blocks");

		int originAtHighEdge = (cx << 4) + 15;
		assertFalse(WorldGenBounds.holdsBiomeSample(originAtHighEdge + 16, originAtHighEdge, cx, cx),
				"origin on the chunk's high edge, probed +16 blocks");
	}

	/** Chunk-centre probes leave 8 blocks of slack per side, so the fuzz can never consume them. */
	@Test
	void chunkCentreProbesAreSafeForEveryNeighbour() {
		for (int cx : CHUNKS) {
			for (int cz : CHUNKS) {
				for (int dx = -1; dx <= 1; dx++) {
					for (int dz = -1; dz <= 1; dz++) {
						int x = WorldGenBounds.chunkCenterBlock(cx + dx);
						int z = WorldGenBounds.chunkCenterBlock(cz + dz);
						assertTrue(WorldGenBounds.holdsBiomeSample(x, z, cx, cz),
								"chunk-centre probe at offset " + dx + "," + dz + " from " + cx + "," + cz);
					}
				}
			}
		}
	}

	@Test
	void chunkCentreIsInsideItsOwnChunk() {
		for (int c : CHUNKS) {
			assertEquals(c, WorldGenBounds.chunkOf(WorldGenBounds.chunkCenterBlock(c)));
		}
	}

	@Test
	void clampPullsColumnsBackIntoTheWindowAndLeavesLegalOnesAlone() {
		for (int c : CHUNKS) {
			int min = (c - 1) << 4;
			int max = ((c + 1) << 4) + 15;
			for (int block = min - 40; block <= max + 40; block++) {
				int clamped = WorldGenBounds.clampBlockToWindow(block, c);
				assertTrue(clamped >= min && clamped <= max, "clamped " + block + " to " + clamped);
				assertTrue(WorldGenBounds.holdsBlock(clamped, clamped, c, c));
				if (block >= min && block <= max) {
					assertEquals(block, clamped, "legal columns must not move");
				}
			}
		}
	}

	// --- invariants of the callers that feed positions to the level ---

	/**
	 * A city piece owns exactly one chunk cell and probes only inside it, which is strictly stronger
	 * than the old write window: the same probes that used to sit at the very edge of a ±1-chunk
	 * window (a group's outer lattice cell) now sit in the middle of the chunk being generated.
	 *
	 * <p>This replaces the old {@code CellLattice.OFFSETS} assertion. The lattice is gone — its job
	 * of keeping groups from overlapping is done by the structure set's {@code separation} and by
	 * {@code CityLayout}'s grid — but the window rule it was measured against still governs the
	 * biome reads below, so the class and this test stay.
	 */
	@Test
	void cityCellProbesStayInsideTheGeneratingChunk() {
		for (int cx : CHUNKS) {
			for (int cz : CHUNKS) {
				assertTrue(WorldGenBounds.holdsChunk(cx, cz, cx, cz), "a cell is its own chunk");
				int wx = cx << 4;
				int wz = cz << 4;
				// BuildingPiece.CELL_PROBES / Streets.paveCell sample 0..15 into the cell.
				for (int px : new int[]{0, 8, 15}) {
					for (int pz : new int[]{0, 8, 15}) {
						assertEquals(cx, WorldGenBounds.chunkOf(wx + px),
								"cell probe " + px + "," + pz + " left the chunk on X");
						assertEquals(cz, WorldGenBounds.chunkOf(wz + pz),
								"cell probe " + px + "," + pz + " left the chunk on Z");
						assertTrue(WorldGenBounds.holdsBlock(wx + px, wz + pz, cx, cz));
					}
				}
				// ...and 16 would not, which is why the probe constants stop at FOOTPRINT - 1.
				assertFalse(wx + 16 <= ((cx << 4) + 15), "block 16 belongs to the next cell");
				assertEquals(cx + 1, WorldGenBounds.chunkOf(wx + 16));
			}
		}
	}

	@Test
	void styleProbeColumnsAreDeterministicAndSafe() {
		for (int cx : CHUNKS) {
			for (int cz : CHUNKS) {
				int[][] columns = StyleSelector.probeColumns(cx, cz);

				assertEquals(9, columns.length, "origin chunk plus its 8 neighbours");
				assertArrayEquals(columns, StyleSelector.probeColumns(cx, cz),
						"same chunk must always yield the same probes, in the same order");
				assertEquals(WorldGenBounds.chunkCenterBlock(cx), columns[0][0], "origin probed first");
				assertEquals(WorldGenBounds.chunkCenterBlock(cz), columns[0][1], "origin probed first");

				for (int[] column : columns) {
					assertTrue(WorldGenBounds.holdsBiomeSample(column[0], column[1], cx, cz),
							"probe " + Arrays.toString(column) + " escapes the window");
				}
			}
		}
	}

	/**
	 * Style selection must not depend on where inside the chunk the feature happened to land —
	 * otherwise the same chunk could produce different styles for different placement rolls.
	 */
	@Test
	void styleProbesDependOnlyOnTheChunk() {
		int cx = -12;
		int cz = 30;
		int[][] expected = StyleSelector.probeColumns(cx, cz);
		for (int inChunkX = 0; inChunkX < 16; inChunkX++) {
			for (int inChunkZ = 0; inChunkZ < 16; inChunkZ++) {
				int originX = (cx << 4) + inChunkX;
				int originZ = (cz << 4) + inChunkZ;
				assertArrayEquals(expected,
						StyleSelector.probeColumns(WorldGenBounds.chunkOf(originX), WorldGenBounds.chunkOf(originZ)));
			}
		}
	}
}
