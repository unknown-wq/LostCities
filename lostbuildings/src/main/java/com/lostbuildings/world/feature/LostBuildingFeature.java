package com.lostbuildings.world.feature;

import com.lostbuildings.LostBuildings;
import com.mojang.serialization.Codec;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;

/**
 * The Lost Buildings worldgen feature. Placement is delegated to a {@link BuildingPlacement}
 * strategy so the registration/wiring (Agent A) is decoupled from the real placement (Agent C).
 */
public class LostBuildingFeature extends Feature<LostBuildingConfig> {
	/** Swapped to GroupBuildingPlacement by Agent D once the engine + data exist. */
	public static BuildingPlacement PLACEMENT = new StubBuildingPlacement();

	public LostBuildingFeature(Codec<LostBuildingConfig> codec) {
		super(codec);
	}

	@Override
	public boolean place(FeaturePlaceContext<LostBuildingConfig> context) {
		return PLACEMENT.place(context, LostBuildings.ASSETS, LostBuildings.ENGINE);
	}
}
