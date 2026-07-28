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
 * <p>The fields are flattened into the structure JSON (this is a {@link MapCodec}, not a nested
 * object), next to the vanilla {@code biomes} / {@code step} / {@code terrain_adaptation} settings —
 * which is where the structure's target biomes are configured, tag and all.
 *
 * @param buildings    names of buildings eligible to spawn (e.g. {@code building1..building8})
 * @param minFloors    lowest storey count for a building
 * @param maxFloors    highest storey count for a building
 * @param foundation   whether buildings get a pillar/excavation pass under and around them
 * @param citySize     cells per side of the city square; odd values put a building in the middle
 * @param density      chance a non-central building cell is actually built
 * @param streetBlock  the paving material
 * @param streetWidth  paved width of a street cell's arms, in blocks (16 paves the whole cell)
 */
public record LostCityConfig(
		List<String> buildings,
		int minFloors,
		int maxFloors,
		boolean foundation,
		int citySize,
		float density,
		BlockState streetBlock,
		int streetWidth
) {
	/** Storeys are 6 blocks tall in the engine; the ground level is snapped to a multiple of this. */
	public static final int FLOOR_HEIGHT = 6;

	public static final MapCodec<LostCityConfig> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			Codec.STRING.listOf().fieldOf("buildings").forGetter(LostCityConfig::buildings),
			Codec.INT.optionalFieldOf("min_floors", 2).forGetter(LostCityConfig::minFloors),
			Codec.INT.optionalFieldOf("max_floors", 6).forGetter(LostCityConfig::maxFloors),
			Codec.BOOL.optionalFieldOf("foundation", true).forGetter(LostCityConfig::foundation),
			Codec.intRange(1, 16).optionalFieldOf("city_size", 5).forGetter(LostCityConfig::citySize),
			Codec.floatRange(0.0F, 1.0F).optionalFieldOf("density", 0.85F).forGetter(LostCityConfig::density),
			BlockState.CODEC.optionalFieldOf("street_block", Blocks.STONE_BRICKS.defaultBlockState()).forGetter(LostCityConfig::streetBlock),
			Codec.intRange(1, 16).optionalFieldOf("street_width", 16).forGetter(LostCityConfig::streetWidth)
	).apply(instance, LostCityConfig::new));

	/**
	 * The shipped defaults — the same eight buildings and the same 2..6 storeys the feature used,
	 * so the migration changes the mechanism and not the content.
	 */
	public static LostCityConfig defaults() {
		return new LostCityConfig(
				List.of("building1", "building2", "building3", "building4",
						"building5", "building6", "building7", "building8"),
				2, 6, true,
				5,
				0.85F,
				Blocks.STONE_BRICKS.defaultBlockState(),
				16);
	}

	/** The Minecraft-free view of this config that {@link CityLayout} consumes. */
	public CityLayout.Settings layoutSettings() {
		return new CityLayout.Settings(citySize, density, minFloors, maxFloors, buildings.size(), streetWidth);
	}

	/** Building name for a {@link CityLayout.Cell}, tolerating an index from a stale plan. */
	public String buildingName(int index) {
		if (buildings.isEmpty()) {
			return "";
		}
		return buildings.get(Math.floorMod(index, buildings.size()));
	}
}
