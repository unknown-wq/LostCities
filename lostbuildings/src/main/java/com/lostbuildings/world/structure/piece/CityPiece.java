package com.lostbuildings.world.structure.piece;

import com.lostbuildings.world.feature.StyleSelector;
import com.lostbuildings.world.feature.WorldGenFlags;
import com.lostbuildings.world.structure.CityLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
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
 * <p><b>Shared state comes from the start, not from the world.</b> Ground level, style, climate,
 * storey count and rotation are all decided once in {@code findGenerationPoint} and serialised into
 * the pieces. Chunks generate in an arbitrary order and a piece may be reloaded from NBT long after
 * its neighbours were built, so anything a piece re-derives from the world would drift between
 * neighbours. {@link #cellRandom} exists for the same reason: it is seeded from the world seed and
 * the cell coordinate only, never from the shared per-chunk decoration random, so a cell's contents
 * do not depend on which chunk happened to trigger it.
 */
public abstract class CityPiece extends StructurePiece {

	/** Blocks per storey — must match {@code BuildingEngine}'s own floor height. */
	protected static final int FLOOR_HEIGHT = 6;
	/** Cell footprint: a Lost Cities building fills a whole chunk. */
	protected static final int FOOTPRINT = 16;

	/** Share of flat surfaces that pick up moss in a swamp. */
	private static final double MOSS_CHANCE = 0.35D;
	/** Salt for the weathering roll, so it is independent of every other per-column decision. */
	private static final int WEATHER_SALT = 0x77;

	private static final BlockState SNOW = Blocks.SNOW.defaultBlockState();
	private static final BlockState MOSS = Blocks.MOSS_CARPET.defaultBlockState();

	/** The city's shared ground level: Y of the lowest floor / of the block above the pavement. */
	protected int groundY;

	/** What the local biome has done to this piece since the city was abandoned. */
	protected StyleSelector.Climate climate;

	protected CityPiece(StructurePieceType type, BoundingBox boundingBox, int groundY,
	                    StyleSelector.Climate climate) {
		super(type, 0, boundingBox);
		this.groundY = groundY;
		this.climate = climate == null ? StyleSelector.Climate.TEMPERATE : climate;
	}

	protected CityPiece(StructurePieceType type, CompoundTag tag) {
		super(type, tag);
		this.groundY = tag.getIntOr("GroundY", 0);
		this.climate = readClimate(tag.getStringOr("Climate", StyleSelector.Climate.TEMPERATE.name()));
	}

	private static StyleSelector.Climate readClimate(String name) {
		for (StyleSelector.Climate candidate : StyleSelector.Climate.values()) {
			if (candidate.name().equals(name)) {
				return candidate;
			}
		}
		return StyleSelector.Climate.TEMPERATE;
	}

	@Override
	protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
		tag.putInt("GroundY", this.groundY);
		tag.putString("Climate", this.climate.name());
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

	/**
	 * Biome weathering over the finished cell (IMPROVEMENTS #9): snow on every flat top surface in
	 * the cold biomes, moss creeping over them in a swamp.
	 *
	 * <p>Run <em>after</em> the cell's own blocks are placed, and driven by the chunk's own
	 * {@code WORLD_SURFACE_WG} heightmap rather than by a scan of the whole volume: {@code setBlock}
	 * keeps that heightmap current, so one lookup per column finds the roof of a tower and the
	 * pavement of a street with the same code and 256 probes. Which columns are weathered comes from
	 * the world seed and the absolute column, so it survives a reload unchanged.
	 *
	 * <p>Deliberately additive: nothing already placed is replaced, the layer simply goes on top.
	 * A building whose roof is glass keeps its glass, with snow on it.
	 */
	protected void weather(WorldGenLevel level, BoundingBox chunkBox, long worldSeed) {
		BlockState cover = switch (this.climate) {
			case SNOWY -> SNOW;
			case SWAMPY -> MOSS;
			case TEMPERATE -> null;
		};
		if (cover == null) {
			return;
		}
		boolean everywhere = this.climate == StyleSelector.Climate.SNOWY;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int dx = 0; dx < FOOTPRINT; dx++) {
			for (int dz = 0; dz < FOOTPRINT; dz++) {
				int x = cellMinX() + dx;
				int z = cellMinZ() + dz;
				if (!everywhere
						&& CityLayout.unit(CityLayout.hash(worldSeed, x, z, WEATHER_SALT)) >= MOSS_CHANCE) {
					continue;
				}
				int top = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
				cursor.set(x, top, z);
				if (!chunkBox.isInside(cursor) || !level.getBlockState(cursor).isAir()) {
					continue;
				}
				cursor.set(x, top - 1, z);
				if (!chunkBox.isInside(cursor) || !level.getBlockState(cursor).isFaceSturdy(level, cursor, Direction.UP)) {
					continue;
				}
				cursor.set(x, top, z);
				level.setBlock(cursor, cover, WorldGenFlags.SET_BLOCK);
			}
		}
	}

	/** Bounding box of a cell: the chunk column, from the deepest pillar to above the tallest roof. */
	protected static BoundingBox cellBox(int chunkX, int chunkZ, int groundY, int belowGround, int aboveGround) {
		int minX = chunkX << 4;
		int minZ = chunkZ << 4;
		return new BoundingBox(minX, groundY - belowGround, minZ,
				minX + FOOTPRINT - 1, groundY + aboveGround, minZ + FOOTPRINT - 1);
	}
}
