package com.lostbuildings.world.biome;

import com.lostbuildings.world.feature.ModPlacedFeatures;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BiomeDefaultFeatures;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/**
 * Creates the neutral, plains-like {@code lost_city} biome that hosts the building groups.
 * Shape mirrors desolation's BiomeCreator (26.2 EnvironmentAttributes API).
 */
public class LostCityBiomeCreator {
	@SuppressWarnings("UnnecessaryReturnStatement")
	private LostCityBiomeCreator() {
		return;
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

		// The Lost Buildings feature itself.
		generationSettings.addFeature(GenerationStep.Decoration.SURFACE_STRUCTURES, ModPlacedFeatures.LOST_BUILDING);

		return generationSettings.build();
	}

	private static MobSpawnSettings createSpawnSettings() {
		MobSpawnSettings.Builder spawnSettings = new MobSpawnSettings.Builder();
		BiomeDefaultFeatures.commonSpawns(spawnSettings);
		return spawnSettings.build();
	}
}
