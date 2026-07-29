package com.lostbuildings.engine;

import com.google.gson.JsonParser;
import com.lostbuildings.engine.codec.BuildingPartRE;
import com.lostbuildings.engine.codec.BuildingRE;
import com.lostbuildings.engine.codec.PaletteRE;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How many times {@link BuildingEngine} touches each block it places, and why it is allowed to touch
 * it that few times.
 *
 * <p><b>What this is guarding.</b> Two passes over a building's volume used to be paid for twice
 * over:
 * <ul>
 *   <li>Every pane, bar, fence, wall and stair had its connections resolved as it was placed
 *       <em>and</em> again in the correction pass at the end. The first answer was always thrown
 *       away — the second one recomputes all of the same properties from the finished building —
 *       but it still cost four {@code getBlockState} calls per connectable, about 1,500 per
 *       building.</li>
 *   <li>Every cellar cell was cleared to air <em>before</em> its part was written, so each cell the
 *       part then filled was read once and written twice. About 565 wasted reads and 565 wasted
 *       writes per building; the carve now runs afterwards and skips the cells the part filled.</li>
 * </ul>
 *
 * <p>Both rest on the same invariant, which {@link #connectionStateIsInvisibleToEveryNeighbour()}
 * pins over the whole block registry: <b>what a block connects to depends on its neighbour's block,
 * never on that neighbour's connection state</b>. If that ever stopped being true, a block placed
 * before it was corrected could change somebody else's answer and neither shortcut would be sound.
 *
 * <p>The budget assertions are the ones that fail if somebody puts either pass back. They are
 * deliberately expressed as "one write per position, plus at most one correction per connectable"
 * rather than as magic numbers, so they survive edits to the test geometry.
 */
class EngineWritePassTest {

	private static final int GROUND_Y = 64;
	private static final int SIZE = 8;
	private static final int SLICES = 6;
	private static final long SEED = 0xB0A710C1L;

	@BeforeAll
	static void boot() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	// ------------------------------------------------------------------ the invariant

	/**
	 * Everything {@code BlockStates} asks about a neighbour is a property of that neighbour's
	 * <em>block</em>, not of the connection state written into it. Checked over every connectable
	 * block in the registry and every one of its states, because it is a statement about Minecraft's
	 * block model rather than about this mod's data.
	 */
	@Test
	void connectionStateIsInvisibleToEveryNeighbour() {
		int checked = 0;
		for (Block block : BuiltInRegistries.BLOCK) {
			if (!(block instanceof CrossCollisionBlock || block instanceof WallBlock || block instanceof StairBlock)) {
				continue;
			}
			List<BlockState> states = block.getStateDefinition().getPossibleStates();
			BlockState first = states.get(0);
			for (BlockState state : states) {
				assertEquals(first.isAir(), state.isAir(),
						block + ": isAir() must not depend on the block's own state");
				assertEquals(first.canOcclude(), state.canOcclude(),
						block + ": canOcclude() must not depend on the block's own state - the engine "
								+ "places connectables uncorrected and corrects them in one pass at the end, "
								+ "which is only sound while a half-corrected block looks the same to its "
								+ "neighbours");
				assertEquals(Block.isExceptionForConnection(first), Block.isExceptionForConnection(state),
						block + ": isExceptionForConnection() must not depend on the block's own state");
			}
			checked++;
		}
		assertTrue(checked > 50, "expected the registry to hold plenty of connectable blocks, found " + checked);
	}

	// ------------------------------------------------------------------ budgets

	/**
	 * A building without cellars: every position is written exactly once, apart from the
	 * connectables, which the single correction pass may rewrite once each. The reads are bounded by
	 * that same pass — one to fetch the block plus at most four neighbours — so a second, place-time
	 * correction would immediately push this over budget.
	 */
	@Test
	void connectionsAreResolvedInOnePassNotTwo() {
		Run run = generate(2, 0, 0.0f);

		int connectables = 0;
		for (BlockState state : run.world.values()) {
			if (isConnectable(state.getBlock())) {
				connectables++;
			}
		}
		assertTrue(connectables > 0, "the test part must actually place panes, walls and stairs");

		assertTrue(run.writes <= run.world.size() + connectables,
				"every position should be written once, plus at most one correction per connectable: "
						+ run.writes + " writes for " + run.world.size() + " positions and "
						+ connectables + " connectables. Correcting connections as they are placed as "
						+ "well as at the end is what this is here to catch.");

		assertTrue(run.reads <= 5L * connectables,
				"the only reads left in an uncellared building are the correction pass's own - one "
						+ "block plus at most four neighbours per connectable, so at most "
						+ (5 * connectables) + "; got " + run.reads + ". Roughly twice that means "
						+ "connections are being resolved at placement time too.");
	}

	/**
	 * A building with cellars: the carve must not pre-clear the cells its own part is about to fill.
	 * Expressed as the same "one write per position" budget, because clearing first is exactly what
	 * makes a cell get written twice.
	 */
	@Test
	void theCellarCarveDoesNotClearCellsThePartThenFills() {
		Run run = generate(1, 1, 0.0f);

		int belowConnectables = 0;
		int belowPositions = 0;
		for (Map.Entry<BlockPos, BlockState> e : run.world.entrySet()) {
			if (e.getKey().getY() >= GROUND_Y) {
				continue;
			}
			belowPositions++;
			if (isConnectable(e.getValue().getBlock())) {
				belowConnectables++;
			}
		}
		assertTrue(belowPositions > 0, "the building must actually have a cellar");

		assertTrue(run.belowWrites <= belowPositions + belowConnectables,
				"a cellar cell should be written once - either cleared to air or filled by the part, "
						+ "not both: " + run.belowWrites + " writes for " + belowPositions
						+ " positions and " + belowConnectables + " connectables. Carving the slice "
						+ "before generating the part is what this is here to catch.");

		int cellarVolume = SIZE * SIZE * SLICES;
		int filledByPart = 0;
		for (Map.Entry<BlockPos, BlockState> e : run.world.entrySet()) {
			if (e.getKey().getY() < GROUND_Y && !e.getValue().isAir()) {
				filledByPart++;
			}
		}
		assertTrue(run.belowReads <= (long) (cellarVolume - filledByPart) + 5L * belowConnectables,
				"the carve should only probe the cells its part left empty (" + (cellarVolume - filledByPart)
						+ ") plus the correction pass's " + (5 * belowConnectables) + "; got " + run.belowReads);

		// The correctness half: whatever the carve skipped, no foundation fill may survive inside
		// the cellar. Everything in the volume is either air or something the part placed.
		for (int x = 0; x < SIZE; x++) {
			for (int z = 0; z < SIZE; z++) {
				for (int y = GROUND_Y - SLICES; y < GROUND_Y; y++) {
					BlockPos pos = new BlockPos(x, y, z);
					BlockState state = run.world.get(pos);
					assertFalse(state == null || state == FILL,
							"the cellar still holds foundation fill at " + pos + " - the carve was skipped");
				}
			}
		}
	}

	/**
	 * The reordered carve is only sound while the placement loop never reads the world. If a read
	 * creeps back into it, generating the part before clearing the slice would start answering that
	 * read with the part's own blocks.
	 */
	@Test
	void thePlacementLoopReadsNothingFromTheWorld() {
		Run run = generate(2, 0, 0.0f);
		for (String site : run.readSites) {
			assertFalse(site.endsWith("generatePart"),
					"BuildingEngine.generatePart must not read the world, directly or through a "
							+ "helper: the cellar carve now runs after the part and would answer any "
							+ "such read with the part's own blocks. Read reached from " + site);
		}
	}

	// ------------------------------------------------------------------ harness

	private static BlockState FILL;

	private record Run(Map<BlockPos, BlockState> world, long writes, long reads, long belowWrites,
	                   long belowReads, Set<String> readSites) {
	}

	private static boolean isConnectable(Block block) {
		return block instanceof CrossCollisionBlock || block instanceof WallBlock || block instanceof StairBlock;
	}

	private static Run generate(int floors, int cellars, float damage) {
		FILL = Blocks.COBBLESTONE.defaultBlockState();
		Assets assets = new Assets();

		PaletteRE paletteRE = PaletteRE.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(PALETTE))
				.getOrThrow();
		Palette palette = new Palette("testpalette");
		palette.parsePaletteArray(paletteRE, Map.of());

		BuildingPartRE partRE = BuildingPartRE.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(PART))
				.getOrThrow();
		partRE.setRegistryName(Identifier.fromNamespaceAndPath("lostcities", "testpart"));
		assets.putPart("testpart", new BuildingPart(partRE, Map.of()));

		BuildingRE buildingRE = BuildingRE.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(BUILDING))
				.getOrThrow();
		buildingRE.setRegistryName(Identifier.fromNamespaceAndPath("lostcities", "testbuilding"));
		Building building = new Building(buildingRE, Map.of());
		assets.putBuilding("testbuilding", building);
		assets.resolveReferences();

		Map<BlockPos, BlockState> world = new HashMap<>();
		long[] counters = new long[4];   // writes, reads, belowWrites, belowReads
		Set<String> readSites = new HashSet<>();
		WorldGenLevel level = fakeLevel(world, counters, readSites);

		BuildingEngine engine = new BuildingEngine(assets);
		engine.generateBuilding(level, new BlockPos(0, GROUND_Y, 0), RandomSource.create(SEED),
				Transform.ROTATE_NONE, building, new CompiledPalette(palette),
				new PlaceSettings(floors, floors, true, true, true, 63, cellars, damage,
						BuildingRole.RESIDENTIAL));

		return new Run(world, counters[0], counters[1], counters[2], counters[3], readSites);
	}

	/** Solid below {@link #GROUND_Y}, air above, and it remembers what was written into it. */
	private static WorldGenLevel fakeLevel(Map<BlockPos, BlockState> world, long[] counters,
	                                       Set<String> readSites) {
		BlockState air = Blocks.AIR.defaultBlockState();
		return (WorldGenLevel) Proxy.newProxyInstance(
				EngineWritePassTest.class.getClassLoader(),
				new Class<?>[]{WorldGenLevel.class},
				(proxy, method, args) -> switch (method.getName()) {
					case "setBlock" -> {
						BlockPos pos = ((BlockPos) args[0]).immutable();
						counters[0]++;
						if (pos.getY() < GROUND_Y) {
							counters[2]++;
						}
						world.put(pos, (BlockState) args[1]);
						yield Boolean.TRUE;
					}
					case "getBlockState" -> {
						BlockPos pos = ((BlockPos) args[0]).immutable();
						counters[1]++;
						if (pos.getY() < GROUND_Y) {
							counters[3]++;
						}
						readSites.addAll(callSites());
						BlockState known = world.get(pos);
						yield known != null ? known : (pos.getY() < GROUND_Y ? FILL : air);
					}
					case "hasChunk" -> Boolean.TRUE;
					case "getSeed" -> SEED;
					case "getSeaLevel" -> 63;
					case "getMinY" -> -64;
					case "getMaxY" -> 320;
					case "toString" -> "fake WorldGenLevel";
					case "hashCode" -> System.identityHashCode(proxy);
					case "equals" -> proxy == args[0];
					default -> defaultValue(method.getReturnType());
				});
	}

	/**
	 * Every mod method on the stack of this read, as {@code Class.method}. The whole stack rather
	 * than the innermost frame, so that a read reached indirectly (through {@code BlockStates},
	 * say) is still attributed to the placement loop that asked for it.
	 */
	private static List<String> callSites() {
		return StackWalker.getInstance().walk(frames -> frames
				.filter(f -> f.getClassName().startsWith("com.lostbuildings.")
						&& !f.getClassName().equals(EngineWritePassTest.class.getName()))
				.map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1)
						+ "." + f.getMethodName())
				.toList());
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

	// ------------------------------------------------------------------ the synthetic datapack

	private static final String PALETTE = """
			{"palette": [
			  {"char": " ", "block": "minecraft:air"},
			  {"char": "#", "block": "minecraft:stone_bricks"},
			  {"char": "p", "block": "minecraft:glass_pane"},
			  {"char": "w", "block": "minecraft:cobblestone_wall"},
			  {"char": "s", "block": "minecraft:stone_brick_stairs[facing=north]"}
			]}""";

	/** Panes in a row, walls in a column, stairs in a block: all three connectable families. */
	private static final String PART = """
			{"xsize": 8, "zsize": 8, "slices": [
			  ["########","########","########","########","########","########","########","########"],
			  ["########","#pppppp#","#w    w#","#w    w#","#  ss  #","#  ss  #","#      #","########"],
			  ["########","#pppppp#","#w    w#","#w    w#","#  ss  #","#  ss  #","#      #","########"],
			  ["########","#pppppp#","#w    w#","#w    w#","#  ss  #","#  ss  #","#      #","########"],
			  ["########","#pppppp#","#w    w#","#w    w#","#  ss  #","#  ss  #","#      #","########"],
			  ["########","########","########","########","########","########","########","########"]
			]}""";

	private static final String BUILDING = """
			{"filler": "#", "parts": [{"part": "testpart"}]}""";
}
