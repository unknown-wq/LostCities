package com.lostbuildings.world.structure;

import com.lostbuildings.registry.ModStructureTypes;
import com.lostbuildings.world.feature.StyleSelector;
import com.lostbuildings.world.structure.piece.BuildingPiece;
import com.lostbuildings.world.structure.piece.StreetPiece;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;

import java.util.Optional;

/**
 * The lost city structure — the replacement for the old {@code lostbuildings:lost_building}
 * {@code Feature}.
 *
 * <p><b>Why a structure at all.</b> During the {@code minecraft:features} step the game hands a
 * feature a write window of the generating chunk ±1 — 48×48 blocks. Everything the mod could not do
 * (a real street grid, blocks bigger than two chunks, {@code /locate}, explorer maps) came from that
 * one limit, and {@code CellLattice}'s workaround cost about a third of all firings and most of the
 * density. A structure is assembled once, in the {@code structure_starts} step, and its pieces are
 * written chunk by chunk as those chunks generate. There is no window: a city is as large as its
 * piece list.
 *
 * <p><b>What is decided here.</b> Everything shared by the whole city, because this is the only
 * place that sees the city as a whole:
 * <ul>
 *   <li>the block grid, from the pure {@link CityLayout};</li>
 *   <li>one ground level for every cell, sampled from the chunk generator's noise rather than from
 *       the world (no chunk needs to exist yet), and snapped to a storey boundary so floors line up
 *       between neighbours;</li>
 *   <li>the city's style, sampled once from the biome source at the centre, so a city is not built
 *       half in sandstone.</li>
 * </ul>
 * Each piece then carries its share of those decisions in its NBT and needs no cross-chunk lookup.
 */
public class LostCityStructure extends Structure {

	public static final MapCodec<LostCityStructure> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			settingsCodec(instance),
			LostCityConfig.MAP_CODEC.forGetter(structure -> structure.config)
	).apply(instance, LostCityStructure::new));

	/** Corners of a cell, used to find the terrain low point of the city. */
	private static final int[][] CELL_CORNERS = {{0, 0}, {15, 0}, {0, 15}, {15, 15}};

	private final LostCityConfig config;

	public LostCityStructure(Structure.StructureSettings settings, LostCityConfig config) {
		super(settings);
		this.config = config;
	}

	public LostCityConfig config() {
		return this.config;
	}

	@Override
	protected Optional<Structure.GenerationStub> findGenerationPoint(Structure.GenerationContext context) {
		if (this.config.buildings().isEmpty()) {
			return Optional.empty();
		}
		ChunkPos origin = context.chunkPos();
		CityLayout.Plan plan = CityLayout.plan(context.seed(), origin.x(), origin.z(), this.config.layoutSettings());
		if (plan.isEmpty()) {
			return Optional.empty();   // cannot happen: the centre cell is unconditional
		}

		int groundY = groundLevel(context, plan);
		int minY = context.heightAccessor().getMinY();
		if (groundY <= minY + LostCityConfig.FLOOR_HEIGHT) {
			return Optional.empty();
		}

		BlockPos centre = new BlockPos(origin.getMiddleBlockX(), groundY, origin.getMiddleBlockZ());
		String style = resolveStyle(context, centre);

		return Optional.of(new Structure.GenerationStub(centre, builder -> addPieces(builder, plan, groundY, style)));
	}

	private void addPieces(StructurePiecesBuilder builder, CityLayout.Plan plan, int groundY, String style) {
		for (CityLayout.Cell cell : plan.cells()) {
			switch (cell.role()) {
				case BUILDING -> builder.addPiece(new BuildingPiece(
						cell.chunkX(), cell.chunkZ(), groundY,
						this.config.buildingName(cell.buildingIndex()), style,
						cell.floors(), cell.quarterTurns(), this.config.foundation()));
				case STREET -> builder.addPiece(new StreetPiece(
						cell.chunkX(), cell.chunkZ(), groundY,
						this.config.streetBlock(), this.config.streetWidth(), cell.neighbourMask()));
			}
		}
	}

	/**
	 * One ground level for the whole city: the lowest terrain height across the corners of every
	 * building cell, snapped down to a multiple of the storey height.
	 *
	 * <p>Taking the minimum means the city is cut into a slope rather than left standing on stilts
	 * over it — the same rule the group placement used, now applied over a whole city instead of a
	 * five-cell star. The samples come from {@link ChunkGenerator#getFirstOccupiedHeight}, which
	 * evaluates the terrain noise directly, so no chunk has to exist and the answer is identical no
	 * matter which chunk triggered the start.
	 */
	private static int groundLevel(Structure.GenerationContext context, CityLayout.Plan plan) {
		ChunkGenerator generator = context.chunkGenerator();
		int lowest = Integer.MAX_VALUE;
		for (CityLayout.Cell cell : plan.cells()) {
			if (cell.role() != CityLayout.Role.BUILDING) {
				continue;
			}
			int x0 = cell.chunkX() << 4;
			int z0 = cell.chunkZ() << 4;
			for (int[] corner : CELL_CORNERS) {
				lowest = Math.min(lowest, generator.getFirstOccupiedHeight(
						x0 + corner[0], z0 + corner[1], Heightmap.Types.WORLD_SURFACE_WG,
						context.heightAccessor(), context.randomState()));
			}
		}
		if (lowest == Integer.MAX_VALUE) {
			lowest = generator.getSeaLevel();
		}
		return Math.floorDiv(lowest, LostCityConfig.FLOOR_HEIGHT) * LostCityConfig.FLOOR_HEIGHT;
	}

	/**
	 * The style the whole city is built from.
	 *
	 * <p>Sampled from the biome <em>source</em> rather than from the level: at start-assembly time
	 * there is no world to read, and sampling once at the centre is also what keeps every building
	 * of a city in one palette. The biome→style table itself is untouched
	 * ({@link StyleSelector#styleFor}) — that file stays the single point of entry for styling.
	 */
	private static String resolveStyle(Structure.GenerationContext context, BlockPos centre) {
		Holder<Biome> biome = context.chunkGenerator().getBiomeSource().getNoiseBiome(
				QuartPos.fromBlock(centre.getX()), QuartPos.fromBlock(centre.getY()), QuartPos.fromBlock(centre.getZ()),
				context.randomState().sampler());
		String style = StyleSelector.styleFor(biome);
		return style == null ? StyleSelector.DEFAULT_STYLE : style;
	}

	@Override
	public StructureType<?> type() {
		return ModStructureTypes.LOST_CITY;
	}
}
