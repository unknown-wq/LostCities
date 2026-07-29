package com.lostbuildings.world.feature;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Foundation} against a fake column of terrain.
 *
 * <p><b>What this is really guarding.</b> The clear and drain loops used to probe every block from
 * the building base to the top of the clear volume — 58 blocks per column for an eight-storey house,
 * so 15,104 {@code getBlockState} calls per building, against 6,206 for the whole building engine.
 * Almost all of them were reads of blocks that were already air. They are now bounded by the
 * column's {@code WORLD_SURFACE_WG} height, which is sound because that heightmap's predicate is
 * exactly "not air": everything at or above it fails both {@code !isAir()} and {@code liquid()}, so
 * the skipped probes could never have written anything.
 *
 * <p>The assertions below are therefore in two halves. The <em>writes</em> half is the correctness
 * one — it pins the exact set of blocks the pass places, which the bound must not change. The
 * <em>reads</em> half is the one that fails if somebody removes the bound again: it asserts the pass
 * costs a handful of probes per column rather than the full height of the building.
 *
 * <p>Driven through a JDK proxy {@link WorldGenLevel} in the style of {@code StreetPieceTrafficTest},
 * so it needs no server and no real chunk.
 */
class FoundationTest {

	private static final int BASE_Y = 70;
	/** Highest non-air block of the fake terrain; {@code getHeight} therefore answers one above it. */
	private static final int TERRAIN_TOP = 71;
	private static final int SIZE = 4;
	/** Eight storeys' worth of headroom — the same shape {@code BuildingPiece} asks for. */
	private static final int CLEAR_HEIGHT = (8 + 1) * 6 + 4;

	@BeforeAll
	static void boot() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void clearsTheTerrainStandingInsideTheFootprintAndNothingElse() {
		Map<BlockPos, BlockState> written = new LinkedHashMap<>();
		AtomicInteger reads = new AtomicInteger();
		WorldGenLevel level = fakeLevel(written, reads);

		Foundation.build(level, new BlockPos(0, BASE_Y, 0), SIZE, SIZE, CLEAR_HEIGHT,
				Blocks.COBBLESTONE.defaultBlockState(), 63, RandomSource.create(1L));

		// The terrain occupies BASE_Y and TERRAIN_TOP in every column; both are cleared to air, and
		// nothing above TERRAIN_TOP is touched because there is nothing there.
		assertEquals(SIZE * SIZE * 2, written.size(),
				"the pass should clear exactly the two solid blocks standing in each of the "
						+ (SIZE * SIZE) + " columns");
		for (int dx = 0; dx < SIZE; dx++) {
			for (int dz = 0; dz < SIZE; dz++) {
				for (int y = BASE_Y; y <= TERRAIN_TOP; y++) {
					BlockPos pos = new BlockPos(dx, y, dz);
					assertEquals(Blocks.AIR.defaultBlockState(), written.get(pos),
							"column (" + dx + "," + dz + ") should have been cleared at y=" + y);
				}
			}
		}

		// The bound: a handful of probes per column, not one per block of the clear volume. Three
		// reads per column with it (one pillar probe and the two solid blocks); 59 without.
		int perColumn = reads.get() / (SIZE * SIZE);
		assertTrue(perColumn <= 8,
				"Foundation should probe a few blocks per column, not the whole clear volume; got "
						+ perColumn + " reads per column (" + reads.get() + " in total). If the "
						+ "WORLD_SURFACE_WG bound in Foundation.build was removed, this is what it cost.");
	}

	/**
	 * A level whose terrain is solid up to {@link #TERRAIN_TOP} and air above, with a
	 * {@code WORLD_SURFACE_WG} height that agrees with it. Writes are recorded; they do not change
	 * what subsequent reads answer, which is fine here because the pass never reads a column twice.
	 */
	private static WorldGenLevel fakeLevel(Map<BlockPos, BlockState> written, AtomicInteger reads) {
		BlockState air = Blocks.AIR.defaultBlockState();
		BlockState stone = Blocks.STONE.defaultBlockState();
		return (WorldGenLevel) Proxy.newProxyInstance(
				FoundationTest.class.getClassLoader(),
				new Class<?>[]{WorldGenLevel.class},
				(proxy, method, args) -> switch (method.getName()) {
					case "setBlock" -> {
						written.put(((BlockPos) args[0]).immutable(), (BlockState) args[1]);
						yield Boolean.TRUE;
					}
					case "getBlockState" -> {
						reads.incrementAndGet();
						yield ((BlockPos) args[0]).getY() <= TERRAIN_TOP ? stone : air;
					}
					// getFirstAvailable semantics: one past the highest non-air block.
					case "getHeight" -> TERRAIN_TOP + 1;
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
