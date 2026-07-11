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

	@SuppressWarnings("UnnecessaryReturnStatement")
	private ModConfiguredFeatures() {
		return;
	}

	public static void bootstrap(BootstrapContext<ConfiguredFeature<?, ?>> context) {
		FeatureUtils.register(context, LOST_BUILDING, ModFeatures.LOST_BUILDING, new LostBuildingConfig(
				List.of("building1", "building2", "building3", "building4",
						"building5", "building6", "building7", "building8"),
				2,   // minFloors
				6,   // maxFloors (multi-story: engine randomizes floors in [min,max] per building)
				true, // foundation
				2,   // groupMin
				5,   // groupMax
				24,  // spacing (>footprint so streets have gaps to run through)
				// Landmarks: rare standalone variety (a cabin in the weeds, a lone radio tower).
				List.of("cabin", "radiotower"),
				0.15f, // landmarkChance — ~1 in 7 standalone slots becomes a landmark
				// 2x2 multi-buildings assembled from four quadrant JSONs each.
				List.of("town", "shopping", "shopping_open", "library", "center", "oilrig"),
				0.25f  // multibuildingChance — ~1 in 4 groups anchors a big 2x2 landmark building
		));
	}

	private static ResourceKey<ConfiguredFeature<?, ?>> createRegistryKey(String name) {
		return ResourceKey.create(Registries.CONFIGURED_FEATURE, Identifier.fromNamespaceAndPath(LostBuildings.MOD_ID, name));
	}
}
