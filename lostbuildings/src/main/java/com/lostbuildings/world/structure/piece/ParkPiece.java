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
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;

/**
 * A green cell: one of the shipped {@code park_*} parts instead of a house (PORT #4).
 *
 * <p>In the original a park is a variant of a <em>street</em> chunk. Here it is a variant of a
 * <em>building</em> lot, which is the shape this grid has: the streets already form a closed lattice
 * and taking a cell out of it would punch a hole in the road network, while taking a building lot out
 * leaves the roads intact and simply gives the block a square instead of another concrete box. A
 * configurable share of building cells is turned over to parks by {@link com.lostbuildings.world.structure.CityLayout}.
 *
 * <p>The piece lays a flat lawn at the city's shared ground level — so the park is flush with the
 * pavement around it rather than perched on the terrain — and stamps the chosen park part on top of
 * it. {@code park_pool} and {@code park_fountain*} bring their own water and stonework, and the
 * lawn underneath keeps the edges from showing raw dirt.
 */
public class ParkPiece extends CityPiece {

	/** Room below the lawn for its embankment, and above it for the tallest park part. */
	private static final int BELOW_GROUND = 12;
	private static final int ABOVE_GROUND = 8;

	private static final BlockState LAWN = Blocks.GRASS_BLOCK.defaultBlockState();

	/** Palette character for grass in the shipped palettes ({@code common.json}). */
	private static final char GRASS_CHAR = 'G';

	private final String partName;
	private final String styleName;

	public ParkPiece(int chunkX, int chunkZ, int groundY, String partName, String styleName,
	                 StyleSelector.Climate climate) {
		super(ModStructurePieceTypes.PARK, cellBox(chunkX, chunkZ, groundY, BELOW_GROUND, ABOVE_GROUND), groundY,
				climate);
		this.partName = partName;
		this.styleName = styleName;
	}

	public ParkPiece(CompoundTag tag) {
		super(ModStructurePieceTypes.PARK, tag);
		this.partName = tag.getStringOr("Part", "");
		this.styleName = tag.getStringOr("Style", StyleSelector.DEFAULT_STYLE);
	}

	@Override
	protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
		super.addAdditionalSaveData(context, tag);
		tag.putString("Part", this.partName);
		tag.putString("Style", this.styleName);
	}

	@Override
	public void postProcess(WorldGenLevel level, StructureManager structureManager, ChunkGenerator generator,
	                        RandomSource random, BoundingBox chunkBox, ChunkPos chunkPos, BlockPos referencePos) {
		if (!coversCell(chunkBox)) {
			return;
		}
		Assets assets = LostBuildings.ASSETS;
		BuildingEngine engine = LostBuildings.ENGINE;
		RandomSource rand = cellRandom(level.getSeed(), 0x5A);

		CompiledPalette palette = null;
		if (assets != null && engine != null) {
			Style style = assets.getStyle(this.styleName);
			palette = engine.buildPalette(assets, rand, style);
		}

		// The lawn reuses the street levelling pass: same clearing, same embankment, different
		// surface block. Doing it any other way would leave parks standing on a terrain step while
		// the roads around them are flat.
		BlockState lawn = LAWN;
		if (palette != null) {
			BlockState fromPalette = palette.get(GRASS_CHAR, rand);
			if (fromPalette != null) {
				lawn = fromPalette;
			}
		}
		Streets.paveCell(level, chunkBox, cellMinX(), cellMinZ(), this.groundY, lawn, 16);

		if (palette == null || this.partName.isEmpty()) {
			return;     // a bare lawn is a fine fallback; better than a half-built park
		}
		BuildingPart part = assets.getPart(this.partName);
		if (part == null) {
			return;
		}
		// Park parts start at the surface, one above the lawn block itself.
		PartPlacer.place(level, chunkBox, part, cellMinX(), this.groundY, cellMinZ(),
				Transform.ROTATE_NONE, palette, rand);
	}
}
