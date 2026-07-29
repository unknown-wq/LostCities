package com.lostbuildings.world.structure;

import com.lostbuildings.registry.ModStructureTypes;
import com.lostbuildings.world.feature.StyleSelector;
import com.lostbuildings.world.structure.piece.AirportPiece;
import com.lostbuildings.world.structure.piece.BridgePiece;
import com.lostbuildings.world.structure.piece.BuildingPiece;
import com.lostbuildings.world.structure.piece.ParkPiece;
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
 *       half in sandstone;</li>
 *   <li><b>which street cells are bridges</b> (PORT #6) — this is the only moment the mod can both
 *       see a whole street cell and ask the generator how deep the ground under it is, which is
 *       exactly what "does this road cross water" needs.</li>
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

	/**
	 * How far the ground under a street cell may fall below the road surface before the cell becomes
	 * a bridge. Kept just under {@code Streets.MAX_EMBANKMENT} (8), so the two rules cannot disagree:
	 * anything the paving would have refused to carry is bridged instead of abandoned.
	 */
	private static final int BRIDGE_DROP = 6;

	/**
	 * How many <em>consecutive</em> blocks of a carriageway centreline have to be over
	 * {@link #BRIDGE_DROP} before the cell is bridged. Four is "wider than a road can step over" — a
	 * river, a ravine, a lake edge — and not the lip of a hollow.
	 */
	private static final int BRIDGE_MIN_RUN = 4;

	/** The carriageway is the middle eight columns of a cell; its centreline is column 8. */
	private static final int ROAD_MID = 8;

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

		int[] samples = terrainSamples(context, plan);
		int groundY = groundLevel(samples, context);
		int minY = context.heightAccessor().getMinY();
		if (groundY <= minY + LostCityConfig.FLOOR_HEIGHT) {
			return Optional.empty();
		}
		// A city stands at one level over its whole footprint, so a site with too much relief cannot
		// be built on without cutting into the hill above it and standing on fill below it.
		int maxDiff = this.config.maxHeightDiff();
		if (maxDiff > 0 && CityLayout.groundSpreadFrom(samples) > maxDiff) {
			return Optional.empty();
		}

		BlockPos centre = new BlockPos(origin.getMiddleBlockX(), groundY, origin.getMiddleBlockZ());
		Holder<Biome> biome = biomeAt(context, centre);
		String style = resolveStyle(biome);
		StyleSelector.Climate climate = StyleSelector.climateFor(biome);

		return Optional.of(new Structure.GenerationStub(centre,
				builder -> addPieces(builder, context, plan, groundY, style, climate)));
	}

	private void addPieces(StructurePiecesBuilder builder, Structure.GenerationContext context,
	                       CityLayout.Plan plan, int groundY, String style, StyleSelector.Climate climate) {
		LostCityConfig.StreetSettings streets = this.config.streets();
		// One airfield per city, on the outer ring. It takes its three cells over from whatever the
		// layout put there, so those cells are skipped rather than built twice.
		Airport airport = Airport.forPlan(plan, context.seed());
		for (CityLayout.Cell cell : plan.cells()) {
			if (airport.claims(cell)) {
				continue;
			}
			switch (cell.role()) {
				case BUILDING -> builder.addPiece(new BuildingPiece(
						cell.chunkX(), cell.chunkZ(), groundY,
						this.config.buildingName(cell.buildingIndex()), style,
						cell.floors(), cell.quarterTurns(), this.config.foundation(),
						this.config.cellars(), this.config.damageChance(), cell.kind(), climate));
				// A landmark quadrant is an ordinary building with a name from the 2x2 table, no
				// rotation (the quadrants have to keep their shared edges) and the same storey count
				// as its three siblings — all of which the layout already fixed.
				case MULTI_BUILDING -> builder.addPiece(new BuildingPiece(
						cell.chunkX(), cell.chunkZ(), groundY,
						this.config.multiBuildingName(cell.buildingIndex(), cell.variant()), style,
						cell.floors(), 0, this.config.foundation(),
						this.config.cellars(), this.config.damageChance(), cell.kind(), climate));
				case PARK -> builder.addPiece(new ParkPiece(
						cell.chunkX(), cell.chunkZ(), groundY,
						this.config.parkName(cell.variant()), style, climate));
				case STREET -> {
					if (needsBridge(context, cell, groundY) && !streets.bridges().isEmpty()) {
						// Hashed on the coordinate ACROSS the crossing — the one value every cell of one
						// crossing shares — so a river is spanned by one bridge rather than by an
						// alternating chain of open and covered cells. This is only the fallback: the
						// city style's own bridge list wins, and that is resolved in the piece, where the
						// assets exist. See BridgePiece.Spans.familyFor.
						boolean westEast = (cell.neighbourMask() & (CityLayout.WEST | CityLayout.EAST)) != 0;
						int across = westEast ? cell.chunkZ() : cell.chunkX();
						int pick = (int) Math.floorMod(
								CityLayout.hash(context.seed(), across, westEast ? 0 : 1, 0x5E),
								streets.bridges().size());
						builder.addPiece(new BridgePiece(cell.chunkX(), cell.chunkZ(), groundY,
								this.config.bridgeName(pick), style,
								BridgePiece.turnsForMask(cell.neighbourMask()), cell.neighbourMask(), climate));
					} else {
						builder.addPiece(new StreetPiece(
								cell.chunkX(), cell.chunkZ(), groundY,
								streets.block(), streets.width(), cell.neighbourMask(), style,
								streets.tiles(), streets.lampSpacing(), streets.potholeChance(), climate));
					}
				}
			}
		}
		for (Airport.Segment segment : airport.segments()) {
			builder.addPiece(new AirportPiece(segment, groundY, style, climate));
		}
	}

	/**
	 * Whether this street cell has to be bridged rather than paved.
	 *
	 * <p><b>What this asks.</b> Not "is any corner of the cell low". That was the old rule, and the
	 * four corners are the columns <em>furthest</em> from the carriageway, so a pavement corner
	 * clipping the lip of a shallow grassy hollow bridged the whole 16×16 cell — and at a crossroads,
	 * every arm of it. That is what put a cross of decks over a meadow with no water in sight. The
	 * amplifier is that {@code groundY} is a median snapped <em>up</em> to a multiple of the storey
	 * height, so ground that dips only a little locally can still read as 7–10 below the road.
	 *
	 * <p>A road's question is whether the ground under its own centreline falls away over a stretch
	 * long enough that {@link com.lostbuildings.world.feature.Streets} cannot embank it. So this walks
	 * the centreline of each axis the cell actually carries road on, and bridges only on a contiguous
	 * run.
	 *
	 * <p>{@code OCEAN_FLOOR_WG} is kept: it ignores water, which is what makes a river read as a gap
	 * at all, and it is the same sampler and accessor {@link #groundLevel} uses.
	 */
	private static boolean needsBridge(Structure.GenerationContext context, CityLayout.Cell cell, int groundY) {
		int mask = cell.neighbourMask();
		boolean westEast = (mask & (CityLayout.WEST | CityLayout.EAST)) != 0;
		boolean northSouth = (mask & (CityLayout.NORTH | CityLayout.SOUTH)) != 0;
		return (westEast && spansAGap(context, cell, groundY, true))
				|| (northSouth && spansAGap(context, cell, groundY, false));
	}

	/** Whether one axis of the cell's carriageway centreline crosses an unbridgeable run. */
	private static boolean spansAGap(Structure.GenerationContext context, CityLayout.Cell cell,
	                                 int groundY, boolean alongX) {
		ChunkGenerator generator = context.chunkGenerator();
		int x0 = cell.chunkX() << 4;
		int z0 = cell.chunkZ() << 4;
		int run = 0;
		for (int i = 0; i < 16; i++) {
			int height = generator.getFirstOccupiedHeight(
					x0 + (alongX ? i : ROAD_MID), z0 + (alongX ? ROAD_MID : i),
					Heightmap.Types.OCEAN_FLOOR_WG, context.heightAccessor(), context.randomState());
			run = groundY - 1 - height > BRIDGE_DROP ? run + 1 : 0;
			if (run >= BRIDGE_MIN_RUN) {
				return true;
			}
		}
		return false;
	}

	/**
	 * One ground level for the whole city: the <em>median</em> terrain height across the corners of
	 * every built-on cell, snapped to the nearest multiple of the storey height.
	 *
	 * <p>The samples come from {@link ChunkGenerator#getFirstOccupiedHeight}, which evaluates the
	 * terrain noise directly, so no chunk has to exist and the answer is identical no matter which
	 * chunk triggered the start. Street cells are excluded on purpose: a street that happens to
	 * cross a river would otherwise drag the level down to the river bed, and a street over water is
	 * a bridge anyway.
	 *
	 * <p>The statistic itself lives in {@link CityLayout#groundLevelFrom} — it is pure, so it is
	 * unit-tested there, including the regression this replaced (a minimum over a 144-block-wide
	 * city sinks the whole settlement to its deepest sample and buries the streets).
	 */
	private static int[] terrainSamples(Structure.GenerationContext context, CityLayout.Plan plan) {
		ChunkGenerator generator = context.chunkGenerator();
		int[] samples = new int[plan.cells().size() * CELL_CORNERS.length];
		int n = 0;
		for (CityLayout.Cell cell : plan.cells()) {
			if (cell.role() == CityLayout.Role.STREET) {
				continue;
			}
			int x0 = cell.chunkX() << 4;
			int z0 = cell.chunkZ() << 4;
			for (int[] corner : CELL_CORNERS) {
				samples[n++] = generator.getFirstOccupiedHeight(
						x0 + corner[0], z0 + corner[1], Heightmap.Types.WORLD_SURFACE_WG,
						context.heightAccessor(), context.randomState());
			}
		}
		return java.util.Arrays.copyOf(samples, n);
	}

	/**
	 * The city's shared level from those samples, or sea level snapped to a storey when a plan
	 * somehow has no built-on cell to sample.
	 *
	 * <p>Both statistics — the level and the spread that vetoes the site — are taken from the
	 * <em>same</em> array, so the two decisions cannot be made on different terrain. Note both sort
	 * it in place, which is harmless here and is why the array is ours rather than the caller's.
	 */
	private static int groundLevel(int[] samples, Structure.GenerationContext context) {
		int level = CityLayout.groundLevelFrom(samples, LostCityConfig.FLOOR_HEIGHT);
		return level == Integer.MIN_VALUE
				? Math.floorDiv(context.chunkGenerator().getSeaLevel(), LostCityConfig.FLOOR_HEIGHT)
						* LostCityConfig.FLOOR_HEIGHT
				: level;
	}

	/**
	 * The style the whole city is built from.
	 *
	 * <p>Sampled from the biome <em>source</em> rather than from the level: at start-assembly time
	 * there is no world to read, and sampling once at the centre is also what keeps every building
	 * of a city in one palette. The biome→city style→style chain lives in
	 * {@link StyleSelector#styleFor} — that file stays the single point of entry for styling.
	 */
	private static String resolveStyle(Holder<Biome> biome) {
		return StyleSelector.styleFor(biome);
	}

	/**
	 * The biome at the city's centre, read from the biome <em>source</em> rather than from the level:
	 * at start-assembly time there is no world to read, and one sample at the centre is also what
	 * keeps a whole city in one palette instead of half of it in sandstone.
	 */
	private static Holder<Biome> biomeAt(Structure.GenerationContext context, BlockPos centre) {
		return context.chunkGenerator().getBiomeSource().getNoiseBiome(
				QuartPos.fromBlock(centre.getX()), QuartPos.fromBlock(centre.getY()), QuartPos.fromBlock(centre.getZ()),
				context.randomState().sampler());
	}

	@Override
	public StructureType<?> type() {
		return ModStructureTypes.LOST_CITY;
	}
}
