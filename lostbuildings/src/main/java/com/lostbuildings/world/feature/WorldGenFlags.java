package com.lostbuildings.world.feature;

import net.minecraft.world.level.block.Block;

/**
 * The single set of {@code setBlock} flags used by every worldgen write in this package
 * ({@link Foundation}, {@link Streets}, {@link GroupBuildingPlacement}).
 *
 * <p>History: these were hardcoded as the literal {@code 19} while the surrounding comments
 * claimed "never flag 1". That was wrong — {@code 19 == UPDATE_KNOWN_SHAPE | UPDATE_CLIENTS |
 * UPDATE_NEIGHBORS}, so bit 1 <em>was</em> set and every placed block did kick off neighbour
 * updates during worldgen. The constant below drops that bit, which also lines it up with
 * {@code BuildingEngine}'s own write flags.
 */
public final class WorldGenFlags {

	/**
	 * {@code UPDATE_CLIENTS} (2) — send the change to clients;
	 * {@code UPDATE_KNOWN_SHAPE} (16) — skip vanilla's shape/connection re-evaluation, which the
	 * building engine's own correction pass handles.
	 * {@code UPDATE_NEIGHBORS} (1) is deliberately left off: neighbour updates during worldgen can
	 * cascade into chunks that are not loaded yet.
	 */
	public static final int SET_BLOCK = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

	private WorldGenFlags() {
	}
}
