package com.lostbuildings.world.feature;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the B5 palliative invariant: no chunk cell can ever be claimed by two building groups.
 * Pure arithmetic — no Minecraft classes are touched, so this runs in a plain JVM.
 */
class CellLatticeTest {

	private static final long SEED = 0x5EEDL;

	/**
	 * For every origin chunk in a patch, record which absolute cells it claims. A cell showing up
	 * twice is exactly the overlap bug (group (0,0) and group (1,1) both taking chunk (0,0)).
	 */
	@Test
	void noCellIsClaimedByTwoGroups() {
		Map<Long, String> claimedBy = new HashMap<>();
		for (int cx = -40; cx <= 40; cx++) {
			for (int cz = -40; cz <= 40; cz++) {
				for (int i = 0; i < CellLattice.OFFSETS.length; i++) {
					if (!CellLattice.owns(SEED, cx, cz, i)) {
						continue;
					}
					int cellX = cx + CellLattice.OFFSETS[i][0];
					int cellZ = cz + CellLattice.OFFSETS[i][1];
					long key = (((long) cellX) << 32) ^ (cellZ & 0xffffffffL);
					String owner = cx + "," + cz;
					assertNull(claimedBy.put(key, owner),
							"cell " + cellX + "," + cellZ + " claimed twice (second claim from " + owner + ")");
				}
			}
		}
		assertTrue(claimedBy.size() > 1000, "expected the lattice to hand out plenty of cells");
	}

	/** Every cell has exactly one owner slot, so ownership is total as well as exclusive. */
	@Test
	void everyCellHasExactlyOneOwner() {
		for (int cellX = -20; cellX <= 20; cellX++) {
			for (int cellZ = -20; cellZ <= 20; cellZ++) {
				int slot = CellLattice.ownerSlot(SEED, cellX, cellZ);
				assertTrue(slot >= 0 && slot < CellLattice.OFFSETS.length, "slot in range");

				int owners = 0;
				for (int i = 0; i < CellLattice.OFFSETS.length; i++) {
					int originX = cellX - CellLattice.OFFSETS[i][0];
					int originZ = cellZ - CellLattice.OFFSETS[i][1];
					if (CellLattice.owns(SEED, originX, originZ, i)) {
						owners++;
					}
				}
				assertEquals(1, owners, "cell " + cellX + "," + cellZ + " must have exactly one owner");
			}
		}
	}

	/** Same seed and coordinates must always produce the same answer — worldgen has to be stable. */
	@Test
	void ownershipIsDeterministic() {
		for (int i = 0; i < 200; i++) {
			assertEquals(CellLattice.ownerSlot(SEED, i, -i), CellLattice.ownerSlot(SEED, i, -i));
		}
		assertEquals(CellLattice.hash(SEED, 7, -13), CellLattice.hash(SEED, 7, -13));
	}

	/** All five slots actually get used, i.e. the hash is not degenerate. */
	@Test
	void allSlotsAreReachable() {
		boolean[] seen = new boolean[CellLattice.OFFSETS.length];
		for (int cellX = -30; cellX <= 30; cellX++) {
			for (int cellZ = -30; cellZ <= 30; cellZ++) {
				seen[CellLattice.ownerSlot(SEED, cellX, cellZ)] = true;
			}
		}
		for (int i = 0; i < seen.length; i++) {
			assertTrue(seen[i], "slot " + i + " never selected");
		}
	}
}
