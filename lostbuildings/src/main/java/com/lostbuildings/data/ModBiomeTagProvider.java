package com.lostbuildings.data;

import com.lostbuildings.world.biome.ModBiomes;
import com.lostbuildings.world.structure.ModStructureTags;
import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricTagsProvider;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;

import java.util.concurrent.CompletableFuture;

/**
 * Generates {@code #lostbuildings:has_structure/lost_city} — the biome tag the lost city structure
 * keys off. Shipping it as a tag rather than an inline biome list is what makes the structure's
 * target biomes editable from a datapack.
 */
public class ModBiomeTagProvider extends FabricTagsProvider<Biome> {

	public ModBiomeTagProvider(FabricPackOutput output, CompletableFuture<HolderLookup.Provider> registriesFuture) {
		super(output, Registries.BIOME, registriesFuture);
	}

	@Override
	protected void addTags(HolderLookup.Provider registries) {
		builder(ModStructureTags.HAS_LOST_CITY).add(ModBiomes.LOST_CITY);
	}
}
