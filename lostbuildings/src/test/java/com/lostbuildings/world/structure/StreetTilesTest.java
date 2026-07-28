package com.lostbuildings.world.structure;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mask → {@code street_*} tile mapping (PORT #3), checked exhaustively.
 *
 * <p>There are only sixteen possible masks, so "exhaustively" is literal: every one is enumerated
 * and the choice is verified against the geometry of the shipped parts rather than against a copy of
 * the implementation. The check that matters is the rotation one — a tile turned the wrong way is
 * the failure mode that produces a road pointing into a wall, and it is invisible in a build log.
 *
 * <p>The reference geometry: {@code street_end} points west, {@code street_straight} runs west-east,
 * {@code street_bend} joins west and north, {@code street_t} has everything except south, and a
 * clockwise quarter turn maps west → north → east → south.
 */
class StreetTilesTest {

	private static final int N = CityLayout.NORTH;
	private static final int E = CityLayout.EAST;
	private static final int S = CityLayout.SOUTH;
	private static final int W = CityLayout.WEST;

	/** The sides a tile's arms point at, after {@code turns} clockwise quarter turns. */
	private static int armsOf(StreetTiles.Tile tile) {
		int base = switch (tile.part()) {
			case StreetTiles.NONE -> 0;
			case StreetTiles.END -> W;
			case StreetTiles.STRAIGHT -> W | E;
			case StreetTiles.BEND -> W | N;
			case StreetTiles.T -> W | N | E;
			case StreetTiles.ALL -> N | E | S | W;
			default -> throw new AssertionError("unexpected part " + tile.part());
		};
		return rotate(base, tile.quarterTurns());
	}

	/** Turn a mask clockwise: north→east→south→west. */
	private static int rotate(int mask, int turns) {
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

	/**
	 * The one property that matters: whatever tile and rotation is chosen, the roads it opens onto
	 * are exactly the roads the mask said were there. Everything else about the mapping is taste.
	 */
	@Test
	void everyMaskGetsATileWhoseArmsMatchIt() {
		for (int mask = 0; mask < 16; mask++) {
			StreetTiles.Tile tile = StreetTiles.forMask(mask);
			assertNotNull(tile, "mask " + mask + " produced no tile");
			assertTrue(tile.quarterTurns() >= 0 && tile.quarterTurns() < 4,
					"mask " + mask + " asked for " + tile.quarterTurns() + " turns");
			assertEquals(mask, armsOf(tile),
					"mask " + mask + " -> " + tile.part() + " turned " + tile.quarterTurns());
		}
	}

	/** The right part is chosen for the right number of connections. */
	@Test
	void tileFamilyFollowsTheConnectionCount() {
		assertEquals(StreetTiles.NONE, StreetTiles.forMask(0).part());
		for (int side : new int[]{N, E, S, W}) {
			assertEquals(StreetTiles.END, StreetTiles.forMask(side).part());
		}
		assertEquals(StreetTiles.STRAIGHT, StreetTiles.forMask(W | E).part());
		assertEquals(StreetTiles.STRAIGHT, StreetTiles.forMask(N | S).part());
		assertEquals(StreetTiles.BEND, StreetTiles.forMask(W | N).part());
		assertEquals(StreetTiles.BEND, StreetTiles.forMask(N | E).part());
		assertEquals(StreetTiles.BEND, StreetTiles.forMask(E | S).part());
		assertEquals(StreetTiles.BEND, StreetTiles.forMask(S | W).part());
		assertEquals(StreetTiles.T, StreetTiles.forMask(N | E | S).part());
		assertEquals(StreetTiles.ALL, StreetTiles.forMask(CityLayout.ALL_SIDES).part());
	}

	/** The unrotated tiles are the ones the JSON actually draws. */
	@Test
	void baseOrientationsMatchTheShippedParts() {
		assertEquals(0, StreetTiles.forMask(W).quarterTurns(), "street_end points west unrotated");
		assertEquals(0, StreetTiles.forMask(W | E).quarterTurns(), "street_straight runs west-east unrotated");
		assertEquals(0, StreetTiles.forMask(W | N).quarterTurns(), "street_bend joins west and north unrotated");
		assertEquals(0, StreetTiles.forMask(W | N | E).quarterTurns(), "street_t is missing its south arm unrotated");
	}

	/** Every shipped connectivity tile is reachable — nothing in the data set is dead weight. */
	@Test
	void everyConnectivityTileIsUsedSomewhere() {
		Set<String> used = new HashSet<>();
		for (int mask = 0; mask < 16; mask++) {
			used.add(StreetTiles.forMask(mask).part());
		}
		assertEquals(Set.of(StreetTiles.NONE, StreetTiles.END, StreetTiles.STRAIGHT,
				StreetTiles.BEND, StreetTiles.T, StreetTiles.ALL), used);
	}

	/** The tile choice is a pure function: same mask in, same tile out, forever. */
	@Test
	void selectionIsPure() {
		for (int mask = 0; mask < 16; mask++) {
			assertEquals(StreetTiles.forMask(mask), StreetTiles.forMask(mask));
		}
	}

	/** Through-roads are the straights, and only the straights. */
	@Test
	void throughRoadsAreTheStraights() {
		assertTrue(StreetTiles.isThroughRoad(W | E));
		assertTrue(StreetTiles.isThroughRoad(N | S));
		assertTrue(StreetTiles.isThroughRoad(N | S | E), "a T still carries traffic along one axis");
		assertTrue(!StreetTiles.isThroughRoad(CityLayout.ALL_SIDES), "a crossroads is not a through road");
		assertTrue(!StreetTiles.isThroughRoad(W | N), "a bend is not a through road");
		assertTrue(!StreetTiles.isThroughRoad(0));
	}
}
