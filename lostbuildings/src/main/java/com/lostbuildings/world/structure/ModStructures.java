package com.lostbuildings.world.structure;

import com.lostbuildings.LostBuildings;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;

/**
 * The {@code Registries.STRUCTURE} entries this mod ships, as datagen sources.
 *
 * <p>The generated JSON is what {@code /locate structure lostbuildings:lost_city} resolves and what
 * a datapack overrides to retune a city (see {@link LostCityConfig} for the knobs).
 */
public final class ModStructures {

	public static final ResourceKey<Structure> LOST_CITY = ResourceKey.create(Registries.STRUCTURE,
			Identifier.fromNamespaceAndPath(LostBuildings.MOD_ID, "lost_city"));

	private ModStructures() {
	}

	public static void bootstrap(BootstrapContext<Structure> context) {
		HolderGetter<Biome> biomes = context.lookup(Registries.BIOME);

		context.register(LOST_CITY, new LostCityStructure(
				new Structure.StructureSettings.Builder(biomes.getOrThrow(ModStructureTags.HAS_LOST_CITY))
						.generationStep(GenerationStep.Decoration.SURFACE_STRUCTURES)
						// NONE, not BEARD_*: the city does its own terrain fitting in Foundation
						// (pillar down, excavate up), which is the behaviour the feature had and
						// which wave 1 is explicitly not allowed to change.
						.terrainAdapation(TerrainAdjustment.NONE)
						.build(),
				LostCityConfig.defaults()));
	}
}
