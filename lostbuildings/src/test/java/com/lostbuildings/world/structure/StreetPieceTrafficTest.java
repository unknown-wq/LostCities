package com.lostbuildings.world.structure;

import com.lostbuildings.world.feature.StyleSelector;
import com.lostbuildings.world.structure.piece.StreetPiece;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That {@link StreetPiece} actually puts traffic on the road.
 *
 * <p><b>Why this exists separately from {@code StreetCarsTest} and {@code StreetCarPlacerTest}.</b>
 * Those two cover the car system thoroughly on its own terms — the layout is deterministic and
 * world-aligned, the stencils render to real blocks. Neither one proves the piece ever <em>calls</em>
 * it. That call is a single statement at the end of {@code StreetPiece.decorate}, and a single
 * statement is exactly the kind of thing that gets dropped in a merge or lost when the method is
 * refactored, with every other test still green and the roads silently empty again.
 *
 * <p>So this drives {@code decorate} itself through a recording {@link WorldGenLevel} and asserts
 * cars come out of it. {@code decorate} is private, which is the right visibility for it — the seam
 * is worth a reflective call rather than widening the API for a test.
 */
class StreetPieceTrafficTest {

	/** A cell with roads on all four sides: junction box plus four arms, the busiest case. */
	private static final int ALL = 0b1111;

	private static final int GROUND_Y = 64;

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void decoratePutsCarsOnTheCarriageway() {
		Map<BlockPos, BlockState> written = decorateCell(0, 0, ALL);

		long carBlocks = written.entrySet().stream()
				.filter(e -> isCarBodywork(e.getValue()))
				.count();
		assertTrue(carBlocks > 0,
				"StreetPiece.decorate wrote no car bodywork at all — the StreetCarPlacer call is "
						+ "missing from decorate, or it is being handed the wrong cell origin");
	}

	/**
	 * Every car block must stand on the carriageway, never on the kerb ring the decoration loop
	 * rewrites and never up on the pavement.
	 */
	@Test
	void noCarBlockLandsOnTheKerbRingOrThePavement() {
		Map<BlockPos, BlockState> written = decorateCell(0, 0, ALL);

		written.forEach((pos, state) -> {
			if (!isCarBodywork(state)) {
				return;
			}
			int dx = pos.getX() - 0;
			int dz = pos.getZ() - 0;
			if (dx < 0 || dx > 15 || dz < 0 || dz > 15) {
				return;     // a car straddling the seam; the neighbouring cell owns that column
			}
			assertTrue(StreetDecor.isRoad(dx, dz, ALL),
					"car bodywork at (" + dx + "," + dz + ") is not on the roadway");
			assertFalse(StreetDecor.isKerb(dx, dz, ALL),
					"car bodywork at (" + dx + "," + dz + ") is on the kerb ring, which decorate rewrites");
		});
	}

	/** Two cells of the same shape at different coordinates must not get identical traffic. */
	@Test
	void trafficVariesBetweenCells() {
		long here = countCars(decorateCell(0, 0, ALL));
		long far = countCars(decorateCell(64, 64, ALL));

		assertTrue(here > 0 || far > 0, "neither cell got any traffic");
		// Not an equality assertion: two cells are allowed to coincide. What must not happen is that
		// the placer is coordinate-blind, which would make every cell identical everywhere.
		boolean anyDifference = false;
		for (int cell = 1; cell <= 8 && !anyDifference; cell++) {
			anyDifference = countCars(decorateCell(cell * 16, 0, ALL)) != here;
		}
		assertTrue(anyDifference,
				"every cell produced the same amount of traffic — the placer is not seeing the cell origin");
	}

	private static long countCars(Map<BlockPos, BlockState> written) {
		return written.values().stream().filter(StreetPieceTrafficTest::isCarBodywork).count();
	}

	/**
	 * Bodywork is concrete, terracotta or blackstone. Deliberately narrow: the street tiles and the
	 * decoration loop use stone, sandstone, slabs, iron bars, lanterns and quartz, none of which can
	 * be mistaken for a car. {@code decorate} itself writes only air, a smooth stone slab, iron bars,
	 * a lantern and cracked stone bricks, so a hit here can only have come from the car placer.
	 */
	private static boolean isCarBodywork(BlockState state) {
		String id = state.getBlock().toString();
		return id.contains("concrete") || id.contains("terracotta") || id.contains("blackstone");
	}

	private static Map<BlockPos, BlockState> decorateCell(int cellX, int cellZ, int mask) {
		StreetPiece piece = new StreetPiece(
				cellX >> 4, cellZ >> 4, GROUND_Y,
				Blocks.STONE.defaultBlockState(), 8, mask,
				"citystyle_standard", false, 12, 0.0F,
				StyleSelector.Climate.TEMPERATE);

		Map<BlockPos, BlockState> written = new HashMap<>();
		WorldGenLevel level = recordingLevel(written);
		BoundingBox everywhere = new BoundingBox(
				cellX - 64, GROUND_Y - 32, cellZ - 64,
				cellX + 79, GROUND_Y + 32, cellZ + 79);

		try {
			Method decorate = StreetPiece.class.getDeclaredMethod(
					"decorate", WorldGenLevel.class, BoundingBox.class, long.class, int.class);
			decorate.setAccessible(true);
			decorate.invoke(piece, level, everywhere, 1337L, GROUND_Y - 1);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(
					"StreetPiece.decorate(WorldGenLevel, BoundingBox, long, int) is gone or changed shape; "
							+ "if it was renamed or refactored, check the StreetCarPlacer call survived", e);
		}
		return written;
	}

	private static WorldGenLevel recordingLevel(Map<BlockPos, BlockState> written) {
		return (WorldGenLevel) Proxy.newProxyInstance(
				StreetPieceTrafficTest.class.getClassLoader(),
				new Class<?>[]{WorldGenLevel.class},
				(proxy, method, args) -> {
					if ("setBlock".equals(method.getName()) && args != null && args.length >= 3) {
						written.put(((BlockPos) args[0]).immutable(), (BlockState) args[1]);
						return Boolean.TRUE;
					}
					return switch (method.getName()) {
						case "toString" -> "recording WorldGenLevel";
						case "hashCode" -> System.identityHashCode(proxy);
						case "equals" -> proxy == args[0];
						default -> defaultValue(method.getReturnType());
					};
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
