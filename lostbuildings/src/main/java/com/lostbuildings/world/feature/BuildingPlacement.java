package com.lostbuildings.world.feature;

import com.lostbuildings.engine.Assets;
import com.lostbuildings.engine.BuildingEngine;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;

/**
 * Strategy for actually placing building(s) when the {@link LostBuildingFeature} fires.
 *
 * Agent A ships a stub implementation ({@link StubBuildingPlacement}) that places a single
 * marker block. Agent C ships the real {@code GroupBuildingPlacement}. Agent D swaps them.
 *
 * Frozen §3 contract signature — do not change.
 */
public interface BuildingPlacement {
	boolean place(FeaturePlaceContext<LostBuildingConfig> ctx, Assets assets, BuildingEngine engine);
}
