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
	 * <p><b>Do not lower this to "save" content.</b> It was once cut to 0.05 on the reasoning that
	 * the rarity filter only attempts a group in 1 chunk in 20 anyway, so extra biome area buys a
	 * repainted map rather than buildings. That reasoning is backwards, and it stopped the mod
	 * generating anything at all.
	 *
	 * <p>Biolith does not carve a disc of the requested size: it thresholds a four-octave simplex
	 * field (wavelengths 256/64/16/4 biome cells, i.e. down to ~16 blocks) and keeps the top
	 * {@code chance} of it. Lowering the threshold does not shrink one patch evenly — it strands the
	 * surviving area on the peaks of the <em>finest</em> octave, so the biome stops being blobs and
	 * becomes slivers, many of them narrower than a chunk.
	 *
	 * <p>Sub-chunk patches then interact badly with the placement chain, because
	 * {@code InSquarePlacement} picks a random column in the chunk <em>before</em>
	 * {@code BiomeFilter} tests it: the thinner the patch, the more often that column lands outside
	 * the biome and the whole attempt is discarded. Biome area therefore multiplies the placement
	 * rate twice over — once by how many chunks touch the biome at all, and again by how likely a
	 * random column inside such a chunk is to hit it.
	 */
	private static final double LOST_CITY_CHANCE = 0.15D;

	private BiolithGeneration() {
	}

	public static void init() {
		BiomePlacement.replaceOverworld(Biomes.PLAINS, ModBiomes.LOST_CITY, LOST_CITY_CHANCE);
		BiomePlacement.replaceOverworld(Biomes.FOREST, ModBiomes.LOST_CITY, LOST_CITY_CHANCE);
		BiomePlacement.replaceOverworld(Biomes.SAVANNA, ModBiomes.LOST_CITY, LOST_CITY_CHANCE);
	}
}
