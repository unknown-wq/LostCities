package com.lostbuildings.registry;

import com.lostbuildings.LostBuildings;
import com.lostbuildings.world.structure.LostCityStructure;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.structure.StructureType;

/**
 * Registers the mod's {@code StructureType}s — the codec dispatch keys that let a datapack write
 * {@code "type": "lostbuildings:lost_city"} in a structure JSON.
 *
 * <p>{@link StructureType} is a single-method interface returning the codec, so the type itself is
 * a lambda; the class it dispatches to lives in {@code world/structure}.
 */
public final class ModStructureTypes {

	public static final StructureType<LostCityStructure> LOST_CITY = () -> LostCityStructure.CODEC;

	private ModStructureTypes() {
	}

	public static void init() {
		Registry.register(BuiltInRegistries.STRUCTURE_TYPE,
				Identifier.fromNamespaceAndPath(LostBuildings.MOD_ID, "lost_city"), LOST_CITY);
	}
}
