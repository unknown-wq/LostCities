package com.lostbuildings.registry;

import com.lostbuildings.LostBuildings;
import com.lostbuildings.world.feature.LostBuildingConfig;
import com.lostbuildings.world.feature.LostBuildingFeature;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.feature.Feature;

public class ModFeatures {
	public static final Feature<LostBuildingConfig> LOST_BUILDING = new LostBuildingFeature(LostBuildingConfig.CODEC);

	@SuppressWarnings("UnnecessaryReturnStatement")
	private ModFeatures() {
		return;
	}

	public static void init() {
		register("lost_building", LOST_BUILDING);
	}

	private static void register(String name, Feature<?> feature) {
		Registry.register(BuiltInRegistries.FEATURE, Identifier.fromNamespaceAndPath(LostBuildings.MOD_ID, name), feature);
	}
}
