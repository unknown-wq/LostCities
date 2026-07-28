package com.lostbuildings.world.biome;

import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BiomeDefaultFeatures;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/**
 * Creates the neutral, plains-like {@code lost_city} biome that hosts the building groups.
 * Shape mirrors desolation's BiomeCreator (26.2 EnvironmentAttributes API).
 */
public class LostCityBiomeCreator {
	private LostCityBiomeCreator() {
	}

	public static Biome createLostCity(BootstrapContext<Biome> context) {
		return new Biome.BiomeBuilder()
				.generationSettings(createGenerationSettings(context))
				.mobSpawnSettings(createSpawnSettings())
				.hasPrecipitation(true)
				.temperature(0.8F)
				.downfall(0.4F)
				.specialEffects((new BiomeSpecialEffects.Builder())
						.waterColor(0x3f76e4)
						.build())
				.setAttribute(EnvironmentAttributes.WATER_FOG_COLOR, 0x050533)
				.setAttribute(EnvironmentAttributes.FOG_COLOR, 0xc0d8ff)
				.setAttribute(EnvironmentAttributes.SKY_COLOR, 0x78a7ff)
				.build();
	}

	private static BiomeGenerationSettings createGenerationSettings(BootstrapContext<Biome> context) {
		HolderGetter<ConfiguredWorldCarver<?>> configuredCarvers = context.lookup(Registries.CONFIGURED_CARVER);
		HolderGetter<PlacedFeature> placedFeatures = context.lookup(Registries.PLACED_FEATURE);

		BiomeGenerationSettings.Builder generationSettings = new BiomeGenerationSettings.Builder(placedFeatures, configuredCarvers);

		// Neutral, plains-like base terrain.
		BiomeDefaultFeatures.addDefaultCarversAndLakes(generationSettings);
		BiomeDefaultFeatures.addDefaultMonsterRoom(generationSettings);
		BiomeDefaultFeatures.addDefaultUndergroundVariety(generationSettings);
		BiomeDefaultFeatures.addDefaultOres(generationSettings);
		BiomeDefaultFeatures.addDefaultSoftDisks(generationSettings);
		BiomeDefaultFeatures.addDefaultSprings(generationSettings);

		// Surface life. Without these the biome is a bald wasteland: no grass, no flowers, no
		// trees at all. Same set vanilla plains uses, so a lost_city patch blends into the
		// plains / forest / savanna it replaces instead of reading as a scorched hole.
		BiomeDefaultFeatures.addPlainGrass(generationSettings);
		BiomeDefaultFeatures.addDefaultFlowers(generationSettings);
		BiomeDefaultFeatures.addPlainVegetation(generationSettings);
		BiomeDefaultFeatures.addDefaultMushrooms(generationSettings);
		BiomeDefaultFeatures.addDefaultExtraVegetation(generationSettings, true);

		// No mod feature is added here any more. Buildings arrive through the
		// lostbuildings:lost_city *structure* (see world/structure/), which the biome opts into by
		// carrying the #lostbuildings:has_structure/lost_city tag rather than by listing a feature:
		// structures are collected in the structure_starts step and written chunk by chunk, which is
		// what lifted the 48x48 write window the feature was confined to.

		return generationSettings.build();
	}

	private static MobSpawnSettings createSpawnSettings() {
		MobSpawnSettings.Builder spawnSettings = new MobSpawnSettings.Builder();
		// Passive animals first (cows/sheep/pigs/chickens), then the common ambient + monster set.
		// commonSpawns() alone left the biome without a single peaceful mob.
		BiomeDefaultFeatures.farmAnimals(spawnSettings);
		BiomeDefaultFeatures.commonSpawns(spawnSettings);
		return spawnSettings.build();
	}
}
