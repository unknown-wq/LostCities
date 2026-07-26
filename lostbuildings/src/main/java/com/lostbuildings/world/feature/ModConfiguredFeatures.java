package com.lostbuildings.world.feature;

import com.lostbuildings.LostBuildings;
import com.lostbuildings.registry.ModFeatures;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.data.worldgen.features.FeatureUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;

import java.util.List;

public final class ModConfiguredFeatures {
	public static final ResourceKey<ConfiguredFeature<?, ?>> LOST_BUILDING = createRegistryKey("lost_building");

	private ModConfiguredFeatures() {
	}

	public static void bootstrap(BootstrapContext<ConfiguredFeature<?, ?>> context) {
		FeatureUtils.register(context, LOST_BUILDING, ModFeatures.LOST_BUILDING, new LostBuildingConfig(
				List.of("building1", "building2", "building3", "building4",
						"building5", "building6", "building7", "building8"),
				2,   // minFloors
				6,   // maxFloors (multi-story: engine randomizes floors in [min,max] per building)
				true, // foundation
				2,   // groupMin
				5    // groupMax
		));
	}

	private static ResourceKey<ConfiguredFeature<?, ?>> createRegistryKey(String name) {
		return ResourceKey.create(Registries.CONFIGURED_FEATURE, Identifier.fromNamespaceAndPath(LostBuildings.MOD_ID, name));
	}
}
