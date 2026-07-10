package com.lostbuildings.world.feature;

import com.lostbuildings.engine.Assets;
import com.lostbuildings.engine.BuildingEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;

/**
 * Stub placement: ignores {@code assets}/{@code engine} and places a single marker block at the
 * world-surface heightmap so the feature is exercisable before the real engine (Agent B) and
 * group placement (Agent C) exist. Agent D replaces this with GroupBuildingPlacement.
 */
public class StubBuildingPlacement implements BuildingPlacement {
	@Override
	public boolean place(FeaturePlaceContext<LostBuildingConfig> ctx, Assets assets, BuildingEngine engine) {
		WorldGenLevel world = ctx.level();
		BlockPos origin = ctx.origin();
		BlockPos surface = world.getHeightmapPos(Heightmap.Types.WORLD_SURFACE_WG, origin);
		world.setBlock(surface, Blocks.GOLD_BLOCK.defaultBlockState(), 4);
		return true;
	}
}
