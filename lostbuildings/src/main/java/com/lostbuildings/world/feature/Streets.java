package com.lostbuildings.world.feature;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.List;

/**
 * Lightweight streets between the buildings of a group (PORT-PLAN §11b, preferred option).
 *
 * <p>This is NOT a port of the Lost Cities city/street/highway engine (that whole system was cut
 * in Phase 1). It simply connects the already-placed building footprints with a narrow paved path:
 * for each consecutive pair of sites an L-shaped (Manhattan) route is walked, and every column of
 * the route that falls in the gap between footprints is paved. Columns inside a building footprint
 * are skipped so the road never carves through a house.
 *
 * <p>Streets are laid at the group's <em>shared</em> ground level rather than following the terrain
 * per column: the buildings all stand on that level too, so the block reads as one quarter instead
 * of five houses at five different heights connected by roller-coaster paths. Where the terrain
 * falls away the road is carried on a short embankment of the same material; where it falls away
 * further than {@link #MAX_EMBANKMENT} the column is skipped rather than building a stilted bridge.
 *
 * <p>All writes use {@link WorldGenFlags#SET_BLOCK}.
 */
public final class Streets {

	/** Road surface material — reads clearly as a paved street. */
	private static final BlockState STREET = Blocks.STONE_BRICKS.defaultBlockState();
	private static final BlockState AIR = Blocks.AIR.defaultBlockState();
	/** Half-width of the path: 1 -> a 3-wide street. */
	private static final int HALF_WIDTH = 1;
	/** Headroom cleared above the road surface. */
	private static final int CLEARANCE = 3;
	/** How far the road may be carried on fill before the column is abandoned. */
	private static final int MAX_EMBANKMENT = 8;

	private Streets() {
	}

	/**
	 * Connect the given building sites (NW-bottom corners of each footprint) with streets.
	 * Sites are chained in placement order, yielding a connected road network for the group.
	 *
	 * @param level     worldgen level
	 * @param sites     NW-bottom corners of each placed building footprint
	 * @param footprint footprint size in blocks (square)
	 * @param groundY   the group's shared ground level (building base Y); the road surface is the
	 *                  block below it, so roads and ground floors are flush
	 * @param centerCX  chunk X of the chunk being generated — the road may not leave it by more than
	 *                  {@link WorldGenBounds#WRITE_RADIUS}
	 * @param centerCZ  chunk Z of the chunk being generated
	 * @param rand      random source (reserved; unused for now)
	 */
	public static void connect(WorldGenLevel level, List<BlockPos> sites, int footprint, int groundY,
	                           int centerCX, int centerCZ, RandomSource rand) {
		if (sites.size() < 2) {
			return;
		}
		int half = footprint / 2;
		for (int i = 0; i < sites.size() - 1; i++) {
			BlockPos a = sites.get(i);
			BlockPos b = sites.get(i + 1);
			int ax = a.getX() + half, az = a.getZ() + half;
			int bx = b.getX() + half, bz = b.getZ() + half;
			// L-shaped route: X leg at z=az, then Z leg at x=bx.
			layLegX(level, ax, bx, az, sites, footprint, groundY, centerCX, centerCZ);
			layLegZ(level, az, bz, bx, sites, footprint, groundY, centerCX, centerCZ);
		}
	}

	/** Lay a road segment along X from x0..x1 at fixed z, HALF_WIDTH blocks each side (widened on z). */
	private static void layLegX(WorldGenLevel level, int x0, int x1, int z, List<BlockPos> sites,
	                            int footprint, int groundY, int centerCX, int centerCZ) {
		int lo = Math.min(x0, x1), hi = Math.max(x0, x1);
		for (int x = lo; x <= hi; x++) {
			for (int w = -HALF_WIDTH; w <= HALF_WIDTH; w++) {
				paveColumn(level, x, z + w, sites, footprint, groundY, centerCX, centerCZ);
			}
		}
	}

	/** Lay a road segment along Z from z0..z1 at fixed x, HALF_WIDTH blocks each side (widened on x). */
	private static void layLegZ(WorldGenLevel level, int z0, int z1, int x, List<BlockPos> sites,
	                            int footprint, int groundY, int centerCX, int centerCZ) {
		int lo = Math.min(z0, z1), hi = Math.max(z0, z1);
		for (int z = lo; z <= hi; z++) {
			for (int w = -HALF_WIDTH; w <= HALF_WIDTH; w++) {
				paveColumn(level, x + w, z, sites, footprint, groundY, centerCX, centerCZ);
			}
		}
	}

	/** Pave one column at the group's shared level: surface block, headroom above, fill below. */
	private static void paveColumn(WorldGenLevel level, int x, int z, List<BlockPos> sites,
	                               int footprint, int groundY, int centerCX, int centerCZ) {
		// Route legs run between cell centres and are widened by HALF_WIDTH, so with the shipped
		// lattice (cells ±1, half-width 1) a column is always inside the write window. Dropping the
		// odd column that is not beats a heightmap read the region logs and a write it discards.
		if (!WorldGenBounds.holdsBlock(x, z, centerCX, centerCZ)) {
			return;
		}
		if (insideAnyFootprint(x, z, sites, footprint)) {
			return; // never carve a road through a building footprint
		}
		int surfaceY = groundY - 1;
		if (surfaceY <= level.getMinY() || groundY + CLEARANCE > level.getMaxY()) {
			return;
		}

		// Refuse columns that would need a taller embankment than we are willing to build
		// (deep water, ravines) instead of stilting the road across them.
		int terrainTop = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE_WG, new BlockPos(x, 0, z)).getY();
		if (surfaceY - terrainTop > MAX_EMBANKMENT) {
			return;
		}

		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

		cursor.set(x, surfaceY, z);
		level.setBlock(cursor, STREET, WorldGenFlags.SET_BLOCK);

		// Headroom: grass, snow layers, flowers and any terrain that the shared level cuts into.
		for (int cy = groundY; cy < groundY + CLEARANCE; cy++) {
			cursor.set(x, cy, z);
			if (!level.getBlockState(cursor).isAir()) {
				level.setBlock(cursor, AIR, WorldGenFlags.SET_BLOCK);
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
			level.setBlock(cursor, STREET, WorldGenFlags.SET_BLOCK);
		}
	}

	private static boolean insideAnyFootprint(int x, int z, List<BlockPos> sites, int footprint) {
		for (BlockPos p : sites) {
			if (x >= p.getX() && x < p.getX() + footprint
					&& z >= p.getZ() && z < p.getZ() + footprint) {
				return true;
			}
		}
		return false;
	}
}
