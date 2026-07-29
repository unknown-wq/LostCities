package com.lostbuildings.command;

import com.lostbuildings.LostBuildings;
import com.lostbuildings.engine.Assets;
import com.lostbuildings.engine.codec.DataTools;
import com.lostbuildings.world.structure.LostCityConfig;
import com.lostbuildings.world.structure.LostCityStructure;
import com.lostbuildings.world.structure.ModStructures;
import com.lostbuildings.world.structure.piece.AirportPiece;
import com.lostbuildings.world.structure.piece.BridgePiece;
import com.lostbuildings.world.structure.piece.BuildingPiece;
import com.lostbuildings.world.structure.piece.ParkPiece;
import com.lostbuildings.world.structure.piece.StreetPiece;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceKeyArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * {@code /lostcity} — build a whole city on the spot, at a size you choose.
 *
 * <h2>Why this exists</h2>
 * Until this command, the only way to look at a city was to fly until worldgen happened to roll one:
 * a city needs a biome in {@code #lostbuildings:has_structure/lost_city}, a placement grid hit, and a
 * site flat enough to pass {@link LostCityConfig#maxHeightDiff}. That is three filters between a code
 * change and seeing whether it worked.
 *
 * <h2>What it builds — and why it is the real thing</h2>
 * This does <em>not</em> re-implement city assembly. It walks the same path
 * {@code /place structure} walks: {@link Structure#generate} builds a real
 * {@link StructureStart} for the caller's chunk — which runs
 * {@code LostCityStructure.findGenerationPoint}, the same layout, the same seeded ground level, the
 * same style and bridge decisions — and then {@link StructureStart#placeInChunk} writes each piece
 * exactly as the chunk generator would have. Nothing here knows what a building is. If the command
 * and worldgen ever disagreed, the bug would be in the shared path, which is precisely what a test
 * command should be able to say.
 *
 * <p>Three things are, unavoidably, not identical to a city that grew during worldgen:
 * <ol>
 *   <li><b>The relief veto is off.</b> {@link LostCityConfig#maxHeightDiff} decides whether a site is
 *       <em>offered</em> a city; it changes nothing about the city that is then built. A command that
 *       means "here" cannot honour a rule whose answer is "not here", so it is set to {@code 0}. The
 *       pieces are the pieces the same seed and spot would have produced anyway.</li>
 *   <li><b>The chunks are already finished.</b> During worldgen a city is written into chunks whose
 *       features have not run yet; here they have. Every piece reads
 *       {@code Heightmap.Types.WORLD_SURFACE_WG}, and on a finished chunk that heightmap includes the
 *       trees. So foundations excavate a little more and snow can settle on a canopy. This is the
 *       same discrepancy vanilla's own {@code /place structure} has, and it is why the command is a
 *       development tool and not a world-editing feature.</li>
 *   <li><b>No structure reference is recorded.</b> The city is blocks in the world, not a start in
 *       the chunk's structure list, so {@code /locate} will not find it and its mob-spawn overrides
 *       do not apply. Again: exactly what {@code /place} does.</li>
 * </ol>
 *
 * <h2>Syntax</h2>
 * <pre>
 * /lostcity here [&lt;cells&gt; [&lt;style&gt;]]
 * /lostcity build &lt;structure&gt; [&lt;cells&gt; [&lt;style&gt;]]
 * </pre>
 * {@code <cells>} is the city size in cells — the same unit as {@code city_size} in the structure
 * JSON, one cell being one chunk. It defaults to the value the chosen structure ships with.
 * {@code <style>} forces a palette style ({@code standard}, {@code desert}, {@code snowy},
 * {@code swamp}, …) instead of resolving one from the biome. Permission level 2, like {@code /place}.
 *
 * @see #MAX_CELLS for the size cap and the reason for it
 */
public final class LostCityCommand {

	/**
	 * The largest city the command will build, in cells per side.
	 *
	 * <p>The cost that matters is not the block writes, it is that every chunk the city touches has to
	 * be <em>generated to {@code FULL}</em> before anything may be written to it, synchronously, on
	 * the server thread. At the cap that is {@code 25 * 25 = 625} chunks — about what a player at
	 * render distance 12 already holds, so a server that can host one player can survive this — and
	 * the command will still visibly hang the server for as long as those chunks take.
	 *
	 * <p>Asking for more is refused outright rather than clamped: silently building something other
	 * than what was asked for is worse than an error message, and a caller who typed {@code 200}
	 * wanted to know that 40,000 chunks is not a thing this command does.
	 *
	 * <p>Note this is far above the {@code 1..16} the datapack codec allows for {@code city_size}.
	 * That bound is about cities not overlapping each other on the worldgen placement grid; a one-shot
	 * command places no grid entry and competes with nothing.
	 */
	public static final int MAX_CELLS = 25;

	private static final SimpleCommandExceptionType ERROR_NO_LOST_CITY = new SimpleCommandExceptionType(
			Component.literal("The " + ModStructures.LOST_CITY.identifier() + " structure is not loaded in this world"));

	private static final DynamicCommandExceptionType ERROR_NOT_A_CITY = new DynamicCommandExceptionType(
			id -> Component.literal("Structure " + id + " is not a lost city (it is not a lostbuildings:lost_city)"));

	private static final DynamicCommandExceptionType ERROR_TOO_MANY_CELLS = new DynamicCommandExceptionType(
			cells -> Component.literal("A city of " + cells + " cells is too large: the limit is " + MAX_CELLS
					+ " cells (" + (MAX_CELLS * MAX_CELLS) + " chunks), because every chunk it touches has to be "
					+ "generated on the server thread before the city can be written into it"));

	private static final DynamicCommandExceptionType ERROR_UNKNOWN_STYLE = new DynamicCommandExceptionType(
			style -> Component.literal("Unknown style '" + style + "' - see data/lostbuildings/lostcities/styles/"));

	private static final SimpleCommandExceptionType ERROR_FAILED = new SimpleCommandExceptionType(
			Component.literal("Could not lay out a city here (no buildings configured, or no room above the "
					+ "world floor)"));

	/** Style names the loaded datapack actually has, for tab completion. */
	private static final SuggestionProvider<CommandSourceStack> STYLE_SUGGESTIONS = (context, builder) -> {
		Assets assets = LostBuildings.ASSETS;
		return SharedSuggestionProvider.suggest(assets == null ? List.of() : assets.getStyles().keySet(), builder);
	};

	private LostCityCommand() {
	}

	/** Hooks the command onto the server's dispatcher. Called once, from the mod initializer. */
	public static void init() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> register(dispatcher));
	}

	static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("lostcity")
				// Level 2 = "gamemasters", the same bar /place, /setblock and /fill sit behind.
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("here")
						.executes(context -> build(context, defaultCity(context), OptionalInt.empty(), null))
						.then(Commands.argument("cells", IntegerArgumentType.integer(1))
								.executes(context -> build(context, defaultCity(context), cells(context), null))
								.then(Commands.argument("style", StringArgumentType.word())
										.suggests(STYLE_SUGGESTIONS)
										.executes(context -> build(context, defaultCity(context), cells(context),
												style(context))))))
				.then(Commands.literal("build")
						.then(Commands.argument("structure", ResourceKeyArgument.key(Registries.STRUCTURE))
								.executes(context -> build(context, namedCity(context), OptionalInt.empty(), null))
								.then(Commands.argument("cells", IntegerArgumentType.integer(1))
										.executes(context -> build(context, namedCity(context), cells(context), null))
										.then(Commands.argument("style", StringArgumentType.word())
												.suggests(STYLE_SUGGESTIONS)
												.executes(context -> build(context, namedCity(context), cells(context),
														style(context))))))));
	}

	// ---------------------------------------------------------------- argument plumbing

	private static OptionalInt cells(CommandContext<CommandSourceStack> context) {
		return OptionalInt.of(IntegerArgumentType.getInteger(context, "cells"));
	}

	private static String style(CommandContext<CommandSourceStack> context) {
		return StringArgumentType.getString(context, "style");
	}

	/** The shipped {@code lostbuildings:lost_city}, as the world's registry knows it. */
	private static Holder.Reference<Structure> defaultCity(CommandContext<CommandSourceStack> context)
			throws CommandSyntaxException {
		return context.getSource().registryAccess().lookupOrThrow(Registries.STRUCTURE)
				.get(ModStructures.LOST_CITY)
				.orElseThrow(ERROR_NO_LOST_CITY::create);
	}

	/** Whatever structure the caller named — validated as a lost city inside {@link #build}. */
	private static Holder.Reference<Structure> namedCity(CommandContext<CommandSourceStack> context)
			throws CommandSyntaxException {
		return ResourceKeyArgument.getStructure(context, "structure");
	}

	// ---------------------------------------------------------------- the pure decisions

	/**
	 * The config the city is actually built from: the structure's own, with the caller's size (when
	 * they gave one) and with the terrain-relief veto disabled.
	 *
	 * <p>Split out and pure so the two decisions that are easy to get wrong — "an absent argument
	 * means the shipped default, not zero" and "the veto must not be able to refuse the caller's own
	 * position" — are unit-testable without a server.
	 *
	 * @throws CommandSyntaxException when the requested size is past {@link #MAX_CELLS}
	 */
	public static LostCityConfig configFor(LostCityConfig shipped, OptionalInt requestedCells)
			throws CommandSyntaxException {
		int cells = requestedCells.orElse(shipped.citySize());
		if (cells > MAX_CELLS) {
			throw ERROR_TOO_MANY_CELLS.create(cells);
		}
		return shipped.withCitySize(cells).withMaxHeightDiff(0);
	}

	/**
	 * What a finished start actually contains, counted off the pieces rather than off the plan.
	 *
	 * <p>Counting the emitted pieces is the honest answer to "what did I just get": the plan is what
	 * the layout <em>wanted</em>, and the airfield in particular takes cells over from it, so a report
	 * derived from the plan would over-count by three every time a city has one.
	 *
	 * @param cells     every piece the city emitted; a piece is one 16×16 cell
	 * @param buildings ordinary houses and landmark quadrants alike
	 * @param streets   paved cells
	 * @param bridges   street cells carried over water or a drop instead of paved
	 * @param parks     green cells
	 * @param airfield  cells of the one airfield, {@code 0} when the city has none
	 */
	public record CitySummary(int cells, int buildings, int streets, int bridges, int parks, int airfield) {

		public static CitySummary of(List<StructurePiece> pieces) {
			int buildings = 0;
			int streets = 0;
			int bridges = 0;
			int parks = 0;
			int airfield = 0;
			for (StructurePiece piece : pieces) {
				if (piece instanceof BuildingPiece) {
					buildings++;
				} else if (piece instanceof BridgePiece) {
					bridges++;
				} else if (piece instanceof StreetPiece) {
					streets++;
				} else if (piece instanceof ParkPiece) {
					parks++;
				} else if (piece instanceof AirportPiece) {
					airfield++;
				}
			}
			return new CitySummary(pieces.size(), buildings, streets, bridges, parks, airfield);
		}

		/** The lots — everything that is not road surface. What "how big is this city" usually means. */
		public int lots() {
			return buildings + parks + airfield;
		}

		/** Human-readable tail of the success message, e.g. {@code "18 buildings, 40 streets, 4 parks"}. */
		public String describe() {
			List<String> parts = new ArrayList<>(5);
			append(parts, buildings, "building");
			append(parts, streets, "street");
			append(parts, bridges, "bridge");
			append(parts, parks, "park");
			append(parts, airfield, "airfield cell");
			return parts.isEmpty() ? "nothing" : String.join(", ", parts);
		}

		private static void append(List<String> parts, int count, String noun) {
			if (count > 0) {
				parts.add(count + " " + noun + (count == 1 ? "" : "s"));
			}
		}
	}

	// ---------------------------------------------------------------- the command itself

	private static int build(CommandContext<CommandSourceStack> context, Holder.Reference<Structure> holder,
	                         OptionalInt requestedCells, String requestedStyle) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		if (!(holder.value() instanceof LostCityStructure city)) {
			throw ERROR_NOT_A_CITY.create(holder.key().identifier());
		}
		String style = validateStyle(requestedStyle);
		LostCityConfig config = configFor(city.config(), requestedCells);

		ServerLevel level = source.getLevel();
		ChunkPos origin = ChunkPos.containing(BlockPos.containing(source.getPosition()));
		ChunkGenerator generator = level.getChunkSource().getGenerator();

		// Exactly what PlaceCommand.placeStructure does, with two differences: the structure is the
		// derived copy (never the registry entry), and the holder is still the registry one because
		// generate() only passes it to the JFR profiler - everything that decides what gets built comes
		// off `this`.
		StructureStart start = city.derive(config, style).generate(
				holder, level.dimension(), source.registryAccess(), generator, generator.getBiomeSource(),
				level.getChunkSource().randomState(), level.getStructureManager(), level.getSeed(),
				origin, 0, level, biome -> true);
		if (!start.isValid()) {
			throw ERROR_FAILED.create();
		}

		BoundingBox box = start.getBoundingBox();
		ChunkPos min = new ChunkPos(SectionPos.blockToSectionCoord(box.minX()), SectionPos.blockToSectionCoord(box.minZ()));
		ChunkPos max = new ChunkPos(SectionPos.blockToSectionCoord(box.maxX()), SectionPos.blockToSectionCoord(box.maxZ()));

		// Two passes, not one. Every chunk has to reach FULL *before* the first block is written,
		// because generating chunk N+1 runs its feature step and would happily overwrite the part of
		// the city already standing in chunk N. Vanilla's /place refuses on an unloaded chunk instead;
		// this command's whole point is that you should not have to fly the area in first.
		List<ChunkPos> footprint = ChunkPos.rangeClosed(min, max).toList();
		for (ChunkPos chunk : footprint) {
			level.getChunk(chunk.x(), chunk.z(), ChunkStatus.FULL, true);
		}
		for (ChunkPos chunk : footprint) {
			start.placeInChunk(level, level.structureManager(), generator, level.getRandom(),
					new BoundingBox(chunk.getMinBlockX(), level.getMinY(), chunk.getMinBlockZ(),
							chunk.getMaxBlockX(), level.getMaxY() + 1, chunk.getMaxBlockZ()),
					chunk);
		}

		CitySummary summary = CitySummary.of(start.getPieces());
		String name = holder.key().identifier().toString();
		int size = config.citySize();
		source.sendSuccess(() -> Component.literal(
				"Built " + name + " centred on chunk [" + origin.x() + ", " + origin.z() + "] ("
						+ box.minX() + ".." + box.maxX() + " x " + box.minZ() + ".." + box.maxZ() + "): "
						+ size + "x" + size + " cells requested, " + summary.cells() + " emitted ("
						+ summary.lots() + " lots) - " + summary.describe()
						+ (style == null ? "" : ", style " + style)), true);
		return summary.cells();
	}

	/**
	 * The style name, checked against the loaded datapack.
	 *
	 * <p>{@code Assets.getStyle} never returns null — it falls back to an arbitrary style and logs —
	 * which is right for worldgen, where a typo must not abort a chunk, and wrong for a command, where
	 * silently building the wrong thing is the failure. So the membership test is done here instead.
	 */
	private static String validateStyle(String requested) throws CommandSyntaxException {
		if (requested == null) {
			return null;
		}
		Assets assets = LostBuildings.ASSETS;
		String normalized = DataTools.normalize(requested);
		if (assets == null || !assets.getStyles().containsKey(normalized)) {
			throw ERROR_UNKNOWN_STYLE.create(requested);
		}
		return normalized;
	}
}
