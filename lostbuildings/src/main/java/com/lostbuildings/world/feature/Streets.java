package com.lostbuildings.world.feature;

import com.lostbuildings.world.structure.CityLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Paving for one street cell of the city grid.
 *
 * <p>This is NOT a port of the Lost Cities street/highway engine (still cut; wave 2 replaces the
 * hand-laid slabs below with the shipped {@code street_*} parts). It is the same column-paving
 * logic the old group-connecting version used — surface block, headroom above, embankment below,
 * give up rather than stilt across a ravine — reorganised around a cell instead of an L-shaped
 * route between two building centres.
 *
 * <p>The route logic went away with the feature: on a checkerboard grid a street cell already sits
 * between the buildings it has to connect, so which columns are paved is a pure function of the
 * cell ({@link CityLayout#isStreetColumn}) rather than of the group's placement order.
 *
 * <p>Streets are laid at the city's <em>shared</em> ground level rather than following the terrain
 * per column: the buildings stand on that level too, so the block reads as one quarter instead of
 * houses at five different heights connected by roller-coaster paths.
 *
 * <p>All writes use {@link WorldGenFlags#SET_BLOCK} and are clipped to the caller's {@code chunkBox}.
 */
public final class Streets {

	/** Fallback surface material when a datapack leaves {@code street_block} unset. */
	public static final BlockState DEFAULT_STREET = Blocks.STONE_BRICKS.defaultBlockState();

	private static final BlockState AIR = Blocks.AIR.defaultBlockState();
	/** Headroom cleared above the road surface. */
	private static final int CLEARANCE = 3;
	/** How far the road may be carried on fill before the column is abandoned. */
	private static final int MAX_EMBANKMENT = 8;

	private Streets() {
	}

	/**
	 * Pave one 16×16 street cell.
	 *
	 * @param level    worldgen level
	 * @param chunkBox the writable area handed to the piece; nothing is written outside it
	 * @param minX     world X of the cell's north-west corner (chunk-aligned)
	 * @param minZ     world Z of the cell's north-west corner (chunk-aligned)
	 * @param groundY  the city's shared ground level; the road surface is the block below it, so
	 *                 roads and ground floors are flush
	 * @param material paving block
	 * @param width    paved width of the cell's arms, in blocks (16 paves the whole cell)
	 */
	public static void paveCell(WorldGenLevel level, BoundingBox chunkBox, int minX, int minZ, int groundY,
	                            BlockState material, int width) {
		BlockState surface = material == null ? DEFAULT_STREET : material;
		for (int dx = 0; dx < 16; dx++) {
			for (int dz = 0; dz < 16; dz++) {
				if (!CityLayout.isStreetColumn(dx, dz, width)) {
					continue;
				}
				paveColumn(level, chunkBox, minX + dx, minZ + dz, groundY, surface);
			}
		}
	}

	/** Pave one column at the city's shared level: surface block, headroom above, fill below. */
	private static void paveColumn(WorldGenLevel level, BoundingBox chunkBox, int x, int z, int groundY,
	                               BlockState surface) {
		int surfaceY = groundY - 1;
		if (surfaceY <= level.getMinY() || groundY + CLEARANCE > level.getMaxY()) {
			return;
		}

		// Refuse columns that would need a taller embankment than we are willing to build
		// (deep water, ravines) instead of stilting the road across them.
		int terrainTop = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
		if (surfaceY - terrainTop > MAX_EMBANKMENT) {
			return;
		}

		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

		if (setIfInside(level, chunkBox, cursor.set(x, surfaceY, z), surface)) {
			// Headroom: grass, snow layers, flowers and any terrain that the shared level cuts into.
			for (int cy = groundY; cy < groundY + CLEARANCE; cy++) {
				cursor.set(x, cy, z);
				if (!level.getBlockState(cursor).isAir()) {
					setIfInside(level, chunkBox, cursor, AIR);
				}
			}

			// Embankment: carry the road down to solid ground so it never floats on a slope.
			int floor = Math.max(level.getMinY() + 1, surfaceY - MAX_EMBANKMENT);
			for (int cy = surfaceY - 1; cy >= floor; cy--) {
				cursor.set(x, cy, z);
				BlockState state = level.getBlockState(cursor);
				if (!(state.isAir() || state.liquid() || state.canBeReplaced())) {
					break;
				}
				setIfInside(level, chunkBox, cursor, surface);
			}
		}
	}

	private static boolean setIfInside(WorldGenLevel level, BoundingBox chunkBox, BlockPos pos, BlockState state) {
		if (!chunkBox.isInside(pos)) {
			return false;
		}
		level.setBlock(pos, state, WorldGenFlags.SET_BLOCK);
		return true;
	}
}
