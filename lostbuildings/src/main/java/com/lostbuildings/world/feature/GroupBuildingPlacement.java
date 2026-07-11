package com.lostbuildings.world.feature;

import com.lostbuildings.engine.Assets;
import com.lostbuildings.engine.Building;
import com.lostbuildings.engine.BuildingEngine;
import com.lostbuildings.engine.CompiledPalette;
import com.lostbuildings.engine.PlaceSettings;
import com.lostbuildings.engine.Style;
import com.lostbuildings.engine.Transform;
import com.lostbuildings.world.gen.StyleSelector;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Real placement for the {@code lostbuildings} feature: lays a small, procedurally varied group
 * of intact Lost Cities buildings around the feature origin, each fitted to the terrain with a
 * {@link Foundation}. A group is either a scatter of standalone single-chunk buildings (with the
 * occasional rare "landmark" — cabin / radio tower) or, ~1 in 4, one big 2x2 multi-building
 * (town / shopping / library / …) anchored in a corner with a single standalone across the way.
 * After the buildings are placed {@link Streets} connects them and {@link Decorations} fills the
 * gaps with sparse yard props so a settlement reads as lived-in rather than eight identical boxes.
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

        // A Feature may only write within the origin chunk +/-1 chunk (the FEATURES step's
        // blockStateWriteRadius is 1); anything further is silently dropped, which used to slice
        // buildings that straddled a chunk border down to a single wall. So snap every building to
        // a whole chunk CELL inside that 3x3 window: a 16x16 building fills one chunk exactly and
        // never crosses a border. Standalone cells form a checkerboard (centre + 4 diagonals) so
        // buildings touch only at corners and the orthogonal chunks between them stay free for
        // streets; a 2x2 multi-building fills a corner block of the window, which still fits ±1.
        int centerCX = origin.getX() >> 4;
        int centerCZ = origin.getZ() >> 4;

        // Group size in [groupMin, groupMax].
        int groupMin = Math.max(1, config.groupMin());
        int groupMax = Math.max(groupMin, config.groupMax());
        int count = groupMin + rand.nextInt(groupMax - groupMin + 1);

        List<BlockPos> placedBoxes = new ArrayList<>(); // NW corners of every placed chunk footprint

        // ~multibuildingChance of the time, anchor one coherent 2x2 multi-building in a corner of
        // the window instead of a plain scatter. It returns the free diagonal-opposite corner cell
        // (only corner-touching the 2x2, so it never merges) where we may drop one standalone.
        int[] freeCorner = new int[2];
        boolean placedMulti = false;
        List<String> multis = config.multibuildings();
        if (multis != null && !multis.isEmpty() && rand.nextFloat() < config.multibuildingChance()) {
            placedMulti = tryPlaceMultibuilding(level, rand, origin, config, assets, engine,
                    centerCX, centerCZ, placedBoxes, freeCorner);
        }

        if (placedMulti) {
            // A big building plus, for company, a single standalone across the open ground.
            if (count >= 2) {
                placeStandalone(level, rand, origin, config, assets, engine,
                        centerCX + freeCorner[0], centerCZ + freeCorner[1], placedBoxes);
            }
        } else if (placedBoxes.isEmpty()) {
            // No multi-building (rolled off, unavailable, or cleanly failed with no writes): a varied
            // standalone scatter. Shuffle the checkerboard cells and take a random subset so the
            // layout differs group to group instead of always being the same fixed X.
            List<int[]> cells = new ArrayList<>(CHECKERBOARD_CELLS);
            Collections.shuffle(cells, new java.util.Random(rand.nextLong()));
            int target = Math.min(count, cells.size());
            for (int i = 0; i < cells.size() && placedBoxes.size() < target; i++) {
                int[] cell = cells.get(i);
                placeStandalone(level, rand, origin, config, assets, engine,
                        centerCX + cell[0], centerCZ + cell[1], placedBoxes);
            }
        }

        boolean placedAny = !placedBoxes.isEmpty();

        // Connect the placed buildings with simple streets running through the gaps between
        // footprints (§11b lightweight approach — not a city/street engine port), then scatter a
        // little life into the leftover ground.
        if (placedAny) {
            Streets.connect(level, placedBoxes, FOOTPRINT, rand);
            Decorations.scatter(level, placedBoxes, FOOTPRINT, centerCX, centerCZ, rand);
        }

        return placedAny;
    }

    /**
     * Place a single standalone building in the chunk cell (cx, cz). Rolls the {@code landmarks}
     * table for occasional rare variety, then fits, foundations and generates it. Guarded — never
     * throws; a missing/failed building simply leaves the cell empty.
     *
     * @return true if a building was generated
     */
    private boolean placeStandalone(WorldGenLevel level, RandomSource rand, BlockPos origin,
                                    LostBuildingConfig config, Assets assets, BuildingEngine engine,
                                    int cx, int cz, List<BlockPos> placedBoxes) {
        try {
            int wx = cx << 4;   // chunk-aligned NW corner
            int wz = cz << 4;

            // Ground height at this cell (mirror desolation ScatteredFeature).
            BlockPos ground = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE_WG,
                    new BlockPos(wx, origin.getY(), wz));
            BlockPos site = new BlockPos(wx, ground.getY(), wz);

            String buildingName = pickStandaloneName(rand, config);
            Building building = assets.getBuilding(buildingName);
            if (building == null) {
                return false;
            }

            Transform transform = randomRotation(rand);
            // Style is chosen per-building (biome/temperature aware) so a group can mix materials.
            Style style = StyleSelector.pick(level, site, rand, assets);
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
            return true;
        } catch (Exception e) {
            // Never let a single bad building abort worldgen for the whole group.
            return false;
        }
    }

    /**
     * Try to assemble one 2x2 multi-building (family {@code fam} -> quadrants {@code fam00/01/10/11})
     * anchored in a corner of the 3x3 write window. All four quadrants share one base Y, one style
     * and a fixed floor count so they form a single coherent building, and each quadrant (r,c) is
     * placed chunk-aligned at chunk offset (r,c) from the anchor — matching the original Lost Cities
     * {@code MultiBuilding.getBuilding(x,z)} layout where the first index is the X (chunkX) offset
     * and the second the Z (chunkZ) offset.
     *
     * <p><b>Rotation limitation:</b> only {@link Transform#ROTATE_NONE} is supported for
     * multi-buildings. Rotating a 2x2 assembly also permutes which quadrant sits in which cell;
     * getting that permutation subtly wrong slices buildings, so (per the task's "correctness over
     * cleverness" note) we deliberately keep the un-rotated layout for which the quadrant JSONs are
     * authored. Variety still comes from the four possible corner anchors and the standalone
     * companion.
     *
     * @param placedBoxes appended with the four quadrant NW corners on success
     * @param outFreeCorner filled with the {dx,dz} of the free diagonal-opposite corner cell
     * @return true if the full 2x2 was placed; false (with no writes) if it could not fit/resolve
     */
    private boolean tryPlaceMultibuilding(WorldGenLevel level, RandomSource rand, BlockPos origin,
                                          LostBuildingConfig config, Assets assets, BuildingEngine engine,
                                          int centerCX, int centerCZ, List<BlockPos> placedBoxes,
                                          int[] outFreeCorner) {
        try {
            List<String> families = config.multibuildings();
            String family = families.get(rand.nextInt(families.size()));

            // Resolve all four quadrants up front; bail cleanly (no writes) if any is missing.
            Building[][] quad = new Building[2][2];
            for (int r = 0; r < 2; r++) {
                for (int c = 0; c < 2; c++) {
                    Building b = assets.getBuilding(family + r + "" + c);
                    if (b == null) {
                        return false;
                    }
                    quad[r][c] = b;
                }
            }

            // Anchor the 2x2 at a random corner of the window: offsets in {-1,0} keep quadrants
            // (which add 0..1) inside the ±1 write window.
            int aox = rand.nextBoolean() ? 0 : -1;
            int aoz = rand.nextBoolean() ? 0 : -1;

            // One shared base Y = the lowest of the four quadrant grounds, so no quadrant floats
            // (foundations pillar down; the clear pass removes the little terrain the higher
            // quadrants sit in). This keeps floor levels aligned across the whole building.
            int baseY = Integer.MAX_VALUE;
            for (int r = 0; r < 2; r++) {
                for (int c = 0; c < 2; c++) {
                    int wx = (centerCX + aox + r) << 4;
                    int wz = (centerCZ + aoz + c) << 4;
                    int gy = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE_WG,
                            new BlockPos(wx, origin.getY(), wz)).getY();
                    baseY = Math.min(baseY, gy);
                }
            }

            // One style / palette for the whole assembly so the four quadrants share materials.
            // The StyleSelector seam is still exercised (once per multi-building).
            BlockPos anchorSite = new BlockPos((centerCX + aox) << 4, baseY, (centerCZ + aoz) << 4);
            Style style = StyleSelector.pick(level, anchorSite, rand, assets);
            CompiledPalette pal = engine.buildPalette(assets, rand, style);

            // Fix the floor count so every non-overriding quadrant gets the same silhouette (a
            // quadrant that overrides floors keeps its own, by the family's own design).
            int floors = config.minFloors();
            if (config.maxFloors() > config.minFloors()) {
                floors += rand.nextInt(config.maxFloors() - config.minFloors() + 1);
            }
            PlaceSettings settings = new PlaceSettings(
                    floors, floors,
                    true,   // lighting
                    true,   // spawners
                    true,   // loot
                    level.getSeaLevel());

            int clearHeight = (config.maxFloors() + 1) * FLOOR_HEIGHT + 4;

            for (int r = 0; r < 2; r++) {
                for (int c = 0; c < 2; c++) {
                    int wx = (centerCX + aox + r) << 4;
                    int wz = (centerCZ + aoz + c) << 4;
                    BlockPos site = new BlockPos(wx, baseY, wz);
                    if (config.foundation()) {
                        Foundation.build(level, site, FOOTPRINT, FOOTPRINT, clearHeight, rand);
                    }
                    engine.generateBuilding(level, site, rand, Transform.ROTATE_NONE, quad[r][c], pal, settings);
                    placedBoxes.add(site);
                }
            }

            // Free diagonal-opposite corner: the one X/Z offset the 2x2 does not cover. It only
            // corner-touches the block, so a standalone there can never merge with it.
            outFreeCorner[0] = (aox == 0) ? -1 : 1;
            outFreeCorner[1] = (aoz == 0) ? -1 : 1;
            return true;
        } catch (Exception e) {
            // Any failure -> report "not placed"; caller falls back to a standalone scatter.
            return false;
        }
    }

    /** Pick a standalone building name, occasionally substituting a rarer landmark for variety. */
    private static String pickStandaloneName(RandomSource rand, LostBuildingConfig config) {
        List<String> landmarks = config.landmarks();
        if (landmarks != null && !landmarks.isEmpty() && rand.nextFloat() < config.landmarkChance()) {
            return landmarks.get(rand.nextInt(landmarks.size()));
        }
        List<String> buildings = config.buildings();
        return buildings.get(rand.nextInt(buildings.size()));
    }

    /**
     * Chunk-cell offsets around the origin chunk, checkerboard order: centre first, then the four
     * diagonal chunks. All lie within the FEATURES write window (origin chunk +/-1), and no two
     * share a chunk edge, so buildings never merge and the orthogonal chunks between them are left
     * open for {@link Streets}. Up to five buildings — matching the group max.
     */
    private static final List<int[]> CHECKERBOARD_CELLS = List.of(
            new int[]{0, 0},
            new int[]{1, 1}, new int[]{1, -1}, new int[]{-1, 1}, new int[]{-1, -1});

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
