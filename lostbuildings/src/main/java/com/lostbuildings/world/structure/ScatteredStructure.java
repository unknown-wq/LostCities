package com.lostbuildings.world.structure;

import com.lostbuildings.registry.ModStructureTypes;
import com.lostbuildings.world.feature.StyleSelector;
import com.lostbuildings.world.structure.piece.BuildingPiece;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;

import java.util.List;
import java.util.Optional;

/**
 * A single building standing on its own, well away from any city (PORT #2).
 *
 * <p>The original calls these <em>scattered</em> buildings and ships three: a {@code cabin} in the
 * woods, a {@code radiotower} on a hill, and an {@code oilrig} — the only thing in the whole mod
 * that stands in open water. They are the cheapest content in the port by a distance: no grid, no
 * streets, no shared ground level, just one building (or one 2×2 set of them) placed on the terrain.
 * What they buy is that the world between cities stops being empty.
 *
 * <p><b>How the three variants differ</b> is entirely datapack data, not code: which buildings are
 * eligible, whether the site is measured from the terrain or from sea level, how much slope is
 * tolerated, and how far the building sits above or below that measurement. Two structures are
 * registered from this one class — a land one and an ocean one — because the biome list is a
 * property of the structure, and a cabin must not roll in the middle of an ocean.
 *
 * <p><b>Pieces are ordinary {@link BuildingPiece}s.</b> A scattered building is a building; giving
 * it its own piece class would duplicate the foundation pass, the palette resolution and the NBT
 * format for no gain, and would give the mod two code paths that can disagree about how a building
 * is placed.
 */
public class ScatteredStructure extends Structure {

	/** How many terrain probes across the footprint decide whether the site is flat enough. */
	private static final int[][] PROBES = {{0, 0}, {15, 0}, {0, 15}, {15, 15}, {8, 8}};

	public static final MapCodec<ScatteredStructure> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			settingsCodec(instance),
			ScatteredConfig.MAP_CODEC.forGetter(structure -> structure.config)
	).apply(instance, ScatteredStructure::new));

	/**
	 * Datapack knobs of a scattered structure.
	 *
	 * @param buildings      single-chunk buildings this structure may place
	 * @param multiBuildings 2×2 landmark prefixes it may place instead (see
	 *                       {@link LostCityConfig#multiBuildingName(int, int)} for the naming rule)
	 * @param heightOffset   blocks the building is moved up (positive) or down (negative) from the
	 *                       measured site level; the shipped cabin and radiotower sink 3 blocks in so
	 *                       their plinths are buried, the oil rig stands 1 above the waves
	 * @param overWater      measure the site from sea level rather than from the terrain — the oil
	 *                       rig's whole point
	 * @param foundation     run the pillar/excavation pass (never for something standing in water)
	 * @param floors         storey count handed to the engine; the shipped scattered buildings
	 *                       declare their own limits and override this
	 * @param maxHeightDiff  refuse the site if the terrain across the footprint varies by more than
	 *                       this many blocks — the original's {@code maxheightdiff}
	 */
	public record ScatteredConfig(List<String> buildings, List<String> multiBuildings, int heightOffset,
	                              boolean overWater, boolean foundation, int floors, int maxHeightDiff) {

		public static final MapCodec<ScatteredConfig> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
				Codec.STRING.listOf().optionalFieldOf("buildings", List.of()).forGetter(ScatteredConfig::buildings),
				Codec.STRING.listOf().optionalFieldOf("multi_buildings", List.of()).forGetter(ScatteredConfig::multiBuildings),
				Codec.INT.optionalFieldOf("height_offset", 0).forGetter(ScatteredConfig::heightOffset),
				Codec.BOOL.optionalFieldOf("over_water", false).forGetter(ScatteredConfig::overWater),
				Codec.BOOL.optionalFieldOf("foundation", true).forGetter(ScatteredConfig::foundation),
				Codec.intRange(0, 32).optionalFieldOf("floors", 1).forGetter(ScatteredConfig::floors),
				Codec.intRange(0, 255).optionalFieldOf("max_height_diff", 4).forGetter(ScatteredConfig::maxHeightDiff)
		).apply(instance, ScatteredConfig::new));

		/** {@code scattered/cabin.json} + {@code scattered/radiotower.json}, on land. */
		public static ScatteredConfig land() {
			return new ScatteredConfig(List.of("cabin", "radiotower"), List.of(), -3, false, true, 1, 3);
		}

		/** {@code scattered/oilrig.json} — the one structure that stands in open water. */
		public static ScatteredConfig ocean() {
			return new ScatteredConfig(List.of(), List.of("oilrig"), 1, true, false, 1, 255);
		}

		/** How many things this structure can choose between. */
		public int optionCount() {
			return buildings.size() + multiBuildings.size();
		}
	}

	private final ScatteredConfig config;

	public ScatteredStructure(Structure.StructureSettings settings, ScatteredConfig config) {
		super(settings);
		this.config = config;
	}

	public ScatteredConfig config() {
		return this.config;
	}

	@Override
	protected Optional<Structure.GenerationStub> findGenerationPoint(Structure.GenerationContext context) {
		if (this.config.optionCount() == 0) {
			return Optional.empty();
		}
		ChunkPos origin = context.chunkPos();
		int option = (int) Math.floorMod(
				CityLayout.hash(context.seed(), origin.x(), origin.z(), 0x5C), this.config.optionCount());
		boolean multi = option >= this.config.buildings().size();

		OptionalSite site = siteLevel(context, origin, multi);
		if (site == null) {
			return Optional.empty();
		}
		int groundY = site.level() + this.config.heightOffset();
		if (groundY <= context.heightAccessor().getMinY() + LostCityConfig.FLOOR_HEIGHT) {
			return Optional.empty();
		}

		BlockPos centre = new BlockPos(origin.getMiddleBlockX(), groundY, origin.getMiddleBlockZ());
		Holder<Biome> biome = biomeAt(context, centre);
		String style = StyleSelector.styleFor(biome);
		if (style == null) {
			style = StyleSelector.DEFAULT_STYLE;
		}
		StyleSelector.Climate climate = StyleSelector.climateFor(biome);
		int floors = this.config.floors();
		boolean foundation = this.config.foundation();
		String chosenStyle = style;     // effectively final, for the piece-builder lambda

		return Optional.of(new Structure.GenerationStub(centre, builder -> {
			if (multi) {
				String prefix = this.config.multiBuildings().get(option - this.config.buildings().size());
				for (int dx = 0; dx <= 1; dx++) {
					for (int dz = 0; dz <= 1; dz++) {
						builder.addPiece(new BuildingPiece(origin.x() + dx, origin.z() + dz, groundY,
								prefix + dx + dz, chosenStyle, floors, 0, foundation, 0, 0.0F,
								CityLayout.BuildingKind.RESIDENTIAL, climate));
					}
				}
			} else {
				builder.addPiece(new BuildingPiece(origin.x(), origin.z(), groundY,
						this.config.buildings().get(option), chosenStyle, floors,
						(int) Math.floorMod(CityLayout.hash(context.seed(), origin.x(), origin.z(), 0x5D), 4L),
						foundation, 0, 0.0F, CityLayout.BuildingKind.RESIDENTIAL, climate));
			}
		}));
	}

	/** A resolved site level; {@code null} from {@link #siteLevel} means "this spot will not do". */
	private record OptionalSite(int level) {
	}

	/**
	 * The Y the building sits on, or {@code null} when the terrain rules it out.
	 *
	 * <p>Over water the answer is simply sea level: an oil rig is not built on the sea floor. On land
	 * it is the lowest of the probes, and the site is rejected outright when the probes disagree by
	 * more than {@code maxHeightDiff} — the original's way of keeping a cabin off a cliff edge, and
	 * cheaper than excavating a hillside for a one-storey hut.
	 */
	private OptionalSite siteLevel(Structure.GenerationContext context, ChunkPos origin, boolean multi) {
		ChunkGenerator generator = context.chunkGenerator();
		if (this.config.overWater()) {
			return new OptionalSite(generator.getSeaLevel());
		}
		int span = multi ? 2 : 1;
		int lowest = Integer.MAX_VALUE;
		int highest = Integer.MIN_VALUE;
		for (int cx = 0; cx < span; cx++) {
			for (int cz = 0; cz < span; cz++) {
				int x0 = (origin.x() + cx) << 4;
				int z0 = (origin.z() + cz) << 4;
				for (int[] probe : PROBES) {
					int height = generator.getFirstOccupiedHeight(x0 + probe[0], z0 + probe[1],
							Heightmap.Types.WORLD_SURFACE_WG, context.heightAccessor(), context.randomState());
					lowest = Math.min(lowest, height);
					highest = Math.max(highest, height);
				}
			}
		}
		if (lowest == Integer.MAX_VALUE) {
			return null;
		}
		if (highest - lowest > this.config.maxHeightDiff()) {
			return null;
		}
		if (lowest <= generator.getSeaLevel()) {
			return null;    // a land building would be standing in the sea
		}
		return new OptionalSite(lowest);
	}

	/** The biome under the site, sampled the same way a city samples it: from the biome source. */
	private static Holder<Biome> biomeAt(Structure.GenerationContext context, BlockPos centre) {
		return context.chunkGenerator().getBiomeSource().getNoiseBiome(
				QuartPos.fromBlock(centre.getX()), QuartPos.fromBlock(centre.getY()), QuartPos.fromBlock(centre.getZ()),
				context.randomState().sampler());
	}

	@Override
	public StructureType<?> type() {
		return ModStructureTypes.SCATTERED;
	}
}
