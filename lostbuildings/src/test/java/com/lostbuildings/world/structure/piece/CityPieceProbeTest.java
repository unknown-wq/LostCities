package com.lostbuildings.world.structure.piece;

import com.lostbuildings.LostBuildings;
import com.lostbuildings.registry.ModStructurePieceTypes;
import com.lostbuildings.world.feature.StyleSelector;
import com.lostbuildings.world.structure.Airport;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the two whole-cell surface sweeps cost: {@code CityPiece.weather} and
 * {@code AirportPiece.clearAirspace}.
 *
 * <p><b>The invariant both of them lean on</b> is the one {@code Foundation.build} and
 * {@code Streets.paveColumn} already lean on: {@code Heightmap.Types.WORLD_SURFACE_WG}'s predicate is
 * exactly "not air", and {@code WorldGenLevel.getHeight} answers one past the highest block that
 * matches it. So the block <em>at</em> the returned height is air, by construction, and every block
 * above it is too. {@code weather} used to ask the world to confirm that — one guaranteed-true probe
 * on every one of a cell's 256 columns — and {@code clearAirspace} used to probe eleven blocks of
 * every column looking for something to remove from a volume {@code paveCell} had just emptied.
 *
 * <p>These tests are therefore in two halves, like {@code FoundationTest}. The behavioural half pins
 * what the passes actually place, which the bounds must not change; the budget half is what fails if
 * somebody reinstates the probes.
 *
 * <p>The fake level maintains a real {@code WORLD_SURFACE_WG} height rather than answering a
 * constant, because that is the contract being relied on.
 */
class CityPieceProbeTest {

	private static final int GROUND_Y = 70;
	private static final int COLUMNS = 16 * 16;

	@BeforeAll
	static void boot() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@AfterEach
	void clearAssets() {
		LostBuildings.ASSETS = null;
		LostBuildings.ENGINE = null;
	}

	// ------------------------------------------------------------------ weather

	/**
	 * A snowy cell whose surface is solid gets a snow layer on top of every column, placed on the
	 * block the heightmap points at.
	 */
	@Test
	void snowSettlesOnTheColumnTopTheHeightmapNames() {
		Level level = new Level(GROUND_Y);      // highest solid block is GROUND_Y - 1
		new WeatherProbe(0, 0, StyleSelector.Climate.SNOWY).run(level);

		assertEquals(COLUMNS, level.written.size(), "every column of a snowy cell should get a layer");
		for (int dx = 0; dx < 16; dx++) {
			for (int dz = 0; dz < 16; dz++) {
				assertEquals(Blocks.SNOW.defaultBlockState(),
						level.written.get(new BlockPos(dx, GROUND_Y, dz)),
						"column (" + dx + "," + dz + ") should carry snow at the surface");
			}
		}
	}

	/**
	 * The one probe that is still made is the one that asks a real question: is the block under the
	 * surface something a layer can sit on. A column of water gets nothing.
	 */
	@Test
	void nothingSettlesOnASurfaceThatIsNotSturdy() {
		Level level = new Level(GROUND_Y);
		level.surface = Blocks.WATER.defaultBlockState();
		new WeatherProbe(0, 0, StyleSelector.Climate.SNOWY).run(level);

		assertTrue(level.written.isEmpty(),
				"snow settled on water, so the sturdiness probe was dropped along with the redundant one");
	}

	/**
	 * The budget. One heightmap lookup and <em>one</em> block probe per column: the block at the
	 * height itself is air by the heightmap's own contract and is no longer asked for.
	 */
	@Test
	void weatherProbesOneBlockPerColumnNotTwo() {
		Level level = new Level(GROUND_Y);
		new WeatherProbe(0, 0, StyleSelector.Climate.SNOWY).run(level);

		assertEquals(COLUMNS, level.heights.get(), "one heightmap lookup per column");
		assertEquals(COLUMNS, level.reads.get(),
				"weather should probe one block per column - the surface it is settling on. The block "
						+ "at the heightmap height is air by construction (WORLD_SURFACE_WG's predicate "
						+ "is 'not air'), so reading it back was 256 guaranteed-true probes per cell.");
	}

	/** A temperate cell does nothing at all, and costs nothing at all. */
	@Test
	void aTemperateCellIsFree() {
		Level level = new Level(GROUND_Y);
		new WeatherProbe(0, 0, StyleSelector.Climate.TEMPERATE).run(level);

		assertTrue(level.written.isEmpty());
		assertEquals(0, level.reads.get());
		assertEquals(0, level.heights.get());
	}

	// ------------------------------------------------------------------ the airfield's airspace

	/**
	 * An airfield cell over ground already level with its tarmac has nothing standing in its
	 * airspace, and must not pay to discover that eleven blocks at a time.
	 *
	 * <p>The assets are deliberately left unloaded: the piece then lays its base course, clears its
	 * airspace and returns, which is exactly the pair of sweeps under test. Both go through the same
	 * heightmap bound, so the budget covers both.
	 */
	@Test
	void anAirfieldOverLevelGroundDoesNotSweepItsAirspaceBlockByBlock() {
		Level level = new Level(GROUND_Y - 1);      // terrain surface is the tarmac course itself
		AirportPiece piece = new AirportPiece(new Airport.Segment(0, 0, Airport.PART_TERMINAL, 0),
				GROUND_Y, StyleSelector.DEFAULT_STYLE, StyleSelector.Climate.TEMPERATE);
		piece.postProcess(level.proxy(), null, null, RandomSource.create(1L),
				new BoundingBox(0, -63, 0, 15, 320, 15), new ChunkPos(0, 0),
				new BlockPos(0, GROUND_Y, 0));

		assertFalse(level.written.isEmpty(), "the piece surfaced nothing, so this proves nothing");
		assertTrue(level.reads.get() <= 3 * COLUMNS,
				"an airfield cell on level ground should probe a couple of blocks per column; got "
						+ level.reads.get() + " for " + COLUMNS + " columns. clearAirspace used to walk "
						+ "eleven blocks of every column unconditionally - 2,816 reads a cell - even "
						+ "though paveCell had just emptied that volume.");
	}

	/** Something genuinely standing in the airspace is still removed. */
	@Test
	void anAirfieldStillClearsWhatIsActuallyStandingInItsAirspace() {
		Level level = new Level(GROUND_Y + 4);      // terrain rises four blocks into the airspace
		AirportPiece piece = new AirportPiece(new Airport.Segment(0, 0, Airport.PART_TERMINAL, 0),
				GROUND_Y, StyleSelector.DEFAULT_STYLE, StyleSelector.Climate.TEMPERATE);
		piece.postProcess(level.proxy(), null, null, RandomSource.create(1L),
				new BoundingBox(0, -63, 0, 15, 320, 15), new ChunkPos(0, 0),
				new BlockPos(0, GROUND_Y, 0));

		BlockState air = Blocks.AIR.defaultBlockState();
		for (int y = GROUND_Y; y < GROUND_Y + 4; y++) {
			assertEquals(air, level.written.get(new BlockPos(6, y, 6)),
					"the terrain standing at y=" + y + " should have been cleared out of the airspace");
		}
	}

	// ------------------------------------------------------------------ helpers

	/** A bare {@link CityPiece} that does nothing but the weathering pass. */
	private static final class WeatherProbe extends CityPiece {

		WeatherProbe(int chunkX, int chunkZ, StyleSelector.Climate climate) {
			super(ModStructurePieceTypes.STREET,
					new BoundingBox(chunkX << 4, GROUND_Y - 12, chunkZ << 4,
							(chunkX << 4) + 15, GROUND_Y + 6, (chunkZ << 4) + 15),
					GROUND_Y, climate);
		}

		void run(Level level) {
			postProcess(level.proxy(), null, null, null,
					new BoundingBox(0, -63, 0, 15, 320, 15), new ChunkPos(0, 0), BlockPos.ZERO);
		}

		@Override
		public void postProcess(WorldGenLevel level, StructureManager structureManager,
		                        ChunkGenerator generator, RandomSource random, BoundingBox chunkBox,
		                        ChunkPos chunkPos, BlockPos referencePos) {
			weather(level, chunkBox, level.getSeed());
		}
	}

	/**
	 * Solid terrain below {@code terrainTop}, air above, and a {@code WORLD_SURFACE_WG} height that
	 * is kept in step with every write — the contract both bounds rely on.
	 */
	private static final class Level {

		final Map<BlockPos, BlockState> written = new LinkedHashMap<>();
		final AtomicInteger reads = new AtomicInteger();
		final AtomicInteger heights = new AtomicInteger();
		private final Map<Long, Integer> tops = new LinkedHashMap<>();
		private final int terrainTop;
		/** What the topmost solid block is; swap it to test the sturdiness probe. */
		BlockState surface = Blocks.STONE.defaultBlockState();

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
			if (y >= terrainTop) {
				return Blocks.AIR.defaultBlockState();
			}
			return y == terrainTop - 1 ? surface : Blocks.STONE.defaultBlockState();
		}

		private int top(int x, int z) {
			return tops.computeIfAbsent(col(x, z), key -> terrainTop);
		}

		WorldGenLevel proxy() {
			return (WorldGenLevel) Proxy.newProxyInstance(
					CityPieceProbeTest.class.getClassLoader(),
					new Class<?>[]{WorldGenLevel.class},
					(proxy, method, args) -> switch (method.getName()) {
						case "setBlock" -> {
							BlockPos pos = ((BlockPos) args[0]).immutable();
							BlockState state = (BlockState) args[1];
							written.put(pos, state);
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
						case "getSeed" -> 1234L;
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
