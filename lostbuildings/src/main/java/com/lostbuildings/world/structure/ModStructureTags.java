package com.lostbuildings.world.structure;

import com.lostbuildings.LostBuildings;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

/**
 * Biome tags this mod's structures key off.
 *
 * <p>Naming follows vanilla's {@code #minecraft:has_structure/<name>} convention. Using a tag
 * rather than a hard-coded biome list is the datapack-configurability half of IMPROVEMENTS #10 that
 * matters most: adding {@code lost_city} to a new biome is a one-line tag file, and removing it
 * needs no code at all.
 */
public final class ModStructureTags {

	public static final TagKey<Biome> HAS_LOST_CITY = TagKey.create(Registries.BIOME,
			Identifier.fromNamespaceAndPath(LostBuildings.MOD_ID, "has_structure/lost_city"));

	private ModStructureTags() {
	}
}
