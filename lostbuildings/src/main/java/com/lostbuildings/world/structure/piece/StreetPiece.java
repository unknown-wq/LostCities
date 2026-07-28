package com.lostbuildings.world.structure.piece;

import com.lostbuildings.registry.ModStructurePieceTypes;
import com.lostbuildings.world.feature.Streets;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;

/**
 * One paved cell of the city grid — the cells the checkerboard leaves free between buildings.
 *
 * <p>Wave 1 keeps the hand-laid paving the feature used ({@link Streets}); wave 2 replaces it with
 * the shipped {@code street_*} building parts, selected from {@code neighbourMask}. The mask is
 * already carried by {@code CityLayout.Cell}, so that change touches this class and nothing else.
 */
public class StreetPiece extends CityPiece {

	/** Room below the pavement for the embankment, and above it for the cleared headroom. */
	private static final int BELOW_GROUND = 12;
	private static final int ABOVE_GROUND = 6;

	private final BlockState material;
	private final int width;
	private final int neighbourMask;

	public StreetPiece(int chunkX, int chunkZ, int groundY, BlockState material, int width, int neighbourMask) {
		super(ModStructurePieceTypes.STREET, cellBox(chunkX, chunkZ, groundY, BELOW_GROUND, ABOVE_GROUND), groundY);
		this.material = material;
		this.width = width;
		this.neighbourMask = neighbourMask;
	}

	public StreetPiece(CompoundTag tag) {
		super(ModStructurePieceTypes.STREET, tag);
		this.material = tag.read("Material", BlockState.CODEC).orElse(Streets.DEFAULT_STREET);
		this.width = tag.getIntOr("Width", 16);
		this.neighbourMask = tag.getIntOr("Neighbours", 0);
	}

	@Override
	protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
		super.addAdditionalSaveData(context, tag);
		tag.store("Material", BlockState.CODEC, this.material);
		tag.putInt("Width", this.width);
		tag.putInt("Neighbours", this.neighbourMask);
	}

	/** Which orthogonal neighbours belong to the same city — the input for wave 2's tile choice. */
	public int neighbourMask() {
		return this.neighbourMask;
	}

	@Override
	public void postProcess(WorldGenLevel level, StructureManager structureManager, ChunkGenerator generator,
	                        RandomSource random, BoundingBox chunkBox, ChunkPos chunkPos, BlockPos referencePos) {
		if (!coversCell(chunkBox)) {
			return;
		}
		Streets.paveCell(level, chunkBox, cellMinX(), cellMinZ(), this.groundY, this.material, this.width);
	}
}
