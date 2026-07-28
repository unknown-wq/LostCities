package com.lostbuildings.world.structure.piece;

import com.lostbuildings.LostBuildings;
import com.lostbuildings.engine.Assets;
import com.lostbuildings.engine.BuildingEngine;
import com.lostbuildings.engine.BuildingPart;
import com.lostbuildings.engine.CompiledPalette;
import com.lostbuildings.engine.Style;
import com.lostbuildings.engine.Transform;
import com.lostbuildings.registry.ModStructurePieceTypes;
import com.lostbuildings.world.feature.Streets;
import com.lostbuildings.world.feature.StyleSelector;
import com.lostbuildings.world.feature.WorldGenFlags;
import com.lostbuildings.world.structure.CityLayout;
import com.lostbuildings.world.structure.StreetDecor;
import com.lostbuildings.world.structure.StreetTiles;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;

/**
 * One paved cell of the city grid — the cells the grid leaves free between building lots.
 *
 * <p><b>What wave 2 changed (PORT #3, IMPROVEMENTS #4).</b> Wave 1 laid a flat rectangle of
 * {@code stone_bricks} and said so. This piece now lays that rectangle only as a <em>base course</em>
 * and stamps one of the shipped 16×16 {@code street_*} parts on top of it — carriageway, pavement
 * and all — chosen from the cell's road-connectivity mask by the pure {@link StreetTiles}. The base
 * course still matters: the tiles express pavement as {@code structure_void}, i.e. "leave what is
 * underneath alone", so the pavement you see is the configured {@code street_block} showing through.
 *
 * <p>On top of the tile come the kerbs, the lamp posts and the potholes ({@link StreetDecor}). Doing
 * it here rather than in a separate piece is the cheap option by a wide margin: the columns are
 * already resolved, the chunk is already the right one, and no extra piece has to be serialised.
 *
 * <p>Everything about the layout is decided by pure functions over the mask and the absolute block
 * coordinate, so a street looks the same however its chunks happen to be generated, and lamp posts
 * line up across cell boundaries instead of restarting at each one.
 */
public class StreetPiece extends CityPiece {

	/** Room below the pavement for the embankment, and above it for the cleared headroom. */
	private static final int BELOW_GROUND = 12;
	private static final int ABOVE_GROUND = 6;

	/**
	 * Height of a lamp post, not counting the lantern on top.
	 *
	 * <p>Counted from {@code groundY}, which is the <em>top</em> of the pavement course the tiles lay,
	 * not the surface someone walks on: the bottom post block is flush with the paving and invisible.
	 * A post of 3 therefore read as two bars and a lantern at head height — a bollard, not a street
	 * light. Five puts four bars above the pavement with the lantern at {@code groundY + 5}, one block
	 * under the top of the piece's box ({@code groundY + ABOVE_GROUND}), so it still fits and is still
	 * written.
	 *
	 * <p>Nothing else reaches this high on the kerb: the tiles put their tall decoration
	 * ({@code w}alls, {@code A}rches, torches) on pavement columns only, deliberately keeping the kerb
	 * ring clear because {@link #decorate} overwrites it.
	 */
	private static final int LAMP_HEIGHT = 5;
	/** Share of lamp posts that lost their light — a lost city, not a serviced one. */
	private static final double BROKEN_LAMP_CHANCE = 0.25D;
	/** Salt for the tile-variant roll, so it is independent of every other per-cell decision. */
	private static final int VARIANT_SALT = 0x53;

	private static final BlockState AIR = Blocks.AIR.defaultBlockState();
	private static final BlockState KERB = Blocks.SMOOTH_STONE_SLAB.defaultBlockState()
			.setValue(SlabBlock.TYPE, SlabType.BOTTOM);
	private static final BlockState LAMP_POST = Blocks.IRON_BARS.defaultBlockState();
	private static final BlockState LAMP_HEAD = Blocks.LANTERN.defaultBlockState();
	private static final BlockState POTHOLE_RIM = Blocks.CRACKED_STONE_BRICKS.defaultBlockState();

	private final BlockState material;
	private final int width;
	private final int neighbourMask;
	private final String styleName;
	private final boolean tiles;
	private final int lampSpacing;
	private final float potholeChance;

	public StreetPiece(int chunkX, int chunkZ, int groundY, BlockState material, int width, int neighbourMask,
	                   String styleName, boolean tiles, int lampSpacing, float potholeChance,
	                   StyleSelector.Climate climate) {
		super(ModStructurePieceTypes.STREET, cellBox(chunkX, chunkZ, groundY, BELOW_GROUND, ABOVE_GROUND), groundY,
				climate);
		this.material = material;
		this.width = width;
		this.neighbourMask = neighbourMask;
		this.styleName = styleName;
		this.tiles = tiles;
		this.lampSpacing = lampSpacing;
		this.potholeChance = potholeChance;
	}

	public StreetPiece(CompoundTag tag) {
		super(ModStructurePieceTypes.STREET, tag);
		this.material = tag.read("Material", BlockState.CODEC).orElse(Streets.DEFAULT_STREET);
		this.width = tag.getIntOr("Width", 16);
		this.neighbourMask = tag.getIntOr("Neighbours", 0);
		this.styleName = tag.getStringOr("Style", StyleSelector.DEFAULT_STYLE);
		this.tiles = tag.getBooleanOr("Tiles", true);
		this.lampSpacing = tag.getIntOr("Lamps", 0);
		this.potholeChance = tag.getFloatOr("Potholes", 0.0F);
	}

	@Override
	protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
		super.addAdditionalSaveData(context, tag);
		tag.store("Material", BlockState.CODEC, this.material);
		tag.putInt("Width", this.width);
		tag.putInt("Neighbours", this.neighbourMask);
		tag.putString("Style", this.styleName);
		tag.putBoolean("Tiles", this.tiles);
		tag.putInt("Lamps", this.lampSpacing);
		tag.putFloat("Potholes", this.potholeChance);
	}

	/** Which orthogonal neighbours are also roads — the input for the tile choice. */
	public int neighbourMask() {
		return this.neighbourMask;
	}

	@Override
	public void postProcess(WorldGenLevel level, StructureManager structureManager, ChunkGenerator generator,
	                        RandomSource random, BoundingBox chunkBox, ChunkPos chunkPos, BlockPos referencePos) {
		if (!coversCell(chunkBox)) {
			return;
		}
		// Base course first: it levels the cell, clears the headroom and carries the road down a
		// slope. The tile is laid into the surface it leaves behind.
		Streets.paveCell(level, chunkBox, cellMinX(), cellMinZ(), this.groundY, this.material, this.width);

		long seed = level.getSeed();
		RandomSource rand = cellRandom(seed, 0x57);
		int surfaceY = this.groundY - 1;

		if (this.tiles) {
			layTile(level, chunkBox, rand, seed);
		}
		decorate(level, chunkBox, seed, surfaceY);
		weather(level, chunkBox, seed);
	}

	/**
	 * Stamp the {@code street_*} part chosen for this cell's connectivity over the base course.
	 *
	 * <p>Which of the family's variants is laid comes from the world seed and this cell's coordinate
	 * and nothing else, exactly like the potholes and the broken lamps: a road that regenerates has to
	 * come back the same, and two cells of the same shape have to be allowed to differ — otherwise a
	 * long straight is the same sixteen blocks over and over.
	 */
	private void layTile(WorldGenLevel level, BoundingBox chunkBox, RandomSource rand, long seed) {
		Assets assets = LostBuildings.ASSETS;
		BuildingEngine engine = LostBuildings.ENGINE;
		if (assets == null || engine == null) {
			return;     // assets not loaded yet; the base course alone is a road, just a plain one
		}
		StreetTiles.Tile tile = StreetTiles.forMask(this.neighbourMask,
				CityLayout.hash(seed, cellChunkX(), cellChunkZ(), VARIANT_SALT));
		BuildingPart part = assets.getPart(tile.part());
		if (part == null) {
			// A datapack that removed one variant must not blank the cell: fall back to the family.
			part = assets.getPart(StreetTiles.forMask(this.neighbourMask).part());
		}
		if (part == null) {
			return;
		}
		Style style = assets.getStyle(this.styleName);
		CompiledPalette palette = engine.buildPalette(assets, rand, style);
		Transform transform = Transform.values()[Math.floorMod(tile.quarterTurns(), 4)];
		// The tile's slice 0 is the carriageway itself, so it replaces the base course rather than
		// sitting on it: the part goes at the surface, not one above. Slice 1 is then the raised
		// pavement course at groundY, and the shipped tiles carry three more decoration slices — the
		// piece's box has room for eight in all (groundY - 1 .. groundY + ABOVE_GROUND).
		PartPlacer.place(level, chunkBox, part, cellMinX(), this.groundY - 1, cellMinZ(), transform, palette, rand);
	}

	/**
	 * Kerbstones, lamp posts and potholes (IMPROVEMENTS #4).
	 *
	 * <p>Kerbs and lamps sit one block above the road surface, on the pavement ring that
	 * {@link StreetDecor#isKerb} identifies; potholes cut into the surface itself. Whether a lamp is
	 * still lit comes from the world seed and the lamp's own column, so it survives a reload.
	 *
	 * <p>Where a crossing meets the ring the kerb is <em>dropped</em> instead: the column is cleared
	 * back to the base course, which leaves it flush with the carriageway. The ring used to be written
	 * unconditionally, so the crossings the junction tiles paint ran straight into a raised kerbstone.
	 * A lamp post is not planted there either — it would stand in the middle of the crossing.
	 */
	private void decorate(WorldGenLevel level, BoundingBox chunkBox, long seed, int surfaceY) {
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int dx = 0; dx < 16; dx++) {
			for (int dz = 0; dz < 16; dz++) {
				int wx = cellMinX() + dx;
				int wz = cellMinZ() + dz;
				if (StreetDecor.isRoad(dx, dz, this.neighbourMask)) {
					if (StreetDecor.isPothole(seed, wx, wz, this.potholeChance)) {
						set(level, chunkBox, cursor.set(wx, surfaceY, wz), POTHOLE_RIM);
						set(level, chunkBox, cursor.set(wx, surfaceY - 1, wz), AIR);
					}
					continue;
				}
				if (!StreetDecor.isKerb(dx, dz, this.neighbourMask)) {
					continue;
				}
				if (StreetDecor.isDroppedKerb(dx, dz, this.neighbourMask)) {
					set(level, chunkBox, cursor.set(wx, surfaceY + 1, wz), AIR);
					continue;
				}
				if (StreetDecor.isLamp(wx, wz, dx, dz, this.neighbourMask, this.lampSpacing)) {
					lamp(level, chunkBox, cursor, seed, wx, wz);
				} else {
					set(level, chunkBox, cursor.set(wx, surfaceY + 1, wz), KERB);
				}
			}
		}
	}

	private void lamp(WorldGenLevel level, BoundingBox chunkBox, BlockPos.MutableBlockPos cursor,
	                  long seed, int wx, int wz) {
		int base = this.groundY;
		for (int y = 0; y < LAMP_HEIGHT; y++) {
			set(level, chunkBox, cursor.set(wx, base + y, wz), LAMP_POST);
		}
		boolean broken = CityLayout.unit(CityLayout.hash(seed, wx, wz, 0x4C)) < BROKEN_LAMP_CHANCE;
		if (!broken) {
			set(level, chunkBox, cursor.set(wx, base + LAMP_HEIGHT, wz), LAMP_HEAD);
		}
	}

	private static void set(WorldGenLevel level, BoundingBox chunkBox, BlockPos pos, BlockState state) {
		if (chunkBox.isInside(pos)) {
			level.setBlock(pos, state, WorldGenFlags.SET_BLOCK);
		}
	}
}
