package com.lostbuildings.world.structure;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Datapack-facing knobs of the {@code lostbuildings:lost_city} structure (IMPROVEMENTS #10).
 *
 * <p>Everything here used to be a constant somewhere in Java: the building list and floor range
 * lived in {@code ModConfiguredFeatures}, the street material and width were {@code private static
 * final} fields of {@code Streets}, and city size/density did not exist at all because the feature
 * could not reach past one chunk. They are now fields of the structure's own codec, so
 * {@code data/<namespace>/worldgen/structure/lost_city.json} can change them without a rebuild.
 *
 * <p>The top-level fields are flattened into the structure JSON (this is a {@link MapCodec}, not a
 * nested object), next to the vanilla {@code biomes} / {@code step} / {@code terrain_adaptation}
 * settings. The wave-2 content knobs are grouped under {@code streets} and {@code content}: partly
 * because they belong together, and partly because {@code RecordCodecBuilder.group} tops out at
 * sixteen fields and the flat list had grown past it.
 *
 * @param buildings    names of buildings eligible to spawn (e.g. {@code building1..building8})
 * @param minFloors    lowest storey count for a building
 * @param maxFloors    highest storey count for a building
 * @param foundation   whether buildings get a pillar/excavation pass under and around them
 * @param citySize     cells per side of the city square. A building sits on every cell whose offset
 *                     from the centre is even on both axes and streets fill the rest, so {@code 5}
 *                     gives 3×3 buildings, {@code 9} gives 5×5. Keep
 *                     {@code ModStructureSets.CITY_SEPARATION >= citySize} or two cities can overlap.
 * @param density      chance a non-central building cell is actually built
 * @param cellars      storeys dug below the ground floor (handed straight to the building engine)
 * @param damageChance how badly buildings are ruined, {@code 0} = intact (handed to the engine)
 * @param streets      paving, street furniture and bridges
 * @param content      parks and the 2×2 landmark building
 */
public record LostCityConfig(
		List<String> buildings,
		int minFloors,
		int maxFloors,
		boolean foundation,
		int citySize,
		float density,
		int cellars,
		float damageChance,
		StreetSettings streets,
		ContentSettings content
) {
	/** Storeys are 6 blocks tall in the engine; the ground level is snapped to a multiple of this. */
	public static final int FLOOR_HEIGHT = 6;

	/**
	 * Everything about the cells between the buildings.
	 *
	 * @param block         the base paving material laid under the street tiles
	 * @param width         paved width of a street cell's arms, in blocks (16 paves the whole cell)
	 * @param tiles         whether to lay the shipped {@code street_*} parts on top of the paving
	 * @param lampSpacing   blocks between kerbside lamp posts; {@code 0} disables them
	 * @param potholeChance share of roadway columns broken up by a pothole
	 * @param bridges       {@code bridge_*} parts a street crossing water or a drop is built from
	 */
	public record StreetSettings(BlockState block, int width, boolean tiles, int lampSpacing,
	                             float potholeChance, List<String> bridges) {

		/** The shipped bridge parts, straight out of {@code citystyle_common.json}. */
		public static final List<String> DEFAULT_BRIDGES = List.of("bridge_open", "bridge_covered");

		public static final MapCodec<StreetSettings> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
				BlockState.CODEC.optionalFieldOf("block", Blocks.STONE_BRICKS.defaultBlockState()).forGetter(StreetSettings::block),
				Codec.intRange(1, 16).optionalFieldOf("width", 16).forGetter(StreetSettings::width),
				Codec.BOOL.optionalFieldOf("tiles", true).forGetter(StreetSettings::tiles),
				Codec.intRange(0, 64).optionalFieldOf("lamp_spacing", 8).forGetter(StreetSettings::lampSpacing),
				Codec.floatRange(0.0F, 1.0F).optionalFieldOf("pothole_chance", 0.04F).forGetter(StreetSettings::potholeChance),
				Codec.STRING.listOf().optionalFieldOf("bridges", DEFAULT_BRIDGES).forGetter(StreetSettings::bridges)
		).apply(instance, StreetSettings::new));

		public static StreetSettings defaults() {
			return new StreetSettings(Blocks.STONE_BRICKS.defaultBlockState(), 16, true, 8, 0.04F, DEFAULT_BRIDGES);
		}
	}

	/**
	 * Everything that replaces a building on a building cell.
	 *
	 * @param parks          {@code park_*} parts a park cell may be built from
	 * @param parkChance     chance a building cell becomes a park instead
	 * @param multiBuildings quadrant-name prefixes of the 2×2 landmark buildings
	 * @param downtownChance chance a city is a downtown and gets a landmark at its centre
	 */
	public record ContentSettings(List<String> parks, float parkChance,
	                              List<String> multiBuildings, float downtownChance) {

		/** The shipped park parts, straight out of {@code citystyle_common.json}'s park selector. */
		public static final List<String> DEFAULT_PARKS = List.of(
				"park_plants", "park_plants_pillars", "park_pool", "park_trees",
				"park_fountain1", "park_fountain2");

		/**
		 * The shipped 2×2 landmarks. These are the {@code multibuildings/} entries whose four
		 * quadrants are real, distinct buildings: {@code center} (the antenna tower),
		 * {@code library}, {@code shopping} / {@code shopping_open} (the mall with its atrium) and
		 * {@code town} (the town hall — its multibuilding file is called {@code townhall}, but its
		 * quadrants are {@code town00}..{@code town11}, and it is the quadrants that are named
		 * here). {@code multi1..multi5} and {@code huge1/2} are left out on purpose: those tables
		 * repeat one ordinary building four or nine times, which the single-cell path already does.
		 */
		public static final List<String> DEFAULT_MULTI_BUILDINGS = List.of(
				"center", "library", "shopping", "shopping_open", "town");

		public static final MapCodec<ContentSettings> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
				Codec.STRING.listOf().optionalFieldOf("parks", DEFAULT_PARKS).forGetter(ContentSettings::parks),
				Codec.floatRange(0.0F, 1.0F).optionalFieldOf("park_chance", 0.25F).forGetter(ContentSettings::parkChance),
				Codec.STRING.listOf().optionalFieldOf("multi_buildings", DEFAULT_MULTI_BUILDINGS).forGetter(ContentSettings::multiBuildings),
				Codec.floatRange(0.0F, 1.0F).optionalFieldOf("downtown_chance", 0.3F).forGetter(ContentSettings::downtownChance)
		).apply(instance, ContentSettings::new));

		public static ContentSettings defaults() {
			return new ContentSettings(DEFAULT_PARKS, 0.25F, DEFAULT_MULTI_BUILDINGS, 0.3F);
		}
	}

	public static final MapCodec<LostCityConfig> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			Codec.STRING.listOf().fieldOf("buildings").forGetter(LostCityConfig::buildings),
			Codec.INT.optionalFieldOf("min_floors", 2).forGetter(LostCityConfig::minFloors),
			Codec.INT.optionalFieldOf("max_floors", 6).forGetter(LostCityConfig::maxFloors),
			Codec.BOOL.optionalFieldOf("foundation", true).forGetter(LostCityConfig::foundation),
			Codec.intRange(1, 16).optionalFieldOf("city_size", 9).forGetter(LostCityConfig::citySize),
			Codec.floatRange(0.0F, 1.0F).optionalFieldOf("density", 0.85F).forGetter(LostCityConfig::density),
			Codec.intRange(0, 4).optionalFieldOf("cellars", 1).forGetter(LostCityConfig::cellars),
			Codec.floatRange(0.0F, 1.0F).optionalFieldOf("damage_chance", 0.2F).forGetter(LostCityConfig::damageChance),
			StreetSettings.MAP_CODEC.codec().optionalFieldOf("streets", StreetSettings.defaults()).forGetter(LostCityConfig::streets),
			ContentSettings.MAP_CODEC.codec().optionalFieldOf("content", ContentSettings.defaults()).forGetter(LostCityConfig::content)
	).apply(instance, LostCityConfig::new));

	/**
	 * The shipped defaults — the same eight buildings and the same 2..6 storeys the feature used, on
	 * a 9×9 grid of cells: 5×5 building lots with a full street lattice woven between them.
	 */
	public static LostCityConfig defaults() {
		return new LostCityConfig(
				List.of("building1", "building2", "building3", "building4",
						"building5", "building6", "building7", "building8"),
				2, 6, true,
				9,
				0.85F,
				1,
				0.2F,
				StreetSettings.defaults(),
				ContentSettings.defaults());
	}

	// --- convenience accessors, so call sites read the same as they did in wave 1 ---

	public BlockState streetBlock() {
		return streets.block();
	}

	public int streetWidth() {
		return streets.width();
	}

	/** The Minecraft-free view of this config that {@link CityLayout} consumes. */
	public CityLayout.Settings layoutSettings() {
		return new CityLayout.Settings(citySize, density, minFloors, maxFloors, buildings.size(), streets.width(),
				content.parks().isEmpty() ? 0.0F : content.parkChance(), Math.max(1, content.parks().size()),
				content.multiBuildings().isEmpty() ? 0.0F : content.downtownChance(),
				Math.max(1, content.multiBuildings().size()));
	}

	/** Building name for a {@link CityLayout.Cell}, tolerating an index from a stale plan. */
	public String buildingName(int index) {
		return pick(buildings, index);
	}

	/** Park part for a {@link CityLayout.Cell}, tolerating an index from a stale plan. */
	public String parkName(int index) {
		return pick(content.parks(), index);
	}

	/** Bridge part, chosen by an already-rolled index. */
	public String bridgeName(int index) {
		return pick(streets.bridges(), index);
	}

	/**
	 * The building name of one quadrant of a 2×2 landmark. The original stored these as a table in
	 * {@code multibuildings/*.json} indexed {@code [x][z]}; every shipped table is simply the prefix
	 * with the two indices appended, which is what this reproduces without needing the table loaded.
	 *
	 * @param index    which landmark, modulo the configured list
	 * @param quadrant {@code x * 2 + z}, as carried in {@link CityLayout.Cell#variant()}
	 */
	public String multiBuildingName(int index, int quadrant) {
		String prefix = pick(content.multiBuildings(), index);
		if (prefix.isEmpty()) {
			return "";
		}
		int q = Math.floorMod(quadrant, 4);
		return prefix + (q / 2) + (q % 2);
	}

	private static String pick(List<String> names, int index) {
		if (names.isEmpty()) {
			return "";
		}
		return names.get(Math.floorMod(index, names.size()));
	}
}
