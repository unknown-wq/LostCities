package com.lostbuildings.world.structure.piece;

import com.lostbuildings.LostBuildings;
import com.lostbuildings.engine.Assets;
import com.lostbuildings.engine.BuildingEngine;
import com.lostbuildings.engine.BuildingPart;
import com.lostbuildings.engine.CompiledPalette;
import com.lostbuildings.engine.Style;
import com.lostbuildings.engine.Transform;
import com.lostbuildings.registry.ModStructurePieceTypes;
import com.lostbuildings.world.feature.StyleSelector;
import com.lostbuildings.world.feature.WorldGenFlags;
import com.lostbuildings.world.structure.CityLayout;
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
 * A street cell that spans water or a drop instead of stopping at it (PORT #6).
 *
 * <p>Wave 1's {@code Streets} gave up on any column that would have needed more than eight blocks of
 * embankment, which is the right call for a road — but it also meant a city sitting on a river bank
 * fell apart into islands with roads that ran into the water and ended. The structure now decides,
 * while it is assembling the start and can still sample the terrain noise, that such a cell is a
 * bridge, and this piece carries the road across on one of the shipped {@code bridge_*} parts.
 *
 * <p>The deck sits at exactly the same height as the roads either side of it — the whole point of
 * the city's shared ground level — so a bridge is drop-in compatible with the street tile it
 * replaces. The parts are drawn running west-east, so a north-south crossing is turned a quarter
 * turn, the same convention {@link com.lostbuildings.world.structure.StreetTiles} uses.
 *
 * <p><b>Pillars are deliberately not generated</b> (§9). The original's bridge supports come from its
 * multi-chunk highway code, which is Phase 4 material; here the deck is self-supporting and the
 * shipped {@code bridge_covered} part already reads as a truss. What this piece does add is a
 * <em>clearing</em> pass: anything the terrain left standing in the deck's own volume is removed, so
 * a bridge never comes out half-buried in a river bank.
 */
public class BridgePiece extends CityPiece {

	/** Room below the deck (nothing is built there) and above it for the truss and railings. */
	private static final int BELOW_GROUND = 4;
	private static final int ABOVE_GROUND = 8;

	/** Headroom cleared over the deck so the bridge is not buried in a bank it cuts through. */
	private static final int CLEARANCE = 5;

	private static final BlockState AIR = Blocks.AIR.defaultBlockState();

	private final String partName;
	private final String styleName;
	private final int quarterTurns;

	public BridgePiece(int chunkX, int chunkZ, int groundY, String partName, String styleName, int quarterTurns,
	                   StyleSelector.Climate climate) {
		super(ModStructurePieceTypes.BRIDGE, cellBox(chunkX, chunkZ, groundY, BELOW_GROUND, ABOVE_GROUND), groundY,
				climate);
		this.partName = partName;
		this.styleName = styleName;
		this.quarterTurns = quarterTurns;
	}

	public BridgePiece(CompoundTag tag) {
		super(ModStructurePieceTypes.BRIDGE, tag);
		this.partName = tag.getStringOr("Part", "");
		this.styleName = tag.getStringOr("Style", StyleSelector.DEFAULT_STYLE);
		this.quarterTurns = tag.getIntOr("Turns", 0);
	}

	@Override
	protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
		super.addAdditionalSaveData(context, tag);
		tag.putString("Part", this.partName);
		tag.putString("Style", this.styleName);
		tag.putInt("Turns", this.quarterTurns);
	}

	/**
	 * How far a bridge part has to be turned to follow a road with this connectivity mask. The parts
	 * run west-east unrotated, so a road that continues north or south (and not east or west) is the
	 * one that needs the quarter turn.
	 */
	public static int turnsForMask(int mask) {
		boolean alongX = (mask & (CityLayout.WEST | CityLayout.EAST)) != 0;
		return alongX ? 0 : 1;
	}

	@Override
	public void postProcess(WorldGenLevel level, StructureManager structureManager, ChunkGenerator generator,
	                        RandomSource random, BoundingBox chunkBox, ChunkPos chunkPos, BlockPos referencePos) {
		if (!coversCell(chunkBox)) {
			return;
		}
		Assets assets = LostBuildings.ASSETS;
		BuildingEngine engine = LostBuildings.ENGINE;
		if (assets == null || engine == null || this.partName.isEmpty()) {
			return;
		}
		BuildingPart part = assets.getPart(this.partName);
		if (part == null) {
			return;
		}
		RandomSource rand = cellRandom(level.getSeed(), 0x5B);
		Style style = assets.getStyle(this.styleName);
		CompiledPalette palette = engine.buildPalette(assets, rand, style);

		clearDeckVolume(level, chunkBox);
		PartPlacer.place(level, chunkBox, part, cellMinX(), this.groundY - 1, cellMinZ(),
				Transform.values()[Math.floorMod(this.quarterTurns, 4)], palette, rand);
	}

	/** Remove terrain standing in the deck's volume, so the crossing is actually passable. */
	private void clearDeckVolume(WorldGenLevel level, BoundingBox chunkBox) {
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int dx = 0; dx < FOOTPRINT; dx++) {
			for (int dz = 0; dz < FOOTPRINT; dz++) {
				for (int y = this.groundY; y < this.groundY + CLEARANCE; y++) {
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
