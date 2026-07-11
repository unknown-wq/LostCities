package com.lostbuildings.world.biome;

import com.lostbuildings.LostBuildings;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;

public class ModBiomes {
	public static final ResourceKey<Biome> LOST_CITY = ResourceKey.create(Registries.BIOME,
			Identifier.fromNamespaceAndPath(LostBuildings.MOD_ID, "lost_city"));

	@SuppressWarnings("UnnecessaryReturnStatement")
	private ModBiomes() {
		return;
	}

	public static void bootstrap(BootstrapContext<Biome> context) {
		context.register(LOST_CITY, LostCityBiomeCreator.createLostCity(context));
	}
}
