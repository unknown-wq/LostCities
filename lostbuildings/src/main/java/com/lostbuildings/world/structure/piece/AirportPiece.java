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
import com.lostbuildings.world.structure.Airport;
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
 * One cell of the city's airfield — a third of the runway, and on the middle cell the terminal.
 *
 * <p>Which cells these are, which part each of them gets and which way round the strip lies is all
 * decided by the pure {@link Airport} at start-assembly time, where the whole city is visible. This
 * piece is the block-writing half: it takes one {@link Airport.Segment} and stamps it.
 *
 * <p><b>Flush with the city, like a park.</b> The cell is surfaced by the same
 * {@link Streets#paveCell} the roads and the park lawns use, with tarmac instead of stone brick and
 * the full 16-block width: same shared ground level, same excavation of anything standing above it,
 * same embankment carrying it down a slope, same refusal to stilt a surface across a ravine. A
 * runway that followed the terrain per column would not be a runway.
 *
 * <p>On top of that base course goes one {@code airport_*} part, laid at {@code groundY - 1} exactly
 * as {@code StreetPiece} lays a street tile: the part's slice 0 <em>replaces</em> the tarmac with the
 * runway markings rather than sitting on top of it, so the paint is flush and the surface is one
 * block thick.
 *
 * <p><b>The terminal is a decorative part, not a building.</b> It is 10×5 and stands beside the
 * runway rather than filling a lot, so it cannot be a {@code BuildingPiece} — that path owns a whole
 * 16×16 cell, picks a storey count and applies a random quarter turn, all three of which would put
 * the terminal somewhere other than next to its own runway. The cost is the documented
 * {@link PartPlacer} simplification: no spawners and no loot NBT on this path. For a control hut
 * with a glazed front that is no loss.
 */
public class AirportPiece extends CityPiece {

	/** Room below the tarmac for its embankment, and above it for the control cab. */
	private static final int BELOW_GROUND = 12;
	private static final int ABOVE_GROUND = 10;

	/** Runway surface. Also the fallback when the assets are not loaded: a plain apron is still flat. */
	private static final BlockState TARMAC = Blocks.CONCRETE.gray().defaultBlockState();

	private static final BlockState AIR = Blocks.AIR.defaultBlockState();

	/** Salt for this cell's random source, independent of every other per-cell decision. */
	private static final int RANDOM_SALT = 0x41;

	private final String partName;
	private final int quarterTurns;
	private final String styleName;

	public AirportPiece(Airport.Segment segment, int groundY, String styleName,
	                    StyleSelector.Climate climate) {
		super(ModStructurePieceTypes.AIRPORT,
				cellBox(segment.chunkX(), segment.chunkZ(), groundY, BELOW_GROUND, ABOVE_GROUND),
				groundY, climate);
		this.partName = segment.partName();
		this.quarterTurns = segment.quarterTurns();
		this.styleName = styleName;
	}

	public AirportPiece(CompoundTag tag) {
		super(ModStructurePieceTypes.AIRPORT, tag);
		this.partName = tag.getStringOr("Part", "");
		this.quarterTurns = tag.getIntOr("Turns", 0);
		this.styleName = tag.getStringOr("Style", StyleSelector.DEFAULT_STYLE);
	}

	@Override
	protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
		super.addAdditionalSaveData(context, tag);
		tag.putString("Part", this.partName);
		tag.putInt("Turns", this.quarterTurns);
		tag.putString("Style", this.styleName);
	}

	/** The {@code airport_*} part stamped on this cell. */
	public String partName() {
		return this.partName;
	}

	@Override
	public void postProcess(WorldGenLevel level, StructureManager structureManager, ChunkGenerator generator,
	                        RandomSource random, BoundingBox chunkBox, ChunkPos chunkPos, BlockPos referencePos) {
		if (!coversCell(chunkBox)) {
			return;
		}
		long seed = level.getSeed();
		RandomSource rand = cellRandom(seed, RANDOM_SALT);

		// Base course first — it levels the cell against the city's shared ground level and decides
		// for itself which columns it can honestly carry. Then the airspace above it: an airfield is
		// the one cell of a city that has to be genuinely open, and paveCell only guarantees three
		// blocks of headroom.
		Streets.paveCell(level, chunkBox, cellMinX(), cellMinZ(), this.groundY, TARMAC, 16);
		clearAirspace(level, chunkBox);

		Assets assets = LostBuildings.ASSETS;
		BuildingEngine engine = LostBuildings.ENGINE;
		if (assets == null || engine == null || this.partName.isEmpty()) {
			return;     // bare tarmac is still an airfield, just an unmarked one
		}
		BuildingPart part = assets.getPart(this.partName);
		if (part == null) {
			return;
		}
		Style style = assets.getStyle(this.styleName);
		CompiledPalette palette = engine.buildPalette(assets, rand, style);
		Transform transform = Transform.values()[Math.floorMod(this.quarterTurns, 4)];
		PartPlacer.place(level, chunkBox, part, cellMinX(), this.groundY - 1, cellMinZ(),
				transform, palette, rand);

		weather(level, chunkBox, seed);
	}

	/**
	 * Empty the volume the terminal and the approach occupy.
	 *
	 * <p>Nothing on the {@link PartPlacer} path can carve: a space in a part means "leave the world
	 * alone", which is right for a street tile laid into a road but wrong for the inside of a
	 * building. {@code BuildingPiece} clears its lot for the same reason; this is that clear, sized to
	 * this piece's own box so it cannot write outside it.
	 */
	private void clearAirspace(WorldGenLevel level, BoundingBox chunkBox) {
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int dx = 0; dx < FOOTPRINT; dx++) {
			for (int dz = 0; dz < FOOTPRINT; dz++) {
				for (int y = this.groundY; y <= this.groundY + ABOVE_GROUND; y++) {
					cursor.set(cellMinX() + dx, y, cellMinZ() + dz);
					if (!chunkBox.isInside(cursor) || level.getBlockState(cursor).isAir()) {
						continue;
					}
					level.setBlock(cursor, AIR, WorldGenFlags.SET_BLOCK);
				}
			}
		}
	}
}
