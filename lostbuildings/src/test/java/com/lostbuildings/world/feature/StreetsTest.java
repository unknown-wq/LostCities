package com.lostbuildings.world.feature;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Streets#paveCell} against fake columns of terrain — what it writes, and what it costs.
 *
 * <p><b>What this is guarding.</b> Both of {@code paveColumn}'s loops used to probe the world one
 * block at a time over their whole range: three blocks of headroom above the road even where the
 * road stood proud of the terrain and there was provably nothing there, and every rung of the
 * embankment down to solid ground even where the rung was known to be above the terrain surface.
 * Over a 9×9 city — 54 street cells, 6 park cells and 3 airfield cells, all of which pave through
 * this method — that was 90,145 {@code getBlockState} calls. Both loops are now bounded by the
 * column's {@code WORLD_SURFACE_WG} height, which is sound because that heightmap's predicate is
 * exactly "not air": every block at or above it is air, which fails the headroom loop's
 * {@code !isAir()} test and passes the embankment loop's "is this replaceable" test, so in neither
 * case could asking have changed the outcome. The same city now costs 25,681.
 *
 * <p>So the assertions come in pairs. The <em>writes</em> half pins the exact blocks each of the
 * three shapes of ground produces, which the bounds must not change. The <em>reads</em> half is what
 * fails if somebody puts the unbounded loops back: it asserts a probe budget per column rather than
 * one probe per block of the swept volume.
 *
 * <p>The fake level maintains a real {@code WORLD_SURFACE_WG} heightmap — "one past the highest
 * non-air block", updated by every {@code setBlock} — because that invariant <em>is</em> the thing
 * under test. A level that answered a constant height would let a broken bound pass.
 */
class StreetsTest {

	private static final int GROUND_Y = 70;
	/** Whole cell paved, which is the shipped street width and what parks and airfields always use. */
	private static final int WIDTH = 16;
	private static final int COLUMNS = 16 * 16;

	/**
	 * Deliberately a method, not a {@code static final} field: touching {@code Blocks} would
	 * initialise Minecraft's registries at class-load time, i.e. before {@link #boot()} runs.
	 */
	private static BlockState paving() {
		return Blocks.STONE_BRICKS.defaultBlockState();
	}

	@BeforeAll
	static void boot() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	// ------------------------------------------------------------------ flush with the terrain

	/**
	 * The ordinary case: the terrain surface is already the road surface. Nothing to excavate and
	 * nothing to fill, so the pass writes one paving block per column and stops.
	 */
	@Test
	void groundAtTheRoadSurfaceIsPavedAndNothingElseIsTouched() {
		Level level = new Level(GROUND_Y - 1);
		paveCell(level);

		assertEquals(COLUMNS, level.written.size(),
				"a cell flush with the terrain should write exactly its 256 paving blocks");
		for (int dx = 0; dx < 16; dx++) {
			for (int dz = 0; dz < 16; dz++) {
				assertEquals(paving(), level.written.get(new BlockPos(dx, GROUND_Y - 1, dz)),
						"column (" + dx + "," + dz + ") was not paved");
			}
		}
	}

	/**
	 * The budget for that case. One probe per column — the first rung of the embankment, which is
	 * the first block the heightmap does not already answer for. Without the bounds it is five: three
	 * blocks of headroom that were always air plus two of embankment.
	 */
	@Test
	void flatGroundCostsAtMostTwoProbesPerColumn() {
		Level level = new Level(GROUND_Y - 1);
		paveCell(level);

		assertProbeBudget(level, 2);
		assertEquals(COLUMNS, level.heights.get(),
				"the heightmap should be consulted exactly once per column");
	}

	// ------------------------------------------------------------------ terrain standing above

	/**
	 * Terrain standing above the shared level is dug away, so the road is not left entombed under the
	 * hillside the city was levelled into.
	 */
	@Test
	void terrainAboveTheRoadIsExcavatedAndTheAirAboveItIsLeftAlone() {
		int terrainTop = GROUND_Y + 5;      // highest solid block is GROUND_Y + 4
		Level level = new Level(terrainTop);
		paveCell(level);

		BlockState air = Blocks.AIR.defaultBlockState();
		for (int y = GROUND_Y; y < terrainTop; y++) {
			assertEquals(air, level.written.get(new BlockPos(3, y, 5)),
					"the terrain standing at y=" + y + " should have been cleared");
		}
		assertNull(level.written.get(new BlockPos(3, terrainTop, 5)),
				"nothing above the terrain surface should be written: it was already air, and the "
						+ "bound exists precisely so it is not even looked at");
		assertEquals(paving(), level.written.get(new BlockPos(3, GROUND_Y - 1, 5)),
				"the road surface itself is still laid");
	}

	/** Excavating five blocks costs five probes plus the one embankment probe, not the full 64. */
	@Test
	void excavationCostsOneProbePerBlockActuallyDugAndNoMore() {
		Level level = new Level(GROUND_Y + 5);
		paveCell(level);

		// 5 cleared blocks + 1 embankment probe. MAX_HEADROOM is 64, so an unbounded loop would be
		// far more; the point of the assertion is that it tracks the terrain, not the constant.
		assertProbeBudget(level, 7);
	}

	// ------------------------------------------------------------------ road carried on fill

	/**
	 * Ground below the shared level is filled up to it, so the road never floats. The fill stops at
	 * the first solid block rather than running the whole allowance.
	 */
	@Test
	void groundBelowTheRoadIsCarriedDownOnAnEmbankment() {
		int terrainTop = GROUND_Y - 4;      // highest solid block is GROUND_Y - 5
		Level level = new Level(terrainTop);
		paveCell(level);

		assertEquals(paving(), level.written.get(new BlockPos(7, GROUND_Y - 1, 9)));
		for (int y = GROUND_Y - 2; y >= terrainTop; y--) {
			assertEquals(paving(), level.written.get(new BlockPos(7, y, 9)),
					"the embankment should have filled y=" + y);
		}
		assertNull(level.written.get(new BlockPos(7, terrainTop - 1, 9)),
				"the embankment must stop at the first solid block, not dig into it");
	}

	/**
	 * ...and the fill above the terrain is placed <em>unread</em>: the heightmap already says those
	 * blocks are air, and air is replaceable. Only the rung that ends the loop is probed.
	 */
	@Test
	void fillAboveTheTerrainCostsNoProbesAtAll() {
		Level level = new Level(GROUND_Y - 4);
		paveCell(level);

		assertProbeBudget(level, 1);
	}

	// ------------------------------------------------------------------ the refusal still stands

	/** A column that would need more embankment than the allowance is refused, as it always was. */
	@Test
	void aColumnOverADropIsRefusedRatherThanStilted() {
		Level level = new Level(GROUND_Y - 40);
		paveCell(level);

		assertTrue(level.written.isEmpty(),
				"a road 40 blocks above the ground should be refused outright, not built on stilts");
		assertEquals(0, level.reads.get(), "a refused column must not probe the world either");
	}

	// ------------------------------------------------------------------ helpers

	private static void paveCell(Level level) {
		Streets.paveCell(level.proxy(), new BoundingBox(0, -63, 0, 15, 320, 15), 0, 0, GROUND_Y,
				paving(), WIDTH);
	}

	private static void assertProbeBudget(Level level, int perColumn) {
		assertFalse(level.written.isEmpty(), "the pass wrote nothing, so a probe budget proves nothing");
		assertTrue(level.reads.get() <= perColumn * COLUMNS,
				"Streets.paveColumn should probe at most " + perColumn + " blocks per column; got "
						+ level.reads.get() + " for " + COLUMNS + " columns ("
						+ (level.reads.get() / (double) COLUMNS) + " each). If the WORLD_SURFACE_WG "
						+ "bounds on the headroom and embankment loops were removed, this is what it cost.");
	}

	/**
	 * A level whose terrain is solid below {@code terrainTop} and air above, with a genuinely
	 * maintained {@code WORLD_SURFACE_WG} height — the invariant the bounds under test rely on.
	 */
	private static final class Level {

		final Map<BlockPos, BlockState> written = new LinkedHashMap<>();
		final AtomicInteger reads = new AtomicInteger();
		final AtomicInteger heights = new AtomicInteger();
		private final Map<Long, Integer> tops = new LinkedHashMap<>();
		private final int terrainTop;

		Level(int terrainTop) {
			this.terrainTop = terrainTop;
		}

		private static long col(int x, int z) {
			return ((long) x << 32) | (z & 0xFFFFFFFFL);
		}

		private BlockState at(int x, int y, int z) {
			BlockState set = written.get(new BlockPos(x, y, z));
			if (set != null) {
				return set;
			}
			return y < terrainTop ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
		}

		private int top(int x, int z) {
			return tops.computeIfAbsent(col(x, z), key -> terrainTop);
		}

		WorldGenLevel proxy() {
			return (WorldGenLevel) Proxy.newProxyInstance(
					StreetsTest.class.getClassLoader(),
					new Class<?>[]{WorldGenLevel.class},
					(proxy, method, args) -> switch (method.getName()) {
						case "setBlock" -> {
							BlockPos pos = ((BlockPos) args[0]).immutable();
							BlockState state = (BlockState) args[1];
							written.put(pos, state);
							// Keep the heightmap honest, exactly as ProtoChunk.setBlockState does.
							int t = top(pos.getX(), pos.getZ());
							if (!state.isAir()) {
								if (pos.getY() + 1 > t) {
									tops.put(col(pos.getX(), pos.getZ()), pos.getY() + 1);
								}
							} else if (pos.getY() + 1 == t) {
								int scan = pos.getY() - 1;
								while (scan > -64 && at(pos.getX(), scan, pos.getZ()).isAir()) {
									scan--;
								}
								tops.put(col(pos.getX(), pos.getZ()), scan + 1);
							}
							yield Boolean.TRUE;
						}
						case "getBlockState" -> {
							reads.incrementAndGet();
							BlockPos pos = (BlockPos) args[0];
							yield at(pos.getX(), pos.getY(), pos.getZ());
						}
						case "getHeight" -> {
							heights.incrementAndGet();
							yield top((Integer) args[1], (Integer) args[2]);
						}
						case "hasChunk" -> Boolean.TRUE;
						case "getSeed" -> 1L;
						case "getSeaLevel" -> 63;
						case "getMinY" -> -64;
						case "getMaxY" -> 320;
						case "toString" -> "fake WorldGenLevel";
						case "hashCode" -> System.identityHashCode(proxy);
						case "equals" -> proxy == args[0];
						default -> defaultValue(method.getReturnType());
					});
		}
	}

	private static Object defaultValue(Class<?> type) {
		if (!type.isPrimitive()) {
			return null;
		}
		if (type == boolean.class) {
			return Boolean.FALSE;
		}
		if (type == long.class) {
			return 0L;
		}
		if (type == float.class) {
			return 0.0F;
		}
		if (type == double.class) {
			return 0.0D;
		}
		if (type == void.class) {
			return null;
		}
		return 0;
	}
}
