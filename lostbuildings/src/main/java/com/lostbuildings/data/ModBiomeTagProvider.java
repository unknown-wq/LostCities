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
 *
 * <p><b>Why the vanilla tags go in as {@code addOptionalTag}.</b> {@code TagsProvider} verifies every
 * tag reference against the tags <em>this</em> provider generates plus its parent provider's, and a
 * mod's tag provider has no vanilla parent — so a plain {@code addTag(BiomeTags.IS_FOREST)} fails
 * datagen with "missing following references" even though the tag obviously exists at runtime.
 * Optional references are the standard way out: they emit {@code "required": false}, and since every
 * one of these is a vanilla tag that is always loaded, the resolved biome set is identical.
 */
public class ModBiomeTagProvider extends FabricTagsProvider<Biome> {

	public ModBiomeTagProvider(FabricPackOutput output, CompletableFuture<HolderLookup.Provider> registriesFuture) {
		super(output, Registries.BIOME, registriesFuture);
	}

	@Override
	protected void addTags(HolderLookup.Provider registries) {
		builder(ModStructureTags.HAS_LOST_CITY).add(ModBiomes.LOST_CITY);

		builder(ModStructureTags.HAS_SCATTERED)
				.addOptionalTag(BiomeTags.IS_FOREST)
				.addOptionalTag(BiomeTags.IS_TAIGA)
				.addOptionalTag(BiomeTags.IS_JUNGLE)
				.addOptionalTag(BiomeTags.IS_SAVANNA)
				.addOptionalTag(BiomeTags.IS_BADLANDS)
				.addOptionalTag(BiomeTags.IS_HILL)
				.addOptionalTag(BiomeTags.IS_MOUNTAIN)
				.addOptionalTag(BiomeTags.HAS_VILLAGE_PLAINS)
				.addOptionalTag(BiomeTags.HAS_VILLAGE_DESERT)
				.addOptionalTag(BiomeTags.HAS_VILLAGE_SAVANNA)
				.addOptionalTag(BiomeTags.HAS_VILLAGE_SNOWY)
				.addOptionalTag(BiomeTags.HAS_VILLAGE_TAIGA);

		builder(ModStructureTags.HAS_OIL_RIG).addOptionalTag(BiomeTags.IS_DEEP_OCEAN);
	}
}
