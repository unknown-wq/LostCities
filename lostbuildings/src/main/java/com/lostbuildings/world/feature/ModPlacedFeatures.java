package com.lostbuildings.world.feature;

import com.lostbuildings.LostBuildings;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.data.worldgen.placement.PlacementUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.BiomeFilter;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.RarityFilter;

public class ModPlacedFeatures {
	public static final ResourceKey<PlacedFeature> LOST_BUILDING = createRegistryKey("lost_building");

	private ModPlacedFeatures() {
	}

	public static void bootstrap(BootstrapContext<PlacedFeature> context) {
		HolderGetter<ConfiguredFeature<?, ?>> configuredFeatures = context.lookup(Registries.CONFIGURED_FEATURE);

		// One group is attempted on average once every N chunks; clustering of individual
		// buildings within the group happens inside BuildingPlacement.place() (Agent C).
		PlacementUtils.register(context, LOST_BUILDING,
				configuredFeatures.getOrThrow(ModConfiguredFeatures.LOST_BUILDING),
				RarityFilter.onAverageOnceEvery(20),
				InSquarePlacement.spread(),
				PlacementUtils.HEIGHTMAP,
				BiomeFilter.biome());
	}

	private static ResourceKey<PlacedFeature> createRegistryKey(String name) {
		return ResourceKey.create(Registries.PLACED_FEATURE, Identifier.fromNamespaceAndPath(LostBuildings.MOD_ID, name));
	}
}
