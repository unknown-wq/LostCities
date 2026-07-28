package com.lostbuildings.world.structure.piece;

import com.lostbuildings.LostBuildings;
import com.lostbuildings.engine.Assets;
import com.lostbuildings.engine.Building;
import com.lostbuildings.engine.BuildingEngine;
import com.lostbuildings.engine.BuildingRole;
import com.lostbuildings.engine.CompiledPalette;
import com.lostbuildings.engine.PlaceSettings;
import com.lostbuildings.engine.Style;
import com.lostbuildings.engine.Transform;
import com.lostbuildings.registry.ModStructurePieceTypes;
import com.lostbuildings.world.feature.Foundation;
import com.lostbuildings.world.feature.StyleSelector;
import com.lostbuildings.world.structure.CityLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;

/**
 * One Lost Cities building, filling one chunk cell of the city grid.
 *
 * <p>This is the old {@code GroupBuildingPlacement} loop body, moved verbatim: resolve the building
 * and the palette, size the volume to clear from the storey count, run {@code Foundation} to pillar
 * down and excavate, then hand the site to {@code BuildingEngine}.
 *
 * <p>Every decision the group used to make per firing (which building, how many storeys, which
 * rotation, which style, what ground level) is decided once when the structure start is assembled
 * and carried in this piece's NBT. That is what lets a city span an arbitrary number of chunks:
 * neighbouring cells agree on a ground level and a style without ever being generated in the same
 * pass.
 *
 * <p><b>Landmark quadrants use this same class.</b> A 2×2 landmark (PORT #1) is four ordinary
 * buildings whose names happen to be {@code center00}..{@code center11}: the structure resolves the
 * quadrant to a building name, gives all four the same storey count and no rotation, and the pieces
 * then know nothing about each other. Sharing the class is what guarantees the quadrants line up —
 * there is only one code path that can place them.
 *
 * <p><b>Engine settings.</b> Cellars, damage and the building's role come from the datapack config
 * and the layout, are stored in NBT, and are handed to the engine through {@code PlaceSettings}. The
 * layout deliberately speaks in its own {@link CityLayout.BuildingKind}, which is Minecraft-free;
 * {@link #engineRole()} is the single point where that becomes the engine's {@code BuildingRole}.
 */
public class BuildingPiece extends CityPiece {

	/** Headroom cleared above the tallest storey of a building. */
	private static final int CLEAR_MARGIN = 4;
	/** Hard cap on how far a building cut into a hillside will excavate above its base. */
	private static final int MAX_EXCAVATION = 64;
	/** How far below the ground level the piece's box reaches (matches Foundation's pillar depth). */
	private static final int PILLAR_DEPTH = 32;

	private final String buildingName;
	private final String styleName;
	private final int floors;
	private final int quarterTurns;
	private final boolean foundation;
	private final int cellars;
	private final float damageChance;
	private final CityLayout.BuildingKind kind;

	public BuildingPiece(int chunkX, int chunkZ, int groundY, String buildingName, String styleName,
	                     int floors, int quarterTurns, boolean foundation,
	                     int cellars, float damageChance, CityLayout.BuildingKind kind,
	                     StyleSelector.Climate climate) {
		super(ModStructurePieceTypes.BUILDING,
				cellBox(chunkX, chunkZ, groundY, PILLAR_DEPTH, (floors + 2) * FLOOR_HEIGHT + CLEAR_MARGIN),
				groundY, climate);
		this.buildingName = buildingName;
		this.styleName = styleName;
		this.floors = floors;
		this.quarterTurns = quarterTurns;
		this.foundation = foundation;
		this.cellars = cellars;
		this.damageChance = damageChance;
		this.kind = kind == null ? CityLayout.BuildingKind.RESIDENTIAL : kind;
	}

	public BuildingPiece(CompoundTag tag) {
		super(ModStructurePieceTypes.BUILDING, tag);
		this.buildingName = tag.getStringOr("Building", "");
		this.styleName = tag.getStringOr("Style", StyleSelector.DEFAULT_STYLE);
		this.floors = tag.getIntOr("Floors", 2);
		this.quarterTurns = tag.getIntOr("Turns", 0);
		this.foundation = tag.getBooleanOr("Foundation", true);
		this.cellars = tag.getIntOr("Cellars", 0);
		this.damageChance = tag.getFloatOr("Damage", 0.0F);
		this.kind = readKind(tag.getStringOr("Kind", CityLayout.BuildingKind.RESIDENTIAL.name()));
	}

	private static CityLayout.BuildingKind readKind(String name) {
		for (CityLayout.BuildingKind candidate : CityLayout.BuildingKind.values()) {
			if (candidate.name().equals(name)) {
				return candidate;
			}
		}
		return CityLayout.BuildingKind.RESIDENTIAL;
	}

	@Override
	protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
		super.addAdditionalSaveData(context, tag);
		tag.putString("Building", this.buildingName);
		tag.putString("Style", this.styleName);
		tag.putInt("Floors", this.floors);
		tag.putInt("Turns", this.quarterTurns);
		tag.putBoolean("Foundation", this.foundation);
		tag.putInt("Cellars", this.cellars);
		tag.putFloat("Damage", this.damageChance);
		tag.putString("Kind", this.kind.name());
	}

	@Override
	public void postProcess(WorldGenLevel level, StructureManager structureManager, ChunkGenerator generator,
	                        RandomSource random, BoundingBox chunkBox, ChunkPos chunkPos, BlockPos referencePos) {
		if (!coversCell(chunkBox)) {
			return;
		}
		Assets assets = LostBuildings.ASSETS;
		BuildingEngine engine = LostBuildings.ENGINE;
		if (assets == null || engine == null) {
			return;     // assets not loaded yet; skipping beats writing a half-built house
		}
		Building building = assets.getBuilding(this.buildingName);
		if (building == null) {
			return;
		}
		Style style = resolveStyle(assets);
		if (style == null) {
			return;
		}

		// Seeded from the world seed and this cell only, so the house does not depend on what else
		// happened to generate in this chunk first (see CityPiece#cellRandom).
		RandomSource rand = cellRandom(level.getSeed(), 0x51);

		BlockPos site = new BlockPos(cellMinX(), this.groundY, cellMinZ());
		CompiledPalette palette = engine.buildPalette(assets, rand, style);
		PlaceSettings settings = new PlaceSettings(
				this.floors, this.floors,
				true,   // lighting
				true,   // spawners
				true,   // loot
				level.getSeaLevel(),
				this.cellars,
				this.damageChance,
				engineRole());

		if (this.foundation) {
			int slopeAbove = cellTerrainTop(level) - this.groundY;
			Foundation.build(level, site, FOOTPRINT, FOOTPRINT,
					clearHeight(building, this.floors, slopeAbove),
					fillerState(building, palette, rand), settings.waterLevel(), rand);
		}

		engine.generateBuilding(level, site, rand, Transform.values()[Math.floorMod(this.quarterTurns, 4)],
				building, palette, settings);

		weather(level, chunkBox, level.getSeed());
	}

	/**
	 * The engine's role for this building's layout kind.
	 *
	 * <p>The one place the Minecraft-free layout enum meets the engine enum, on purpose: the frozen
	 * cross-agent contract (PHASE3-PLAN §3) puts {@code BuildingRole} in {@code engine/} and forbids
	 * {@code CityLayout} from importing it. If the engine's constant names ever change, this method
	 * is the whole of the fix.
	 */
	private BuildingRole engineRole() {
		return switch (this.kind) {
			case SHOP -> BuildingRole.SHOP;
			case LIBRARY -> BuildingRole.LIBRARY;
			case TOWER -> BuildingRole.TOWER;
			case RESIDENTIAL -> BuildingRole.RESIDENTIAL;
		};
	}

	/** Style for this building, falling back to {@code standard} if the chosen one is not loaded. */
	private Style resolveStyle(Assets assets) {
		Style style = assets.getStyle(this.styleName);
		if (style == null && !StyleSelector.DEFAULT_STYLE.equals(this.styleName)) {
			style = assets.getStyle(StyleSelector.DEFAULT_STYLE);
		}
		return style;
	}

	/**
	 * Highest terrain point over this cell — how far the slope reaches into the building volume.
	 *
	 * <p>Every probe is inside the cell, hence inside the chunk being generated, so unlike the old
	 * feature this needs no window clamping at all.
	 */
	private int cellTerrainTop(WorldGenLevel level) {
		int highest = Integer.MIN_VALUE;
		for (int[] probe : CELL_PROBES) {
			int x = cellMinX() + probe[0];
			int z = cellMinZ() + probe[1];
			highest = Math.max(highest, level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z));
		}
		return highest == Integer.MIN_VALUE ? this.groundY : highest;
	}

	/** Corners and centre of a 16×16 cell — enough to characterise the slope it sits on. */
	private static final int[][] CELL_PROBES = {
			{0, 0}, {FOOTPRINT - 1, 0}, {0, FOOTPRINT - 1}, {FOOTPRINT - 1, FOOTPRINT - 1},
			{FOOTPRINT / 2, FOOTPRINT / 2}
	};

	/**
	 * The building's own filler block, resolved through the palette, so a plinth under a sandstone
	 * house is sandstone rather than a cobblestone stump.
	 */
	private static BlockState fillerState(Building building, CompiledPalette palette, RandomSource rand) {
		BlockState state = palette.get(building.getFillerBlock(), rand);
		return state == null ? Foundation.DEFAULT_FILL : state;
	}

	/**
	 * Height to clear above the building base. Mirrors {@code BuildingEngine.generateBuilding},
	 * which stacks {@code floors + 1} parts of {@link #FLOOR_HEIGHT} blocks each. Buildings that
	 * declare {@code overrideFloors} ignore the storey count handed to them and roll their own, so
	 * for those the clear volume is sized from their declared maximum instead.
	 *
	 * @param slopeAbove how far the cell's highest terrain point rises above the shared ground level
	 */
	private static int clearHeight(Building building, int floors, int slopeAbove) {
		int effective = floors;
		if (Boolean.TRUE.equals(building.getOverrideFloors()) && building.getMaxFloors() >= 0) {
			effective = Math.max(effective, building.getMaxFloors());
		}
		int buildingHeight = (effective + 1) * FLOOR_HEIGHT + CLEAR_MARGIN;
		return Math.max(buildingHeight, Math.min(slopeAbove + 1, MAX_EXCAVATION));
	}
}
