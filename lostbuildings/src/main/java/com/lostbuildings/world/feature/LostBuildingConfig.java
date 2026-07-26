package com.lostbuildings.world.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

import java.util.List;

/**
 * Configuration for the Lost Buildings feature (§3 frozen contract).
 *
 * @param buildings  names of buildings eligible to spawn (e.g. building1..building8)
 * @param minFloors  minimum number of floors per building
 * @param maxFloors  maximum number of floors per building
 * @param foundation whether to generate foundations under buildings
 * @param groupMin   minimum buildings per generated group
 * @param groupMax   maximum buildings per generated group
 */
public record LostBuildingConfig(
		List<String> buildings,
		int minFloors,
		int maxFloors,
		boolean foundation,
		int groupMin,
		int groupMax
) implements FeatureConfiguration {
	public static final Codec<LostBuildingConfig> CODEC = RecordCodecBuilder.create((instance) -> instance.group(
			Codec.STRING.listOf().fieldOf("buildings").forGetter(LostBuildingConfig::buildings),
			Codec.INT.fieldOf("min_floors").orElse(2).forGetter(LostBuildingConfig::minFloors),
			Codec.INT.fieldOf("max_floors").orElse(6).forGetter(LostBuildingConfig::maxFloors),
			Codec.BOOL.fieldOf("foundation").orElse(true).forGetter(LostBuildingConfig::foundation),
			Codec.INT.fieldOf("group_min").orElse(2).forGetter(LostBuildingConfig::groupMin),
			Codec.INT.fieldOf("group_max").orElse(5).forGetter(LostBuildingConfig::groupMax)
	).apply(instance, LostBuildingConfig::new));
}
