package com.lostbuildings.command;

import com.lostbuildings.LostBuildings;
import com.lostbuildings.world.feature.StyleSelector;
import com.lostbuildings.world.structure.CityLayout;
import com.lostbuildings.world.structure.LostCityConfig;
import com.lostbuildings.world.structure.LostCityStructure;
import com.lostbuildings.world.structure.piece.AirportPiece;
import com.lostbuildings.world.structure.piece.BridgePiece;
import com.lostbuildings.world.structure.piece.BuildingPiece;
import com.lostbuildings.world.structure.piece.CityPiece;
import com.lostbuildings.world.structure.piece.ParkPiece;
import com.lostbuildings.world.structure.piece.StreetPiece;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.MapCodec;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.server.level.WorldGenRegion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@code /lostcity here} actually builds.
 *
 * <p>The command itself is Brigadier plumbing plus a copy of {@code PlaceCommand.placeStructure},
 * and neither is worth a fake server. The three things that <em>are</em> the command — and that a
 * later refactor could silently break — are all reachable without one:
 *
 * <ol>
 *   <li><b>Argument to config.</b> An absent {@code <cells>} must mean "the size this structure
 *       ships with", not zero; a size past the cap must be refused rather than clamped; and the
 *       terrain-relief veto has to be switched off, because a command that means "here" cannot
 *       honour a rule whose answer is "not here". {@link LostCityCommand#configFor} is pure.</li>
 *   <li><b>Config to city.</b> That the config the command derives really does produce a city of the
 *       requested extent, that it builds on ground the registered structure would have refused, and
 *       that a style override reaches the pieces. This drives the real
 *       {@link Structure#findValidGenerationPoint} against a stub chunk generator, so it is the
 *       actual assembly code the command runs, not a re-description of it.</li>
 *   <li><b>Not touching the registry.</b> Deriving must leave the structure the world generates from
 *       exactly as it was.</li>
 * </ol>
 *
 * <p>What is deliberately <em>not</em> covered: the block writes. Those go through
 * {@code StructureStart.placeInChunk} into a real {@code ServerLevel}, which is the one part of the
 * path this mod does not own and vanilla already tests.
 */
class LostCityCommandTest {

	private static final long SEED = 0x10571C171E5L;
	private static final int SEA_LEVEL = 63;
	private static final int MIN_Y = -64;
	private static final int WORLD_HEIGHT = 384;

	private static Holder<Biome> plains;
	private static RandomState randomState;

	@BeforeAll
	static void boot() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		// The one thing in the generation context that cannot be stubbed: findGenerationPoint asks the
		// RandomState for its climate sampler before it can look up a biome. Building the vanilla
		// overworld one costs about half a second, once.
		HolderLookup.Provider lookup = VanillaRegistries.createLookup();
		// A *direct* holder, not the registry's Reference: a Reference handed out by a bare
		// HolderLookup has no tags bound to it and answers `is(TagKey)` by throwing, which is a
		// worldgen-only invariant this test has no business reproducing. A direct holder answers
		// "in no tag", so StyleSelector falls through to its default and the biome stops being a
		// variable in these assertions.
		plains = Holder.direct(lookup.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value());
		randomState = RandomState.create(lookup, NoiseGeneratorSettings.OVERWORLD, SEED);
	}

	// ------------------------------------------------------------------ argument to config

	@Test
	void anAbsentSizeMeansTheSizeTheStructureShipsWith() throws CommandSyntaxException {
		LostCityConfig shipped = LostCityConfig.defaults();
		assertEquals(shipped.citySize(), LostCityCommand.configFor(shipped, OptionalInt.empty()).citySize());

		// ...and it is the *structure's* value, not a constant: a datapack that retuned city_size is
		// what the command has to default to.
		LostCityConfig retuned = shipped.withCitySize(5);
		assertEquals(5, LostCityCommand.configFor(retuned, OptionalInt.empty()).citySize());
	}

	@Test
	void anExplicitSizeWins() throws CommandSyntaxException {
		assertEquals(3, LostCityCommand.configFor(LostCityConfig.defaults(), OptionalInt.of(3)).citySize());
		assertEquals(LostCityCommand.MAX_CELLS,
				LostCityCommand.configFor(LostCityConfig.defaults(), OptionalInt.of(LostCityCommand.MAX_CELLS))
						.citySize());
	}

	@Test
	void pastTheCapItRefusesRatherThanClamps() {
		LostCityConfig shipped = LostCityConfig.defaults();
		assertThrows(CommandSyntaxException.class,
				() -> LostCityCommand.configFor(shipped, OptionalInt.of(LostCityCommand.MAX_CELLS + 1)));
		assertThrows(CommandSyntaxException.class,
				() -> LostCityCommand.configFor(shipped, OptionalInt.of(10_000)));
	}

	@Test
	void theTerrainReliefVetoIsAlwaysOff() throws CommandSyntaxException {
		LostCityConfig shipped = LostCityConfig.defaults();
		assertTrue(shipped.maxHeightDiff() > 0, "the shipped config is expected to veto steep sites");
		assertEquals(0, LostCityCommand.configFor(shipped, OptionalInt.empty()).maxHeightDiff());
		// ...without disturbing the caller's own config.
		assertEquals(LostCityConfig.DEFAULT_MAX_HEIGHT_DIFF, shipped.maxHeightDiff());
	}

	@Test
	void everyOtherKnobIsCarriedThroughUntouched() throws CommandSyntaxException {
		LostCityConfig shipped = LostCityConfig.defaults();
		LostCityConfig derived = LostCityCommand.configFor(shipped, OptionalInt.of(7));
		assertEquals(shipped.buildings(), derived.buildings());
		assertEquals(shipped.minFloors(), derived.minFloors());
		assertEquals(shipped.maxFloors(), derived.maxFloors());
		assertEquals(shipped.density(), derived.density());
		assertEquals(shipped.cellars(), derived.cellars());
		assertEquals(shipped.damageChance(), derived.damageChance());
		assertEquals(shipped.foundation(), derived.foundation());
		assertSame(shipped.streets(), derived.streets());
		assertSame(shipped.content(), derived.content());
	}

	@Test
	void derivingNothingAllocatesNothing() {
		LostCityConfig shipped = LostCityConfig.defaults();
		assertSame(shipped, shipped.withCitySize(shipped.citySize()));
		assertSame(shipped, shipped.withMaxHeightDiff(shipped.maxHeightDiff()));
	}

	// ------------------------------------------------------------------ config to city

	/**
	 * The whole point of {@code <cells>}: the city really is that many cells across.
	 *
	 * <p>Checked as an extent rather than as a piece count because density rolls buildings away — but
	 * never streets, and on this grid every cell whose offset from the centre is odd on either axis is
	 * a street. So the outermost row and column are always emitted and the span is exact.
	 *
	 * <p><b>The airfield is excluded, and that is not cosmetic.</b> {@code Airport.forPlan} places its
	 * three cells at {@code origin ± outerRing(plan)} rather than out of the grid, and for an
	 * <em>even</em> {@code city_size} the grid is not symmetric about its origin — with
	 * {@code cells = 16} the offsets run {@code -7..8}, so the outer ring is 8 and an airfield chosen
	 * for the low side lands at {@code -8}, one cell outside the city. Every shipped size is odd
	 * ({@code 9}, and {@code 5} for the charred towns), where the grid is symmetric and the question
	 * does not arise, so this is a latent quirk of even sizes and not a live bug — but a command that
	 * lets an operator type any number makes even sizes reachable, so it is named here rather than
	 * silently averaged away.
	 */
	@Test
	void theCityIsTheSizeItWasAskedFor() throws CommandSyntaxException {
		for (int cells : new int[]{1, 2, 3, 5, 9, 16, LostCityCommand.MAX_CELLS}) {
			List<StructurePiece> pieces = layOutCity(cells, null, flat(65));
			assertTrue(!pieces.isEmpty(), cells + " cells should produce a city");

			int minX = Integer.MAX_VALUE;
			int maxX = Integer.MIN_VALUE;
			int minZ = Integer.MAX_VALUE;
			int maxZ = Integer.MIN_VALUE;
			for (StructurePiece piece : pieces) {
				if (piece instanceof AirportPiece) {
					continue;
				}
				CityPiece cell = (CityPiece) piece;
				minX = Math.min(minX, cell.cellChunkX());
				maxX = Math.max(maxX, cell.cellChunkX());
				minZ = Math.min(minZ, cell.cellChunkZ());
				maxZ = Math.max(maxZ, cell.cellChunkZ());
			}
			assertEquals(cells, maxX - minX + 1, "city of " + cells + " cells should span " + cells + " chunks in X");
			assertEquals(cells, maxZ - minZ + 1, "city of " + cells + " cells should span " + cells + " chunks in Z");
		}
	}

	/**
	 * A bigger {@code <cells>} is a bigger city, not the same city rescaled: the 9-cell grid is the
	 * 5-cell grid plus a ring, so it has strictly more cells on the same seed and spot.
	 */
	@Test
	void moreCellsMeansMoreCity() throws CommandSyntaxException {
		int small = layOutCity(5, null, flat(65)).size();
		int large = layOutCity(9, null, flat(65)).size();
		assertTrue(large > small, "9 cells (" + large + ") should out-build 5 cells (" + small + ")");
	}

	/** The site the registered structure refuses is the site the command builds on. */
	@Test
	void theCommandBuildsOnGroundWorldgenWouldHaveRefused() throws CommandSyntaxException {
		ChunkGenerator hillside = sloped(65, 2);   // 2 blocks of climb per block of X

		LostCityStructure shipped = cityStructure(LostCityConfig.defaults());
		assertTrue(shipped.findValidGenerationPoint(context(hillside, new ChunkPos(0, 0))).isEmpty(),
				"a 2:1 hillside should be past max_height_diff");

		assertTrue(!layOutCity(9, null, hillside).isEmpty(),
				"the command's config should build there anyway");
	}

	/** {@code <style>} is not just parsed — every building carries it instead of the biome's answer. */
	@Test
	void theStyleOverrideReachesThePieces() throws CommandSyntaxException {
		List<StructurePiece> fromBiome = layOutCity(9, null, flat(65));
		List<StructurePiece> forced = layOutCity(9, "desert", flat(65));

		// No assets are loaded in a unit test, so the biome answer falls back to the default style.
		assertTrue(buildingStyles(fromBiome).contains(StyleSelector.DEFAULT_STYLE));
		assertEquals(1, buildingStyles(fromBiome).size(), "one city, one style");

		assertEquals(List.of("desert"), List.copyOf(buildingStyles(forced)));
	}

	// ------------------------------------------------------------------ not touching the registry

	@Test
	void derivingLeavesTheRegisteredStructureAlone() throws CommandSyntaxException {
		LostCityStructure registered = cityStructure(LostCityConfig.defaults());
		LostCityConfig before = registered.config();

		LostCityStructure derived = registered.derive(
				LostCityCommand.configFor(before, OptionalInt.of(3)), "desert");

		assertNotSame(registered, derived);
		assertSame(before, registered.config(), "the registry entry must keep its own config");
		assertEquals(LostCityConfig.defaults().citySize(), registered.config().citySize());
		assertEquals(3, derived.config().citySize());

		// The copy is the same structure in every way that generation reads.
		assertEquals(registered.step(), derived.step());
		assertEquals(registered.terrainAdaptation(), derived.terrainAdaptation());
		assertEquals(registered.biomes(), derived.biomes());
		assertEquals(registered.type(), derived.type());
	}

	/**
	 * Deriving with the size the structure already has must be a no-op on the seeded draw order:
	 * running the command with no {@code <cells>} has to produce the city worldgen would have.
	 */
	@Test
	void aDefaultSizedCommandBuildsExactlyWhatWorldgenWouldHave() throws CommandSyntaxException {
		LostCityConfig shipped = LostCityConfig.defaults();
		LostCityConfig commandConfig = LostCityCommand.configFor(shipped, OptionalInt.empty());

		CityLayout.Plan fromWorldgen = CityLayout.plan(SEED, 4, -7, shipped.layoutSettings());
		CityLayout.Plan fromCommand = CityLayout.plan(SEED, 4, -7, commandConfig.layoutSettings());
		assertEquals(fromWorldgen, fromCommand);
	}

	// ------------------------------------------------------------------ the report

	@Test
	void theSummaryCountsWhatWasActuallyEmitted() {
		List<StructurePiece> pieces = List.of(
				building(0, 0), building(2, 0),
				street(1, 0), street(0, 1), street(1, 1),
				new BridgePiece(2, 1, 64, "bridge_open", "standard", 0, CityLayout.ALL_SIDES,
						StyleSelector.Climate.TEMPERATE),
				new ParkPiece(2, 2, 64, "park_trees", "standard", StyleSelector.Climate.TEMPERATE),
				new AirportPiece(new com.lostbuildings.world.structure.Airport.Segment(4, 4, "airport_runway", 0),
						64, "standard", StyleSelector.Climate.TEMPERATE));

		LostCityCommand.CitySummary summary = LostCityCommand.CitySummary.of(pieces);
		assertEquals(8, summary.cells());
		assertEquals(2, summary.buildings());
		assertEquals(3, summary.streets());
		assertEquals(1, summary.bridges(), "a bridge is not counted twice as a street");
		assertEquals(1, summary.parks());
		assertEquals(1, summary.airfield());
		assertEquals(4, summary.lots(), "lots are everything that is not road surface");
		assertEquals("2 buildings, 3 streets, 1 bridge, 1 park, 1 airfield cell", summary.describe());
	}

	@Test
	void anEmptySummaryReadsAsNothing() {
		LostCityCommand.CitySummary summary = LostCityCommand.CitySummary.of(List.of());
		assertEquals(0, summary.cells());
		assertEquals("nothing", summary.describe());
	}

	// ------------------------------------------------------------------ helpers

	/** The pieces the command's own config produces at the origin, through the real assembly path. */
	private static List<StructurePiece> layOutCity(int cells, String style, ChunkGenerator generator)
			throws CommandSyntaxException {
		LostCityConfig config = LostCityCommand.configFor(LostCityConfig.defaults(), OptionalInt.of(cells));
		LostCityStructure structure = cityStructure(LostCityConfig.defaults()).derive(config, style);
		Optional<Structure.GenerationStub> stub =
				structure.findValidGenerationPoint(context(generator, new ChunkPos(0, 0)));
		return stub.map(s -> s.getPiecesBuilder().build().pieces()).orElse(List.of());
	}

	private static java.util.Set<String> buildingStyles(List<StructurePiece> pieces) {
		java.util.Set<String> styles = new java.util.LinkedHashSet<>();
		for (StructurePiece piece : pieces) {
			if (piece instanceof BuildingPiece building) {
				styles.add(building.styleName());
			}
		}
		return styles;
	}

	private static LostCityStructure cityStructure(LostCityConfig config) {
		return new LostCityStructure(new Structure.StructureSettings(HolderSet.direct(plains)), config);
	}

	private static Structure.GenerationContext context(ChunkGenerator generator, ChunkPos chunkPos) {
		return new Structure.GenerationContext(
				null, generator, generator.getBiomeSource(), randomState, null, SEED, chunkPos,
				LevelHeightAccessor.create(MIN_Y, WORLD_HEIGHT), biome -> true);
	}

	private static ChunkGenerator flat(int height) {
		return new StubGenerator((x, z) -> height);
	}

	/** Ground that climbs {@code slope} blocks for every block of {@code x}. */
	private static ChunkGenerator sloped(int height, int slope) {
		return new StubGenerator((x, z) -> height + x * slope);
	}

	private static BuildingPiece building(int chunkX, int chunkZ) {
		return new BuildingPiece(chunkX, chunkZ, 64, "building1", "standard", 3, 0, true, 1, 0.0F,
				CityLayout.BuildingKind.RESIDENTIAL, StyleSelector.Climate.TEMPERATE);
	}

	private static StreetPiece street(int chunkX, int chunkZ) {
		return new StreetPiece(chunkX, chunkZ, 64, Blocks.STONE_BRICKS.defaultBlockState(), 16,
				CityLayout.ALL_SIDES, "standard", true, 8, 0.0F, StyleSelector.Climate.TEMPERATE);
	}

	// ------------------------------------------------------------------ the stub generator

	private interface Terrain {
		int heightAt(int x, int z);
	}

	/**
	 * The smallest thing {@code findGenerationPoint} will accept: it asks for terrain heights, a sea
	 * level and a biome, and nothing else. Every other {@code ChunkGenerator} method throws, so a
	 * future read from the structure that this stub cannot honestly answer fails loudly here rather
	 * than quietly agreeing with itself.
	 */
	private static final class StubGenerator extends ChunkGenerator {

		private final Terrain terrain;

		StubGenerator(Terrain terrain) {
			super(new StubBiomeSource());
			this.terrain = terrain;
		}

		@Override
		protected MapCodec<? extends ChunkGenerator> codec() {
			throw new UnsupportedOperationException();
		}

		@Override
		public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random) {
			return this.terrain.heightAt(x, z);
		}

		@Override
		public int getSeaLevel() {
			return SEA_LEVEL;
		}

		@Override
		public int getMinY() {
			return MIN_Y;
		}

		@Override
		public int getGenDepth() {
			return WORLD_HEIGHT;
		}

		@Override
		public void applyCarvers(WorldGenRegion region, long seed, RandomState random, net.minecraft.world.level.biome.BiomeManager biomes,
		                         StructureManager structures, ChunkAccess chunk) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void buildSurface(WorldGenRegion region, StructureManager structures, RandomState random, ChunkAccess chunk) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void spawnOriginalMobs(WorldGenRegion region) {
			throw new UnsupportedOperationException();
		}

		@Override
		public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState random,
		                                                    StructureManager structures, ChunkAccess chunk) {
			throw new UnsupportedOperationException();
		}

		@Override
		public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState random) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void addDebugScreenInfo(List<String> lines, RandomState random, BlockPos pos) {
			throw new UnsupportedOperationException();
		}
	}

	/** One biome everywhere, so the city's style comes from the override or from the fallback. */
	private static final class StubBiomeSource extends BiomeSource {

		@Override
		protected MapCodec<? extends BiomeSource> codec() {
			throw new UnsupportedOperationException();
		}

		@Override
		protected Stream<Holder<Biome>> collectPossibleBiomes() {
			return Stream.of(plains);
		}

		@Override
		public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
			return plains;
		}
	}
}
