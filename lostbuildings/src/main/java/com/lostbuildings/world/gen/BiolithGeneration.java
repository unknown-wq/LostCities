package com.lostbuildings.world.gen;

import com.lostbuildings.world.biome.ModBiomes;
import com.terraformersmc.biolith.api.biome.BiomePlacement;
import net.minecraft.world.level.biome.Biomes;

/**
 * Injects the {@code lost_city} biome into overworld generation via Biolith. Mirrors
 * desolation's DesolationBiolithGeneration (replaceOverworld on a couple of vanilla biomes).
 */
public class BiolithGeneration {
	/** Fraction of the targeted vanilla biome replaced with lost_city. */
	private static final double LOST_CITY_CHANCE = 0.15D;

	@SuppressWarnings("UnnecessaryReturnStatement")
	private BiolithGeneration() {
		return;
	}

	public static void init() {
		BiomePlacement.replaceOverworld(Biomes.PLAINS, ModBiomes.LOST_CITY, LOST_CITY_CHANCE);
		BiomePlacement.replaceOverworld(Biomes.FOREST, ModBiomes.LOST_CITY, LOST_CITY_CHANCE);
		BiomePlacement.replaceOverworld(Biomes.SAVANNA, ModBiomes.LOST_CITY, LOST_CITY_CHANCE);
	}
}
