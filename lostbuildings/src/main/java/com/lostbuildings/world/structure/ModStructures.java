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

	public static final ResourceKey<Structure> LOST_CITY = key("lost_city");
	/** Cabins and radio towers, on land (PORT #2). */
	public static final ResourceKey<Structure> SCATTERED = key("scattered");
	/** The oil rig — the only structure in the mod that stands in open water (PORT #2). */
	public static final ResourceKey<Structure> OIL_RIG = key("oil_rig");

	private ModStructures() {
	}

	private static ResourceKey<Structure> key(String name) {
		return ResourceKey.create(Registries.STRUCTURE, Identifier.fromNamespaceAndPath(LostBuildings.MOD_ID, name));
	}

	public static void bootstrap(BootstrapContext<Structure> context) {
		HolderGetter<Biome> biomes = context.lookup(Registries.BIOME);

		context.register(LOST_CITY, new LostCityStructure(
				new Structure.StructureSettings.Builder(biomes.getOrThrow(ModStructureTags.HAS_LOST_CITY))
						.generationStep(GenerationStep.Decoration.SURFACE_STRUCTURES)
						// NONE, not BEARD_*: the city does its own terrain fitting in Foundation
						// (pillar down, excavate up), which is the behaviour the feature had and
						// which the migration was explicitly not allowed to change.
						.terrainAdapation(TerrainAdjustment.NONE)
						.build(),
				LostCityConfig.defaults()));

		context.register(SCATTERED, new ScatteredStructure(
				new Structure.StructureSettings.Builder(biomes.getOrThrow(ModStructureTags.HAS_SCATTERED))
						.generationStep(GenerationStep.Decoration.SURFACE_STRUCTURES)
						// A lone hut on a hillside is exactly what vanilla's beard was made for, and
						// unlike a city it has no shared ground level that the beard could contradict.
						.terrainAdapation(TerrainAdjustment.BEARD_THIN)
						.build(),
				ScatteredStructure.ScatteredConfig.land()));

		context.register(OIL_RIG, new ScatteredStructure(
				new Structure.StructureSettings.Builder(biomes.getOrThrow(ModStructureTags.HAS_OIL_RIG))
						.generationStep(GenerationStep.Decoration.SURFACE_STRUCTURES)
						.terrainAdapation(TerrainAdjustment.NONE)
						.build(),
				ScatteredStructure.ScatteredConfig.ocean()));
	}
}
