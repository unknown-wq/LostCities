package com.lostbuildings.engine;

import com.lostbuildings.LostBuildings;
import com.lostbuildings.engine.codec.ConditionRE;
import com.lostbuildings.engine.codec.PaletteSelector;
import com.lostbuildings.engine.condition.ConditionContext;
import com.lostbuildings.engine.condition.ConditionResolver;
import com.lostbuildings.engine.damage.DamageArea;
import com.lostbuildings.engine.damage.Weathering;
import com.lostbuildings.engine.util.Tools;
import com.lostbuildings.world.feature.WorldGenBounds;
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
 *
 * <h2>Wave 2</h2>
 * Three features were added without changing the shape of {@link #generateBuilding}:
 * <ul>
 *   <li><b>Cellars</b> ({@link PlaceSettings#cellars}). The storey loop runs from {@code -cellars}
 *       upward. Each cellar slice is carved to air before its part is written — see
 *       {@link #carveCellar} for why that is not optional.</li>
 *   <li><b>Damage</b> ({@link PlaceSettings#damageChance}). {@link DamageArea} lays out blast
 *       spheres and per-storey weathering; {@link Weathering} turns a damage number into a block
 *       swap. At {@code damageChance == 0} not a single extra number is drawn from the building's
 *       random stream, so an undamaged building is exactly what the mod generated before.</li>
 *   <li><b>Honest conditions</b> ({@link ConditionResolver}). Loot tables and spawner mobs now
 *       resolve against the position and the {@link BuildingRole} instead of a blind weighted pick.
 *       The number of values drawn from the random stream is unchanged, so this does not move the
 *       blocks around either.</li>
 * </ul>
 */
public class BuildingEngine {

    private static final int FLOORHEIGHT = 6;
    // Worldgen-safe flags: notify clients, do not trigger neighbour updates (bit 1 must stay off).
    private static final int SET_FLAGS = Block.UPDATE_CLIENTS;

    // net.minecraft.world.RandomizableContainer.LOOT_TABLE_TAG / LOOT_TABLE_SEED_TAG (26.2).
    private static final String LOOT_TABLE_TAG = "LootTable";
    private static final String LOOT_TABLE_SEED_TAG = "LootTableSeed";

    /** Condition names the "nastier spawner higher up" promotion moves between. */
    private static final String EASY_MOBS = "easymobs";
    private static final String HARD_MOBS = "hardmobs";

    /** Fraction of a damaged building's ground floor that ends up under debris. */
    private static final float RUBBLE_COVERAGE = 0.35f;

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
     * How deep below {@code origin} a building generated with these settings reaches.
     *
     * <p><b>Contract for the placement pass.</b> {@code Foundation} pillars solid fill downward from
     * one block under the building base; run unchanged against a building with cellars it would
     * simply backfill the hole. Callers that pass {@code cellars > 0} must therefore either keep
     * their support pillars below {@code origin.getY() - cellarDepth(settings)} or size the piece's
     * bounding box to include it. The engine defends itself as well ({@link #carveCellar} re-clears
     * every cellar slice before writing it), so a caller that forgets does not produce a solid
     * block of cobblestone — it only wastes the writes.
     */
    public static int cellarDepth(PlaceSettings settings) {
        return settings.cellars() * FLOORHEIGHT;
    }

    /** Blocks per storey; the placement pass needs the same number to size its clear volume. */
    public static int floorHeight() {
        return FLOORHEIGHT;
    }

    /**
     * Generate a whole building. {@code origin} is the world position of the bottom corner of the
     * <em>ground floor</em>: floors stack upward from it and cellars are dug downward from it, so a
     * building keeps its street-level height no matter how many cellars it has.
     */
    public void generateBuilding(WorldGenLevel level, BlockPos origin, RandomSource rand, Transform t,
                                 Building b, CompiledPalette pal, PlaceSettings s) {
        CompiledPalette palette = pal;
        Palette buildingPalette = b.getLocalPalette(assets);
        if (buildingPalette != null) {
            palette = derive(pal, buildingPalette);
        }

        int floors = pickFloors(b, rand, s);
        int cellars = pickCellars(b, s);

        int chunkX = origin.getX() >> 4;
        int chunkZ = origin.getZ() >> 4;

        // Laid out from the world seed and the cell coordinate alone — never from `rand`, so an
        // undamaged building draws exactly the same numbers it did before wave 2.
        DamageArea damage = DamageArea.forBuilding(level.getSeed(), chunkX, chunkZ, origin.getY(),
                floors, cellars, FLOORHEIGHT, s.damageChance());

        ConditionContext base = new ConditionContext(0, floors, cellars, null, b.getName(),
                chunkX, chunkZ, s.role());

        // Positions of connectable blocks (panes/bars/fences/walls/stairs) placed by this building.
        // They are re-corrected in a second pass below, once every neighbour exists — otherwise a
        // block placed before its same-building neighbour never sees it and stays disconnected.
        List<BlockPos> connectables = new ArrayList<>();

        int footprintX = 0;
        int footprintZ = 0;

        for (int f = -cellars; f <= floors; f++) {
            ConditionContext ctx = base.atFloor(f);
            String partName = b.getRandomPart(rand, ctx);
            if (partName == null && f < 0) {
                // The building declares no cellar part (only the town* quadrants do in the shipped
                // data). Reuse an ordinary storey slice underground rather than skipping the level:
                // its walls and stairwell are what makes the cellar readable, and being buried is
                // what makes it dark. Logged as a §9 simplification, not as a bug.
                // Storey 1 rather than 0, so the cellar does not inherit the front door — unless the
                // building is a bungalow, where storey 1 would be its roof.
                partName = b.getRandomPart(rand, base.atFloor(floors >= 2 ? 1 : 0));
            }
            if (partName == null) {
                continue;
            }
            BuildingPart part = assets.getPart(partName);
            if (part == null) {
                continue;
            }
            int height = f * FLOORHEIGHT;
            if (f < 0) {
                carveCellar(level, origin, part, height);
            }
            footprintX = Math.max(footprintX, part.getXSize());
            footprintZ = Math.max(footprintZ, part.getZSize());
            generatePart(level, origin, part, t, 0, height, 0, palette, rand, s,
                    connectables, ctx.inPart(part.getName()), damage);
        }

        if (!damage.isIntact()) {
            scatterRubble(level, origin, b, palette, rand, damage, footprintX, footprintZ);
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
     * <p>Public because the placement pass must reach the same number before generation starts;
     * keeping one implementation is what stops the two from drifting apart.
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
     * How many cellars this building gets. The building's own {@code mincellars}/{@code maxcellars}
     * clamp the caller's request, the same way the floor range works — {@code shopping*} declares
     * {@code maxcellars: 0} because its ground floor is a shop window, and that has to win.
     *
     * <p>Deliberately draws nothing from the random stream: the caller has already decided the
     * number (it has to, so that the piece's bounding box and the excavation can account for it),
     * and a second roll here could only disagree with it.
     */
    public static int pickCellars(Building b, PlaceSettings s) {
        int cellars = s.cellars();
        if (b.getMaxCellars() >= 0) {
            cellars = Math.min(cellars, b.getMaxCellars());
        }
        if (b.getMinCellars() >= 0) {
            cellars = Math.max(cellars, b.getMinCellars());
        }
        return Math.clamp(cellars, 0, PlaceSettings.MAX_CELLARS);
    }

    /**
     * Clear one cellar slice before its part is written.
     *
     * <p>This is not belt-and-braces. The placement pass runs {@code Foundation} first, and
     * {@code Foundation} pillars solid fill downward from below the building base — straight through
     * the volume the cellar is about to occupy. A palette's "air" character means <em>leave the
     * world alone</em>, so without this pass a cellar would be generated as a solid block of
     * foundation fill with a doorway painted on it.
     */
    private void carveCellar(WorldGenLevel level, BlockPos origin, BuildingPart part, int height) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int slices = part.getSliceCount();
        // Square extent: a rotated non-square part occupies the transposed rectangle, and carving
        // the enclosing square costs nothing (WorldGenBounds clips anything outside the cell).
        int extent = Math.max(part.getXSize(), part.getZSize());
        for (int x = 0; x < extent; x++) {
            for (int z = 0; z < extent; z++) {
                for (int y = 0; y < slices; y++) {
                    cursor.set(origin.getX() + x, origin.getY() + height + y, origin.getZ() + z);
                    if (!WorldGenBounds.canRead(level, cursor)) {
                        continue;
                    }
                    if (!level.getBlockState(cursor).isAir()) {
                        level.setBlock(cursor, BlockStates.AIR, SET_FLAGS);
                    }
                }
            }
        }
    }

    /**
     * Strew the building's own rubble material over the ground floor of a damaged building.
     *
     * <p>The shipped buildings all declare a {@code rubble} palette character (a broken-brick
     * variant); using it keeps the debris in the building's material instead of dropping generic
     * gravel into a sandstone house. Only ever writes into air, so it fills the holes the blast
     * left rather than replacing surviving floor.
     */
    private void scatterRubble(WorldGenLevel level, BlockPos origin, Building b, CompiledPalette palette,
                               RandomSource rand, DamageArea damage, int sizeX, int sizeZ) {
        Character rubbleChar = b.getRubbleBlock();
        if (rubbleChar == null || sizeX <= 0 || sizeZ <= 0) {
            return;
        }
        float coverage = damage.rubbleFactor() * RUBBLE_COVERAGE;
        if (coverage <= 0.0f) {
            return;
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                if (rand.nextFloat() >= coverage) {
                    continue;
                }
                cursor.set(origin.getX() + x, origin.getY(), origin.getZ() + z);
                if (!WorldGenBounds.canRead(level, cursor)) {
                    continue;
                }
                if (!level.getBlockState(cursor).isAir()) {
                    continue;
                }
                BlockState rubble = palette.get(rubbleChar.charValue(), rand);
                if (rubble == null || rubble == BlockStates.AIR || rubble == BlockStates.STRUCTURE_VOID) {
                    continue;
                }
                level.setBlock(cursor, rubble, SET_FLAGS);
            }
        }
    }

    /**
     * Generate a single part at (ox, oy, oz) relative to origin. Returns the y level above the part.
     */
    private int generatePart(WorldGenLevel level, BlockPos origin, IBuildingPart part, Transform transform,
                             int ox, int oy, int oz, CompiledPalette basePalette, RandomSource rand, PlaceSettings s,
                             List<BlockPos> connectables, ConditionContext ctx, DamageArea damage) {
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
                    // Shipped parts are 16x16 and sites are chunk-aligned inside the write window,
                    // so this never fires — but a datapack part wider than a chunk would otherwise
                    // read and write outside the window, and the region logs both.
                    if (!WorldGenBounds.canRead(level, pos)) {
                        continue;
                    }
                    Palette.Info inf = compiledPalette.getInfo(c);

                    // Damage first: a block that the blast ate never becomes a chest or a spawner,
                    // and a block that only weathered keeps whatever the palette attached to it.
                    // damageAt() returns 0 for an undamaged building, and Weathering short-circuits
                    // on 0 without drawing anything, so this whole branch is free at damageChance 0.
                    float dmg = damage.damageAt(pos.getX(), pos.getY(), pos.getZ(), ctx.floor());
                    if (dmg > 0.0f) {
                        BlockState after = Weathering.damage(b, dmg, pos.getY(), s.waterLevel(), compiledPalette, rand);
                        if (after != b) {
                            if (after == BlockStates.AIR) {
                                continue;   // destroyed: leave the hole
                            }
                            b = after;
                            inf = null;     // a cracked wall is no longer a chest
                        }
                    }

                    if (inf != null) {
                        if (inf.isTorch()) {
                            if (!s.lighting()) {
                                continue;   // no torches
                            }
                        } else if (inf.mobId() != null && !inf.mobId().isEmpty()) {
                            if (!s.spawners()) {
                                continue;   // no spawners -> leave empty
                            }
                            handleSpawner(level, pos, inf.mobId(), rand, ctx);
                        } else {
                            String loot = (s.loot() && inf.loot() != null && !inf.loot().isEmpty()) ? inf.loot() : null;
                            if (inf.tag() != null || loot != null) {
                                handleBlockEntity(level, pos, b, inf.tag(), loot, rand, ctx);
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

    private void handleSpawner(WorldGenLevel level, BlockPos pos, String mobCondition, RandomSource rand,
                               ConditionContext ctx) {
        String mobId = resolveCondition(promote(mobCondition, level.getSeed(), pos, ctx), rand, ctx,
                ConditionResolver.Kind.MOB);
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
     * "The higher you climb, the worse the welcome": an {@code easymobs} spawner is promoted to
     * {@code hardmobs} with a probability that rises with the storey and with the building's role
     * (IMPROVEMENTS #2). A palette character fixes which condition a spawner uses, so promoting the
     * condition is the only way to make height matter without rewriting all 186 parts.
     *
     * <p>The roll comes from a hash of the world seed and the spawner's position rather than from
     * the building's {@link RandomSource}. That is deliberate: it is just as reproducible, it does
     * not depend on how many blocks were placed first, and — the point — it draws nothing, so the
     * whole feature leaves the block-placement stream untouched.
     */
    private String promote(String mobCondition, long worldSeed, BlockPos pos, ConditionContext ctx) {
        if (!EASY_MOBS.equals(mobCondition) || assets.getCondition(HARD_MOBS) == null) {
            return mobCondition;
        }
        float chance = ctx.role().hardMobChance(ctx.floor());
        if (chance <= 0.0f) {
            return mobCondition;
        }
        return positionRoll(worldSeed, pos.getX(), pos.getY(), pos.getZ()) < chance ? HARD_MOBS : mobCondition;
    }

    /** A stable float in {@code [0, 1)} for a world position — SplitMix64 over seed and coordinates. */
    private static float positionRoll(long worldSeed, int x, int y, int z0) {
        long z = worldSeed
                ^ (x * 0x9E3779B97F4A7C15L)
                ^ (y * 0xC2B2AE3D27D4EB4FL)
                ^ (z0 * 0x165667B19E3779F9L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z = z ^ (z >>> 31);
        return (z >>> 40) / (float) (1 << 24);
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
                                   @Nullable CompoundTag extra, @Nullable String lootCondition, RandomSource rand,
                                   ConditionContext ctx) {
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
            // Rolled unconditionally, even when the role's signature table is about to win, so that
            // the number of values taken from the building's random stream never depends on a loot
            // decision — otherwise every block placed after a chest would move.
            String rolled = resolveCondition(lootCondition, rand, ctx, ConditionResolver.Kind.LOOT);
            String signature = signatureLoot(lootCondition, ctx, level.getSeed(), pos);
            String lootTable = signature != null ? signature : rolled;
            // Same reason: the seed is drawn whether or not a table was found.
            long lootSeed = rand.nextLong();
            if (lootTable != null && !lootTable.isEmpty()) {
                Identifier lootId = Identifier.tryParse(lootTable);
                if (lootId == null) {
                    warnOnce("loottable:" + lootTable, "Invalid loot table id '" + lootTable + "' - ignored");
                } else {
                    tag.putString(LOOT_TABLE_TAG, lootId.toString());
                    tag.putLong(LOOT_TABLE_SEED_TAG, lootSeed);
                }
            }
        }
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());
        tag.putString("id", typeKey.toString());
        level.getChunk(pos).setBlockEntityNbt(tag);
    }

    /**
     * A role's signature loot table, or {@code null} to fall through to the condition.
     *
     * <p>Rolled from the same position-derived stream as the spawner promotion, and for the same
     * reason: a library that hands out stronghold-library loot must not shift the blocks of the
     * library around. Only applies to real conditions — a palette entry that names a loot table
     * outright is taken at its word.
     */
    @Nullable
    private String signatureLoot(String lootCondition, ConditionContext ctx, long worldSeed, BlockPos pos) {
        if (assets.getCondition(lootCondition) == null) {
            return null;
        }
        String signature = ctx.role().signatureLoot();
        if (signature == null) {
            return null;
        }
        // A salt distinct from the spawner roll, so the two decisions stay independent.
        float roll = positionRoll(worldSeed ^ 0x2545F4914F6CDD1DL, pos.getX(), pos.getY(), pos.getZ());
        return roll < ctx.role().signatureChance(ctx.floor()) ? signature : null;
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
     * Resolve a condition reference (e.g. "chestloot", "easymobs") to a concrete value.
     *
     * <p>Since wave 2 this is an honest resolution: candidates whose conditions do not hold at
     * {@code ctx} are removed from the bag and the survivors are re-weighted by the building's role
     * ({@link ConditionResolver}). Exactly one float is drawn, the same as the weights-only pick it
     * replaces. If the name is not a known condition it is returned unchanged and nothing is drawn.
     */
    private String resolveCondition(String name, RandomSource rand, ConditionContext ctx, ConditionResolver.Kind kind) {
        ConditionRE condition = assets.getCondition(name);
        if (condition == null) {
            return name;
        }
        return ConditionResolver.resolve(condition.getValues(), ctx, kind, rand);
    }
}
