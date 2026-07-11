package com.lostbuildings.world.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

import java.util.List;

/**
 * Configuration for the Lost Buildings feature (§3 frozen contract, extended for variety).
 *
 * @param buildings          names of standalone single-chunk buildings eligible to spawn
 *                           (e.g. building1..building8)
 * @param minFloors          minimum number of floors per building
 * @param maxFloors          maximum number of floors per building
 * @param foundation         whether to generate foundations under buildings
 * @param groupMin           minimum buildings per generated group
 * @param groupMax           maximum buildings per generated group
 * @param spacing            spacing (blocks) between building sites within a group
 * @param landmarks          rare "landmark" standalone buildings (e.g. cabin, radiotower). A
 *                           standalone slot substitutes one of these with probability
 *                           {@code landmarkChance}. Empty list disables landmarks.
 * @param landmarkChance     probability [0,1] that a given standalone slot becomes a landmark
 * @param multibuildings     family prefixes of 2x2 multi-buildings (e.g. town, shopping,
 *                           shopping_open, library, center, oilrig). A full building of family
 *                           {@code fam} is the four quadrants {@code fam00/01/10/11}. Empty list
 *                           disables multi-buildings.
 * @param multibuildingChance probability [0,1] that a group places a 2x2 multi-building
 */
public record LostBuildingConfig(
		List<String> buildings,
		int minFloors,
		int maxFloors,
		boolean foundation,
		int groupMin,
		int groupMax,
		int spacing,
		List<String> landmarks,
		float landmarkChance,
		List<String> multibuildings,
		float multibuildingChance
) implements FeatureConfiguration {
	public static final Codec<LostBuildingConfig> CODEC = RecordCodecBuilder.create((instance) -> instance.group(
			Codec.STRING.listOf().fieldOf("buildings").forGetter(LostBuildingConfig::buildings),
			Codec.INT.fieldOf("min_floors").orElse(2).forGetter(LostBuildingConfig::minFloors),
			Codec.INT.fieldOf("max_floors").orElse(6).forGetter(LostBuildingConfig::maxFloors),
			Codec.BOOL.fieldOf("foundation").orElse(true).forGetter(LostBuildingConfig::foundation),
			Codec.INT.fieldOf("group_min").orElse(2).forGetter(LostBuildingConfig::groupMin),
			Codec.INT.fieldOf("group_max").orElse(5).forGetter(LostBuildingConfig::groupMax),
			Codec.INT.fieldOf("spacing").orElse(24).forGetter(LostBuildingConfig::spacing),
			// New variety fields — all default to "off" so pre-existing configs decode unchanged.
			Codec.STRING.listOf().optionalFieldOf("landmarks", List.of()).forGetter(LostBuildingConfig::landmarks),
			Codec.FLOAT.optionalFieldOf("landmark_chance", 0.0f).forGetter(LostBuildingConfig::landmarkChance),
			Codec.STRING.listOf().optionalFieldOf("multibuildings", List.of()).forGetter(LostBuildingConfig::multibuildings),
			Codec.FLOAT.optionalFieldOf("multibuilding_chance", 0.0f).forGetter(LostBuildingConfig::multibuildingChance)
	).apply(instance, LostBuildingConfig::new));
}
