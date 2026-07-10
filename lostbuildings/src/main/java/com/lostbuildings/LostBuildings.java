package com.lostbuildings;

import com.lostbuildings.engine.AssetLoader;
import com.lostbuildings.engine.Assets;
import com.lostbuildings.engine.BuildingEngine;
import com.lostbuildings.registry.ModFeatures;
import com.lostbuildings.world.gen.BiolithGeneration;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class LostBuildings implements ModInitializer {
	public static final String MOD_ID = "lostbuildings";
	public static final Logger LOGGER = LogManager.getLogger(StringUtils.capitalize(MOD_ID));

	/**
	 * Loaded assets (buildings/parts/palettes/...). Populated at SERVER_STARTING because the
	 * ResourceManager (with datapacks applied) is only available then; earlier callbacks fire
	 * too early. Owned/typed by Agent B (com.lostbuildings.engine).
	 */
	public static Assets ASSETS;

	/** Stateless building generation engine (owner: Agent B). */
	public static final BuildingEngine ENGINE = new BuildingEngine();

	@Override
	public void onInitialize() {
		ModFeatures.init();
		BiolithGeneration.init();

		// Load assets once the server's ResourceManager is available (datapacks applied).
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			ASSETS = AssetLoader.load(server.getResourceManager());
			LOGGER.info("Lost Buildings assets loaded.");
		});

		LOGGER.info("Lost Buildings initialized!");
	}
}
