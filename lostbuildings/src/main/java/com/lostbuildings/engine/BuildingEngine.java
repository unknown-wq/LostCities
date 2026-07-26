package com.lostbuildings.engine;

import com.lostbuildings.LostBuildings;
import com.lostbuildings.engine.codec.ConditionPart;
import com.lostbuildings.engine.codec.ConditionRE;
import com.lostbuildings.engine.codec.PaletteSelector;
import com.lostbuildings.engine.util.Tools;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The building-generation engine: builds compiled palettes from a style and generates the block
 * layout of a building into the world. Rewritten (not copied) from the original terrain feature;
 * the chunk-cache writer, edit-mode, corridor connections and off-thread deferred tasks are gone —
 * block writes go directly through {@link WorldGenLevel#setBlock}.
 */
public class BuildingEngine {

    private static final int FLOORHEIGHT = 6;
    // Worldgen-safe flags: notify clients, do not trigger neighbour updates (bit 1 must stay off).
    private static final int SET_FLAGS = Block.UPDATE_CLIENTS;

    // net.minecraft.world.RandomizableContainer.LOOT_TABLE_TAG / LOOT_TABLE_SEED_TAG (26.2).
    private static final String LOOT_TABLE_TAG = "LootTable";
    private static final String LOOT_TABLE_SEED_TAG = "LootTableSeed";

    private final Assets assets;
    // Worldgen runs on many threads: every cache below has to be concurrent.
    private final Map<Block, BlockEntityType<?>> typeCache = new ConcurrentHashMap<>();

    /**
     * Compiled palettes keyed by the exact set of source palettes they were merged from. Compiling
     * is not cheap and the same style/building/part combinations recur for every single building.
     */
    private final Map<List<Palette>, CompiledPalette> paletteCache = new ConcurrentHashMap<>();
    private final Map<DerivedPaletteKey, CompiledPalette> derivedPaletteCache = new ConcurrentHashMap<>();

    /** Deduplication for one-shot warnings (unknown palette characters, bad loot ids, ...). */
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    /** A base compiled palette overlaid with one extra (building- or part-local) palette. */
    private record DerivedPaletteKey(CompiledPalette base, Palette extra) {
    }

    public BuildingEngine(Assets assets) {
        this.assets = assets;
    }

    private void warnOnce(String key, String message) {
        if (warned.add(key)) {
            LostBuildings.LOGGER.warn("[lostbuildings] {}", message);
        }
    }

    private CompiledPalette derive(CompiledPalette base, Palette extra) {
        return derivedPaletteCache.computeIfAbsent(new DerivedPaletteKey(base, extra),
                k -> new CompiledPalette(k.base(), k.extra()));
    }

    /**
     * Build a compiled palette for a building style: from every random-palette group, one palette
     * is chosen (weighted by factor) and all chosen palettes are merged.
     */
    public CompiledPalette buildPalette(Assets a, RandomSource rand, Style style) {
        if (style == null) {
            warnOnce("nullstyle", "buildPalette() called with a null style - using an empty palette");
            style = Style.empty("<null>");
        }
        List<Palette> chosen = new ArrayList<>();
        for (List<PaletteSelector> group : style.getRandomPaletteChoices()) {
            if (group.isEmpty()) {
                continue;
            }
            PaletteSelector selector = Tools.getRandomFromList(rand, group, PaletteSelector::factor);
            if (selector == null) {
                continue;
            }
            Palette palette = a.getPalette(selector.palette());
            if (palette != null) {
                chosen.add(palette);
            }
        }
        // Key on the identity of the chosen palettes: List.equals/hashCode fall through to Palette's
        // identity equals, which is exactly the "set of selected palettes" key we want.
        return paletteCache.computeIfAbsent(List.copyOf(chosen),
                key -> new CompiledPalette(key.toArray(new Palette[0])));
    }

    /**
     * Generate a whole building at the given origin (the world position of the bottom corner of
     * the ground floor). Floors are stacked upward; cellars are not generated.
     */
    public void generateBuilding(WorldGenLevel level, BlockPos origin, RandomSource rand, Transform t,
                                 Building b, CompiledPalette pal, PlaceSettings s) {
        CompiledPalette palette = pal;
        Palette buildingPalette = b.getLocalPalette(assets);
        if (buildingPalette != null) {
            palette = derive(pal, buildingPalette);
        }

        int floors = pickFloors(b, rand, s);

        // Positions of connectable blocks (panes/bars/fences/walls/stairs) placed by this building.
        // They are re-corrected in a second pass below, once every neighbour exists — otherwise a
        // block placed before its same-building neighbour never sees it and stays disconnected.
        List<BlockPos> connectables = new ArrayList<>();

        int height = 0;
        for (int f = 0; f <= floors; f++) {
            boolean isTop = (f == floors);
            boolean isGround = (f == 0);
            String partName = b.getRandomPart(rand, isTop, isGround, false, f);
            if (partName != null) {
                BuildingPart part = assets.getPart(partName);
                if (part != null) {
                    generatePart(level, origin, part, t, 0, height, 0, palette, rand, s, connectables);
                }
            }
            height += FLOORHEIGHT;
        }

        // Second correction pass: now the whole building is in the world, so pane/bar/fence/wall
        // connections and stair shapes resolve against all four neighbours (bug: adjacent stained
        // glass panes were not connecting because the neighbour was placed after correction).
        for (BlockPos pos : connectables) {
            BlockState cur = level.getBlockState(pos);
            BlockState fixed = BlockStates.correct(level, pos, cur);
            if (fixed != null && fixed != cur) {
                level.setBlock(pos, fixed, SET_FLAGS);
            }
        }
    }

    private int pickFloors(Building b, RandomSource rand, PlaceSettings s) {
        // A caller that already knows the storey count says so by passing an exact range. The
        // placement pass needs the number up front (it clears the volume above the house before
        // the engine runs), so it picks via pickFloors(...) below and hands the answer back here.
        // Re-deriving it would let the two disagree and leave the top floors buried in terrain.
        if (s.minFloors() == s.maxFloors()) {
            return s.minFloors();
        }
        return pickFloors(b, rand, s.minFloors(), s.maxFloors());
    }

    /**
     * Canonical storey-count selection: a building that declares its own floor limits wins over the
     * feature config. Intersecting the two ranges instead produced nonsense for buildings that only
     * fit at one height (e.g. "cabin" with minfloors=maxfloors=1 came out as a 2-3 storey stack).
     *
     * <p>Public because {@code GroupBuildingPlacement} must reach the same number before generation
     * starts; keeping one implementation is what stops the two from drifting apart.
     */
    public static int pickFloors(Building b, RandomSource rand, int configMin, int configMax) {
        int min = configMin;
        int max = configMax;
        if (b.getMinFloors() >= 0) {
            min = b.getMinFloors();
        }
        if (b.getMaxFloors() >= 0) {
            max = b.getMaxFloors();
        }
        if (max < min) {
            max = min;
        }
        return max > min ? min + rand.nextInt(max - min + 1) : min;
    }

    /**
     * Generate a single part at (ox, oy, oz) relative to origin. Returns the y level above the part.
     */
    private int generatePart(WorldGenLevel level, BlockPos origin, IBuildingPart part, Transform transform,
                             int ox, int oy, int oz, CompiledPalette basePalette, RandomSource rand, PlaceSettings s,
                             List<BlockPos> connectables) {
        CompiledPalette compiledPalette = basePalette;
        Palette partPalette = part.getLocalPalette(assets);
        if (partPalette != null) {
            compiledPalette = derive(basePalette, partPalette);
        }

        int xSize = part.getXSize();
        int zSize = part.getZSize();
        for (int x = 0; x < xSize; x++) {
            for (int z = 0; z < zSize; z++) {
                char[] vs = part.getVSlice(x, z);
                if (vs == null) {
                    continue;
                }
                int rx = ox + transform.rotateX(x, z, xSize, zSize);
                int rz = oz + transform.rotateZ(x, z, xSize, zSize);
                for (int y = 0; y < vs.length; y++) {
                    char c = vs[y];
                    BlockState b = compiledPalette.get(c, rand);
                    if (b == null) {
                        // A broken/foreign datapack must not kill chunk generation: warn once per
                        // (part, character) and treat the cell as air (= leave the world untouched).
                        warnOnce("palette:" + part.getName() + ":" + c,
                                "Could not find entry '" + c + "' in the palette for part '" + part.getName() + "' - using air");
                        continue;
                    }
                    if (transform != Transform.ROTATE_NONE) {
                        b = b.rotate(transform.getMcRotation());
                    }
                    // Air means "leave the world untouched here".
                    if (b == BlockStates.AIR || b == BlockStates.STRUCTURE_VOID) {
                        continue;
                    }

                    BlockPos pos = origin.offset(rx, oy + y, rz);
                    Palette.Info inf = compiledPalette.getInfo(c);

                    if (inf != null) {
                        if (inf.isTorch()) {
                            if (!s.lighting()) {
                                continue;   // no torches
                            }
                        } else if (inf.mobId() != null && !inf.mobId().isEmpty()) {
                            if (!s.spawners()) {
                                continue;   // no spawners -> leave empty
                            }
                            handleSpawner(level, pos, inf.mobId(), rand);
                        } else {
                            String loot = (s.loot() && inf.loot() != null && !inf.loot().isEmpty()) ? inf.loot() : null;
                            if (inf.tag() != null || loot != null) {
                                handleBlockEntity(level, pos, b, inf.tag(), loot, rand);
                            }
                        }
                    }

                    BlockState corrected = BlockStates.correct(level, pos, b);
                    if (corrected == null) {
                        continue;   // STRUCTURE_VOID passthrough
                    }
                    level.setBlock(pos, corrected, SET_FLAGS);

                    Block cb = corrected.getBlock();
                    if (cb instanceof CrossCollisionBlock || cb instanceof WallBlock || cb instanceof StairBlock) {
                        connectables.add(pos.immutable());
                    }
                }
            }
        }
        return oy + part.getSliceCount();
    }

    // --- inlined handlers (run synchronously) ---

    private void handleSpawner(WorldGenLevel level, BlockPos pos, String mobCondition, RandomSource rand) {
        String mobId = resolveCondition(mobCondition, rand);
        if (mobId == null) {
            return;
        }
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:mob_spawner");
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());
        CompoundTag entity = new CompoundTag();
        entity.putString("id", mobId);
        CompoundTag spawnData = new CompoundTag();
        spawnData.put("entity", entity);
        tag.put("SpawnData", spawnData);
        level.getChunk(pos).setBlockEntityNbt(tag);
    }

    /**
     * Write the pending block-entity NBT for a position. During the FEATURES step the chunk is still
     * a {@code ProtoChunk} and {@link WorldGenLevel#getBlockEntity} returns {@code null} there, so
     * the data has to go through {@code ChunkAccess.setBlockEntityNbt} — it is applied when the
     * chunk is promoted and the real block entity is created.
     *
     * <p>The loot table is stored the way {@code RandomizableContainer.tryLoadLootTable} reads it
     * back in 26.2: {@code "LootTable"} (a plain resource-location string, decoded with
     * {@code LootTable.KEY_CODEC}) and {@code "LootTableSeed"} (a long; 0 means "roll a fresh random
     * seed on first open", so a seed derived from the worldgen random is written for determinism).
     */
    private void handleBlockEntity(WorldGenLevel level, BlockPos pos, BlockState b,
                                   @Nullable CompoundTag extra, @Nullable String lootCondition, RandomSource rand) {
        BlockEntityType<?> type = getTypeForBlock(b);
        if (type == null) {
            return;
        }
        Identifier typeKey = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
        if (typeKey == null) {
            return;
        }
        CompoundTag tag = extra == null ? new CompoundTag() : extra.copy();
        if (lootCondition != null) {
            String lootTable = resolveCondition(lootCondition, rand);
            if (lootTable != null && !lootTable.isEmpty()) {
                Identifier lootId = Identifier.tryParse(lootTable);
                if (lootId == null) {
                    warnOnce("loottable:" + lootTable, "Invalid loot table id '" + lootTable + "' - ignored");
                } else {
                    tag.putString(LOOT_TABLE_TAG, lootId.toString());
                    tag.putLong(LOOT_TABLE_SEED_TAG, rand.nextLong());
                }
            }
        }
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());
        tag.putString("id", typeKey.toString());
        level.getChunk(pos).setBlockEntityNbt(tag);
    }

    private BlockEntityType<?> getTypeForBlock(BlockState state) {
        return typeCache.computeIfAbsent(state.getBlock(), block -> {
            for (BlockEntityType<?> type : BuiltInRegistries.BLOCK_ENTITY_TYPE) {
                if (type.isValid(state)) {
                    return type;
                }
            }
            return null;
        });
    }

    /**
     * Resolve a condition reference (e.g. "chestloot", "easymobs") to a concrete value by picking a
     * factor-weighted entry. Position/part filters are ignored (simplified for intact buildings).
     * If the name is not a known condition it is returned unchanged (treated as a literal value).
     */
    private String resolveCondition(String name, RandomSource rand) {
        ConditionRE condition = assets.getCondition(name);
        if (condition == null) {
            return name;
        }
        List<ConditionPart> values = condition.getValues();
        if (values.isEmpty()) {
            return null;
        }
        ConditionPart chosen = Tools.getRandomFromList(rand, values, ConditionPart::getFactor);
        return chosen == null ? null : chosen.getValue();
    }
}
