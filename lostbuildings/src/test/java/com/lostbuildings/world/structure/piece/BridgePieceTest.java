package com.lostbuildings.world.structure.piece;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lostbuildings.engine.util.Tools;
import com.lostbuildings.world.feature.StyleSelector;
import com.lostbuildings.world.structure.CityLayout;
import com.lostbuildings.world.structure.StreetDecor;
import net.minecraft.SharedConstants;
import com.lostbuildings.engine.Assets;
import com.lostbuildings.engine.codec.CityStyleRE;
import com.lostbuildings.engine.codec.ObjectSelector;
import com.lostbuildings.engine.codec.SelectorsRE;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That a bridge is a bridge: it stays inside its own cell, it seams with whatever is next to it, and
 * it stands on something.
 *
 * <p><b>Why this class exists.</b> Every one of the failures it pins reached a player's screenshot.
 * The piece used to stamp one fixed 16×10 part, unrotated for an east–west road and quarter-turned
 * for a north–south one, and that is not enough information to place a bridge:
 *
 * <ul>
 *   <li>A bridged <b>crossroads</b> came out as an east–west plate whose parapets ran straight across
 *       the north–south carriageway, while the cells north and south of it came out as north–south
 *       plates that stopped three blocks short. Two decks at right angles, cutting through each other,
 *       with a hole at every seam. {@link #noParapetIsBuiltAcrossAnArmThatCarriesRoad} and
 *       {@link #twoBridgedCellsMeetWithoutAGapOrAParapetBetweenThem} are that bug written down.</li>
 *   <li>Nothing was ever built <b>below</b> the deck, so a span over dry ground was a plate floating
 *       in the air — {@link #everyBridgeStandsOnSomething}.</li>
 *   <li>Nothing checked that the piece wrote only in its own cell —
 *       {@link #aBridgeWritesNothingOutsideItsOwnCell}.</li>
 * </ul>
 *
 * <p>The end-to-end tests drive the real {@code postProcess} through a recording {@link WorldGenLevel}
 * — the JDK-proxy pattern {@code StreetTilesTest} and {@code StreetPieceTrafficTest} use — with a
 * terrain function it can answer {@code getBlockState} from, so the pier descent is exercised for
 * real. No assets are loaded, which is deliberate: it proves the deck, the parapet and the piers
 * survive the "assets not ready yet" path, exactly as {@code StreetPiece}'s base course does.
 */
class BridgePieceTest {

	private static final int GROUND_Y = 64;
	private static final int WEST_EAST = CityLayout.WEST | CityLayout.EAST;
	private static final int NORTH_SOUTH = CityLayout.NORTH | CityLayout.SOUTH;
	private static final int ALL = CityLayout.ALL_SIDES;
	/** An arbitrary but fixed world seed, so every assertion here is reproducible. */
	private static final long SEED = 0x1057C1719ABCDL;

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	// ------------------------------------------------------------------ the placement code

	/** The turn is a turn: the parts run west-east unrotated, so only a north-south road is turned. */
	@Test
	void onlyANorthSouthRoadIsQuarterTurned() {
		for (int mask = 1; mask <= ALL; mask++) {
			boolean alongX = (mask & WEST_EAST) != 0;
			assertEquals(alongX ? 0 : 1, BridgePiece.turnsForMask(mask),
					"mask " + mask + ": the quarter turn is wrong for this axis");
		}
	}

	/** The mask survives the piece's own NBT round trip, which is how a reloaded world gets it back. */
	@Test
	void theMaskSurvivesTheNbtRoundTrip() {
		for (int mask = 1; mask <= ALL; mask++) {
			assertEquals(mask, new BridgePiece(savedTag(BridgePiece.turnsForMask(mask), mask)).neighbourMask(),
					"mask " + mask + " did not survive being saved and loaded");
		}
	}

	/**
	 * A bridge saved before the mask was carried has only a Turns tag. It must come back as the
	 * through road that turn implies — not as a cell with no roads at all, which would parapet all
	 * four sides shut and wall the road off.
	 */
	@Test
	void aLegacyBridgeWithoutAMaskStillDescribesAThroughRoad() {
		assertEquals(WEST_EAST, new BridgePiece(savedTag(0, 0)).neighbourMask(),
				"an old saved bridge with Turns=0 and no Neighbours must come back as a west-east road,"
						+ " not as a cell with no roads at all, which would parapet all four sides shut");
		assertEquals(NORTH_SOUTH, new BridgePiece(savedTag(1, 0)).neighbourMask());
	}

	/**
	 * The NBT a saved bridge actually carries. The bounding box is written by the vanilla
	 * {@code StructurePiece} and is required to read one back, so a hand-built tag has to include it.
	 * A {@code Neighbours} of 0 stands for the tag being absent, which is the pre-mask save format.
	 */
	private static CompoundTag savedTag(int turns, int mask) {
		CompoundTag tag = new CompoundTag();
		tag.store("BB", BoundingBox.CODEC, new BoundingBox(0, GROUND_Y - 20, 0, 15, GROUND_Y + 18, 15));
		tag.putInt("GroundY", GROUND_Y);
		tag.putString("Climate", StyleSelector.Climate.TEMPERATE.name());
		tag.putString("Part", "bridge_open");
		tag.putString("Style", "citystyle_standard");
		tag.putInt("Turns", turns);
		tag.putInt("Neighbours", mask);
		return tag;
	}

	/**
	 * The city style's {@code selectors.bridges} decides the family, and one crossing gets one family.
	 *
	 * <p>Before this the field was parsed and read by nothing, and the family was rolled per cell at
	 * structure-start, so a single river crossing alternated open and covered every sixteen blocks.
	 */
	@Test
	void theCityStyleChoosesTheBridgeFamilyOncePerCrossing() {
		Assets assets = new Assets();
		assets.getCityStyles().put("test_style", cityStyleNaming("bridge_covered"));

		// Every cell of one west-east crossing shares its chunk Z, so all of them must agree.
		for (int cellX = -4; cellX <= 4; cellX++) {
			assertEquals("bridge_covered",
					BridgePiece.Spans.familyFor(assets, "test_style", "bridge_open", WEST_EAST, SEED, cellX, 7),
					"cell " + cellX + " of one crossing picked a different family");
		}
	}

	/** With no assets, or a style naming no bridges, the name handed down by the structure stands. */
	@Test
	void theStructuresChoiceStandsWhenTheStyleNamesNoBridges() {
		assertEquals("bridge_open",
				BridgePiece.Spans.familyFor(null, "test_style", "bridge_open", WEST_EAST, SEED, 0, 0),
				"with no assets loaded the fallback must be used, not an empty name");

		Assets empty = new Assets();
		assertEquals("bridge_open",
				BridgePiece.Spans.familyFor(empty, "missing_style", "bridge_open", WEST_EAST, SEED, 0, 0),
				"an unknown city style must fall back, not blank the bridge");
	}

	private static CityStyleRE cityStyleNaming(String... bridges) {
		List<ObjectSelector> selectors = new ArrayList<>();
		for (String bridge : bridges) {
			selectors.add(new ObjectSelector(1.0F, bridge));
		}
		SelectorsRE s = new SelectorsRE(null, null, null, null, selectors, null, null);
		return new CityStyleRE(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
				Optional.empty(), Optional.empty(), Optional.of(s));
	}

	// ------------------------------------------------------------------ the seam

	/**
	 * A parapet may never stand on an edge that a road continues over. This is the single rule that
	 * makes two bridge cells joinable, and the one the fixed plate broke.
	 */
	@Test
	void noParapetIsBuiltAcrossAnArmThatCarriesRoad() {
		for (int mask = 0; mask <= ALL; mask++) {
			for (int dx = 0; dx < 16; dx++) {
				for (int dz = 0; dz < 16; dz++) {
					if (!BridgePiece.isParapet(dx, dz, mask)) {
						continue;
					}
					assertFalse(StreetDecor.isRoad(dx, dz, mask),
							"mask " + mask + ": parapet at (" + dx + "," + dz + ") stands on the carriageway");
					assertTrue(dx == 0 || dz == 0 || dx == 15 || dz == 15,
							"mask " + mask + ": parapet at (" + dx + "," + dz + ") is not on a cell edge");
				}
			}
			// The bug, stated directly: nothing may stand in the eight-column roadway band of an edge
			// that a road crosses. That band is where the fixed plate used to run its parapet, walling
			// off the arm of the junction it stood on.
			for (int i = 4; i <= 11; i++) {
				assertFalse((mask & CityLayout.NORTH) != 0 && BridgePiece.isParapet(i, 0, mask),
						"mask " + mask + ": parapet across the north arm at x=" + i);
				assertFalse((mask & CityLayout.SOUTH) != 0 && BridgePiece.isParapet(i, 15, mask),
						"mask " + mask + ": parapet across the south arm at x=" + i);
				assertFalse((mask & CityLayout.WEST) != 0 && BridgePiece.isParapet(0, i, mask),
						"mask " + mask + ": parapet across the west arm at z=" + i);
				assertFalse((mask & CityLayout.EAST) != 0 && BridgePiece.isParapet(15, i, mask),
						"mask " + mask + ": parapet across the east arm at z=" + i);
			}
		}
	}

	/** A cell with no through road at all is still fenced: an island of deck is not a diving board. */
	@Test
	void aCellWithNoRoadNeighboursIsFencedOnEveryEdge() {
		long fenced = 0;
		for (int dx = 0; dx < 16; dx++) {
			for (int dz = 0; dz < 16; dz++) {
				if (BridgePiece.isParapet(dx, dz, 0)) {
					fenced++;
				}
			}
		}
		assertEquals(60, fenced,
				"an unconnected cell should be walled all the way round its edge: 4 * 16 columns minus"
						+ " the 4 corners counted twice");
	}

	/**
	 * The real thing: a bridged crossroads and the bridged cell north of it, both generated, must not
	 * leave a hole between them and must not build anything across each other's carriageway.
	 */
	@Test
	void twoBridgedCellsMeetWithoutAGapOrAParapetBetweenThem() {
		Map<BlockPos, BlockState> junction = generate(0, 0, ALL, GROUND_Y - 10);
		Map<BlockPos, BlockState> approach = generate(0, -1, NORTH_SOUTH, GROUND_Y - 10);

		Set<Long> shared = new HashSet<>(columnsOf(junction));
		shared.retainAll(new HashSet<>(columnsOf(approach)));
		assertTrue(shared.isEmpty(),
				shared.size() + " columns were written by both pieces - one cell, one piece is the whole "
						+ "contract of CityPiece, and two pieces sharing a column is the overlap a player "
						+ "reported as \"they cut into each other\"");

		for (int dx = 0; dx < 16; dx++) {
			BlockPos southOfSeam = new BlockPos(dx, GROUND_Y - 1, 0);       // junction cell, z = 0
			BlockPos northOfSeam = new BlockPos(dx, GROUND_Y - 1, -1);      // approach cell, z = -1
			assertNotNull(junction.get(southOfSeam),
					"no deck at (" + dx + ", 0) on the junction cell's north edge - that is the gap "
							+ "the old fixed plate left, because its deck was only ten columns wide");
			assertNotNull(approach.get(northOfSeam),
					"no deck at (" + dx + ", -1) on the approach cell's south edge");
			assertEquals(junction.get(southOfSeam) == null, approach.get(northOfSeam) == null,
					"the two cells disagree about whether column " + dx + " is decked");

			// Nothing standing in the way of traffic crossing the seam.
			if (StreetDecor.isRoad(dx, 0, ALL)) {
				assertNothingAt(junction, dx, GROUND_Y, 0);
				assertNothingAt(approach, dx, GROUND_Y, -1);
			}
		}
	}

	private static void assertNothingAt(Map<BlockPos, BlockState> written, int x, int y, int z) {
		BlockState state = written.get(new BlockPos(x, y, z));
		assertTrue(state == null || state.isAir(),
				"something was built at (" + x + "," + y + "," + z + "), which is carriageway a road "
						+ "crosses the seam on - a parapet here is exactly the bug where one bridge deck "
						+ "walled off the arm of the one next to it");
	}

	// ------------------------------------------------------------------ containment

	/** Nothing a bridge writes may land outside the 16×16 cell the piece owns. */
	@Test
	void aBridgeWritesNothingOutsideItsOwnCell() {
		for (int mask : new int[]{WEST_EAST, NORTH_SOUTH, ALL, CityLayout.NORTH,
				CityLayout.NORTH | CityLayout.EAST, CityLayout.NORTH | CityLayout.EAST | CityLayout.SOUTH, 0}) {
			for (int cell : new int[]{0, 3, -2}) {
				Map<BlockPos, BlockState> written = generate(cell, cell, mask, GROUND_Y - 9);
				int minX = cell << 4;
				int minZ = cell << 4;
				written.keySet().forEach(pos -> assertTrue(
						pos.getX() >= minX && pos.getX() < minX + 16
								&& pos.getZ() >= minZ && pos.getZ() < minZ + 16,
						"mask " + mask + ", cell " + cell + ": wrote at " + pos + ", outside the cell "
								+ "[" + minX + ".." + (minX + 15) + "]"));
				assertFalse(written.isEmpty(), "mask " + mask + ": the piece built nothing at all");
			}
		}
	}

	/** And nothing outside the piece's own bounding box either — the box the structure reserved. */
	@Test
	void aBridgeWritesNothingOutsideItsOwnBoundingBox() {
		BridgePiece piece = piece(0, 0, ALL);
		BoundingBox box = piece.getBoundingBox();
		Map<BlockPos, BlockState> written = run(piece, GROUND_Y - 30);
		written.keySet().forEach(pos -> assertTrue(box.isInside(pos),
				"wrote at " + pos + ", outside the piece's own box " + box
						+ " - a structure piece that writes outside its box is invisible to every"
						+ " overlap check Minecraft does"));
	}

	// ------------------------------------------------------------------ the deck

	/** The deck follows {@link StreetDecor} exactly, which is what makes it join a street cell. */
	@Test
	void theDeckIsLaidOutLikeTheStreetItContinues() {
		int mask = WEST_EAST;
		Map<BlockPos, BlockState> written = generate(0, 0, mask, GROUND_Y - 9);
		int decked = 0;
		int footway = 0;
		for (int dx = 0; dx < 16; dx++) {
			for (int dz = 0; dz < 16; dz++) {
				BlockState deck = written.get(new BlockPos(dx, GROUND_Y - 1, dz));
				BlockState above = written.get(new BlockPos(dx, GROUND_Y, dz));
				if (deck != null && !deck.isAir()) {
					decked++;
				}
				if (StreetDecor.isRoad(dx, dz, mask)) {
					assertTrue(above == null || above.isAir() || above.is(Blocks.COBWEB),
							"(" + dx + "," + dz + ") is carriageway, but something solid was built on it");
				} else {
					assertNotNull(above, "(" + dx + "," + dz + ") is pavement but has no footway course");
					footway++;
				}
			}
		}
		assertEquals(128, footway, "a west-east cell has eight pavement rows of sixteen");
		assertTrue(decked > 16 * 16 - 20,
				"only " + decked + " of 256 columns were decked; a bridge deck is the whole cell, or "
						+ "its neighbour has nothing to butt against");
	}

	/**
	 * The gutter channel lands exactly where the shipped street tile puts one.
	 *
	 * <p>Checked against {@code parts/street_straight.json} itself rather than against a copy of its
	 * layout, because the whole point of deriving the deck from {@link StreetDecor} is that the bridge
	 * and the road cannot drift apart. Half a block of drift here is a lip across two of the sixteen
	 * columns at every abutment.
	 */
	@Test
	void theGutterChannelMatchesTheShippedStreetTile() {
		JsonArray slice0 = json(partFile("street_straight")).getAsJsonArray("slices")
				.get(0).getAsJsonArray();
		for (int dz = 0; dz < 16; dz++) {
			String row = slice0.get(dz).getAsString();
			for (int dx = 0; dx < 16; dx++) {
				char c = row.charAt(dx);
				// '_' is the half-slab channel; ':' is the drain grate the tile drops into it
				boolean tileHasGutter = c == '_' || c == ':';
				assertEquals(tileHasGutter, BridgePiece.isGutter(dx, dz, WEST_EAST),
						"street_straight has '" + c + "' at (" + dx + "," + dz + ") but the bridge deck "
								+ (BridgePiece.isGutter(dx, dz, WEST_EAST) ? "does" : "does not")
								+ " sink a gutter there");
			}
		}
	}

	/** A gutter is never cut round the ends of a span, where the road carries on into the next cell. */
	@Test
	void theGutterDoesNotRunRoundTheEndsOfASpan() {
		for (int dz = 4; dz <= 11; dz++) {
			assertEquals(dz == 4 || dz == 11, BridgePiece.isGutter(0, dz, WEST_EAST),
					"the west edge of a west-east span at z=" + dz + ": the carriageway continues into "
							+ "the next cell there, so only the two channels beside the kerbs are gutter");
			assertEquals(dz == 4 || dz == 11, BridgePiece.isGutter(15, dz, WEST_EAST));
		}
	}

	/** Holes in the deck are real, and never on the outer ring where they would read as a bad seam. */
	@Test
	void theDeckRotsInTheMiddleAndNotAtTheEdges() {
		int holes = 0;
		for (int cell = 0; cell < 24; cell++) {
			Map<BlockPos, BlockState> written = generate(cell, 0, WEST_EAST, GROUND_Y - 9);
			int minX = cell << 4;
			for (int dx = 0; dx < 16; dx++) {
				for (int dz = 0; dz < 16; dz++) {
					BlockState deck = written.get(new BlockPos(minX + dx, GROUND_Y - 1, dz));
					if (deck != null && !deck.isAir()) {
						continue;
					}
					holes++;
					assertTrue(dx >= 2 && dz >= 2 && dx <= 13 && dz <= 13,
							"a missing deck plate at (" + dx + "," + dz + ") is on the outer ring, where "
									+ "it looks like a mismatch with the next cell rather than damage");
				}
			}
		}
		assertTrue(holes > 0, "no deck plate was ever missing across 24 cells - the decay is dead code");
	}

	// ------------------------------------------------------------------ the substructure

	/** A bridge holds itself up: the pier reaches the ground, wherever the ground is. */
	@Test
	void everyBridgeStandsOnSomething() {
		Map<BlockPos, BlockState> written = generate(0, 0, WEST_EAST, GROUND_Y - 9);
		int lowest = written.keySet().stream().mapToInt(BlockPos::getY).min().orElse(GROUND_Y);
		assertTrue(lowest <= GROUND_Y - 9,
				"the lowest block the piece wrote is at y=" + lowest + ", but the terrain under it is at "
						+ (GROUND_Y - 9) + " - the deck is floating, which is what the screenshot showed");
	}

	/** A deeper gap gets a taller pier; the descent is driven by the world, not by a constant. */
	@Test
	void aPierIsAsLongAsTheGapUnderIt() {
		int shallow = depth(generate(0, 0, WEST_EAST, GROUND_Y - 5));
		int deep = depth(generate(0, 0, WEST_EAST, GROUND_Y - 16));
		assertTrue(deep > shallow + 6,
				"a pier over a 16-block gap (" + deep + ") is barely longer than one over a 5-block gap ("
						+ shallow + ") - the descent is not reading the terrain");
	}

	/** A pier drives on through water rather than stopping at the surface of a river. */
	@Test
	void aPierIsNotStoppedByWater() {
		Map<BlockPos, BlockState> dry = generate(0, 0, WEST_EAST, GROUND_Y - 14, GROUND_Y - 14);
		Map<BlockPos, BlockState> flooded = generate(0, 0, WEST_EAST, GROUND_Y - 14, GROUND_Y - 3);
		assertEquals(depth(dry), depth(flooded),
				"a pier stopped short in water: it must found on the river bed, not float on the surface");
	}

	/** A junction puts its pier under the junction box, not two bents fighting over the same columns. */
	@Test
	void aJunctionGetsASinglePierUnderItsCrossing() {
		Map<BlockPos, BlockState> written = generate(0, 0, ALL, GROUND_Y - 12);
		Set<Long> legs = new HashSet<>();
		written.forEach((pos, state) -> {
			if (pos.getY() < GROUND_Y - 4) {
				legs.add((long) pos.getX() << 32 | (pos.getZ() & 0xFFFFFFFFL));
			}
		});
		assertFalse(legs.isEmpty(), "a bridged crossroads grew no pier at all");
		written.forEach((pos, state) -> {
			if (pos.getY() >= GROUND_Y - 4) {
				return;
			}
			assertTrue(pos.getX() >= 6 && pos.getX() <= 9 && pos.getZ() >= 6 && pos.getZ() <= 9,
					"a junction's pier leg at " + pos + " is outside the junction box, so it would come "
							+ "down in the middle of one of the arms");
		});
	}

	// ------------------------------------------------------------------ span selection

	/** A crossing is one bridge: every cell of it agrees on the structural style. */
	@Test
	void aCrossingHasOneStyleAlongItsWholeLength() {
		long seed = 987654321L;
		BridgePiece.Spans.Style style = BridgePiece.Spans.styleFor(seed, 0, 4);
		for (int along = -40; along <= 40; along++) {
			assertEquals(style, BridgePiece.Spans.styleFor(seed, 0, 4),
					"the style of a crossing must not depend on where along it you ask");
			BridgePiece.Spans.Span span = BridgePiece.Spans.forCell(WEST_EAST, seed, along, 4, "bridge_open");
			assertEquals(style, span.style(),
					"cell " + along + " of the crossing at z=4 disagrees about the style");
			assertEquals(0, span.quarterTurns(), "a west-east span is never turned");
		}
	}

	/** Two different crossings are allowed — and expected — to be different bridges. */
	@Test
	void differentCrossingsGetDifferentStyles() {
		long seed = 24680L;
		Set<BridgePiece.Spans.Style> seen = new HashSet<>();
		for (int across = -60; across <= 60; across++) {
			seen.add(BridgePiece.Spans.styleFor(seed, 0, across));
		}
		assertEquals(BridgePiece.Spans.Style.values().length, seen.size(),
				"every crossing in the world came out as the same kind of bridge");
	}

	/** A suspension crossing puts a pylon every third cell, and a deck span in between. */
	@Test
	void aSuspensionCrossingTowersEveryThirdCell() {
		long seed = findSeedFor(BridgePiece.Spans.Style.SUSPENSION);
		int towers = 0;
		for (int along = 0; along < 12; along++) {
			BridgePiece.Spans.Span span = BridgePiece.Spans.forCell(WEST_EAST, seed, along, 0, "bridge_open");
			if (span.tower()) {
				towers++;
				assertEquals(0, Math.floorMod(along, 3), "a pylon at cell " + along + " is off the grid");
				assertTrue(span.part().endsWith("_tower"), span.part());
			}
		}
		assertEquals(4, towers, "twelve cells of a suspension crossing should carry four pylons");
	}

	/** Anything that is not a straight through-road is a junction, with nothing built over the deck. */
	@Test
	void everyNonThroughMaskIsAJunction() {
		for (int mask = 0; mask <= ALL; mask++) {
			BridgePiece.Spans.Span span = BridgePiece.Spans.forCell(mask, 1L, 0, 0, "bridge_open");
			boolean through = (mask & WEST_EAST) == WEST_EAST ^ (mask & NORTH_SOUTH) == NORTH_SOUTH;
			if (!through) {
				assertEquals("bridge_open_junction", span.part(),
						"mask " + mask + " is a junction, a bend or a stub; a truss over it would stand "
								+ "across one of its arms");
				assertEquals(0, span.quarterTurns(),
						"a junction part is four-fold symmetric and must never be turned");
			} else {
				assertNotEquals("bridge_open_junction", span.part(), "mask " + mask);
			}
		}
	}

	/** Every part name the selector can produce actually ships. */
	@Test
	void everyPartTheSelectorCanNameExists() {
		Set<String> named = new TreeSet<>();
		for (String family : List.of("bridge_open", "bridge_covered")) {
			for (long seed = 0; seed < 40; seed++) {
				for (int mask = 0; mask <= ALL; mask++) {
					for (int along = 0; along < 6; along++) {
						named.add(BridgePiece.Spans.forCell(mask, seed, along, (int) seed, family).part());
					}
				}
			}
		}
		assertTrue(named.size() >= 12, "the selector only ever names " + named + " - the variants are dead");
		for (String part : named) {
			assertTrue(Files.isRegularFile(partFile(part)),
					"the span selector can ask for '" + part + "' but parts/" + part + ".json does not ship,"
							+ " which silently falls back to the plain family part");
		}
	}

	// ------------------------------------------------------------------ the main cable

	/** The catenary is continuous: it is a function of the world coordinate, not of the cell. */
	@Test
	void theMainCableJoinsUpAcrossCellBoundaries() {
		int previous = BridgePiece.cableHeight(-100);
		for (int along = -99; along <= 200; along++) {
			int here = BridgePiece.cableHeight(along);
			assertTrue(Math.abs(here - previous) <= 1,
					"the cable jumps " + (here - previous) + " blocks at x=" + along
							+ " - a cable drawn per cell rather than per world coordinate would step at "
							+ "every multiple of 16");
			previous = here;
		}
	}

	/** It is anchored at the pylons and sags between them, rather than being a flat line. */
	@Test
	void theMainCableIsAnchoredAtEveryPylonAndSagsBetween() {
		for (int k = -2; k <= 2; k++) {
			int anchor = k * 48 + 7;
			assertEquals(15, BridgePiece.cableHeight(anchor),
					"the cable does not reach the top of the pylon at x=" + anchor);
			assertEquals(15 - 9, BridgePiece.cableHeight(anchor + 24),
					"the cable does not sag to its midspan height between pylons");
		}
	}

	// ------------------------------------------------------------------ the datapack

	/**
	 * Every block id in every bridge part's local palette resolves to a real block.
	 *
	 * <p>{@code Tools.stringToState} logs a warning and returns air for anything it cannot parse, so a
	 * renamed block is invisible: the bridge just comes out with holes in it. This version of the game
	 * renamed {@code minecraft:chain} to {@code minecraft:iron_chain}, which is exactly the kind of
	 * thing that would have shipped unnoticed.
	 */
	@Test
	void everyBlockNamedByABridgePaletteStillExists() {
		int checked = 0;
		for (Path file : bridgeParts()) {
			JsonObject part = json(file);
			if (!part.has("palette")) {
				continue;
			}
			for (JsonElement entry : part.getAsJsonObject("palette").getAsJsonArray("palette")) {
				JsonObject e = entry.getAsJsonObject();
				for (String id : blockIds(e)) {
					BlockState state = Tools.stringToState(id);
					assertFalse(state.isAir() && !id.equals("minecraft:air"),
							file.getFileName() + ": '" + id + "' does not parse to a block - "
									+ "Tools.stringToState falls back to air, so this is a silent hole");
					checked++;
				}
			}
		}
		assertTrue(checked > 40, "only " + checked + " block ids were checked; the walk is broken");
	}

	/** No bridge part may be taller than the box the piece reserves for it. */
	@Test
	void noBridgePartOverflowsTheVerticalBudget() {
		for (Path file : bridgeParts()) {
			int slices = json(file).getAsJsonArray("slices").size();
			assertTrue(slices <= BridgePiece.PART_SLICE_BUDGET,
					file.getFileName() + " has " + slices + " slices but only "
							+ BridgePiece.PART_SLICE_BUDGET + " fit between the part origin and the top "
							+ "of the piece's box; the rest is computed and silently discarded");
		}
	}

	/**
	 * A junction part is placed unrotated whatever the junction looks like, so it has to look the same
	 * from all four sides.
	 */
	@Test
	void everyJunctionPartIsFourFoldSymmetric() {
		for (Path file : bridgeParts()) {
			if (!file.getFileName().toString().contains("junction")) {
				continue;
			}
			JsonArray slices = json(file).getAsJsonArray("slices");
			for (int s = 0; s < slices.size(); s++) {
				JsonArray rows = slices.get(s).getAsJsonArray();
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						char here = rows.get(z).getAsString().charAt(x);
						// a clockwise quarter turn takes (x, z) to (15 - z, x)
						char turned = rows.get(x).getAsString().charAt(15 - z);
						assertEquals(here, turned,
								file.getFileName() + " slice " + s + ": (" + x + "," + z + ")='" + here
										+ "' but its quarter turn (" + (15 - z) + "," + x + ")='" + turned
										+ "'. A junction is stamped unrotated, so an asymmetric one has "
										+ "its gantry across only one pair of arms.");
					}
				}
			}
		}
	}

	/** No part may build anything on the carriageway at head height along a through span. */
	@Test
	void noThroughSpanPartBlocksTheCarriageway() {
		for (Path file : bridgeParts()) {
			String name = file.getFileName().toString();
			if (name.contains("junction") || name.contains("ruin")) {
				continue;   // a wreck is allowed to be, and is meant to be, impassable
			}
			JsonArray slices = json(file).getAsJsonArray("slices");
			for (int s = 0; s <= 2 && s < slices.size(); s++) {
				JsonArray rows = slices.get(s).getAsJsonArray();
				for (int z = 4; z <= 11; z++) {
					String row = rows.get(z).getAsString();
					assertEquals("                ", row,
							name + " slice " + s + " row " + z + " is carriageway at deck level and must be"
									+ " left to BridgePiece: \"" + row + "\"");
				}
			}
		}
	}

	// ------------------------------------------------------------------ harness

	private static BridgePiece piece(int cellX, int cellZ, int mask) {
		return new BridgePiece(cellX, cellZ, GROUND_Y, "bridge_open", "citystyle_standard",
				BridgePiece.turnsForMask(mask), mask, StyleSelector.Climate.TEMPERATE);
	}

	private static Map<BlockPos, BlockState> generate(int cellX, int cellZ, int mask, int terrainTop) {
		return generate(cellX, cellZ, mask, terrainTop, Integer.MIN_VALUE);
	}

	private static Map<BlockPos, BlockState> generate(int cellX, int cellZ, int mask, int terrainTop,
	                                                  int waterTop) {
		return run(piece(cellX, cellZ, mask), terrainTop, waterTop);
	}

	private static Map<BlockPos, BlockState> run(BridgePiece piece, int terrainTop) {
		return run(piece, terrainTop, Integer.MIN_VALUE);
	}

	/**
	 * Drive the real {@code postProcess}. The writable box is deliberately far wider than the cell, so
	 * a write that escaped the cell is <em>recorded</em> rather than clipped away and missed.
	 */
	private static Map<BlockPos, BlockState> run(BridgePiece piece, int terrainTop, int waterTop) {
		Map<BlockPos, BlockState> written = new HashMap<>();
		// Centred on the cell, and 64 blocks wider than it on every side: a write that escaped the
		// cell must be *recorded* rather than clipped away by the box and never seen.
		BoundingBox box = piece.getBoundingBox();
		BoundingBox everywhere = new BoundingBox(
				box.minX() - 64, GROUND_Y - 128, box.minZ() - 64,
				box.maxX() + 64, GROUND_Y + 128, box.maxZ() + 64);
		piece.postProcess(recordingLevel(written, terrainTop, waterTop), null, null, null,
				everywhere, null, BlockPos.ZERO);
		return written;
	}

	private static List<Long> columnsOf(Map<BlockPos, BlockState> written) {
		List<Long> out = new ArrayList<>();
		written.keySet().forEach(pos -> out.add((long) pos.getX() << 32 | (pos.getZ() & 0xFFFFFFFFL)));
		return out;
	}

	private static int depth(Map<BlockPos, BlockState> written) {
		return GROUND_Y - written.keySet().stream().mapToInt(BlockPos::getY).min().orElse(GROUND_Y);
	}

	private static long findSeedFor(BridgePiece.Spans.Style style) {
		for (long seed = 0; seed < 10_000; seed++) {
			if (BridgePiece.Spans.styleFor(seed, 0, 0) == style) {
				return seed;
			}
		}
		throw new AssertionError("no seed produces a " + style + " crossing");
	}

	/**
	 * A {@link WorldGenLevel} that remembers what was written and answers reads from a flat terrain
	 * function: stone at or below {@code terrainTop}, water above it up to {@code waterTop}, air over
	 * that. The reads matter — the pier descent is driven by them.
	 */
	private static WorldGenLevel recordingLevel(Map<BlockPos, BlockState> written, int terrainTop,
	                                            int waterTop) {
		BlockState stone = Blocks.STONE.defaultBlockState();
		BlockState water = Blocks.WATER.defaultBlockState();
		BlockState air = Blocks.AIR.defaultBlockState();
		return (WorldGenLevel) Proxy.newProxyInstance(
				BridgePieceTest.class.getClassLoader(),
				new Class<?>[]{WorldGenLevel.class},
				(proxy, method, args) -> {
					String name = method.getName();
					if ("setBlock".equals(name) && args != null && args.length >= 3) {
						written.put(((BlockPos) args[0]).immutable(), (BlockState) args[1]);
						return Boolean.TRUE;
					}
					if ("getBlockState".equals(name) && args != null && args.length == 1) {
						int y = ((BlockPos) args[0]).getY();
						BlockState existing = written.get(((BlockPos) args[0]).immutable());
						if (existing != null) {
							return existing;
						}
						if (y <= terrainTop) {
							return stone;
						}
						return y <= waterTop ? water : air;
					}
					return switch (name) {
						case "getSeed" -> 4242L;
						case "getMinY" -> -64;
						case "getMaxY" -> 320;
						case "getHeight" -> terrainTop + 1;
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

	// ------------------------------------------------------------------ datapack access

	private static final Path PARTS = locateParts();

	private static Path locateParts() {
		Path here = Path.of("").toAbsolutePath();
		for (Path candidate = here; candidate != null; candidate = candidate.getParent()) {
			Path parts = candidate.resolve("src/main/resources/data/lostbuildings/lostcities/parts");
			if (Files.isDirectory(parts)) {
				return parts;
			}
			Path nested = candidate.resolve("lostbuildings/src/main/resources/data/lostbuildings/lostcities/parts");
			if (Files.isDirectory(nested)) {
				return nested;
			}
		}
		throw new AssertionError("cannot find the shipped parts directory from " + here);
	}

	private static Path partFile(String name) {
		return PARTS.resolve(name + ".json");
	}

	private static List<Path> bridgeParts() {
		try (Stream<Path> files = Files.list(PARTS)) {
			List<Path> out = files.filter(p -> p.getFileName().toString().startsWith("bridge_"))
					.sorted().toList();
			assertFalse(out.isEmpty(), "no bridge parts found under " + PARTS);
			return out;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static JsonObject json(Path file) {
		try {
			return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Every block string an entry can resolve to: a plain {@code block} or a weighted {@code blocks}. */
	private static List<String> blockIds(JsonObject entry) {
		List<String> out = new ArrayList<>();
		if (entry.has("block")) {
			out.add(entry.get("block").getAsString());
		}
		if (entry.has("damaged")) {
			out.add(entry.get("damaged").getAsString());
		}
		if (entry.has("blocks")) {
			for (JsonElement block : entry.getAsJsonArray("blocks")) {
				out.add(block.getAsJsonObject().get("block").getAsString());
			}
		}
		return out;
	}
}
