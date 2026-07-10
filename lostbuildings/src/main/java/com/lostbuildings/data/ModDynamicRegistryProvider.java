package com.lostbuildings.data;

import com.lostbuildings.LostBuildings;
import com.lostbuildings.world.biome.ModBiomes;
import com.lostbuildings.world.feature.ModConfiguredFeatures;
import com.lostbuildings.world.feature.ModPlacedFeatures;
import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricDynamicRegistryProvider;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistrySetBuilder;
import net.minecraft.core.registries.Registries;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class ModDynamicRegistryProvider extends FabricDynamicRegistryProvider {
	protected ModDynamicRegistryProvider(FabricPackOutput output, CompletableFuture<HolderLookup.Provider> registriesFuture) {
		super(output, registriesFuture);
	}

	public static void buildRegistry(RegistrySetBuilder registryBuilder) {
		registryBuilder.add(Registries.CONFIGURED_FEATURE, ModConfiguredFeatures::bootstrap);
		registryBuilder.add(Registries.PLACED_FEATURE, ModPlacedFeatures::bootstrap);
		registryBuilder.add(Registries.BIOME, ModBiomes::bootstrap);
	}

	@Override
	public void configure(HolderLookup.Provider registries, Entries entries) {
		addAll(entries, registries.lookupOrThrow(Registries.CONFIGURED_FEATURE), LostBuildings.MOD_ID);
		addAll(entries, registries.lookupOrThrow(Registries.PLACED_FEATURE), LostBuildings.MOD_ID);
		addAll(entries, registries.lookupOrThrow(Registries.BIOME), LostBuildings.MOD_ID);
	}

	@Override
	public String getName() {
		return "Lost Buildings Dynamic Registries";
	}

	/**
	 * Version of FabricDynamicRegistryProvider.Entries.addAll() using a specified mod ID.
	 */
	@SuppressWarnings("UnusedReturnValue")
	public <T> List<Holder<T>> addAll(Entries entries, HolderLookup.RegistryLookup<T> registry, String modId) {
		return registry.listElementIds()
				.filter(registryKey -> registryKey.identifier().getNamespace().equals(modId))
				.map(key -> entries.add(registry, key))
				.toList();
	}
}
