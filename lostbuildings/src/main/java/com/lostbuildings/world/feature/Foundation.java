package com.lostbuildings.world.feature;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Foundation / terrain fitting for a placed building, analog of Lost Cities'
 * {@code LostCityTerrainFeature.fillToGround}. For each column of the building footprint it
 * <ol>
 *   <li>pillars solid fill down from the building base to the solid ground surface so the house
 *       is never left floating,</li>
 *   <li>clears the volume above the base (up to {@code clearHeight}) so intruding terrain, trees
 *       <em>and water</em> do not poke through the house, and</li>
 *   <li>drains any remaining liquid inside the footprint up to sea level, so a house placed in
 *       shallow water does not end up flooded from above.</li>
 * </ol>
 *
 * <p>All writes use {@link WorldGenFlags#SET_BLOCK}.
 */
public final class Foundation {

	/** Used when the building's own filler block cannot be resolved from the palette. */
	public static final BlockState DEFAULT_FILL = Blocks.COBBLESTONE.defaultBlockState();

	private static final BlockState AIR = Blocks.AIR.defaultBlockState();
	/** How far below the building base a support pillar may reach before giving up. */
	private static final int MAX_PILLAR_DEPTH = 32;

	private Foundation() {
	}

	/**
	 * @param level       worldgen level
	 * @param origin      NW-bottom corner of the building footprint (building base Y)
	 * @param width       footprint size along X (blocks)
	 * @param length      footprint size along Z (blocks)
	 * @param clearHeight how many blocks above the base to clear of terrain and liquid
	 * @param fill        block the support pillars are made of — pass the building's own filler so
	 *                    the plinth reads as part of the house instead of a cobblestone stump
	 * @param waterLevel  sea level; liquid inside the footprint is drained at least up to here
	 * @param rand        random source (reserved for jittered fill variety)
	 */
	public static void build(WorldGenLevel level, BlockPos origin, int width, int length,
	                         int clearHeight, BlockState fill, int waterLevel, RandomSource rand) {
		BlockState pillar = fill == null ? DEFAULT_FILL : fill;
		int baseY = origin.getY();
		int deepest = Math.max(level.getMinY() + 1, baseY - MAX_PILLAR_DEPTH);
		int clearTop = Math.min(level.getMaxY(), baseY + clearHeight);
		// Drain at least up to sea level even when the building itself is shorter than the water
		// column above it — otherwise the cleared interior refills from the top (bug B10).
		int drainTop = Math.min(level.getMaxY(), Math.max(clearTop, waterLevel + 1));

		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

		for (int dx = 0; dx < width; dx++) {
			for (int dz = 0; dz < length; dz++) {
				int wx = origin.getX() + dx;
				int wz = origin.getZ() + dz;

				// The whole column is read and written, so bound it once here instead of per block.
				// With a chunk-aligned 16x16 site inside the write window this never skips anything;
				// it is what keeps a wider footprint or a stray site from reaching out of the window.
				if (!WorldGenBounds.canRead(level, cursor.set(wx, baseY, wz))) {
					continue;
				}

				// 1) Pillar down: from just below the building base to the first solid block (or
				//    the deepest limit). Water counts as replaceable, so a submerged site gets a
				//    solid plinth rather than a house standing in a lake.
				int y = baseY - 1;
				cursor.set(wx, y, wz);
				while (y > deepest && isReplaceable(level.getBlockState(cursor))) {
					level.setBlock(cursor, pillar, WorldGenFlags.SET_BLOCK);
					y--;
					cursor.set(wx, y, wz);
				}

				// 2) Clear the building volume: hillsides, trees AND liquids all go. Liquids used
				//    to be skipped here, which is exactly why buildings generated flooded.
				for (int cy = baseY; cy < clearTop; cy++) {
					cursor.set(wx, cy, wz);
					if (!level.getBlockState(cursor).isAir()) {
						level.setBlock(cursor, AIR, WorldGenFlags.SET_BLOCK);
					}
				}

				// 3) Above the building, remove liquid only (never carve terrain) up to sea level.
				for (int cy = clearTop; cy < drainTop; cy++) {
					cursor.set(wx, cy, wz);
					if (level.getBlockState(cursor).liquid()) {
						level.setBlock(cursor, AIR, WorldGenFlags.SET_BLOCK);
					}
				}
			}
		}
	}

	private static boolean isReplaceable(BlockState state) {
		return state.isAir() || state.liquid() || state.canBeReplaced();
	}
}
