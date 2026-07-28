package com.lostbuildings.world.structure;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lostbuildings.engine.BuildingPart;
import com.lostbuildings.engine.CompiledPalette;
import com.lostbuildings.engine.Palette;
import com.lostbuildings.engine.Transform;
import com.lostbuildings.engine.codec.BuildingPartRE;
import com.lostbuildings.engine.codec.PaletteRE;
import com.lostbuildings.world.structure.piece.PartPlacer;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
 *
 * <p>The second half of the class is about the other half of laying a tile — resolving its
 * characters. Those tests need real {@link BlockState}s, so they bootstrap Minecraft's registries in
 * {@link #bootstrapMinecraft()}; everything above them is still plain arithmetic.
 */
class StreetTilesTest {

	private static final int N = CityLayout.NORTH;
	private static final int E = CityLayout.EAST;
	private static final int S = CityLayout.SOUTH;
	private static final int W = CityLayout.WEST;

	/**
	 * Registries and block states, once for the whole class.
	 *
	 * <p>{@code Tools.stringToState} — which every palette entry goes through — looks blocks up in
	 * {@code BuiltInRegistries}, so a palette test without this resolves everything to nothing and
	 * would pass for the wrong reason. Bootstrapping is idempotent and takes a couple of seconds.
	 */
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

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

	// ------------------------------------------------------------------ variants (defect 2)

	/** A variant key of zero is the family itself, which is what keeps {@code forMask(mask)} honest. */
	@Test
	void theFirstVariantOfEveryFamilyIsTheFamilyItself() {
		for (String family : StreetTiles.families()) {
			assertEquals(family, StreetTiles.variantsOf(family).get(0),
					family + " must be the first entry of its own variant list");
		}
		for (int mask = 0; mask < 16; mask++) {
			assertEquals(StreetTiles.forMask(mask), StreetTiles.forMask(mask, 0L),
					"variant key 0 must reproduce the plain tile for mask " + mask);
			assertFalse(StreetTiles.variantsOf(StreetTiles.forMask(mask).part()).isEmpty(),
					"mask " + mask + " selects a family with no variant list at all");
		}
	}

	/**
	 * The point of variants: a key changes the tile, the same key never does.
	 *
	 * <p>The rotation must survive the substitution — a variant that came back unturned would point a
	 * road into a wall, which is the failure this whole class exists to catch.
	 */
	@Test
	void everyVariantIsReachableAndTheChoiceIsAFunctionOfTheKey() {
		Set<String> seen = new HashSet<>();
		for (long key = -64; key < 64; key++) {
			StreetTiles.Tile tile = StreetTiles.forMask(W | E, key);
			assertEquals(tile, StreetTiles.forMask(W | E, key), "key " + key + " is not stable");
			assertEquals(0, tile.quarterTurns(), "a variant must keep its family's rotation");
			seen.add(tile.part());
		}
		assertEquals(new HashSet<>(StreetTiles.variantsOf(StreetTiles.STRAIGHT)), seen,
				"some straight variant is unreachable, or one was invented");

		// A north-south straight is the same family turned once; the variants come with it.
		Set<String> turned = new HashSet<>();
		for (long key = 0; key < 32; key++) {
			StreetTiles.Tile tile = StreetTiles.forMask(N | S, key);
			assertEquals(1, tile.quarterTurns());
			turned.add(tile.part());
		}
		assertEquals(seen, turned, "a turned road must draw from the same variants");
	}

	/** A family with a single tile ignores the key entirely rather than falling off the list. */
	@Test
	void aFamilyWithoutVariantsIgnoresTheKey() {
		for (long key = -8; key < 8; key++) {
			assertEquals(StreetTiles.ALL, StreetTiles.forMask(CityLayout.ALL_SIDES, key).part());
			assertEquals(StreetTiles.BEND, StreetTiles.forMask(W | N, key).part());
		}
	}

	/**
	 * The rule that makes variants safe: neighbouring cells pick independently, so every variant has
	 * to draw the same outer ring as the family it stands in for, or the road breaks at the seam.
	 *
	 * <p>Checked on the two structural slices only — the carriageway and the pavement course. What a
	 * variant does above them is decoration, and decoration is allowed to differ across a boundary
	 * (the shipped tiles already differ there among themselves).
	 */
	@Test
	void everyVariantIsARealPartThatSeamsWithItsFamily() {
		for (String family : StreetTiles.families()) {
			JsonObject base = shippedPart(family);
			for (String variant : StreetTiles.variantsOf(family)) {
				JsonObject part = shippedPart(variant);
				assertEquals(16, part.get("xsize").getAsInt(), variant + " is not 16 wide");
				assertEquals(16, part.get("zsize").getAsInt(), variant + " is not 16 deep");
				for (int slice : new int[]{0, 1}) {
					String[] wanted = sliceRows(base, slice);
					String[] got = sliceRows(part, slice);
					assertEquals(wanted[0], got[0], variant + " slice " + slice + ": north edge differs");
					assertEquals(wanted[15], got[15], variant + " slice " + slice + ": south edge differs");
					for (int z = 0; z < 16; z++) {
						assertEquals(wanted[z].charAt(0), got[z].charAt(0),
								variant + " slice " + slice + " row " + z + ": west edge differs");
						assertEquals(wanted[z].charAt(15), got[z].charAt(15),
								variant + " slice " + slice + " row " + z + ": east edge differs");
					}
				}
			}
		}
	}

	/**
	 * The shipped junction tiles paint their crossings with a character no shared palette defines, so
	 * they only appear at all because {@code PartPlacer} now resolves a part's own palette. Reading
	 * the real files is the point: a synthetic part would prove the code and not the data.
	 */
	@Test
	void theShippedJunctionTilesPaintTheirCrossingsFromTheirOwnPalette() {
		for (String name : new String[]{StreetTiles.ALL, StreetTiles.T}) {
			JsonObject json = shippedPart(name);
			assertTrue(json.has("palette"), name + " lost its local palette");
			BuildingPart part = part(json.toString());
			CompiledPalette style = compiled(STYLE_PALETTE);
			assertNull(style.get(',', RandomSource.create(0)),
					"the crossing character must not be a style character, or this proves nothing");
			BlockState paint = PartPlacer.paletteFor(part, style).get(',', RandomSource.create(0));
			assertNotNull(paint, name + ": the crossing paint resolved to nothing");
			assertEquals("minecraft:white_concrete", BuiltInRegistries.BLOCK.getKey(paint.getBlock()).toString());
		}
	}

	/** One shipped part, read off the test classpath. */
	private static JsonObject shippedPart(String name) {
		String path = "/data/lostbuildings/lostcities/parts/" + name + ".json";
		try (InputStream in = StreetTilesTest.class.getResourceAsStream(path)) {
			assertNotNull(in, "no such part: " + path);
			return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
					.getAsJsonObject();
		} catch (IOException e) {
			throw new AssertionError("could not read " + path, e);
		}
	}

	private static String[] sliceRows(JsonObject part, int slice) {
		JsonArray rows = part.getAsJsonArray("slices").get(slice).getAsJsonArray();
		assertEquals(16, rows.size(), "slice " + slice + " is not 16 rows");
		String[] result = new String[16];
		for (int z = 0; z < 16; z++) {
			result[z] = rows.get(z).getAsString();
		}
		return result;
	}

	// ------------------------------------------------------------------ local palettes (defect 1)

	/** A style palette: the sort of thing {@code BuildingEngine.buildPalette} hands to a piece. */
	private static final String STYLE_PALETTE = """
			{"palette": [
			  {"char": "S", "block": "minecraft:smooth_stone_slab[type=double]"},
			  {"char": "#", "block": "minecraft:black_concrete"}
			]}""";

	/**
	 * The bug in one test: a part-local {@code "palette"} used to be dropped on the floor by
	 * {@code PartPlacer}, so a character only it defined resolved to nothing and the block vanished.
	 */
	@Test
	void aPartsLocalPaletteIsResolvedByThePartPlacer() {
		BuildingPart part = part("""
				{"xsize": 1, "zsize": 1, "slices": [["*"]],
				 "palette": {"palette": [
				   {"char": "*", "block": "minecraft:smooth_stone_slab[type=bottom]"}
				 ]}}""");
		CompiledPalette resolved = PartPlacer.paletteFor(part, compiled(STYLE_PALETTE));

		assertNotNull(resolved.get('*', RandomSource.create(0)),
				"the part's own palette character resolved to nothing");
		assertEquals(Blocks.SMOOTH_STONE_SLAB, resolved.get('*', RandomSource.create(0)).getBlock());
	}

	/**
	 * The merge order, which is the half of the fix that a "does it compile" check cannot see:
	 * {@code BuildingEngine.generatePart} layers the part's palette <em>over</em> the style's, so the
	 * part wins for a character both define and the style still supplies everything else.
	 */
	@Test
	void aLocalPaletteOverridesTheStyleAndLeavesTheRestOfItAlone() {
		BuildingPart part = part("""
				{"xsize": 1, "zsize": 1, "slices": [["#"]],
				 "palette": {"palette": [
				   {"char": "#", "block": "minecraft:polished_andesite"}
				 ]}}""");
		CompiledPalette resolved = PartPlacer.paletteFor(part, compiled(STYLE_PALETTE));

		assertEquals(Blocks.POLISHED_ANDESITE, resolved.get('#', RandomSource.create(0)).getBlock(),
				"the part's entry must win over the style's for the same character");
		assertEquals(Blocks.SMOOTH_STONE_SLAB, resolved.get('S', RandomSource.create(0)).getBlock(),
				"a character the part does not mention must still come from the style");
	}

	/** No local palette, no derived palette: the style's own instance is handed straight back. */
	@Test
	void aPartWithoutALocalPaletteUsesTheStylePaletteUnchanged() {
		BuildingPart part = part("""
				{"xsize": 1, "zsize": 1, "slices": [["S"]]}""");
		CompiledPalette style = compiled(STYLE_PALETTE);
		assertSame(style, PartPlacer.paletteFor(part, style));
	}

	/**
	 * End to end: run a part with a local palette through {@link PartPlacer#place} and look at the
	 * blocks that came out.
	 *
	 * <p>The negative half is the point. {@code '?'} is defined by nobody, so it must place nothing —
	 * without it this test would also pass for an implementation that placed some block for every
	 * character. {@code 'M'} carries a {@code mob}, which {@code PartPlacer} skips deliberately
	 * (see its class javadoc), and that is asserted here rather than trusted.
	 */
	@Test
	void placeWritesTheBlocksALocalPaletteDefinesAndOnlyThose() {
		BuildingPart part = part("""
				{"xsize": 4, "zsize": 1, "slices": [["*S?M"]],
				 "palette": {"palette": [
				   {"char": "*", "block": "minecraft:gold_block"},
				   {"char": "M", "block": "minecraft:spawner", "mob": "easymobs"}
				 ]}}""");
		Map<BlockPos, BlockState> written = new HashMap<>();
		BoundingBox box = new BoundingBox(0, 0, 0, 15, 128, 15);
		PartPlacer.place(recordingLevel(written), box, part, 0, 64, 0, Transform.ROTATE_NONE,
				compiled(STYLE_PALETTE), RandomSource.create(1));

		assertEquals(Blocks.GOLD_BLOCK, blockAt(written, 0, 64, 0),
				"the local palette's character did not reach the world");
		assertEquals(Blocks.SMOOTH_STONE_SLAB, blockAt(written, 1, 64, 0),
				"the style palette must keep working alongside the local one");
		assertFalse(written.containsKey(new BlockPos(2, 64, 0)),
				"an undefined character must leave the world alone, not place a block");
		assertFalse(written.containsKey(new BlockPos(3, 64, 0)),
				"a palette entry carrying a mob is skipped by PartPlacer on purpose");
		assertEquals(2, written.size(), "PartPlacer wrote something it was not asked to");
	}

	/** Nothing at all is written outside the box the piece may legally touch. */
	@Test
	void placeStaysInsideTheBoxItIsGiven() {
		BuildingPart part = part("""
				{"xsize": 4, "zsize": 1, "slices": [["****"]],
				 "palette": {"palette": [
				   {"char": "*", "block": "minecraft:gold_block"}
				 ]}}""");
		Map<BlockPos, BlockState> written = new HashMap<>();
		PartPlacer.place(recordingLevel(written), new BoundingBox(0, 0, 0, 1, 128, 15), part,
				0, 64, 0, Transform.ROTATE_NONE, compiled(STYLE_PALETTE), RandomSource.create(1));
		assertEquals(2, written.size(), "the two columns outside the box must not be written");
	}

	private static net.minecraft.world.level.block.Block blockAt(Map<BlockPos, BlockState> written,
	                                                             int x, int y, int z) {
		BlockState state = written.get(new BlockPos(x, y, z));
		assertNotNull(state, "nothing was written at (" + x + "," + y + "," + z + ")");
		return state.getBlock();
	}

	/** A {@link BuildingPart} straight from the JSON a datapack would ship. */
	private static BuildingPart part(String json) {
		BuildingPartRE re = BuildingPartRE.CODEC
				.parse(JsonOps.INSTANCE, JsonParser.parseString(json))
				.getOrThrow()
				.setRegistryName(Identifier.fromNamespaceAndPath("lostbuildings", "test_part"));
		return new BuildingPart(re, Map.of());
	}

	/** A compiled palette straight from the JSON a datapack would ship. */
	private static CompiledPalette compiled(String json) {
		PaletteRE re = PaletteRE.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
		Palette palette = new Palette("test");
		palette.parsePaletteArray(re, Map.of());
		return new CompiledPalette(palette);
	}

	/**
	 * A {@link WorldGenLevel} that only remembers what was set on it.
	 *
	 * <p>{@code WorldGenLevel} is an interface, so a JDK proxy is enough and no Minecraft server has
	 * to exist. {@code PartPlacer} only calls {@code setBlock} for the plain blocks used here —
	 * {@code BlockStates.correct} returns immediately for anything that is not a stair, wall, pane or
	 * bar — so every other method can answer with a type default.
	 */
	private static WorldGenLevel recordingLevel(Map<BlockPos, BlockState> written) {
		return (WorldGenLevel) Proxy.newProxyInstance(
				StreetTilesTest.class.getClassLoader(),
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
