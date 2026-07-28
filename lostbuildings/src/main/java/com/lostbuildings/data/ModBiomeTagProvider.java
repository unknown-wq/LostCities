package com.lostbuildings.data;

import com.lostbuildings.world.biome.ModBiomes;
import com.lostbuildings.world.structure.ModStructureTags;
import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricTagsProvider;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;

import java.util.concurrent.CompletableFuture;

/**
 * Generates the {@code #lostbuildings:has_structure/*} biome tags the mod's structures key off.
 * Shipping them as tags rather than inline biome lists is what makes the structures' target biomes
 * editable from a datapack.
 *
 * <ul>
 *   <li>{@code lost_city} — only the mod's own biome, which Biolith paints over the overworld;</li>
 *   <li>{@code scattered} — dry land, expressed as the vanilla terrain tags plus the five
 *       {@code has_structure/village_*} tags. Those five are the cheapest honest definition of
 *       "somewhere a person could plausibly have built a hut": vanilla already curates them for
 *       exactly that, and between them they pick up plains, deserts, savannas, taigas and the snowy
 *       biomes without naming a single biome by hand;</li>
 *   <li>{@code oil_rig} — deep ocean only, the one place the shipped rig makes sense.</li>
 * </ul>
 */
public class ModBiomeTagProvider extends FabricTagsProvider<Biome> {

	public ModBiomeTagProvider(FabricPackOutput output, CompletableFuture<HolderLookup.Provider> registriesFuture) {
		super(output, Registries.BIOME, registriesFuture);
	}

	@Override
	protected void addTags(HolderLookup.Provider registries) {
		builder(ModStructureTags.HAS_LOST_CITY).add(ModBiomes.LOST_CITY);

		builder(ModStructureTags.HAS_SCATTERED)
				.addTag(BiomeTags.IS_FOREST)
				.addTag(BiomeTags.IS_TAIGA)
				.addTag(BiomeTags.IS_JUNGLE)
				.addTag(BiomeTags.IS_SAVANNA)
				.addTag(BiomeTags.IS_BADLANDS)
				.addTag(BiomeTags.IS_HILL)
				.addTag(BiomeTags.IS_MOUNTAIN)
				.addTag(BiomeTags.HAS_VILLAGE_PLAINS)
				.addTag(BiomeTags.HAS_VILLAGE_DESERT)
				.addTag(BiomeTags.HAS_VILLAGE_SAVANNA)
				.addTag(BiomeTags.HAS_VILLAGE_SNOWY)
				.addTag(BiomeTags.HAS_VILLAGE_TAIGA);

		builder(ModStructureTags.HAS_OIL_RIG).addTag(BiomeTags.IS_DEEP_OCEAN);
	}
}
