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
 *
 * <p>The scattered structures get two tags rather than one because their whole difference is where
 * they stand: a cabin belongs anywhere with ground, an oil rig only in deep ocean, and a structure's
 * biome list is the only place that distinction can live.
 */
public final class ModStructureTags {

	public static final TagKey<Biome> HAS_LOST_CITY = create("has_structure/lost_city");
	public static final TagKey<Biome> HAS_SCATTERED = create("has_structure/scattered");
	public static final TagKey<Biome> HAS_OIL_RIG = create("has_structure/oil_rig");

	private ModStructureTags() {
	}

	private static TagKey<Biome> create(String path) {
		return TagKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath(LostBuildings.MOD_ID, path));
	}
}
