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
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Real placement for the {@code lostbuildings} feature: lays a small group (2-5)
 * of intact Lost Cities buildings around the feature origin, each fitted to the
 * terrain with a {@link Foundation}. Replaces Agent A's single-marker stub.
 *
 * <p>Coded against the frozen §3 contracts:
 * <ul>
 *   <li>{@link BuildingEngine#buildPalette(Assets, RandomSource, Style)}</li>
 *   <li>{@link BuildingEngine#generateBuilding(WorldGenLevel, BlockPos, RandomSource, Transform, Building, CompiledPalette, PlaceSettings)}</li>
 * </ul>
 *
 * Assumptions about Agent B's {@link Assets} accessors (see PORT-STATUS "Contract
 * deviations" — Agent D reconcile): {@code assets.getBuilding(String)} and
 * {@code assets.getStyle(String)}.
 */
public class GroupBuildingPlacement implements BuildingPlacement {

    /** Lost Cities buildings occupy a 16x16 chunk footprint. */
    private static final int FOOTPRINT = 16;
    /** Approximate blocks per floor (Lost Cities floor height). Used to size the clear volume. */
    private static final int FLOOR_HEIGHT = 6;
    /** Default style name to build palettes from (styles/standard.json is present in data). */
    private static final String DEFAULT_STYLE = "standard";

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

        Style style = assets.getStyle(DEFAULT_STYLE);

        // Group size in [groupMin, groupMax], clamped to a sane 1..5.
        int groupMin = Math.max(1, config.groupMin());
        int groupMax = Math.max(groupMin, config.groupMax());
        int count = groupMin + rand.nextInt(groupMax - groupMin + 1);

        int spacing = Math.max(FOOTPRINT, config.spacing());

        // Candidate sites arranged on a coarse grid around the origin, ring order
        // (centre first, then the 8 neighbours, then outer) with light per-site jitter.
        List<int[]> offsets = gridOffsets(count);

        List<BlockPos> placedBoxes = new ArrayList<>(); // NW corners of already-placed footprints
        boolean placedAny = false;

        for (int i = 0; i < offsets.size() && placedBoxes.size() < count; i++) {
            int[] off = offsets.get(i);
            int jitterX = rand.nextInt(7) - 3;
            int jitterZ = rand.nextInt(7) - 3;
            int wx = origin.getX() + off[0] * spacing + jitterX;
            int wz = origin.getZ() + off[1] * spacing + jitterZ;

            // Ground height at this site (mirror desolation ScatteredFeature).
            BlockPos ground = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE_WG,
                    new BlockPos(wx, origin.getY(), wz));
            BlockPos site = new BlockPos(wx, ground.getY(), wz);

            // bbox anti-overlap: reject if this footprint overlaps an already-placed one.
            if (overlaps(site, placedBoxes)) {
                continue;
            }

            String buildingName = buildingNames.get(rand.nextInt(buildingNames.size()));
            Building building = assets.getBuilding(buildingName);
            if (building == null) {
                continue;
            }

            Transform transform = randomRotation(rand);
            CompiledPalette pal = engine.buildPalette(assets, rand, style);

            PlaceSettings settings = new PlaceSettings(
                    config.minFloors(), config.maxFloors(),
                    true,   // lighting
                    true,   // spawners
                    true,   // loot
                    level.getSeaLevel());

            // Foundation first: pillar to ground + clear the volume above, so the
            // house sits on solid ground and no terrain intrudes.
            if (config.foundation()) {
                int clearHeight = (config.maxFloors() + 1) * FLOOR_HEIGHT + 4;
                Foundation.build(level, site, FOOTPRINT, FOOTPRINT, clearHeight, rand);
            }

            engine.generateBuilding(level, site, rand, transform, building, pal, settings);

            placedBoxes.add(site);
            placedAny = true;
        }

        // Connect the placed buildings with simple streets running through the gaps between
        // footprints (§11b lightweight approach — not a city/street engine port).
        if (placedAny) {
            Streets.connect(level, placedBoxes, FOOTPRINT, rand);
        }

        return placedAny;
    }

    /**
     * Grid offsets in ring order around (0,0): centre, then the 8 surrounding cells,
     * then the next ring — enough to seat up to 5 non-overlapping sites.
     */
    private static List<int[]> gridOffsets(int count) {
        List<int[]> list = new ArrayList<>();
        int ring = 0;
        while (list.size() < Math.max(count, 5) + 4) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) == ring) {
                        list.add(new int[]{dx, dz});
                    }
                }
            }
            ring++;
            if (ring > 4) break;
        }
        return list;
    }

    /** True if a 16x16 footprint at {@code site} overlaps any already-placed footprint. */
    private static boolean overlaps(BlockPos site, List<BlockPos> placed) {
        for (BlockPos p : placed) {
            boolean sepX = Math.abs(site.getX() - p.getX()) >= FOOTPRINT;
            boolean sepZ = Math.abs(site.getZ() - p.getZ()) >= FOOTPRINT;
            if (!sepX && !sepZ) {
                return true;
            }
        }
        return false;
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
