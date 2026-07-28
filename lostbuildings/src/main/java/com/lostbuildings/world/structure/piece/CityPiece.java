package com.lostbuildings.world.structure.piece;

import com.lostbuildings.world.structure.CityLayout;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;

/**
 * Base class for every piece of a lost city (PHASE3-PLAN §3).
 *
 * <p><b>The contract.</b> A city piece owns exactly one chunk-aligned 16×16 cell. Minecraft calls
 * {@code postProcess} once per chunk that a piece's bounding box intersects, and hands it
 * {@code ChunkGenerator.getWritableArea(chunk)} — precisely that one chunk's column. Because a
 * cell <em>is</em> a chunk, the {@code chunkBox} a piece is handed always covers its whole
 * footprint, and any write inside the footprint is legal by construction. That is what replaces the
 * old {@code WorldGenBounds} write-window dance: the feature had to prove every position sat inside
 * an origin-chunk ±1 window shared by the whole group, a piece merely has to stay in its own cell.
 *
 * <p><b>Shared state comes from the start, not from the world.</b> Ground level, style, storey
 * count and rotation are all decided once in {@code LostCityStructure.findGenerationPoint} and
 * serialised into the pieces. Chunks generate in an arbitrary order and a piece may be reloaded
 * from NBT long after its neighbours were built, so anything a piece re-derives from the world
 * would drift between neighbours. {@link #cellRandom} exists for the same reason: it is seeded from
 * the world seed and the cell coordinate only, never from the shared per-chunk decoration random,
 * so a cell's contents do not depend on which chunk happened to trigger it.
 */
public abstract class CityPiece extends StructurePiece {

	/** Blocks per storey — must match {@code BuildingEngine}'s own floor height. */
	protected static final int FLOOR_HEIGHT = 6;
	/** Cell footprint: a Lost Cities building fills a whole chunk. */
	protected static final int FOOTPRINT = 16;

	/** The city's shared ground level: Y of the lowest floor / of the block above the pavement. */
	protected int groundY;

	protected CityPiece(StructurePieceType type, BoundingBox boundingBox, int groundY) {
		super(type, 0, boundingBox);
		this.groundY = groundY;
	}

	protected CityPiece(StructurePieceType type, CompoundTag tag) {
		super(type, tag);
		this.groundY = tag.getIntOr("GroundY", 0);
	}

	@Override
	protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
		tag.putInt("GroundY", this.groundY);
	}

	/** Chunk X of the cell this piece owns. */
	public int cellChunkX() {
		return this.boundingBox.minX() >> 4;
	}

	/** Chunk Z of the cell this piece owns. */
	public int cellChunkZ() {
		return this.boundingBox.minZ() >> 4;
	}

	/** World X of the cell's north-west corner. */
	protected int cellMinX() {
		return this.boundingBox.minX();
	}

	/** World Z of the cell's north-west corner. */
	protected int cellMinZ() {
		return this.boundingBox.minZ();
	}

	/**
	 * Whether the chunk being written is the one this cell lives in.
	 *
	 * <p>The engine only calls {@code postProcess} for intersecting chunks and a cell spans exactly
	 * one, so this is always true in practice. It is checked anyway because everything downstream —
	 * the building engine, {@code Foundation}, {@code Streets} — writes to the level directly rather
	 * than through {@link StructurePiece#placeBlock}, and a silent escape would be a "detected unsafe
	 * terrain read/write during worldgen" log line rather than an exception.
	 */
	protected boolean coversCell(BoundingBox chunkBox) {
		return cellMinX() >= chunkBox.minX() && cellMinX() + FOOTPRINT - 1 <= chunkBox.maxX()
				&& cellMinZ() >= chunkBox.minZ() && cellMinZ() + FOOTPRINT - 1 <= chunkBox.maxZ();
	}

	/**
	 * A random source that depends on nothing but the world seed and this cell's coordinate.
	 *
	 * <p>Deliberately <em>not</em> the {@code RandomSource} handed to {@code postProcess}: that one
	 * is the per-chunk decoration random, so a piece drawing from it would produce different blocks
	 * depending on how many other structures fired in that chunk first.
	 */
	protected RandomSource cellRandom(long worldSeed, int salt) {
		return RandomSource.create(CityLayout.hash(worldSeed, cellChunkX(), cellChunkZ(), salt));
	}

	/** Bounding box of a cell: the chunk column, from the deepest pillar to above the tallest roof. */
	protected static BoundingBox cellBox(int chunkX, int chunkZ, int groundY, int belowGround, int aboveGround) {
		int minX = chunkX << 4;
		int minZ = chunkZ << 4;
		return new BoundingBox(minX, groundY - belowGround, minZ,
				minX + FOOTPRINT - 1, groundY + aboveGround, minZ + FOOTPRINT - 1);
	}
}
