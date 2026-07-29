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
	/** Headroom cleared above the road surface, even where the terrain is already lower. */
	private static final int CLEARANCE = 3;
	/**
	 * How far above the road the terrain may be dug away when the cell's ground sits higher than the
	 * city's shared level.
	 *
	 * <p>Buildings have always excavated what stands above them ({@code BuildingPiece.clearHeight}
	 * carries a 64-block allowance); streets cleared a flat {@link #CLEARANCE} and nothing more, so
	 * on any city whose level fell below the surrounding surface the roads stayed entombed while the
	 * houses around them stood in open pits.
	 *
	 * <p><b>This matches the buildings' 64 deliberately.</b> It was 24, which is worse than either
	 * extreme: the houses were dug out and the roads between them were not, so past 24 blocks of
	 * slope a city came out as open pits joined by tunnels. Whatever the right amount of excavation
	 * is, it has to be the same number for both, or the city disagrees with itself. Sites too steep
	 * to excavate at all are now refused outright before any of this runs — see
	 * {@code LostCityConfig.DEFAULT_MAX_HEIGHT_DIFF} — which is the honest place to draw that line,
	 * rather than half-digging a mountainside and leaving the result to the player.
	 */
	private static final int MAX_HEADROOM = 64;
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
		// The world's own limits do not vary across a cell, so they are asked for once here rather
		// than twice in each of the 256 columns. The test is unchanged: it depends only on groundY,
		// so when it rejects it rejects the whole cell, and the loop below never ran either.
		int minY = level.getMinY();
		if (groundY - 1 <= minY || groundY + CLEARANCE > level.getMaxY()) {
			return;
		}
		// One cursor for the whole cell. It used to be allocated inside paveColumn, i.e. once per
		// paved column — 256 short-lived MutableBlockPos per street *and* park *and* airport cell,
		// ~16k per city, on a chunk-generation thread. Nothing keeps a reference to it: every use
		// either reads it back immediately or hands it to setBlock, which copies.
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int dx = 0; dx < 16; dx++) {
			for (int dz = 0; dz < 16; dz++) {
				if (!CityLayout.isStreetColumn(dx, dz, width)) {
					continue;
				}
				paveColumn(level, chunkBox, cursor, minX + dx, minZ + dz, groundY, minY, surface);
			}
		}
	}

	/** Pave one column at the city's shared level: surface block, headroom above, fill below. */
	private static void paveColumn(WorldGenLevel level, BoundingBox chunkBox, BlockPos.MutableBlockPos cursor,
	                               int x, int z, int groundY, int minY, BlockState surface) {
		int surfaceY = groundY - 1;

		// Refuse columns that would need a taller embankment than we are willing to build
		// (deep water, ravines) instead of stilting the road across them.
		int terrainTop = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
		if (surfaceY - terrainTop > MAX_EMBANKMENT) {
			return;
		}

		if (setIfInside(level, chunkBox, cursor.set(x, surfaceY, z), surface)) {
			// Headroom: grass, snow layers, flowers, and any terrain standing above the shared level
			// — up to MAX_HEADROOM, so a road that runs below the surrounding surface is dug out
			// instead of being left buried under it.
			//
			// Bounded by terrainTop for the same reason Foundation's clear loop is: WORLD_SURFACE_WG's
			// predicate is exactly "not air", so getHeight() returns one past the highest non-air
			// block and everything at or above it is air — which fails this loop's `!isAir()` test, so
			// the skipped probes could never have written anything. terrainTop was read before the
			// only write that has happened since (the surface course at surfaceY, below this range),
			// and clearing only ever lowers the surface, so it stays a valid upper bound throughout.
			// On flat ground, where the road sits at or above the terrain, that is the whole loop:
			// 3 reads per column became 0. Measured over a 9x9 city (54 street + 6 park + 3 airport
			// cells, 16,128 paved columns): this bound alone took the method from 90,145
			// getBlockState calls to 45,347, and the embankment bound below took it to 25,681.
			int headroom = Math.min(Math.max(CLEARANCE, terrainTop - surfaceY), MAX_HEADROOM);
			int headEnd = Math.min(groundY + headroom, terrainTop);
			for (int cy = groundY; cy < headEnd; cy++) {
				cursor.set(x, cy, z);
				if (!level.getBlockState(cursor).isAir()) {
					setIfInside(level, chunkBox, cursor, AIR);
				}
			}

			// Embankment: carry the road down to solid ground so it never floats on a slope.
			//
			// The same heightmap bound, applied downwards. A block at or above terrainTop is air, and
			// air passes this loop's replaceable test, so those rungs are known to be filled without
			// asking: the read is only made once the loop drops below the terrain surface, which is
			// also the only place the loop can stop. The descent visits each Y exactly once and writes
			// nothing above where it currently is, so terrainTop is as valid at the bottom of the
			// column as it was at the top. On a road laid flush with flat ground that is the first
			// rung of the loop and hence half its reads.
			int floor = Math.max(minY + 1, surfaceY - MAX_EMBANKMENT);
			for (int cy = surfaceY - 1; cy >= floor; cy--) {
				cursor.set(x, cy, z);
				if (cy < terrainTop) {
					BlockState state = level.getBlockState(cursor);
					if (!(state.isAir() || state.liquid() || state.canBeReplaced())) {
						break;
					}
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
