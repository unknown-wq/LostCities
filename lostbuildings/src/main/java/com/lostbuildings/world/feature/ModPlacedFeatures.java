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
		// buildings within the group happens inside BuildingPlacement.place().
		//
		// N was 20 back when a firing built the whole group — up to five buildings at once. It no
		// longer does: CellLattice hands a group the cells it exclusively owns, which is one cell
		// in expectation, and no cell at all in (4/5)^5 ≈ 33% of firings. Leaving N at 20 after
		// that change quietly cut the building count by about a factor of five, on top of the
		// biome-area cut made in the same commit. 6 restores roughly the density the 20 was
		// chosen for; it is a tuning knob, not an invariant.
		PlacementUtils.register(context, LOST_BUILDING,
				configuredFeatures.getOrThrow(ModConfiguredFeatures.LOST_BUILDING),
				RarityFilter.onAverageOnceEvery(6),
				InSquarePlacement.spread(),
				PlacementUtils.HEIGHTMAP,
				BiomeFilter.biome());
	}

	private static ResourceKey<PlacedFeature> createRegistryKey(String name) {
		return ResourceKey.create(Registries.PLACED_FEATURE, Identifier.fromNamespaceAndPath(LostBuildings.MOD_ID, name));
	}
}
