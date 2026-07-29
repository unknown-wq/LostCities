package com.lostbuildings.world.structure.piece;

import com.lostbuildings.LostBuildings;
import com.lostbuildings.engine.Assets;
import com.lostbuildings.engine.BuildingEngine;
import com.lostbuildings.engine.BuildingPart;
import com.lostbuildings.engine.CompiledPalette;
import com.lostbuildings.engine.Style;
import com.lostbuildings.engine.codec.CityStyleRE;
import com.lostbuildings.engine.codec.ObjectSelector;
import com.lostbuildings.engine.codec.SelectorsRE;
import com.lostbuildings.engine.Transform;
import com.lostbuildings.registry.ModStructurePieceTypes;
import com.lostbuildings.world.feature.StyleSelector;
import com.lostbuildings.world.feature.WorldGenFlags;
import com.lostbuildings.world.structure.CityLayout;
import com.lostbuildings.world.structure.StreetDecor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;

import java.util.List;

/**
 * A street cell that spans water or a drop instead of stopping at it (PORT #6).
 *
 * <p>Wave 1's {@code Streets} gave up on any column that would have needed more than eight blocks of
 * embankment, which is the right call for a road — but it also meant a city sitting on a river bank
 * fell apart into islands with roads that ran into the water and ended. The structure decides, while
 * it is assembling the start and can still sample the terrain noise, that such a cell is a bridge,
 * and this piece carries the road across it.
 *
 * <h2>What this piece builds, and why the split is where it is</h2>
 *
 * <p>A bridge is three things, and they want three different authors:
 *
 * <ul>
 *   <li><b>The deck</b> — carriageway, footway, kerb ring and parapet. Built <em>here</em>, in Java,
 *       from the cell's road-connectivity mask and the very same {@link StreetDecor} predicates
 *       {@code StreetPiece} uses. That is not a shortcut, it is the fix for the bug this piece
 *       shipped with: a fixed 16×10 plate drawn from a part cannot know which of its four sides
 *       carries a road, so a bridged junction came out as an east–west plate with its parapets
 *       running straight across the north–south carriageway, and its neighbours came out as
 *       north–south plates that stopped three blocks short of it. Two decks at right angles cutting
 *       through one another, with holes at the seam. Deriving the deck from the mask makes the seam
 *       correct by construction for all sixteen masks, and makes it correct against a
 *       <em>street</em> cell too, because both sides are reading the same function.</li>
 *   <li><b>The substructure</b> — pier bents marching down to whatever is under the cell. Also here,
 *       because how far down "down" is depends on the terrain, which no fixed-height part can know.
 *       The old piece built none at all, so an open span over a dry hollow was a plate hanging in
 *       the air.</li>
 *   <li><b>The superstructure</b> — trusses, arches, suspension pylons, gantries, sign boards, the
 *       roof of a covered span, and the wreckage of any of them. That is pure decoration at a fixed
 *       height above a known deck, which is exactly what a {@code bridge_*} part is good at, so it
 *       stays in the datapack and is stamped by {@link PartPlacer}.</li>
 * </ul>
 *
 * <p>The two halves meet through the part's {@code "meta"} block: a part names the character it wants
 * used for the deck, the piers, the parapet and the cables ({@link Mat}), and this piece resolves
 * those characters through the same compiled palette the part itself is drawn with. So the material
 * vocabulary of a bridge lives in the datapack even though the geometry is computed — and a datapack
 * that ships a part with no {@code meta} at all still gets a working bridge from the defaults.
 *
 * <h2>Vertical budget</h2>
 *
 * <p>The box is {@code groundY - }{@value #BELOW_GROUND}{@code  ..  groundY + }{@value #ABOVE_GROUND}.
 * The part origin is {@code groundY - 1} (the carriageway itself, flush with the roads either side),
 * so a part may carry up to {@value #PART_SLICE_BUDGET} slices; the piers use the room below.
 *
 * <h2>What decides which part</h2>
 *
 * <p>{@link Spans#forCell} — the bridge equivalent of {@code StreetTiles.forMask}. A cell that is not
 * a straight through-road is a <em>junction</em> and gets a part with nothing overhead, because a
 * truss across a crossroads would block the arm it crosses. A straight cell belongs to a
 * <em>crossing</em>, and a crossing's structural style is a hash of the axis and of the coordinate
 * <em>across</em> it — the one coordinate every cell of one crossing shares — so a whole river
 * crossing comes out as one bridge (a suspension bridge, a truss viaduct or a chain of arches)
 * instead of sixteen unrelated blocks. Within a suspension crossing the pylons stand every third
 * cell, and the main cable is drawn here rather than in a part precisely so that it is a function of
 * the world coordinate and therefore continuous from one cell to the next.
 */
public class BridgePiece extends CityPiece {

	/** Room below the deck for the pier bents, and above it for pylons, trusses and gantries. */
	private static final int BELOW_GROUND = 20;
	private static final int ABOVE_GROUND = 18;

	/**
	 * Slices a {@code bridge_*} part may carry: the box top minus the part origin ({@code groundY-1}),
	 * inclusive. Anything past this is computed by {@link PartPlacer} and thrown away by the
	 * {@code chunkBox.isInside} test, so it is silently missing rather than an error.
	 */
	public static final int PART_SLICE_BUDGET = ABOVE_GROUND + 2;

	/** Headroom cleared over the deck so the bridge is not buried in a bank it cuts through. */
	private static final int CLEARANCE = 9;

	// ---------------------------------------------------------------- deck geometry (see StreetDecor)

	/** Y of the carriageway, relative to {@code groundY}. Flush with the streets either side. */
	private static final int DECK = -1;
	/** Y of the footway top and of the kerb slab. */
	private static final int FOOTWAY = 0;
	/** Y of the parapet wall, and of the slab capping it. */
	private static final int PARAPET = 1;
	private static final int PARAPET_CAP = 2;
	/** Y of the soffit: edge girders and cross beams hung under the deck. */
	private static final int SOFFIT = -2;

	/** Top of a suspension pylon, and the sag of the main cable at midspan. */
	private static final int PYLON_TOP = 15;
	private static final int CABLE_SAG = 9;
	/** Along-axis offset of the pylon inside a tower cell, and the cell period between towers. */
	private static final int PYLON_OFFSET = 7;
	private static final int TOWER_PERIOD = 3;

	/** Lamps on the parapet, spaced on the absolute world axis so they carry across cells. */
	private static final int LAMP_SPACING = 8;

	// ---------------------------------------------------------------- decay

	/** Share of interior deck plates that have fallen through. Never on the outer ring (see seam). */
	private static final double MISSING_PLATE_CHANCE = 0.055D;
	/** Share of parapet columns that are gone entirely, and of those that are merely buckled. */
	private static final double PARAPET_GONE_CHANCE = 0.07D;
	private static final double PARAPET_BUCKLED_CHANCE = 0.16D;
	/** Share of deck columns strewn with wreckage. */
	private static final double DEBRIS_CHANCE = 0.03D;
	/** Share of pier bays that lost a brace. */
	private static final double BRACE_GONE_CHANCE = 0.2D;
	/** Share of suspension hangers that have snapped. */
	private static final double HANGER_GONE_CHANCE = 0.18D;
	/** Share of spans whose superstructure is a wreck rather than a structure. */
	private static final double RUIN_CHANCE = 0.18D;

	private static final int SALT_PLATE = 0x11;
	private static final int SALT_PARAPET = 0x12;
	private static final int SALT_DEBRIS = 0x13;
	private static final int SALT_BRACE = 0x14;
	private static final int SALT_HANGER = 0x15;
	private static final int SALT_RUIN = 0x16;
	private static final int SALT_CROSSING = 0x17;
	/** Salt for the family roll, independent of the structural-style roll that shares its inputs. */
	private static final int SALT_FAMILY = 0x18;

	private static final BlockState AIR = Blocks.AIR.defaultBlockState();

	private final String partName;
	private final String styleName;
	/** Quarter turn applied to the part: 0 for a west–east bridge, 1 for a north–south one. */
	private final int quarterTurns;
	/** Which of the four sides carry a road — see {@link #neighbourMask()}. */
	private final int neighbourMask;

	public BridgePiece(int chunkX, int chunkZ, int groundY, String partName, String styleName, int quarterTurns,
	                   int neighbourMask, StyleSelector.Climate climate) {
		super(ModStructurePieceTypes.BRIDGE, cellBox(chunkX, chunkZ, groundY, BELOW_GROUND, ABOVE_GROUND), groundY,
				climate);
		this.partName = partName;
		this.styleName = styleName;
		this.quarterTurns = quarterTurns;
		this.neighbourMask = neighbourMask;
	}

	public BridgePiece(CompoundTag tag) {
		super(ModStructurePieceTypes.BRIDGE, tag);
		this.partName = tag.getStringOr("Part", "");
		this.styleName = tag.getStringOr("Style", StyleSelector.DEFAULT_STYLE);
		this.quarterTurns = Math.floorMod(tag.getIntOr("Turns", 0), 4);
		// A world saved before the mask was carried has no Neighbours tag. Fall back to the through
		// road its quarter turn implies, which is what such a piece drew anyway.
		this.neighbourMask = tag.getIntOr("Neighbours", 0) == 0
				? (this.quarterTurns == 0 ? CityLayout.WEST | CityLayout.EAST : CityLayout.NORTH | CityLayout.SOUTH)
				: tag.getIntOr("Neighbours", 0);
	}

	@Override
	protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
		super.addAdditionalSaveData(context, tag);
		tag.putString("Part", this.partName);
		tag.putString("Style", this.styleName);
		tag.putInt("Turns", this.quarterTurns);
		tag.putInt("Neighbours", this.neighbourMask);
	}

	/**
	 * The quarter turn a road with this connectivity mask needs.
	 *
	 * <p>The parts run west–east unrotated, so it is the road continuing north or south — and not
	 * east or west — that needs the turn.
	 *
	 * <p>The mask itself is <em>not</em> encoded here. A bridge needs to know which of its four sides
	 * carry a road for the same reasons a street cell does: which columns are carriageway, which are
	 * footway, where the kerb ring runs, which edges get a parapet. A rotation alone cannot express a
	 * bridged crossroads, and pretending it could is what made two arms of one bridged junction cut
	 * through each other. So the mask travels as its own constructor argument and its own NBT tag,
	 * the way {@code StreetPiece} has always carried it.
	 */
	public static int turnsForMask(int mask) {
		return (mask & (CityLayout.WEST | CityLayout.EAST)) != 0 ? 0 : 1;
	}

	/** This cell's road-connectivity mask: which of its four sides a road continues over. */
	public int neighbourMask() {
		return this.neighbourMask;
	}

	@Override
	public void postProcess(WorldGenLevel level, StructureManager structureManager, ChunkGenerator generator,
	                        RandomSource random, BoundingBox chunkBox, ChunkPos chunkPos, BlockPos referencePos) {
		if (!coversCell(chunkBox)) {
			return;
		}
		long seed = level.getSeed();
		RandomSource rand = cellRandom(seed, 0x5B);
		int mask = neighbourMask();

		Assets assets = LostBuildings.ASSETS;
		BuildingEngine engine = LostBuildings.ENGINE;
		String family = Spans.familyFor(assets, this.styleName, this.partName, mask, seed,
				cellChunkX(), cellChunkZ());
		Spans.Span span = Spans.forCell(mask, seed, cellChunkX(), cellChunkZ(), family);

		BuildingPart part = null;
		CompiledPalette palette = null;
		if (assets != null && engine != null) {
			Style style = assets.getStyle(this.styleName);
			palette = engine.buildPalette(assets, rand, style);
			part = assets.getPart(span.part());
			if (part == null && !this.partName.isEmpty()) {
				// A datapack that ships only the two family parts still gets a bridge: the deck, the
				// piers and the parapet are computed, and the family part stands in for the variant.
				part = assets.getPart(this.partName);
			}
		}
		// Palette-backed materials, with hard defaults so a bridge is still a bridge before the assets
		// are loaded — the same bargain StreetPiece strikes with its base course.
		Mat mat = new Mat(part, palette, rand);

		clearDeckVolume(level, chunkBox);
		layDeck(level, chunkBox, seed, mask, mat);
		raiseParapet(level, chunkBox, seed, mask, mat);
		buildPiers(level, chunkBox, seed, mask, mat);

		if (part != null && palette != null) {
			PartPlacer.place(level, chunkBox, part, cellMinX(), this.groundY + DECK, cellMinZ(),
					Transform.values()[Math.floorMod(span.quarterTurns(), 4)], palette, rand);
		}
		if (span.style() == Spans.Style.SUSPENSION) {
			drawCables(level, chunkBox, seed, span, mat);
		}
		weather(level, chunkBox, seed);
	}

	// ------------------------------------------------------------------ the deck

	/**
	 * Remove terrain standing in the deck's volume, so the crossing is actually passable.
	 *
	 * <p>Only the volume above the carriageway: the deck itself is written over whatever is at
	 * {@code groundY - 1} a moment later, and the piers want to <em>find</em> the ground below.
	 */
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

	/**
	 * Carriageway, footway, kerb ring and soffit — the road surface of the bridge.
	 *
	 * <p>Every column of the cell is decked, and which kind of decking it gets comes from
	 * {@link StreetDecor#isRoad} and {@link StreetDecor#isKerb} over this cell's mask. That is the
	 * whole reason a bridge now seams: a street cell's carriageway, kerb and pavement are laid out by
	 * those same two predicates, so a bridge abutting a street, or a bridge abutting a bridge at a
	 * right angle, agrees with its neighbour column for column without either of them knowing what
	 * the other is.
	 *
	 * <p>Heights match a street exactly — carriageway at {@code groundY - 1}, footway a full block at
	 * {@code groundY}, kerb a half slab at {@code groundY} — so there is no step at the joint. That is
	 * also why the deck is <em>not</em> cambered: a crowned carriageway would be a lip at both
	 * abutments. The camber such a deck would have is expressed downwards instead, in the soffit.
	 */
	private void layDeck(WorldGenLevel level, BoundingBox chunkBox, long seed, int mask, Mat mat) {
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int dx = 0; dx < FOOTPRINT; dx++) {
			for (int dz = 0; dz < FOOTPRINT; dz++) {
				int wx = cellMinX() + dx;
				int wz = cellMinZ() + dz;
				boolean road = StreetDecor.isRoad(dx, dz, mask);
				boolean kerb = StreetDecor.isKerb(dx, dz, mask);

				if (road && isMissingPlate(seed, wx, wz, dx, dz)) {
					// A hole right through the deck. Left as air on purpose: the soffit below is
					// skipped too, so it is a fall, not a trap door onto a girder.
					set(level, chunkBox, cursor.set(wx, this.groundY + DECK, wz), AIR);
					continue;
				}

				set(level, chunkBox, cursor.set(wx, this.groundY + DECK, wz),
						isGutter(dx, dz, mask) ? mat.of(Mat.GUTTER) : mat.of(Mat.DECK));
				if (!road) {
					set(level, chunkBox, cursor.set(wx, this.groundY + FOOTWAY, wz),
							kerb ? mat.of(Mat.KERB) : mat.of(Mat.FOOTWAY));
				}
				soffit(level, chunkBox, cursor, wx, wz, dx, dz, mask, mat);

				if (road && !kerb && CityLayout.unit(CityLayout.hash(seed, wx, wz, SALT_DEBRIS)) < DEBRIS_CHANCE) {
					set(level, chunkBox, cursor.set(wx, this.groundY + FOOTWAY, wz), mat.of(Mat.DEBRIS));
				}
			}
		}
	}

	/**
	 * The underside of the deck: a girder under every kerb line and a cross beam every four blocks.
	 *
	 * <p>This is where the bridge gets its depth. Seen from the water a flat plate reads as
	 * placeholder geometry — which is exactly what a player said of the old one — while an edge beam
	 * with cross members reads as a structure, and it costs one extra block in maybe a third of the
	 * columns. The cross-beam pitch is measured on the <em>absolute</em> axis, like the lamps and the
	 * potholes, so the beams march evenly down a crossing instead of restarting at every cell.
	 */
	private void soffit(WorldGenLevel level, BoundingBox chunkBox, BlockPos.MutableBlockPos cursor,
	                    int wx, int wz, int dx, int dz, int mask, Mat mat) {
		if (!StreetDecor.isRoad(dx, dz, mask) && !StreetDecor.isKerb(dx, dz, mask)) {
			return;     // no girder under the footway; the deck plate is enough
		}
		boolean edge = StreetDecor.isKerb(dx, dz, mask);
		boolean crossBeam = Math.floorMod(wx, 4) == 0 || Math.floorMod(wz, 4) == 0;
		if (!edge && !crossBeam) {
			return;
		}
		set(level, chunkBox, cursor.set(wx, this.groundY + SOFFIT, wz),
				edge ? mat.of(Mat.GIRDER) : mat.of(Mat.BEAM));
	}

	/**
	 * The gutter channel: a carriageway column that touches the kerb ring, sunk half a block like the
	 * shipped {@code street_*} tiles sink theirs.
	 *
	 * <p>Small, and the reason it is here: without it the bridge's deck stands half a block proud of
	 * the street's gutter at the abutment, so a crossing has a lip along two of its sixteen columns at
	 * every joint. The rule is the tiles' own — {@code street_straight} rows 4 and 11 are {@code _} —
	 * expressed against the mask so it lands correctly on an arm of a junction too.
	 */
	static boolean isGutter(int dx, int dz, int mask) {
		if (!StreetDecor.isRoad(dx, dz, mask)) {
			return false;
		}
		return isPavement(dx - 1, dz, mask) || isPavement(dx + 1, dz, mask)
				|| isPavement(dx, dz - 1, mask) || isPavement(dx, dz + 1, mask);
	}

	/**
	 * A column of <em>this</em> cell that is not carriageway. Deliberately false outside the cell:
	 * a road column on the cell edge continues into the neighbour, so it is not a gutter — which is
	 * the difference between the two rows of gutter a street has and a channel round all four sides.
	 */
	private static boolean isPavement(int dx, int dz, int mask) {
		return dx >= 0 && dz >= 0 && dx < FOOTPRINT && dz < FOOTPRINT && !StreetDecor.isRoad(dx, dz, mask);
	}

	private boolean isMissingPlate(long seed, int wx, int wz, int dx, int dz) {
		// Never on the outer two rings: a hole at a cell edge would read as a mismatch with the
		// neighbouring cell rather than as damage, and the seam is the thing being defended here.
		if (dx < 2 || dz < 2 || dx > FOOTPRINT - 3 || dz > FOOTPRINT - 3) {
			return false;
		}
		return CityLayout.unit(CityLayout.hash(seed, wx, wz, SALT_PLATE)) < MISSING_PLATE_CHANCE;
	}

	/**
	 * The parapet: a wall and its capping slab along every cell edge that no road continues over.
	 *
	 * <p>Mask-driven for the same reason the deck is. An edge whose neighbour is another road cell
	 * gets nothing, so two bridge cells in a row leave the carriageway open between them and a bridged
	 * junction leaves all four arms open — which is precisely what the old fixed plate could not do,
	 * and why its parapets ran across the arm of the crossing it was standing on.
	 *
	 * <p>Every third or so column is missing or buckled, and there is a lantern every
	 * {@value #LAMP_SPACING} blocks on the absolute axis, so the light carries on from the street's
	 * lamp posts rather than stopping at the abutment.
	 */
	private void raiseParapet(WorldGenLevel level, BoundingBox chunkBox, long seed, int mask, Mat mat) {
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int dx = 0; dx < FOOTPRINT; dx++) {
			for (int dz = 0; dz < FOOTPRINT; dz++) {
				if (!isParapet(dx, dz, mask)) {
					continue;
				}
				int wx = cellMinX() + dx;
				int wz = cellMinZ() + dz;
				double roll = CityLayout.unit(CityLayout.hash(seed, wx, wz, SALT_PARAPET));
				if (roll < PARAPET_GONE_CHANCE) {
					continue;   // a gap you can walk off
				}
				set(level, chunkBox, cursor.set(wx, this.groundY + PARAPET, wz), mat.of(Mat.PARAPET));
				if (roll < PARAPET_GONE_CHANCE + PARAPET_BUCKLED_CHANCE) {
					continue;   // wall still standing, coping gone
				}
				set(level, chunkBox, cursor.set(wx, this.groundY + PARAPET_CAP, wz), mat.of(Mat.CAPSTONE));
				if (Math.floorMod(wx, LAMP_SPACING) == 0 && Math.floorMod(wz, LAMP_SPACING) == 0) {
					set(level, chunkBox, cursor.set(wx, this.groundY + PARAPET_CAP + 1, wz), mat.of(Mat.LAMP));
				}
			}
		}
	}

	/**
	 * Whether this column carries parapet: an edge column of the cell on a side no road continues
	 * over, and not itself carriageway.
	 */
	static boolean isParapet(int dx, int dz, int mask) {
		if (StreetDecor.isRoad(dx, dz, mask)) {
			return false;
		}
		return (dz == 0 && (mask & CityLayout.NORTH) == 0)
				|| (dz == FOOTPRINT - 1 && (mask & CityLayout.SOUTH) == 0)
				|| (dx == 0 && (mask & CityLayout.WEST) == 0)
				|| (dx == FOOTPRINT - 1 && (mask & CityLayout.EAST) == 0);
	}

	// ------------------------------------------------------------------ the substructure

	/**
	 * One pier bent per cell, standing on whatever is under the deck.
	 *
	 * <p>The bent is a pair of leg groups on either side of the carriageway joined by a cap beam
	 * immediately under the soffit, cross-braced every {@value #BRACE_PITCH} blocks as it descends and
	 * finished with a spread footing where it lands. It is placed at the middle of the cell, so a
	 * crossing grows a pier every sixteen blocks — the trestle rhythm — and a bridged junction gets a
	 * single cluster under its junction box instead of two bents fighting for the same columns.
	 *
	 * <p>The descent stops at the first block that is neither air nor water nor replaceable ground
	 * cover, which is what makes this work over a river, over a dry hollow and over a ravine with the
	 * same code. It is bounded by {@value #BELOW_GROUND} so a pier over a genuinely bottomless drop
	 * stops rather than running to the world floor, and by the box the piece may write in.
	 */
	private void buildPiers(WorldGenLevel level, BoundingBox chunkBox, long seed, int mask, Mat mat) {
		boolean alongX = StreetDecor.isRoad(0, 8, mask) || StreetDecor.isRoad(15, 8, mask);
		boolean alongZ = StreetDecor.isRoad(8, 0, mask) || StreetDecor.isRoad(8, 15, mask);
		boolean junction = alongX == alongZ;    // a crossroads, a bend, or an island: pier in the middle

		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		int capY = this.groundY + SOFFIT - 1;

		for (int dx = 0; dx < FOOTPRINT; dx++) {
			for (int dz = 0; dz < FOOTPRINT; dz++) {
				if (!inBent(dx, dz, junction, alongX)) {
					continue;
				}
				int wx = cellMinX() + dx;
				int wz = cellMinZ() + dz;
				set(level, chunkBox, cursor.set(wx, capY, wz), mat.of(Mat.PIER_CAP));
				if (isLeg(dx, dz, junction, alongX)) {
					sinkLeg(level, chunkBox, cursor, wx, wz, capY - 1, mat);
				} else {
					brace(level, chunkBox, seed, cursor, wx, wz, capY - 1, mat);
				}
			}
		}
	}

	/** Blocks between the horizontal ties that turn four legs into a braced tower. */
	private static final int BRACE_PITCH = 4;

	/** The footprint of the bent: two rows across the deck, spanning the carriageway and its kerbs. */
	private static boolean inBent(int dx, int dz, boolean junction, boolean alongX) {
		if (junction) {
			return dx >= 6 && dx <= 9 && dz >= 6 && dz <= 9;
		}
		int along = alongX ? dx : dz;
		int across = alongX ? dz : dx;
		return along >= 7 && along <= 8 && across >= 2 && across <= 13;
	}

	/** The load-bearing columns of a bent; everything else in its footprint is cap and bracing. */
	private static boolean isLeg(int dx, int dz, boolean junction, boolean alongX) {
		if (junction) {
			return (dx == 6 || dx == 9) && (dz == 6 || dz == 9);
		}
		int across = alongX ? dz : dx;
		return across == 2 || across == 3 || across == 12 || across == 13;
	}

	/** Drive one leg down until it finds something to stand on, then spread a footing under it. */
	private void sinkLeg(WorldGenLevel level, BoundingBox chunkBox, BlockPos.MutableBlockPos cursor,
	                     int wx, int wz, int fromY, Mat mat) {
		int floor = Math.max(level.getMinY() + 1, this.groundY - BELOW_GROUND);
		for (int y = fromY; y >= floor; y--) {
			cursor.set(wx, y, wz);
			if (!chunkBox.isInside(cursor)) {
				return;
			}
			boolean landed = isFooting(level.getBlockState(cursor));
			level.setBlock(cursor, landed ? mat.of(Mat.FOOTING) : mat.of(Mat.PIER), WorldGenFlags.SET_BLOCK);
			if (landed) {
				// One course into the ground, so the pier is founded rather than resting on the turf.
				cursor.set(wx, y - 1, wz);
				set(level, chunkBox, cursor, mat.of(Mat.FOOTING));
				return;
			}
		}
	}

	/** Horizontal ties between the legs, every {@value #BRACE_PITCH} blocks and not all of them left. */
	private void brace(WorldGenLevel level, BoundingBox chunkBox, long seed, BlockPos.MutableBlockPos cursor,
	                   int wx, int wz, int fromY, Mat mat) {
		int floor = Math.max(level.getMinY() + 1, this.groundY - BELOW_GROUND);
		for (int y = fromY; y >= floor; y--) {
			if (Math.floorMod(this.groundY - y, BRACE_PITCH) != 0) {
				continue;
			}
			cursor.set(wx, y, wz);
			if (!chunkBox.isInside(cursor)) {
				return;
			}
			if (!isFooting(level.getBlockState(cursor))) {
				if (CityLayout.unit(CityLayout.hash(seed, wx, wz * 31 + y, SALT_BRACE)) >= BRACE_GONE_CHANCE) {
					level.setBlock(cursor, mat.of(Mat.BRACE), WorldGenFlags.SET_BLOCK);
				}
				continue;
			}
			return;     // the bay has reached the ground; the legs carry on alone
		}
	}

	/**
	 * Whether a pier that reached this block has arrived. Air and water are open span; so is anything
	 * a plant or a snow layer, which would otherwise stop a pier a block short of the actual ground.
	 */
	private static boolean isFooting(BlockState state) {
		return state != null && !state.isAir() && state.getFluidState().isEmpty()
				&& !state.canBeReplaced();
	}

	// ------------------------------------------------------------------ the main cable

	/**
	 * The main cable of a suspension crossing, and the hangers under it.
	 *
	 * <p>Drawn here and not in the tower part because it has to be <em>continuous</em>: its height at
	 * a given block is a parabola in the absolute along-axis coordinate, anchored at the pylon tops
	 * every {@code TOWER_PERIOD * 16} blocks. Two neighbouring cells therefore agree on the cable's
	 * height at their shared edge without either of them knowing the other exists — which a part,
	 * whose slices are fixed heights, cannot do. It is the same trick {@code StreetDecor} plays with
	 * lamp spacing, applied to a curve.
	 */
	private void drawCables(WorldGenLevel level, BoundingBox chunkBox, long seed, Spans.Span span, Mat mat) {
		boolean alongX = span.quarterTurns() == 0;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		BlockState cable = alongX ? mat.of(Mat.CABLE_X) : mat.of(Mat.CABLE_Z);

		for (int i = 0; i < FOOTPRINT; i++) {
			int along = (alongX ? cellMinX() : cellMinZ()) + i;
			int cableY = this.groundY + cableHeight(along);
			for (int side = 0; side < 2; side++) {
				int across = (alongX ? cellMinZ() : cellMinX()) + (side == 0 ? 0 : FOOTPRINT - 1);
				int wx = alongX ? along : across;
				int wz = alongX ? across : along;
				set(level, chunkBox, cursor.set(wx, cableY, wz), cable);
				if (CityLayout.unit(CityLayout.hash(seed, wx, wz, SALT_HANGER)) < HANGER_GONE_CHANCE) {
					continue;
				}
				for (int y = cableY - 1; y > this.groundY + PARAPET_CAP; y--) {
					set(level, chunkBox, cursor.set(wx, y, wz), mat.of(Mat.HANGER));
				}
			}
		}
	}

	/**
	 * Height of the main cable above {@code groundY} at an absolute along-axis coordinate: a parabola
	 * from {@link #PYLON_TOP} at each pylon down to {@code PYLON_TOP - CABLE_SAG} at midspan.
	 */
	static int cableHeight(int along) {
		int span = TOWER_PERIOD * FOOTPRINT;
		int half = span / 2;
		double t = Math.floorMod(along - PYLON_OFFSET, span) - half;
		double unit = t / half;
		return PYLON_TOP - (int) Math.round(CABLE_SAG * (1.0D - unit * unit));
	}

	// ------------------------------------------------------------------ which part

	/**
	 * Which {@code bridge_*} part a cell gets and how far it is turned — the bridge counterpart of
	 * {@code StreetTiles}, and pure for the same reason: it is a function of an {@code int} mask, a
	 * seed and a cell coordinate, so it is a unit test rather than a screenshot.
	 */
	public static final class Spans {

		/** How a whole crossing is built. Constant along one crossing; see {@link #styleFor}. */
		public enum Style {
			/** Pylons every third cell with a cable slung between them. */
			SUSPENSION,
			/** A through truss on every span. */
			TRUSS,
			/** A chain of tied arches, one per cell. */
			ARCH
		}

		/** Part-name suffixes. A family that ships none of these falls back to the family part. */
		public static final String TOWER = "_tower";
		public static final String CABLE = "_cable";
		public static final String TRUSS = "_truss";
		public static final String ARCH = "_arch";
		public static final String RUIN = "_ruin";
		public static final String JUNCTION = "_junction";

		/** A span choice: the part to stamp, its clockwise quarter turns, and the crossing's style. */
		public record Span(String part, int quarterTurns, Style style, boolean tower) {
		}

		private Spans() {
		}

		/**
		 * The span for one bridged cell.
		 *
		 * @param mask   the cell's road-connectivity mask
		 * @param seed   world seed
		 * @param cellX  cell chunk X
		 * @param cellZ  cell chunk Z
		 * @param family the configured part name, used as the family prefix and as the fallback
		 */
		public static Span forCell(int mask, long seed, int cellX, int cellZ, String family) {
			String base = family == null ? "" : family;
			boolean westEast = (mask & (CityLayout.WEST | CityLayout.EAST))
					== (CityLayout.WEST | CityLayout.EAST);
			boolean northSouth = (mask & (CityLayout.NORTH | CityLayout.SOUTH))
					== (CityLayout.NORTH | CityLayout.SOUTH);
			// A crossroads, a bend, a T or a dead end: nothing may be built over the deck, because
			// whatever it is would stand across one of the arms.
			if (westEast == northSouth) {
				return new Span(base + JUNCTION, 0, Style.TRUSS, false);
			}
			int quarterTurns = westEast ? 0 : 1;
			int along = westEast ? cellX : cellZ;
			int across = westEast ? cellZ : cellX;
			Style style = styleFor(seed, quarterTurns, across);
			boolean tower = style == Style.SUSPENSION && Math.floorMod(along, TOWER_PERIOD) == 0;

			if (!tower && CityLayout.unit(CityLayout.hash(seed, cellX, cellZ, SALT_RUIN)) < RUIN_CHANCE) {
				return new Span(base + RUIN, quarterTurns, style, false);
			}
			String suffix = switch (style) {
				case SUSPENSION -> tower ? TOWER : CABLE;
				case TRUSS -> TRUSS;
				case ARCH -> ARCH;
			};
			return new Span(base + suffix, quarterTurns, style, tower);
		}

		/**
		 * Which bridge family this cell is built from — {@code bridge_open}, {@code bridge_covered},
		 * or whatever else the city style names.
		 *
		 * <p><b>Why it is resolved here and not at structure-start.</b> The city style lives in the
		 * datapack, and {@code LostCityStructure} deliberately touches no assets: a structure start can
		 * be computed before the datapack has finished loading, which is why every asset lookup in this
		 * mod is deferred to the piece. So the name handed down from the structure is a fallback, and
		 * the city style's own {@code selectors.bridges} is preferred here, where the assets exist.
		 * Before this the field was parsed by {@code SelectorsRE} and read by nothing, so editing a
		 * city style's bridges did precisely nothing.
		 *
		 * <p><b>Hashed across the axis, not per cell.</b> Every cell of one crossing shares the
		 * coordinate across it, so a whole river crossing is one family. Hashing per cell — which is
		 * what the structure-start fallback used to do — alternated open and covered every sixteen
		 * blocks along a single bridge.
		 */
		public static String familyFor(Assets assets, String cityStyleName, String fallback, int mask,
		                               long seed, int cellX, int cellZ) {
			CityStyleRE cityStyle = assets == null || cityStyleName == null
					? null : assets.getCityStyle(cityStyleName);
			SelectorsRE selectors = cityStyle == null ? null : cityStyle.getSelectors();
			List<ObjectSelector> families = selectors == null || selectors.bridges() == null
					? List.of() : selectors.bridges();
			if (families.isEmpty()) {
				return fallback;
			}
			boolean westEast = (mask & (CityLayout.WEST | CityLayout.EAST))
					== (CityLayout.WEST | CityLayout.EAST);
			int across = westEast ? cellZ : cellX;
			int axis = westEast ? 0 : 1;
			long h = CityLayout.hash(seed, across, axis, SALT_FAMILY);
			return families.get((int) Math.floorMod(h, families.size())).value();
		}

		/**
		 * The structural style of the crossing this cell belongs to.
		 *
		 * <p>Hashed from the axis and the coordinate <em>across</em> it, and from nothing else. Every
		 * cell of one crossing shares that coordinate and no cell of a parallel crossing does, so a
		 * whole river crossing comes out as one coherent bridge while the next one along is allowed to
		 * be a different one. Hashing per cell instead — which is what the part choice upstream does —
		 * gives a crossing that changes its mind every sixteen blocks.
		 */
		public static Style styleFor(long seed, int axis, int across) {
			long h = CityLayout.hash(seed, across, axis, SALT_CROSSING);
			return Style.values()[(int) Math.floorMod(h, Style.values().length)];
		}
	}

	// ------------------------------------------------------------------ materials

	/**
	 * The bridge's materials: a palette character per structural role, resolved through the part's
	 * {@code "meta"} block and then through the compiled palette the part itself is drawn with.
	 *
	 * <p><b>Why through the part.</b> The geometry of a bridge has to be computed (see the class
	 * javadoc), but its <em>materials</em> are exactly the kind of thing a datapack should own, and
	 * until recently a {@code bridge_*} part could not own anything at all — {@code PartPlacer}
	 * ignored a part's local palette, so any character only it defined resolved to air. Now that it
	 * does not, a part can declare {@code {"key": "pier", "char": "N"}} and define {@code N} in its own
	 * palette, and the piers this class builds come out in that material without a single shared
	 * palette file being touched.
	 *
	 * <p>Every role has a default character that the style palettes are known to define, and a hard
	 * {@link BlockState} behind that, so a part with no {@code meta}, a part with a bad character and
	 * the pre-asset-load case all still produce a bridge rather than a hole.
	 */
	static final class Mat {

		record Role(String key, char defaultChar, BlockState fallback) {
		}

		private static final BlockState SLAB_TOP = Blocks.SMOOTH_STONE_SLAB.defaultBlockState()
				.setValue(SlabBlock.TYPE, SlabType.TOP);
		private static final BlockState SLAB_BOTTOM = Blocks.SMOOTH_STONE_SLAB.defaultBlockState()
				.setValue(SlabBlock.TYPE, SlabType.BOTTOM);

		/** The carriageway plate, flush with the streets either side. */
		static final Role DECK = new Role("deck", 'S',
				Blocks.SMOOTH_STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.DOUBLE));
		/** The gutter channel beside the kerb: half a block down, as the street tiles lay it. */
		static final Role GUTTER = new Role("gutter", '_', SLAB_BOTTOM);
		/** The raised footway. */
		static final Role FOOTWAY = new Role("footway", 'x', Blocks.STONE_BRICKS.defaultBlockState());
		/** The kerb: a half slab, as {@code StreetPiece} lays it. */
		static final Role KERB = new Role("kerb", '_', SLAB_BOTTOM);
		static final Role PARAPET = new Role("parapet", 'w', Blocks.STONE_BRICK_WALL.defaultBlockState());
		static final Role CAPSTONE = new Role("capstone", '=', SLAB_BOTTOM);
		static final Role LAMP = new Role("lamp", 'h', Blocks.LANTERN.defaultBlockState());
		/** Edge girders and cross beams under the deck. */
		static final Role GIRDER = new Role("girder", 'v', Blocks.DEEPSLATE_TILES.defaultBlockState());
		static final Role BEAM = new Role("beam", '=', SLAB_TOP);
		static final Role PIER = new Role("pier", '#', Blocks.STONE_BRICKS.defaultBlockState());
		static final Role PIER_CAP = new Role("piercap", 'v', Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
		static final Role FOOTING = new Role("footing", '#', Blocks.COBBLESTONE.defaultBlockState());
		static final Role BRACE = new Role("brace", ':', Blocks.IRON_BARS.defaultBlockState());
		static final Role CABLE_X = new Role("cablex", 'X',
				Blocks.IRON_CHAIN.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
		static final Role CABLE_Z = new Role("cablez", 'Z',
				Blocks.IRON_CHAIN.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.Z));
		static final Role HANGER = new Role("hanger", '{', Blocks.IRON_CHAIN.defaultBlockState());
		static final Role DEBRIS = new Role("debris", '%', Blocks.COBWEB.defaultBlockState());

		private final BuildingPart part;
		private final CompiledPalette palette;
		private final RandomSource rand;

		Mat(BuildingPart part, CompiledPalette palette, RandomSource rand) {
			this.part = part;
			this.palette = palette == null ? null : PartPlacer.paletteFor(part, palette);
			this.rand = rand;
		}

		/**
		 * The block for a role. Re-rolled per call on purpose: a weighted palette entry is what gives a
		 * pier its variegation, and a bridge whose every pier block was decided once would be flat.
		 */
		BlockState of(Role role) {
			char c = role.defaultChar();
			if (this.part != null) {
				Character declared = this.part.getMetaChar(role.key());
				if (declared != null) {
					c = declared;
				}
			}
			if (this.palette != null) {
				BlockState state = this.palette.get(c, this.rand);
				if (state != null && !state.isAir() && !state.is(Blocks.STRUCTURE_VOID)) {
					return state;
				}
			}
			return role.fallback();
		}
	}

	// ------------------------------------------------------------------ plumbing

	private static void set(WorldGenLevel level, BoundingBox chunkBox, BlockPos pos, BlockState state) {
		if (state != null && chunkBox.isInside(pos)) {
			level.setBlock(pos, state, WorldGenFlags.SET_BLOCK);
		}
	}
}
