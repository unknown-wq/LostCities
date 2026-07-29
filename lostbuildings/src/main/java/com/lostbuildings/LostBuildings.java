package com.lostbuildings;

import com.lostbuildings.command.LostCityCommand;
import com.lostbuildings.engine.AssetLoader;
import com.lostbuildings.engine.Assets;
import com.lostbuildings.engine.BuildingEngine;
import com.lostbuildings.registry.ModStructurePieceTypes;
import com.lostbuildings.registry.ModStructureTypes;
import com.lostbuildings.world.gen.BiolithGeneration;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.reloader.SimpleReloadListener;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class LostBuildings implements ModInitializer {
	public static final String MOD_ID = "lostbuildings";
	public static final Logger LOGGER = LogManager.getLogger(StringUtils.capitalize(MOD_ID));

	/**
	 * Loaded assets (buildings/parts/palettes/...). Populated from the server-data reload listener,
	 * so it also follows {@code /reload} and datapack changes. Owned/typed by Agent B
	 * (com.lostbuildings.engine).
	 *
	 * <p>{@code volatile}: written on the server thread, read from every worldgen worker thread. The
	 * volatile store is also what safely publishes the whole (immutable) asset graph behind it.
	 */
	public static volatile Assets ASSETS;

	/** Building generation engine (owner: Agent B). Instance-based — built after ASSETS loads. */
	public static volatile BuildingEngine ENGINE;

	@Override
	public void onInitialize() {
		ModStructureTypes.init();
		ModStructurePieceTypes.init();
		BiolithGeneration.init();
		LostCityCommand.init();

		// Reload assets on every datapack (re)load, i.e. also on /reload — not just once at startup.
		// Parsing happens in prepare() (worker thread), publishing in apply() (server thread).
		ResourceLoader.get(PackType.SERVER_DATA).registerReloadListener(
				Identifier.fromNamespaceAndPath(MOD_ID, "assets"),
				new SimpleReloadListener<Assets>() {
					@Override
					protected Assets prepare(PreparableReloadListener.SharedState state) {
						return AssetLoader.load(state.resourceManager());
					}

					@Override
					protected void apply(Assets assets, PreparableReloadListener.SharedState state) {
						publishAssets(assets);
					}
				});

		// Belt and braces: if the reload listener has not run for some reason (e.g. another mod
		// swallowed the reload), make sure the assets exist by the time the world starts ticking.
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			if (ASSETS == null) {
				publishAssets(AssetLoader.load(server.getResourceManager()));
			}
		});

		LOGGER.info("Lost Buildings initialized!");
	}

	private static void publishAssets(Assets assets) {
		BuildingEngine engine = new BuildingEngine(assets);
		// Publish the engine first: it only ever reads the assets it was handed.
		ENGINE = engine;
		ASSETS = assets;
		LOGGER.info("Lost Buildings assets loaded.");
	}
}
