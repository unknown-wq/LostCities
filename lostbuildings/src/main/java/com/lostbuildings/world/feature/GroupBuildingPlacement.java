package com.lostbuildings.world.feature;

import com.lostbuildings.engine.Assets;
import com.lostbuildings.engine.Building;
import com.lostbuildings.engine.BuildingEngine;
import com.lostbuildings.engine.CompiledPalette;
import com.lostbuildings.engine.PlaceSettings;
import com.lostbuildings.engine.Style;
import com.lostbuildings.engine.Transform;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Real placement for the {@code lostbuildings} feature: lays a small group of intact Lost Cities
 * buildings around the feature origin, all standing on one shared ground level and connected by
 * {@link Streets}.
 *
 * <p>Each building is snapped to a whole chunk cell inside the feature's write window (origin
 * chunk ±1), so a 16×16 building never straddles a chunk border and can never be clipped down to a
 * single wall. Which of those cells this group is allowed to use is decided by
 * {@link CellLattice}, so two groups fired from nearby chunks can no longer both claim the same
 * cell and interpenetrate.
 */
public class GroupBuildingPlacement implements BuildingPlacement {

	/** Lost Cities buildings occupy a 16x16 chunk footprint. */
	private static final int FOOTPRINT = 16;
	/** Blocks per floor — must match {@code BuildingEngine}'s own floor height. */
	private static final int FLOOR_HEIGHT = 6;
	/** Headroom cleared above the tallest storey of a building. */
	private static final int CLEAR_MARGIN = 4;
	/** Hard cap on how far a building cut into a hillside will excavate above its base. */
	private static final int MAX_EXCAVATION = 64;

	@Override
	public boolean place(FeaturePlaceContext<LostBuildingConfig> ctx, Assets assets, BuildingEngine engine) {
		WorldGenLevel level = ctx.level();
		RandomSource rand = ctx.random();
		BlockPos origin = ctx.origin();
		LostBuildingConfig config = ctx.config();

		List<String> buildingNames = config.buildings();
		if (buildingNames == null || buildingNames.isEmpty()) {
			return false;
		}

		Style style = resolveStyle(assets, level, origin);
		if (style == null) {
			return false;
		}

		int centerCX = origin.getX() >> 4;
		int centerCZ = origin.getZ() >> 4;

		// Which cells may this group use at all? (B5 palliative — see CellLattice.)
		List<int[]> cells = ownedCells(level.getSeed(), centerCX, centerCZ);
		if (cells.isEmpty()) {
			return false;
		}

		// Group size in [groupMin, groupMax], clamped to the cells actually owned.
		int groupMin = Math.max(1, config.groupMin());
		int groupMax = Math.max(groupMin, config.groupMax());
		int count = Math.min(groupMin + rand.nextInt(groupMax - groupMin + 1), cells.size());
		cells = cells.subList(0, count);

		// One ground level for the whole group: on a slope, five independent heightmap reads put
		// the five houses on five different Y values and the block stops reading as a block.
		int groundY = groupGroundY(level, origin, centerCX, centerCZ, cells);

		List<BlockPos> placedSites = new ArrayList<>();

		for (int[] cell : cells) {
			int wx = (centerCX + cell[0]) << 4;   // chunk-aligned NW corner
			int wz = (centerCZ + cell[1]) << 4;
			BlockPos site = new BlockPos(wx, groundY, wz);

			String buildingName = buildingNames.get(rand.nextInt(buildingNames.size()));
			Building building = assets.getBuilding(buildingName);
			if (building == null) {
				continue;
			}

			Transform transform = randomRotation(rand);
			CompiledPalette pal = engine.buildPalette(assets, rand, style);

			// Pick the storey count here rather than letting the engine roll its own, so the
			// cleared volume can be sized from the building that is actually going up.
			int floors = pickFloors(building, rand, config);
			PlaceSettings settings = new PlaceSettings(
					floors, floors,
					true,   // lighting
					true,   // spawners
					true,   // loot
					level.getSeaLevel());

			// Foundation first: pillar to ground + clear the volume above, so the house sits on
			// solid ground, no terrain intrudes and no water is left inside.
			if (config.foundation()) {
				Foundation.build(level, site, FOOTPRINT, FOOTPRINT,
						clearHeight(building, floors,
								cellTerrainTop(level, origin, wx, wz, centerCX, centerCZ) - groundY),
						fillerState(building, pal, rand), settings.waterLevel(), rand);
			}

			engine.generateBuilding(level, site, rand, transform, building, pal, settings);

			placedSites.add(site);
		}

		if (placedSites.isEmpty()) {
			return false;
		}

		// Connect the placed buildings with simple streets running through the gaps between
		// footprints (§11b lightweight approach — not a city/street engine port).
		Streets.connect(level, placedSites, FOOTPRINT, groundY, centerCX, centerCZ, rand);
		return true;
	}

	/**
	 * The subset of {@link CellLattice#OFFSETS} this group exclusively owns, in lattice order.
	 *
	 * <p>Cells outside the feature's write window are dropped rather than built: every position in
	 * this class (site corners, terrain probes, streets) is derived from a cell, so this one filter
	 * keeps the whole placement pass inside the window even if {@code OFFSETS} ever grows past ±1.
	 */
	private static List<int[]> ownedCells(long seed, int centerCX, int centerCZ) {
		List<int[]> owned = new ArrayList<>(CellLattice.OFFSETS.length);
		for (int i = 0; i < CellLattice.OFFSETS.length; i++) {
			int[] offset = CellLattice.OFFSETS[i];
			if (!WorldGenBounds.holdsChunk(centerCX + offset[0], centerCZ + offset[1], centerCX, centerCZ)) {
				continue;
			}
			if (CellLattice.owns(seed, centerCX, centerCZ, i)) {
				owned.add(offset);
			}
		}
		return owned;
	}

	/**
	 * One ground level for the whole group: the lowest terrain height across the corners of every
	 * cell, snapped down to a multiple of {@link #FLOOR_HEIGHT} so storeys line up between
	 * neighbouring buildings. Taking the minimum means the group is cut into a slope rather than
	 * left standing on stilts over it.
	 */
	private static int groupGroundY(WorldGenLevel level, BlockPos origin, int centerCX, int centerCZ,
	                                List<int[]> cells) {
		int lowest = Integer.MAX_VALUE;
		for (int[] cell : cells) {
			int wx = (centerCX + cell[0]) << 4;
			int wz = (centerCZ + cell[1]) << 4;
			for (int[] corner : CELL_PROBES) {
				lowest = Math.min(lowest,
						terrainTop(level, origin, wx + corner[0], wz + corner[1], centerCX, centerCZ));
			}
		}
		if (lowest == Integer.MAX_VALUE) {
			lowest = origin.getY();
		}
		return Math.floorDiv(lowest, FLOOR_HEIGHT) * FLOOR_HEIGHT;
	}

	/** Highest terrain point over one cell — how far the slope reaches into the building volume. */
	private static int cellTerrainTop(WorldGenLevel level, BlockPos origin, int wx, int wz,
	                                  int centerCX, int centerCZ) {
		int highest = Integer.MIN_VALUE;
		for (int[] corner : CELL_PROBES) {
			highest = Math.max(highest,
					terrainTop(level, origin, wx + corner[0], wz + corner[1], centerCX, centerCZ));
		}
		return highest;
	}

	/**
	 * Heightmap height of one column, with the column pulled back into the feature's write window
	 * first (see {@link WorldGenBounds}).
	 *
	 * <p>Clamping rather than skipping is deliberate: callers fold these samples into a min or a max,
	 * so dropping one would silently change the group's ground level, while nudging an out-of-window
	 * column to the nearest legal one keeps the terrain estimate honest. With the shipped
	 * {@link CellLattice#OFFSETS} (±1 cell) and {@link #CELL_PROBES} (0..15 blocks into a cell) the
	 * clamp never fires — it exists so that widening either of those cannot reintroduce an
	 * out-of-window read.
	 */
	private static int terrainTop(WorldGenLevel level, BlockPos origin, int x, int z,
	                              int centerCX, int centerCZ) {
		int safeX = WorldGenBounds.clampBlockToWindow(x, centerCX);
		int safeZ = WorldGenBounds.clampBlockToWindow(z, centerCZ);
		return level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE_WG,
				new BlockPos(safeX, origin.getY(), safeZ)).getY();
	}

	/**
	 * Corners and centre of a 16x16 cell — enough to characterise the slope it sits on.
	 *
	 * <p>{@code FOOTPRINT - 1} rather than {@code FOOTPRINT}: the far corner is the cell's last
	 * block, not the first block of the next cell. Using {@code FOOTPRINT} would push probes on an
	 * outer cell two chunks out from the generating chunk.
	 */
	private static final int[][] CELL_PROBES = {
			{0, 0}, {FOOTPRINT - 1, 0}, {0, FOOTPRINT - 1}, {FOOTPRINT - 1, FOOTPRINT - 1},
			{FOOTPRINT / 2, FOOTPRINT / 2}
	};

	/** Style for this group, falling back to {@code standard} if the chosen one is not loaded. */
	private static Style resolveStyle(Assets assets, WorldGenLevel level, BlockPos origin) {
		String name = StyleSelector.select(level, origin);
		Style style = assets.getStyle(name);
		if (style == null && !StyleSelector.DEFAULT_STYLE.equals(name)) {
			style = assets.getStyle(StyleSelector.DEFAULT_STYLE);
		}
		return style;
	}

	/**
	 * The building's own filler block, resolved through the group's palette, so a plinth under a
	 * sandstone house is sandstone rather than a cobblestone stump.
	 */
	private static BlockState fillerState(Building building, CompiledPalette palette, RandomSource rand) {
		BlockState state = palette.get(building.getFillerBlock(), rand);
		return state == null ? Foundation.DEFAULT_FILL : state;
	}

	/**
	 * Height to clear above the building base. Mirrors {@code BuildingEngine.generateBuilding},
	 * which stacks {@code floors + 1} parts of {@link #FLOOR_HEIGHT} blocks each.
	 *
	 * <p>This used to be computed from {@code config.maxFloors()} unconditionally — 46 blocks for
	 * every building, so a two-storey house in a hillside had a cave carved out above it and the
	 * clear pass did ~59k pointless block reads.
	 *
	 * <p>Buildings that declare {@code overrideFloors} ignore the storey count handed to them and
	 * roll their own, so for those the clear volume is sized from their declared maximum instead.
	 *
	 * @param slopeAbove how far the cell's highest terrain point rises above the shared ground
	 *                   level; the excavation has to reach at least that far or the hillside is
	 *                   left sitting on the roof
	 */
	private static int clearHeight(Building building, int floors, int slopeAbove) {
		int effective = floors;
		if (Boolean.TRUE.equals(building.getOverrideFloors()) && building.getMaxFloors() >= 0) {
			effective = Math.max(effective, building.getMaxFloors());
		}
		int buildingHeight = (effective + 1) * FLOOR_HEIGHT + CLEAR_MARGIN;
		return Math.max(buildingHeight, Math.min(slopeAbove + 1, MAX_EXCAVATION));
	}

	/**
	 * Storey count for one building. Delegates to {@link BuildingEngine#pickFloors} so the number
	 * used to clear the volume above the house is the same one the engine builds to — a local copy
	 * of the rule drifted out of sync the moment the engine's own version changed.
	 */
	private static int pickFloors(Building building, RandomSource rand, LostBuildingConfig config) {
		return BuildingEngine.pickFloors(building, rand, config.minFloors(), config.maxFloors());
	}

	/** Pick one of the four cardinal rotations. Transform enum constants are stable (§4). */
	private static Transform randomRotation(RandomSource rand) {
		return switch (rand.nextInt(4)) {
			case 1 -> Transform.ROTATE_90;
			case 2 -> Transform.ROTATE_180;
			case 3 -> Transform.ROTATE_270;
			default -> Transform.ROTATE_NONE;
		};
	}
}
