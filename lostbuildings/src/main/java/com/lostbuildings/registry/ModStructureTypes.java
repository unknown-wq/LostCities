package com.lostbuildings.registry;

import com.lostbuildings.LostBuildings;
import com.lostbuildings.world.structure.LostCityStructure;
import com.lostbuildings.world.structure.ScatteredStructure;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.structure.StructureType;

/**
 * Registers the mod's {@code StructureType}s — the codec dispatch keys that let a datapack write
 * {@code "type": "lostbuildings:lost_city"} in a structure JSON.
 *
 * <p>{@link StructureType} is a single-method interface returning the codec, so the type itself is
 * a lambda; the classes it dispatches to live in {@code world/structure}.
 *
 * <p>{@code scattered} is one type behind two registered structures (a land one and an ocean one):
 * what separates a cabin from an oil rig is the biome list and a handful of datapack numbers, not
 * code.
 */
public final class ModStructureTypes {

	public static final StructureType<LostCityStructure> LOST_CITY = () -> LostCityStructure.CODEC;
	public static final StructureType<ScatteredStructure> SCATTERED = () -> ScatteredStructure.CODEC;

	private ModStructureTypes() {
	}

	public static void init() {
		register("lost_city", LOST_CITY);
		register("scattered", SCATTERED);
	}

	private static void register(String name, StructureType<?> type) {
		Registry.register(BuiltInRegistries.STRUCTURE_TYPE,
				Identifier.fromNamespaceAndPath(LostBuildings.MOD_ID, name), type);
	}
}
