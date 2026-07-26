package com.lostbuildings.world.gen;

import com.lostbuildings.world.biome.ModBiomes;
import com.terraformersmc.biolith.api.biome.BiomePlacement;
import net.minecraft.world.level.biome.Biomes;

/**
 * Injects the {@code lost_city} biome into overworld generation via Biolith. Mirrors
 * desolation's DesolationBiolithGeneration (replaceOverworld on a couple of vanilla biomes).
 */
public class BiolithGeneration {
	/**
	 * Fraction of the targeted vanilla biome replaced with lost_city.
	 *
	 * <p>Kept low on purpose: buildings are only attempted in roughly 1 chunk in 20 inside the
	 * biome ({@code ModPlacedFeatures} rarity filter), so a high replacement chance mostly buys
	 * repainted biome map rather than content. At 0.05 a twentieth of plains/forest/savanna
	 * carries the biome instead of nearly a sixth.
	 */
	private static final double LOST_CITY_CHANCE = 0.05D;

	private BiolithGeneration() {
	}

	public static void init() {
		BiomePlacement.replaceOverworld(Biomes.PLAINS, ModBiomes.LOST_CITY, LOST_CITY_CHANCE);
		BiomePlacement.replaceOverworld(Biomes.FOREST, ModBiomes.LOST_CITY, LOST_CITY_CHANCE);
		BiomePlacement.replaceOverworld(Biomes.SAVANNA, ModBiomes.LOST_CITY, LOST_CITY_CHANCE);
	}
}
