package com.lostbuildings.world.structure;

import com.lostbuildings.LostBuildings;
import com.lostbuildings.engine.Assets;
import com.lostbuildings.engine.BuildingEngine;
import com.lostbuildings.engine.BuildingPart;
import com.lostbuildings.engine.CompiledPalette;
import com.lostbuildings.engine.Palette;
import com.lostbuildings.engine.Transform;
import com.lostbuildings.engine.codec.BuildingPartRE;
import com.lostbuildings.world.feature.StyleSelector;
import com.lostbuildings.world.structure.piece.AirportPiece;
import com.lostbuildings.world.structure.piece.PartPlacer;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The city airfield: where {@link Airport} puts it, and what {@link AirportPiece} builds there.
 *
 * <p>The first half is plain arithmetic over a {@link CityLayout.Plan} — determinism, one airport per
 * city, on the edge, laid out in a straight line with the terminal in the middle and facing the city.
 * None of it touches Minecraft, which is the whole point of {@code Airport} being a separate class.
 *
 * <p>The second half drives {@code AirportPiece.postProcess} through a recording
 * {@link WorldGenLevel} — the same JDK-proxy trick {@code StreetTilesTest} and
 * {@code StreetPieceTrafficTest} use — against the <em>shipped</em> {@code airport_*} parts read off
 * the test classpath. That is what proves the piece really lays a surface, really calls
 * {@code PartPlacer}, and really paints the runway where the layout said the runway would be. Each of
 * those is one statement, and one statement is exactly what gets lost in a refactor with every other
 * test still green.
 */
class AirportTest {

	private static final int GROUND_Y = 64;
	private static final long SEED = 0x1057C1719ABCDL;

	/** A representative city: the shipped {@code city_size} of 9, so the outer ring is 4 cells out. */
	private static final CityLayout.Settings CITY =
			new CityLayout.Settings(9, 0.9D, 1, 6, 4, 16, 0.15D, 2, 0.2D, 1);

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@AfterEach
	void clearAssets() {
		LostBuildings.ASSETS = null;
		LostBuildings.ENGINE = null;
	}

	// ------------------------------------------------------------------ where it goes

	/**
	 * One airfield per city big enough to warrant one, and it is exactly {@link Airport#LENGTH}
	 * distinct cells.
	 *
	 * <p>Small settlements are skipped rather than asserted over — see
	 * {@link #onlyCitiesWithMoreThanFifteenLotsGetAnAirfield}. The count is asserted at the end so
	 * that a threshold which accidentally rejected everything would fail here rather than pass
	 * vacuously.
	 */
	@Test
	void everyCityGetsExactlyOneAirfieldOfThreeCells() {
		int withAirfield = 0;
		for (int city = 0; city < 200; city++) {
			Airport airport = airportOf(city);
			if (!airport.exists()) {
				continue;
			}
			withAirfield++;
			assertEquals(Airport.LENGTH, airport.segments().size(),
					"city " + city + ": an airfield is " + Airport.LENGTH + " cells");
			Set<String> cells = new HashSet<>();
			for (Airport.Segment segment : airport.segments()) {
				assertTrue(cells.add(segment.chunkX() + "/" + segment.chunkZ()),
						"city " + city + ": two segments landed on the same cell");
			}
		}
		assertTrue(withAirfield > 150,
				"only " + withAirfield + " of 200 cities got an airfield; the size threshold is "
						+ "rejecting ordinary cities, not just small towns");
	}

	/**
	 * Every segment sits on the outermost ring of the city — the definition of "outskirts" this
	 * design uses. Nothing may be one ring in, and nothing may be one ring out either: a cell beyond
	 * the plan is outside the terrain the site check vetted, and at the shipped
	 * {@code CITY_SEPARATION} two neighbouring cities could reach the same one.
	 */
	@Test
	void theAirfieldIsAlwaysOnTheOutermostRingOfTheCity() {
		for (int city = 0; city < 200; city++) {
			CityLayout.Plan plan = planOf(city);
			int edge = Airport.outerRing(plan);
			for (Airport.Segment segment : Airport.forPlan(plan, SEED).segments()) {
				int ring = Math.max(Math.abs(segment.chunkX() - plan.originChunkX()),
						Math.abs(segment.chunkZ() - plan.originChunkZ()));
				assertEquals(edge, ring, "city " + city + ": segment at ("
						+ segment.chunkX() + "," + segment.chunkZ() + ") is on ring " + ring
						+ ", but the city's edge is ring " + edge);
			}
		}
	}

	/** A runway is straight: three cells in a row along one axis, adjacent, in order. */
	@Test
	void theSegmentsAreThreeAdjacentCellsInAStraightLine() {
		for (int city = 0; city < 200; city++) {
			Airport airport = airportOf(city);
			if (!airport.exists()) {
				continue;   // too small for an airfield
			}
			List<Airport.Segment> segments = airport.segments();
			boolean alongX = airport.side().runwayAlongX();
			for (int i = 1; i < segments.size(); i++) {
				Airport.Segment previous = segments.get(i - 1);
				Airport.Segment current = segments.get(i);
				if (alongX) {
					assertEquals(previous.chunkZ(), current.chunkZ(),
							"city " + city + ": an along-X runway must keep one chunk Z");
					assertEquals(previous.chunkX() + 1, current.chunkX(),
							"city " + city + ": the segments are not consecutive along X");
				} else {
					assertEquals(previous.chunkX(), current.chunkX(),
							"city " + city + ": an along-Z runway must keep one chunk X");
					assertEquals(previous.chunkZ() + 1, current.chunkZ(),
							"city " + city + ": the segments are not consecutive along Z");
				}
			}
		}
	}

	/**
	 * The runway runs <em>parallel</em> to the city edge it is on, never out of the city into the
	 * wilderness: every segment keeps the coordinate that names the edge, and that coordinate is the
	 * edge's own.
	 */
	@Test
	void theRunwayRunsParallelToTheEdgeItIsOn() {
		for (int city = 0; city < 200; city++) {
			CityLayout.Plan plan = planOf(city);
			Airport airport = Airport.forPlan(plan, SEED);
			if (!airport.exists()) {
				continue;   // too small for an airfield
			}
			int edge = Airport.outerRing(plan);
			int expected = switch (airport.side()) {
				case NORTH -> plan.originChunkZ() - edge;
				case SOUTH -> plan.originChunkZ() + edge;
				case WEST -> plan.originChunkX() - edge;
				case EAST -> plan.originChunkX() + edge;
			};
			for (Airport.Segment segment : airport.segments()) {
				int fixed = airport.side().runwayAlongX() ? segment.chunkZ() : segment.chunkX();
				assertEquals(expected, fixed, "city " + city + ": a " + airport.side()
						+ " runway must lie along that edge, not run away from it");
			}
		}
	}

	/** The terminal is in the middle, so the runway has a threshold at each end. */
	@Test
	void theTerminalIsTheMiddleSegmentAndTheEndsAreThresholds() {
		for (int city = 0; city < 50; city++) {
			List<Airport.Segment> segments = airportOf(city).segments();
			if (segments.isEmpty()) {
				continue;   // too small for an airfield
			}
			assertEquals(Airport.PART_THRESHOLD_START, segments.get(0).partName());
			assertEquals(Airport.PART_TERMINAL, segments.get(1).partName());
			assertEquals(Airport.PART_THRESHOLD_END, segments.get(2).partName());
		}
	}

	/**
	 * The terminal ends up on the side of the runway that faces the city.
	 *
	 * <p>The parts are drawn once, with the terminal on the part's low-z rows, so this is entirely a
	 * question of which quarter turn each edge is given. Applying the real {@link Transform} to a
	 * terminal column and asking which half of the cell it lands in is the cheapest way to pin that
	 * choice; get it wrong and the terminal's blank back wall looks out over the wilderness while its
	 * glazed front faces the city across the runway.
	 */
	@Test
	void theTerminalFacesTheCityOnEveryEdge() {
		Set<Airport.Side> seen = new HashSet<>();
		for (int city = 0; city < 200; city++) {
			Airport airport = airportOf(city);
			if (!airport.exists()) {
				continue;   // too small for an airfield
			}
			Airport.Side side = airport.side();
			seen.add(side);
			Transform transform = Transform.values()[side.quarterTurns()];
			// (8, 2) is inside the terminal footprint in the unrotated part.
			int dx = transform.rotateX(8, 2, 16, 16);
			int dz = transform.rotateZ(8, 2, 16, 16);
			int towardsCity = switch (side) {
				case NORTH -> dz;           // city lies at larger Z
				case SOUTH -> 15 - dz;      // city lies at smaller Z
				case WEST -> dx;            // city lies at larger X
				case EAST -> 15 - dx;       // city lies at smaller X
			};
			assertTrue(towardsCity >= 8, "on the " + side + " edge the terminal landed "
					+ towardsCity + " blocks from the city-facing edge of its cell, i.e. on the far "
					+ "side of the runway");
		}
		assertEquals(4, seen.size(), "200 cities never used all four edges - the side roll is stuck");
	}

	// ------------------------------------------------------------------ determinism

	/** The same city always rebuilds its airfield in the same place. */
	@Test
	void theSameCityAlwaysGetsTheSameAirfield() {
		for (int city = 0; city < 50; city++) {
			// Deliberately a freshly planned city each time rather than the same Plan object twice:
			// the guarantee that matters is "regenerating the world reproduces it", not "the method
			// is referentially transparent".
			assertEquals(airportOf(city).segments(), airportOf(city).segments(),
					"city " + city + ": the airfield moved between two identical generations");
		}
	}

	/** ...and a different seed moves it, or the "deterministic" test above proves nothing. */
	@Test
	void adifferentSeedMovesTheAirfield() {
		CityLayout.Plan plan = planOf(0);
		boolean moved = false;
		for (long seed = 1; seed <= 32 && !moved; seed++) {
			moved = !Airport.forPlan(plan, seed).segments().equals(Airport.forPlan(plan, SEED).segments());
		}
		assertTrue(moved, "32 different seeds all put the airfield on the same cells - "
				+ "the placement is not seeded at all");
	}

	/** Different cities put their airfields on different edges. */
	@Test
	void differentCitiesUseDifferentEdges() {
		Set<Airport.Side> sides = new HashSet<>();
		Set<Integer> offsets = new HashSet<>();
		for (int city = 0; city < 200; city++) {
			Airport airport = airportOf(city);
			if (!airport.exists()) {
				continue;   // too small for an airfield
			}
			sides.add(airport.side());
			CityLayout.Plan plan = planOf(city);
			Airport.Segment middle = airport.segments().get(1);
			offsets.add(airport.side().runwayAlongX()
					? middle.chunkX() - plan.originChunkX()
					: middle.chunkZ() - plan.originChunkZ());
		}
		assertEquals(4, sides.size(), "the airfield always lands on the same edge");
		assertTrue(offsets.size() > 1, "the airfield always lands at the same point along the edge");
	}

	// ------------------------------------------------------------------ what it takes over

	/** The airfield is on the edge, so it can never eat the city's centre cell. */
	@Test
	void theAirfieldNeverClaimsTheCityCentre() {
		for (int city = 0; city < 200; city++) {
			CityLayout.Plan plan = planOf(city);
			assertFalse(Airport.forPlan(plan, SEED).claims(plan.originChunkX(), plan.originChunkZ()),
					"city " + city + ": the airfield claimed the centre cell");
		}
	}

	/** {@code claims} answers for exactly the segments and for nothing else. */
	@Test
	void claimsAnswersForTheSegmentsAndOnlyForThem() {
		CityLayout.Plan plan = planOf(0);
		Airport airport = Airport.forPlan(plan, SEED);
		for (Airport.Segment segment : airport.segments()) {
			assertTrue(airport.claims(segment.chunkX(), segment.chunkZ()));
		}
		int claimed = 0;
		for (CityLayout.Cell cell : plan.cells()) {
			if (airport.claims(cell)) {
				claimed++;
			}
		}
		assertTrue(claimed <= Airport.LENGTH,
				"claims() matched " + claimed + " city cells, more than the airfield has");
		// A cell far outside the city must never be claimed, or claims() is answering true blindly.
		assertFalse(airport.claims(plan.originChunkX() + 1000, plan.originChunkZ() + 1000));
	}

	/**
	 * Only a city big enough to warrant one gets an airfield. A runway takes three cells of the
	 * outer ring, which on a small town is most of one side of it.
	 */
	@Test
	void onlyCitiesWithMoreThanFifteenLotsGetAnAirfield() {
		// city_size 5 — the size the charred towns ship at. Too small.
		CityLayout.Plan town = CityLayout.plan(SEED, 0, 0,
				new CityLayout.Settings(5, 0.9D, 1, 6, 4, 16, 0.15D, 2, 0.2D, 1));
		int townLots = Airport.buildingLots(town);
		assertTrue(townLots <= 15, "expected a small town, got " + townLots + " lots");
		assertTrue(Airport.outerRing(town) >= 1, "the town does have an edge; size is the only reason to refuse");
		assertFalse(Airport.forPlan(town, SEED).exists(),
				"a " + townLots + "-lot town should not get an airfield");

		// city_size 9 — the shipped default. Big enough.
		CityLayout.Plan city = CityLayout.plan(SEED, 0, 0, CITY);
		int cityLots = Airport.buildingLots(city);
		assertTrue(cityLots > 15, "expected a full city, got only " + cityLots + " lots");
		assertTrue(Airport.forPlan(city, SEED).exists(),
				"a " + cityLots + "-lot city should get an airfield");
	}

	/** The count is of built-on lots, not of every cell — streets and parks are not the settlement. */
	@Test
	void onlyBuiltOnLotsCountTowardsTheThreshold() {
		CityLayout.Plan city = CityLayout.plan(SEED, 0, 0, CITY);
		long built = city.cells().stream()
				.filter(c -> c.role() == CityLayout.Role.BUILDING || c.role() == CityLayout.Role.MULTI_BUILDING)
				.count();

		assertEquals(built, Airport.buildingLots(city));
		assertTrue(Airport.buildingLots(city) < city.cells().size(),
				"every cell counted, so streets and parks are being mistaken for buildings");
	}

	/** A city with no edge to build on gets no airfield instead of one hanging off its only cell. */
	@Test
	void aCityWithNoEdgeGetsNoAirfield() {
		CityLayout.Plan single = CityLayout.plan(SEED, 0, 0, new CityLayout.Settings(1, 1.0D, 1, 1, 1, 16));
		assertEquals(0, Airport.outerRing(single), "a one-cell city should have no ring at all");
		Airport airport = Airport.forPlan(single, SEED);
		assertFalse(airport.exists());
		assertTrue(airport.segments().isEmpty());
		assertFalse(airport.claims(0, 0));
	}

	// ------------------------------------------------------------------ what gets built

	/** Every part {@link Airport} names actually ships in the datapack. */
	@Test
	void everyPartTheAirfieldNamesIsShipped() {
		for (String name : List.of(Airport.PART_THRESHOLD_START, Airport.PART_TERMINAL,
				Airport.PART_THRESHOLD_END)) {
			assertNotNull(shippedPartJson(name), "parts/" + name + ".json is missing");
		}
	}

	/**
	 * Every block string the parts name actually parses, and every character they use is defined.
	 *
	 * <p>Both failures are silent: {@code Tools.stringToState} logs a warning and returns air for a
	 * malformed blockstate, and {@code CompiledPalette} returns nothing for an undefined character —
	 * and {@code PartPlacer} reads both as "leave the world alone", so a typo in a property name is a
	 * hole in the terminal rather than a build failure. The parts carry their whole vocabulary in
	 * their own local palette, so an <em>empty</em> style palette underneath is the honest base to
	 * resolve against: anything that survives that resolves in every city style too.
	 */
	@Test
	void everyCharacterTheAirportPartsUseResolvesToARealBlock() {
		CompiledPalette empty = new CompiledPalette(new Palette("empty"));
		for (String name : List.of(Airport.PART_THRESHOLD_START, Airport.PART_TERMINAL,
				Airport.PART_THRESHOLD_END)) {
			BuildingPart part = shippedPart(name);
			CompiledPalette resolved = PartPlacer.paletteFor(part, empty);
			Set<Character> used = new HashSet<>();
			for (int x = 0; x < part.getXSize(); x++) {
				for (int z = 0; z < part.getZSize(); z++) {
					char[] column = part.getVSlice(x, z);
					if (column != null) {
						for (char c : column) {
							used.add(c);
						}
					}
				}
			}
			used.remove(' ');
			assertFalse(used.isEmpty(), name + " draws nothing at all");
			for (char c : used) {
				BlockState state = resolved.get(c, RandomSource.create(1));
				assertNotNull(state, name + ": character '" + c + "' resolves to nothing, so it "
						+ "silently becomes air");
				assertFalse(state.isAir(), name + ": character '" + c + "' resolved to air, which "
						+ "means its blockstate string failed to parse");
			}
		}
	}

	/**
	 * The piece surfaces its whole cell at the city's shared ground level, so the runway is flush
	 * with the roads rather than perched on the terrain.
	 */
	@Test
	void theWholeCellIsSurfacedAtTheCitysGroundLevel() {
		Map<BlockPos, BlockState> written = build(new Airport.Segment(0, 0, Airport.PART_TERMINAL, 0));

		int surfaced = 0;
		for (int dx = 0; dx < 16; dx++) {
			for (int dz = 0; dz < 16; dz++) {
				BlockState state = written.get(new BlockPos(dx, GROUND_Y - 1, dz));
				assertNotNull(state, "column (" + dx + "," + dz + ") was not surfaced at all");
				if (state.is(Blocks.CONCRETE.gray())) {
					surfaced++;
				}
			}
		}
		// The runway paint and the terminal floor legitimately replace the tarmac on their columns.
		assertTrue(surfaced > 128, "only " + surfaced + " of 256 columns are tarmac");
	}

	/**
	 * The piece really stamps its part: the runway edge lines are painted, on the rows the part draws
	 * them on. This is the {@code PartPlacer} call, which is one statement in {@code postProcess}.
	 */
	@Test
	void theRunwayMarkingsArePainted() {
		Map<BlockPos, BlockState> written = build(new Airport.Segment(0, 0, Airport.PART_TERMINAL, 0));

		// The unrotated part draws a continuous white edge line along z = 6 and z = 14.
		for (int edge : new int[]{6, 14}) {
			for (int dx = 0; dx < 16; dx++) {
				assertEquals(Blocks.CONCRETE.white(), blockAt(written, dx, GROUND_Y - 1, edge),
						"the runway edge line at z=" + edge + " is missing at x=" + dx);
			}
		}
		// ...and nothing was painted on the shoulder outside it.
		assertFalse(written.getOrDefault(new BlockPos(0, GROUND_Y - 1, 15), Blocks.AIR.defaultBlockState())
				.is(Blocks.CONCRETE.white()), "the shoulder was painted as if it were the runway");
		// The edge lights stand on the shoulder, one block above the surface.
		assertEquals(Blocks.LANTERN, blockAt(written, 3, GROUND_Y, 15), "a runway edge light is missing");
		assertEquals(Blocks.LANTERN, blockAt(written, 12, GROUND_Y, 15), "a runway edge light is missing");
	}

	/** The two threshold parts paint markings too, and they are not the same markings. */
	@Test
	void bothThresholdsArePaintedAndTheyMirrorEachOther() {
		Set<BlockPos> start = paintedColumns(build(new Airport.Segment(0, 0, Airport.PART_THRESHOLD_START, 0)));
		Set<BlockPos> end = paintedColumns(build(new Airport.Segment(0, 0, Airport.PART_THRESHOLD_END, 0)));

		assertFalse(start.isEmpty(), "the start threshold painted nothing");
		assertFalse(end.isEmpty(), "the end threshold painted nothing");
		assertEquals(start.size(), end.size(), "the two thresholds should be mirror images");
		assertFalse(start.equals(end), "the two thresholds are identical, so one of them is at the "
				+ "wrong end of the runway");
	}

	/** The terminal stands beside the runway, on the half of the cell that faces the city. */
	@Test
	void theTerminalStandsOnTheCityFacingHalfOfItsCell() {
		for (Airport.Side side : Airport.Side.values()) {
			Map<BlockPos, BlockState> written =
					build(new Airport.Segment(0, 0, Airport.PART_TERMINAL, side.quarterTurns()));

			int walls = 0;
			for (Map.Entry<BlockPos, BlockState> entry : written.entrySet()) {
				if (!entry.getValue().is(Blocks.CONCRETE.lightGray())) {
					continue;
				}
				walls++;
				BlockPos pos = entry.getKey();
				int towardsCity = switch (side) {
					case NORTH -> pos.getZ();
					case SOUTH -> 15 - pos.getZ();
					case WEST -> pos.getX();
					case EAST -> 15 - pos.getX();
				};
				assertTrue(towardsCity >= 8, side + ": a terminal wall at " + pos
						+ " is on the far side of the runway from the city");
			}
			assertTrue(walls > 20, side + ": only " + walls + " terminal wall blocks were placed");
		}
	}

	/** Nothing at all is written outside the cell the piece owns. */
	@Test
	void nothingIsWrittenOutsideTheCell() {
		Map<BlockPos, BlockState> written = build(new Airport.Segment(3, -5, Airport.PART_TERMINAL, 0));
		int minX = 3 << 4;
		int minZ = -5 << 4;
		written.keySet().forEach(pos -> assertTrue(
				pos.getX() >= minX && pos.getX() < minX + 16 && pos.getZ() >= minZ && pos.getZ() < minZ + 16,
				"the piece wrote outside its own cell at " + pos));
		assertFalse(written.isEmpty(), "the piece wrote nothing at all, so this proves nothing");
	}

	/**
	 * The wiring {@code LostCityStructure.addPieces} is meant to carry, run verbatim.
	 *
	 * <p>Three statements — take the airfield for the plan, skip the cells it has taken over, add one
	 * piece per segment — and this test is a compiled copy of them. If the API those statements use
	 * ever changes shape this file stops compiling, which is a much louder failure than a city that
	 * quietly stops having an airport.
	 */
	@Test
	void theStructureWiringAddsOnePiecePerSegmentAndSkipsThoseCells() {
		CityLayout.Plan plan = planOf(0);
		int groundY = GROUND_Y;
		String style = "standard";
		StyleSelector.Climate climate = StyleSelector.Climate.TEMPERATE;
		StructurePiecesBuilder builder = new StructurePiecesBuilder();

		Airport airport = Airport.forPlan(plan, SEED);
		int ordinaryCells = 0;
		for (CityLayout.Cell cell : plan.cells()) {
			if (airport.claims(cell)) {
				continue;
			}
			ordinaryCells++;
		}
		for (Airport.Segment segment : airport.segments()) {
			builder.addPiece(new AirportPiece(segment, groundY, style, climate));
		}

		assertFalse(builder.isEmpty(), "the wiring added no airport pieces at all");
		assertTrue(ordinaryCells < plan.cells().size(),
				"the skip matched nothing, so the airfield would be built on top of the city");
		assertEquals(plan.cells().size() - ordinaryCells,
				plan.cells().stream().filter(airport::claims).count(),
				"the skip and claims() disagree about which cells the airfield owns");
	}

	// ------------------------------------------------------------------ helpers

	private static Airport airportOf(int city) {
		return Airport.forPlan(planOf(city), SEED);
	}

	private static CityLayout.Plan planOf(int city) {
		return CityLayout.plan(SEED, city * 14, (city % 7) * 14, CITY);
	}

	private static Set<BlockPos> paintedColumns(Map<BlockPos, BlockState> written) {
		Set<BlockPos> painted = new HashSet<>();
		written.forEach((pos, state) -> {
			if (state.is(Blocks.CONCRETE.white())) {
				painted.add(pos);
			}
		});
		return painted;
	}

	private static Block blockAt(Map<BlockPos, BlockState> written, int x, int y, int z) {
		BlockState state = written.get(new BlockPos(x, y, z));
		assertNotNull(state, "nothing was written at (" + x + "," + y + "," + z + ")");
		return state.getBlock();
	}

	/**
	 * Run one {@link AirportPiece} over a recording level and return everything it wrote.
	 *
	 * <p>The assets are a bare {@link Assets} holding only the part under test: the {@code airport_*}
	 * parts carry their whole vocabulary in their own local palette, so an empty style palette
	 * underneath them resolves every character — which is exactly the property that lets them ship
	 * without touching a shared palette file.
	 */
	private static Map<BlockPos, BlockState> build(Airport.Segment segment) {
		Assets assets = new Assets();
		assets.putPart(segment.partName(), shippedPart(segment.partName()));
		LostBuildings.ASSETS = assets;
		LostBuildings.ENGINE = new BuildingEngine(assets);

		AirportPiece piece = new AirportPiece(segment, GROUND_Y, "standard",
				StyleSelector.Climate.TEMPERATE);
		Map<BlockPos, BlockState> written = new HashMap<>();
		int minX = segment.chunkX() << 4;
		int minZ = segment.chunkZ() << 4;
		BoundingBox chunk = new BoundingBox(minX, GROUND_Y - 64, minZ, minX + 15, GROUND_Y + 64, minZ + 15);
		piece.postProcess(recordingLevel(written), null, null, null, chunk,
				new net.minecraft.world.level.ChunkPos(segment.chunkX(), segment.chunkZ()),
				new BlockPos(minX, GROUND_Y, minZ));
		return written;
	}

	private static BuildingPart shippedPart(String name) {
		BuildingPartRE re = BuildingPartRE.CODEC
				.parse(JsonOps.INSTANCE, JsonParser.parseString(shippedPartJson(name)))
				.getOrThrow()
				.setRegistryName(Identifier.fromNamespaceAndPath("lostbuildings", name));
		return new BuildingPart(re, Map.of());
	}

	/** One shipped part, read off the test classpath exactly as the game would load it. */
	private static String shippedPartJson(String name) {
		String path = "/data/lostbuildings/lostcities/parts/" + name + ".json";
		try (InputStream in = AirportTest.class.getResourceAsStream(path)) {
			assertNotNull(in, "no such part: " + path);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new AssertionError("could not read " + path, e);
		}
	}

	/**
	 * A {@link WorldGenLevel} that remembers what was set on it and answers "flat empty world at the
	 * city's ground level" to everything else. {@code WorldGenLevel} is an interface, so a JDK proxy
	 * is enough and no server has to exist.
	 */
	private static WorldGenLevel recordingLevel(Map<BlockPos, BlockState> written) {
		BlockState air = Blocks.AIR.defaultBlockState();
		return (WorldGenLevel) Proxy.newProxyInstance(
				AirportTest.class.getClassLoader(),
				new Class<?>[]{WorldGenLevel.class},
				(proxy, method, args) -> {
					String name = method.getName();
					if ("setBlock".equals(name) && args != null && args.length >= 3) {
						written.put(((BlockPos) args[0]).immutable(), (BlockState) args[1]);
						return Boolean.TRUE;
					}
					if ("getBlockState".equals(name)) {
						return written.getOrDefault(((BlockPos) args[0]).immutable(), air);
					}
					if ("getHeight".equals(name) && args != null && args.length == 3) {
						return GROUND_Y - 1;    // terrain exactly at the road surface: no cut, no fill
					}
					return switch (name) {
						case "getSeed" -> 1337L;
						case "getMinY" -> -64;
						case "getMaxY" -> 320;
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
