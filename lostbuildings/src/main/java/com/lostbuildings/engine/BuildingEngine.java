package com.lostbuildings.engine;

import com.lostbuildings.engine.codec.ConditionPart;
import com.lostbuildings.engine.codec.ConditionRE;
import com.lostbuildings.engine.codec.PaletteSelector;
import com.lostbuildings.engine.util.Tools;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootTable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    private final Assets assets;
    private final Map<Block, BlockEntityType<?>> typeCache = new HashMap<>();

    public BuildingEngine(Assets assets) {
        this.assets = assets;
    }

    /**
     * Build a compiled palette for a building style: from every random-palette group, one palette
     * is chosen (weighted by factor) and all chosen palettes are merged.
     */
    public CompiledPalette buildPalette(Assets a, RandomSource rand, Style style) {
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
        return new CompiledPalette(chosen.toArray(new Palette[0]));
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
            palette = new CompiledPalette(pal, buildingPalette);
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

        // Final pass: procedural weathering so the finished building reads as an aged ruin rather
        // than a pristine structure. Operates only within this building's 16x16 footprint column,
        // from the ground floor up to the generated top (height == (floors+1) * FLOORHEIGHT here).
        Weathering.apply(level, origin, 16, height, rand);
    }

    private int pickFloors(Building b, RandomSource rand, PlaceSettings s) {
        int min = s.minFloors();
        int max = s.maxFloors();
        if (b.getOverrideFloors() != null && b.getOverrideFloors() && b.getMinFloors() >= 0 && b.getMaxFloors() >= 0) {
            min = b.getMinFloors();
            max = b.getMaxFloors();
        } else {
            if (b.getMinFloors() >= 0) {
                min = Math.max(min, b.getMinFloors());
            }
            if (b.getMaxFloors() >= 0) {
                max = Math.min(max, b.getMaxFloors());
            }
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
            compiledPalette = new CompiledPalette(basePalette, partPalette);
        }

        for (int x = 0; x < part.getXSize(); x++) {
            for (int z = 0; z < part.getZSize(); z++) {
                char[] vs = part.getVSlice(x, z);
                if (vs == null) {
                    continue;
                }
                int rx = ox + transform.rotateX(x, z);
                int rz = oz + transform.rotateZ(x, z);
                for (int y = 0; y < vs.length; y++) {
                    char c = vs[y];
                    BlockState b = compiledPalette.get(c);
                    if (b == null) {
                        throw new RuntimeException("Could not find entry '" + c + "' in the palette for part '" + part.getName() + "'!");
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
                        } else if (inf.tag() != null) {
                            handleBlockEntity(level, pos, b, inf);
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

                    if (inf != null && inf.loot() != null && !inf.loot().isEmpty() && s.loot()) {
                        handleLoot(level, pos, inf.loot(), rand);
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

    private void handleLoot(WorldGenLevel level, BlockPos pos, String lootCondition, RandomSource rand) {
        String lootTable = resolveCondition(lootCondition, rand);
        if (lootTable == null) {
            return;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof RandomizableContainerBlockEntity container) {
            ResourceKey<LootTable> key = ResourceKey.create(Registries.LOOT_TABLE, Identifier.parse(lootTable));
            container.setLootTable(key);
        }
    }

    private void handleBlockEntity(WorldGenLevel level, BlockPos pos, BlockState b, Palette.Info inf) {
        BlockEntityType<?> type = getTypeForBlock(b);
        if (type == null) {
            return;
        }
        Identifier typeKey = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
        if (typeKey == null) {
            return;
        }
        CompoundTag tag = inf.tag().copy();
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
