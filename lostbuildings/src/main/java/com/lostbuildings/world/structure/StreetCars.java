package com.lostbuildings.world.structure;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the abandoned cars are — the traffic that never left the lost city.
 *
 * <p>Pure arithmetic over absolute world coordinates, exactly like {@link StreetDecor}: no world
 * reads, no Minecraft types, no per-chunk random. {@link StreetCarPlacer} turns what this class
 * decides into block writes; everything about <em>which</em> car stands <em>where</em> is decided
 * here and is therefore unit-testable in a plain JVM.
 *
 * <h2>The cell-boundary problem, and how it is solved</h2>
 *
 * <p>A car is 3–6 blocks long; a street cell is 16×16 and a {@code StreetPiece} may only write
 * inside its own chunk. Two designs were available.
 *
 * <ol>
 *   <li><b>Keep every car strictly inside one cell's interior.</b> Simple, and nothing is ever
 *       clipped — but it forbids cars anywhere near a cell edge, which on this grid is exactly the
 *       eight-block band that straddles every junction approach. Streets would grow a visible
 *       16-block rhythm of "cars here, never there", and the most interesting places for a wreck
 *       (junction mouths, seams between two straights) would be permanently empty.</li>
 *   <li><b>Derive the car's identity and position from absolute world coordinates</b>, so that two
 *       neighbouring cells independently compute the <em>same</em> car and each writes the part of
 *       it that falls inside its own chunk. This is what {@code StreetDecor} already does for lamp
 *       spacing, and it is what this class does.</li>
 * </ol>
 *
 * <p>Option 2 is taken. The cost is that <em>every</em> input to a car's existence must be something
 * both cells can compute:
 *
 * <ul>
 *   <li><b>Identity.</b> Cars live on a lattice of <em>lanes</em> and <em>slots</em> keyed by
 *       absolute coordinates. A lane is a world row (or column) whose coordinate is {@code 4}, {@code 6},
 *       {@code 8} or {@code 10} modulo 16 — the four two-wide strips of the eight-wide carriageway,
 *       which are at those offsets in <em>every</em> cell because cells are chunk-aligned. A slot is
 *       {@code floorDiv(coordinate, SLOT)} with {@link #SLOT} deliberately <em>coprime with 16</em>,
 *       so the lattice does not line up with the cell grid and cars straddle seams as often as they
 *       sit in the middle of a cell. Occupancy, model, colour, decay, direction and the car's offset
 *       inside its slot all come from {@code CityLayout.hash(seed, lane, slot, salt)} — the lane and
 *       the slot are absolute, so the answer is the same in both cells.</li>
 *   <li><b>Is the ground under it road?</b> The obvious test — {@code StreetDecor.isRoad} on the
 *       owning cell's mask — only answers for the 16 columns of that cell. {@link #isRoadNear}
 *       extends it up to {@link #REACH} columns <em>outside</em> the cell, using nothing but the
 *       owning cell's own mask, and it is provably the same answer the neighbour computes from
 *       <em>its</em> mask: a road arm reaching a cell edge implies the neighbour is a street cell
 *       (that is what the mask bit means), a street cell always has the 8×8 junction box, and the
 *       neighbour's reciprocal arm exists because this cell is itself a street cell. See the javadoc
 *       on {@link #isRoadNear} for the case analysis.</li>
 *   <li><b>Do two cars collide?</b> Two cars on the same axis can never overlap (lanes are two apart
 *       and a car is two wide; a car never leaves its own slot). Two cars on <em>different</em> axes
 *       can only overlap inside a junction box, because an X-axis car always has {@code z ≡ 4..11}
 *       and a Z-axis car always has {@code x ≡ 4..11}. So one coin flip per junction —
 *       {@link #junctionAxis}, a pure function of the seed and the cell's chunk coordinate — decides
 *       which axis may enter it, and cross-axis overlap becomes impossible without any pairwise
 *       search and without either cell needing to know the other's mask.</li>
 * </ul>
 *
 * <p>The residual cost of option 2 is that a straddling car is computed twice and written twice,
 * once per cell, each write clipped to its own chunk. The writes are identical, so that is free.
 *
 * <h2>What is deliberately avoided</h2>
 *
 * <p><b>The kerb ring</b> — {@code StreetPiece.decorate} overwrites it after the tile is stamped, so
 * anything standing on it is destroyed. Cars are required to stand on roadway columns only, and
 * {@code StreetDecor.isKerb} is by definition disjoint from {@code StreetDecor.isRoad}, so no car
 * block can ever land on a kerb. <b>Lamp posts</b> stand on the kerb ring, so the same argument
 * covers them. <b>Potholes</b> need no avoidance at all: {@code decorate} replaces the road surface
 * with a cracked-brick rim and hollows out the block <em>below</em> it, so the carriageway a car
 * stands on stays solid — a potholed column under a car is simply hidden by the car.
 *
 * <h2>How much traffic</h2>
 *
 * <p>Kerbside lanes (the strips at offsets 4 and 10, which touch the kerb) carry most of the cars;
 * the two middle lanes are rare, so the carriageway almost always has a walkable four-block channel
 * down the middle. Cars are placed <em>along</em> the road by construction — a car's axis is its
 * lane's axis — and a car pointing across the traffic is only allowed when the road does not in fact
 * continue along its axis, at {@link #CRASH_CHANCE}: that is the deliberate crash, and it is rare.
 */
public final class StreetCars {

	/** First and last column of the roadway inside a cell — the same 8 columns {@link StreetDecor} uses. */
	private static final int ROAD_MIN = 4;
	private static final int ROAD_MAX = 11;

	/** Cell footprint. Cells are chunk-aligned, which is why lane offsets are the same in every cell. */
	private static final int CELL = 16;

	/**
	 * Cell-relative offsets of the four two-wide traffic lanes inside the eight-wide carriageway.
	 * A lane at 4 touches the kerb at 3, a lane at 10 touches the kerb at 12; 6 and 8 are the middle.
	 */
	static final int[] LANE_OFFSETS = {4, 6, 8, 10};

	/**
	 * Length of one parking slot along the lane, in blocks.
	 *
	 * <p><b>Coprime with 16 on purpose.</b> A slot length that divided the cell size would make every
	 * slot boundary a cell boundary, no car would ever straddle a seam, and the whole world-aligned
	 * design would be untested in practice while the streets grew a visible 16-block rhythm.
	 */
	static final int SLOT = 7;

	/** Longest model, and therefore the furthest a car can reach out of the cell that enumerates it. */
	static final int MAX_LENGTH = 6;

	/**
	 * How far past each end of a car the roadway must continue for the car to count as lying
	 * <em>along</em> the road rather than across it. See {@link #alignedWithRoad}.
	 */
	static final int ALIGNMENT_MARGIN = 3;

	/**
	 * How far outside a cell {@link #isRoadNear} can answer for. One cell's arm (4) plus its junction
	 * box (4): eight columns — which is exactly {@link #MAX_LENGTH} plus {@link #ALIGNMENT_MARGIN}
	 * minus one, i.e. the furthest a car enumerated by this cell can ever probe.
	 */
	static final int REACH = 8;

	/** Chance a kerbside slot holds a car, at density 1. */
	private static final double KERBSIDE_CHANCE = 0.26D;
	/** Chance a middle-lane slot holds a car — rare, so the carriageway stays walkable. */
	private static final double MIDLANE_CHANCE = 0.05D;
	/** Chance a run of {@link #JAM_RUN} slots in one lane is a nose-to-tail jam. */
	private static final double JAM_CHANCE = 0.10D;
	/** Slots per jam. */
	private static final int JAM_RUN = 4;
	/** Occupancy inside a kerbside jam — a queue of cars that never got moving again. */
	private static final double JAM_OCCUPANCY = 0.85D;
	/**
	 * Occupancy inside a middle-lane jam. Deliberately a fraction of {@link #JAM_OCCUPANCY}: a jam
	 * that filled the middle lanes as eagerly as the kerbs would wall the street off, and it would
	 * also drown out the kerbside-parking bias that makes the traffic read as parked rather than as a
	 * car park.
	 */
	private static final double MIDLANE_JAM_OCCUPANCY = 0.22D;
	/** Chance a car that is <em>not</em> aligned with the road it stands on is kept — a crash. */
	private static final double CRASH_CHANCE = 0.25D;

	/** The traffic density a caller gets if it has nothing better to pass. */
	public static final double DEFAULT_DENSITY = 1.0D;

	private static final int SALT_OCCUPY_X = 0xC1;
	private static final int SALT_OCCUPY_Z = 0xC2;
	private static final int SALT_MODEL = 0xC3;
	private static final int SALT_OFFSET = 0xC4;
	private static final int SALT_COLOUR = 0xC5;
	private static final int SALT_DECAY = 0xC6;
	private static final int SALT_FACING = 0xC7;
	private static final int SALT_JAM = 0xC8;
	private static final int SALT_CRASH = 0xC9;
	private static final int SALT_JUNCTION = 0xCA;

	private StreetCars() {
	}

	/** Which way a car points. A car's axis is always its lane's axis. */
	public enum Axis {
		/** Travelling east–west: the car is long in X and two wide in Z. */
		X,
		/** Travelling north–south: the car is long in Z and two wide in X. */
		Z
	}

	/** Bodywork colour. {@link StreetCarPlacer} maps these onto concrete, terracotta and stairs. */
	public enum Colour {
		WHITE, LIGHT_GRAY, GRAY, RED, ORANGE, YELLOW, LIME, GREEN, CYAN, BLUE, BROWN, BLACK
	}

	// --- decay flags -------------------------------------------------------------------------

	/** Bodywork has gone to rust: dull terracotta instead of clean concrete. */
	public static final int RUSTED = 1;
	/** The glazing is gone. */
	public static final int SMASHED = 2;
	/** One end has lost its wheels and sits on the road. */
	public static final int WHEELLESS = 4;
	/** Cobwebs where the glass used to be. */
	public static final int COBWEBBED = 8;
	/** A door hangs open. */
	public static final int DOOR_OPEN = 16;

	// --- stencil characters ------------------------------------------------------------------

	/** Bodywork. */
	public static final char BODY = '#';
	/** Roof / bumper trim — a shade off the bodywork. */
	public static final char TRIM = '%';
	/** Bonnet: a stair sloping up towards the cabin. */
	public static final char BONNET = '^';
	/** Boot: a stair sloping up towards the cabin from the other end. */
	public static final char BOOT = 'v';
	/** Glazing. */
	public static final char GLASS = '=';
	/** Wheel. */
	public static final char WHEEL = 'o';
	/** Radiator grille. */
	public static final char GRILLE = '+';
	/** A crushed panel lying flat — half a block high. */
	public static final char CRUSHED = '~';
	/** A door: bodywork when shut, a trapdoor hanging out when {@link #DOOR_OPEN}. */
	public static final char DOOR = 'd';
	/** Nothing here — leave the world alone. */
	public static final char NOTHING = '.';

	/**
	 * The models, drawn as stencils.
	 *
	 * <p>Local coordinates are {@code (h, u, v)}: {@code h} is height above the carriageway,
	 * {@code u} runs from the nose ({@code 0}) to the tail, {@code v} across the car ({@code 0..1}).
	 * Each string is one {@code u} row of two {@code v} characters, and each array of strings is one
	 * height layer, bottom first — the same "slices bottom to top" convention the part files use.
	 */
	public enum Model {

		/** The default car: bonnet, glazed cabin, boot, a wheel at each end. */
		SALOON(new String[][]{
				{"oo", "##", "##", "oo"},
				{"^^", "==", "%d", "vv"}
		}, 6),

		/** A short two-box car; the same silhouette one block shorter. */
		HATCHBACK(new String[][]{
				{"oo", "##", "oo"},
				{"^^", "==", "%%"}
		}, 5),

		/** Flat-fronted delivery van: grille instead of a bonnet, a tall box behind the cab. */
		VAN(new String[][]{
				{"oo", "##", "##", "oo"},
				{"++", "==", "#d", "##"},
				{"..", "%%", "%%", "%%"}
		}, 4),

		/** Six long and three tall, glazed down both sides — unmistakable at a glance. */
		BUS(new String[][]{
				{"oo", "##", "##", "##", "##", "oo"},
				{"==", "#d", "==", "==", "#d", "=="},
				{"%%", "%%", "%%", "%%", "%%", "%%"}
		}, 2),

		/** Crumpled: one corner folded in, half the roof caved, a wheel torn off. */
		WRECK(new String[][]{
				{"oo", "##", "#o"},
				{"^~", "=.", "%."}
		}, 3),

		/** A burnt-out shell: no glazing anywhere, the cabin open to the sky. */
		BURNT(new String[][]{
				{"oo", "##", "##", "o."},
				{"^^", "..", "%.", "v~"}
		}, 3);

		private final String[][] stencil;
		private final int weight;

		Model(String[][] stencil, int weight) {
			this.stencil = stencil;
			this.weight = weight;
		}

		/** Blocks from nose to tail. */
		public int length() {
			return this.stencil[0].length;
		}

		/** Blocks across — always two, so four lanes fit the eight-wide carriageway exactly. */
		public int width() {
			return 2;
		}

		/** Blocks tall, standing on the carriageway. */
		public int height() {
			return this.stencil.length;
		}

		/** How often this model is picked relative to the others. */
		public int weight() {
			return this.weight;
		}

		/** The stencil character at a local cell; {@link #NOTHING} means "write nothing". */
		public char at(int h, int u, int v) {
			if (h < 0 || h >= height() || u < 0 || u >= length() || v < 0 || v >= width()) {
				return NOTHING;
			}
			return this.stencil[h][u].charAt(v);
		}

		/** A burnt-out shell is painted in blackened stone rather than in bodywork colour. */
		public boolean burntOut() {
			return this == BURNT;
		}
	}

	/**
	 * One car, positioned in absolute world coordinates.
	 *
	 * @param model   which vehicle
	 * @param axis    the road axis it lies along
	 * @param forward whether local {@code u} (nose to tail) increases with the world coordinate; when
	 *                {@code true} the nose is at the low end of the footprint
	 * @param minX    world X of the footprint's north-west corner
	 * @param minZ    world Z of the footprint's north-west corner
	 * @param colour  bodywork colour
	 * @param decay   OR of {@link #RUSTED}, {@link #SMASHED}, {@link #WHEELLESS}, {@link #COBWEBBED}
	 *                and {@link #DOOR_OPEN}
	 */
	public record Car(Model model, Axis axis, boolean forward, int minX, int minZ, Colour colour, int decay) {

		/** Footprint size along world X. */
		public int sizeX() {
			return this.axis == Axis.X ? this.model.length() : this.model.width();
		}

		/** Footprint size along world Z. */
		public int sizeZ() {
			return this.axis == Axis.Z ? this.model.length() : this.model.width();
		}

		/** Whether the column {@code (x, z)} is under this car. */
		public boolean covers(int x, int z) {
			return x >= this.minX && x < this.minX + sizeX() && z >= this.minZ && z < this.minZ + sizeZ();
		}

		/** Whether a decay flag is set. */
		public boolean has(int flag) {
			return (this.decay & flag) != 0;
		}

		/**
		 * Local {@code u} — distance from the nose — of the column {@code (x, z)}, or {@code -1} when
		 * the column is not part of this car.
		 */
		public int noseOffset(int x, int z) {
			if (!covers(x, z)) {
				return -1;
			}
			int along = this.axis == Axis.X ? x - this.minX : z - this.minZ;
			return this.forward ? along : this.model.length() - 1 - along;
		}

		/** Local {@code v} — which side of the car the column is on, {@code 0} or {@code 1}. */
		public int sideOffset(int x, int z) {
			return this.axis == Axis.X ? z - this.minZ : x - this.minX;
		}

		/**
		 * The stencil character standing on the column {@code (x, z)} at height {@code h} above the
		 * carriageway, or {@link #NOTHING} when the column is not part of this car.
		 */
		public char at(int x, int z, int h) {
			int u = noseOffset(x, z);
			return u < 0 ? NOTHING : this.model.at(h, u, sideOffset(x, z));
		}
	}

	/**
	 * Every car whose footprint overlaps the street cell whose north-west corner is
	 * {@code (cellMinX, cellMinZ)}.
	 *
	 * <p>Cars that straddle the cell edge <em>are</em> returned — that is the point. The caller writes
	 * only the blocks that fall inside its own chunk, and the neighbouring cell returns the same car
	 * from the same lattice and writes the rest.
	 *
	 * @param seed      the world seed; the only stateful input
	 * @param cellMinX  world X of the cell's north-west corner (a multiple of 16)
	 * @param cellMinZ  world Z of the cell's north-west corner (a multiple of 16)
	 * @param mask      the cell's road-connectivity mask, as {@code StreetPiece.neighbourMask()}
	 * @param density   scales how many slots are occupied; {@code <= 0} means no cars at all
	 * @return the cars, in a stable order
	 */
	public static List<Car> carsIn(long seed, int cellMinX, int cellMinZ, int mask, double density) {
		List<Car> cars = new ArrayList<>();
		if (density <= 0.0D) {
			return cars;
		}
		for (int offset : LANE_OFFSETS) {
			collect(cars, seed, Axis.X, cellMinZ + offset, cellMinX, cellMinX, cellMinZ, mask, density, offset);
			collect(cars, seed, Axis.Z, cellMinX + offset, cellMinZ, cellMinX, cellMinZ, mask, density, offset);
		}
		return cars;
	}

	/**
	 * All slots of one lane that could reach into this cell, filtered down to the cars that stand.
	 *
	 * <p>The sweep starts deliberately wider than it strictly has to. A car never leaves its own
	 * slot, so the first slot that can touch the cell is the one the cell's own first column falls
	 * in; starting a couple of slots earlier costs two hashes per lane and survives someone later
	 * letting a car overhang its slot.
	 */
	private static void collect(List<Car> cars, long seed, Axis axis, int lane, int alongMin,
	                            int cellMinX, int cellMinZ, int mask, double density, int laneOffset) {
		int first = Math.floorDiv(alongMin - MAX_LENGTH - SLOT, SLOT);
		int last = Math.floorDiv(alongMin + CELL - 1, SLOT);
		for (int slot = first; slot <= last; slot++) {
			Car car = candidate(seed, axis, lane, slot, density, laneOffset);
			if (car == null || !overlapsCell(car, cellMinX, cellMinZ)) {
				continue;
			}
			if (stands(seed, car, cellMinX, cellMinZ, mask)) {
				cars.add(car);
			}
		}
	}

	/**
	 * The car this lane/slot would hold, before any check against the road under it.
	 *
	 * <p>Purely a function of the seed and the absolute lane and slot, which is what makes two
	 * neighbouring cells agree about a car that straddles their boundary.
	 */
	static Car candidate(long seed, Axis axis, int lane, int slot, double density, int laneOffset) {
		int occupySalt = axis == Axis.X ? SALT_OCCUPY_X : SALT_OCCUPY_Z;
		boolean jam = CityLayout.unit(CityLayout.hash(seed, lane, Math.floorDiv(slot, JAM_RUN), SALT_JAM))
				< JAM_CHANCE;
		boolean kerbside = laneOffset == LANE_OFFSETS[0] || laneOffset == LANE_OFFSETS[LANE_OFFSETS.length - 1];
		double base = kerbside
				? (jam ? JAM_OCCUPANCY : KERBSIDE_CHANCE)
				: (jam ? MIDLANE_JAM_OCCUPANCY : MIDLANE_CHANCE);
		double chance = base * density;
		if (CityLayout.unit(CityLayout.hash(seed, lane, slot, occupySalt)) >= chance) {
			return null;
		}
		Model model = pickModel(seed, lane, slot);
		// In a jam the cars bunch at the head of their slots, which is what makes them read as a
		// queue rather than as evenly spaced parking.
		int room = SLOT - model.length() + 1;
		int offset = jam ? 0 : (int) Math.floorMod(CityLayout.hash(seed, lane, slot, SALT_OFFSET), room);
		int start = slot * SLOT + offset;
		Colour colour = Colour.values()[(int) Math.floorMod(CityLayout.hash(seed, lane, slot, SALT_COLOUR),
				Colour.values().length)];
		boolean forward = (CityLayout.hash(seed, lane, slot, SALT_FACING) & 1L) == 0L;
		int decay = decayFor(seed, lane, slot, model);
		return axis == Axis.X
				? new Car(model, axis, forward, start, lane, colour, decay)
				: new Car(model, axis, forward, lane, start, colour, decay);
	}

	private static Model pickModel(long seed, int lane, int slot) {
		int total = 0;
		for (Model model : Model.values()) {
			total += model.weight();
		}
		int roll = (int) Math.floorMod(CityLayout.hash(seed, lane, slot, SALT_MODEL), total);
		for (Model model : Model.values()) {
			roll -= model.weight();
			if (roll < 0) {
				return model;
			}
		}
		return Model.SALOON;
	}

	/** Rust, broken glass, missing wheels and cobwebs, all off one hash so a car never changes. */
	private static int decayFor(long seed, int lane, int slot, Model model) {
		long h = CityLayout.hash(seed, lane, slot, SALT_DECAY);
		int decay = 0;
		if (CityLayout.unit(h) < 0.55D) {
			decay |= RUSTED;
		}
		if ((h & 0x2L) != 0L) {
			decay |= SMASHED;
		}
		if ((h & 0x4L) != 0L && (h & 0x8L) != 0L) {
			decay |= WHEELLESS;
		}
		if ((h & 0x10L) != 0L && (h & 0x20L) != 0L) {
			decay |= COBWEBBED;
		}
		if ((h & 0x40L) != 0L) {
			decay |= DOOR_OPEN;
		}
		if (model.burntOut()) {
			// A shell that burned out kept nothing: the glass is gone and so are the tyres.
			decay |= SMASHED | WHEELLESS;
		}
		return decay;
	}

	/** Whether any of the car's footprint falls inside this cell. */
	static boolean overlapsCell(Car car, int cellMinX, int cellMinZ) {
		return car.minX() < cellMinX + CELL && car.minX() + car.sizeX() > cellMinX
				&& car.minZ() < cellMinZ + CELL && car.minZ() + car.sizeZ() > cellMinZ;
	}

	/**
	 * Whether a candidate car really stands: on roadway all the way, not fighting another axis for a
	 * junction, and either lying along its road or being a rare deliberate crash.
	 *
	 * <p>Every input is either absolute or provably the same on both sides of a cell boundary, so a
	 * straddling car is accepted by both cells or by neither. That is the whole correctness argument
	 * for this file.
	 */
	static boolean stands(long seed, Car car, int cellMinX, int cellMinZ, int mask) {
		for (int x = car.minX(); x < car.minX() + car.sizeX(); x++) {
			for (int z = car.minZ(); z < car.minZ() + car.sizeZ(); z++) {
				if (!isRoadNear(x - cellMinX, z - cellMinZ, mask)) {
					return false;
				}
			}
		}
		if (!ownsJunctions(seed, car)) {
			return false;
		}
		if (alignedWithRoad(car, cellMinX, cellMinZ, mask)) {
			return true;
		}
		int lane = car.axis() == Axis.X ? car.minZ() : car.minX();
		int along = car.axis() == Axis.X ? car.minX() : car.minZ();
		return CityLayout.unit(CityLayout.hash(seed, lane, Math.floorDiv(along, SLOT), SALT_CRASH)) < CRASH_CHANCE;
	}

	/**
	 * Whether the roadway continues along the car's own axis, well past both ends of it.
	 *
	 * <p>A car that fails this is lying across the traffic and is only kept as a deliberate crash.
	 *
	 * <p><b>Why the probes are {@link #ALIGNMENT_MARGIN} blocks out and not one.</b> The carriageway
	 * is eight blocks wide, so a three-block car parked <em>across</em> a street still has roadway
	 * immediately in front of its nose and behind its tail — probing one block out would call it
	 * aligned and the deliberate-crash gate would never fire. Three is the smallest margin that
	 * cannot be satisfied inside an eight-wide band by a car at least three long: it needs
	 * {@code z0 >= 4 + 3} at one end and {@code z0 + length <= 11 - 2} at the other, which has no
	 * solution. It is also exactly what {@link #REACH} affords —
	 * {@code REACH == MAX_LENGTH + ALIGNMENT_MARGIN - 1} — so both probes of a straddling car stay
	 * inside the range where the two cells provably agree.
	 */
	static boolean alignedWithRoad(Car car, int cellMinX, int cellMinZ, int mask) {
		if (car.axis() == Axis.X) {
			int dz = car.minZ() - cellMinZ;
			int dx = car.minX() - cellMinX;
			return isRoadNear(dx - ALIGNMENT_MARGIN, dz, mask)
					&& isRoadNear(dx + car.sizeX() - 1 + ALIGNMENT_MARGIN, dz, mask);
		}
		int dx = car.minX() - cellMinX;
		int dz = car.minZ() - cellMinZ;
		return isRoadNear(dx, dz - ALIGNMENT_MARGIN, mask)
				&& isRoadNear(dx, dz + car.sizeZ() - 1 + ALIGNMENT_MARGIN, mask);
	}

	/**
	 * Whether the car is allowed into every junction box its footprint enters.
	 *
	 * <p>Two cars on the same axis can never share a column, and two cars on different axes can only
	 * ever meet inside a junction box — an X-axis car always lies on rows {@code 4..11} of its cell
	 * and a Z-axis car always on columns {@code 4..11} of its own, so an overlap needs both. Giving
	 * each junction a single owning axis therefore removes cross-axis collisions outright, for one
	 * hash of the junction's chunk coordinate and no knowledge of anyone's mask.
	 */
	static boolean ownsJunctions(long seed, Car car) {
		int firstChunkX = Math.floorDiv(car.minX(), CELL);
		int lastChunkX = Math.floorDiv(car.minX() + car.sizeX() - 1, CELL);
		int firstChunkZ = Math.floorDiv(car.minZ(), CELL);
		int lastChunkZ = Math.floorDiv(car.minZ() + car.sizeZ() - 1, CELL);
		for (int cx = firstChunkX; cx <= lastChunkX; cx++) {
			for (int cz = firstChunkZ; cz <= lastChunkZ; cz++) {
				if (entersJunction(car, cx, cz) && junctionAxis(seed, cx, cz) != car.axis()) {
					return false;
				}
			}
		}
		return true;
	}

	/** Whether the car's footprint reaches into the 8×8 junction box of the cell {@code (cx, cz)}. */
	static boolean entersJunction(Car car, int cellChunkX, int cellChunkZ) {
		int jx = cellChunkX * CELL + ROAD_MIN;
		int jz = cellChunkZ * CELL + ROAD_MIN;
		int span = ROAD_MAX - ROAD_MIN + 1;
		return car.minX() < jx + span && car.minX() + car.sizeX() > jx
				&& car.minZ() < jz + span && car.minZ() + car.sizeZ() > jz;
	}

	/**
	 * Which traffic axis owns a cell's junction box. Absolute chunk coordinates only, so every cell
	 * that can see the junction agrees.
	 */
	public static Axis junctionAxis(long seed, int cellChunkX, int cellChunkZ) {
		return (CityLayout.hash(seed, cellChunkX, cellChunkZ, SALT_JUNCTION) & 1L) == 0L ? Axis.X : Axis.Z;
	}

	/**
	 * {@link StreetDecor#isRoad} extended up to {@link #REACH} columns beyond the cell, answered from
	 * the cell's own mask.
	 *
	 * <p><b>Why this is not a guess.</b> Take a column {@code REACH} or fewer to the west of the cell,
	 * on a row the carriageway occupies ({@code 4..11}).
	 *
	 * <ul>
	 *   <li>If the cell's {@code WEST} bit is clear there is no road that way and — since the bit is
	 *       exactly "the western neighbour is also a street cell" — there is no street piece there to
	 *       write anything either. {@code false} is right.</li>
	 *   <li>If it is set, the western neighbour is a street cell. Columns {@code 8..11} of that
	 *       neighbour are its junction box, which <em>every</em> street cell has. Columns {@code 12..15}
	 *       are its eastern arm, which exists exactly when its {@code EAST} bit is set — and that bit
	 *       is set, because this cell is a street cell and neighbour masks are reciprocal. So all eight
	 *       columns are roadway, whatever else the neighbour's mask says.</li>
	 * </ul>
	 *
	 * <p>The neighbour, asked about the same world column through <em>its</em> mask, reaches the same
	 * answer by the mirror image of that argument. A diagonal column — outside the cell on both axes —
	 * is never roadway and returns {@code false}.
	 *
	 * @param dx   column X relative to the cell's north-west corner, {@code -REACH .. 15 + REACH}
	 * @param dz   column Z relative to the cell's north-west corner, {@code -REACH .. 15 + REACH}
	 * @param mask the cell's road-connectivity mask
	 */
	public static boolean isRoadNear(int dx, int dz, int mask) {
		boolean insideX = dx >= 0 && dx < CELL;
		boolean insideZ = dz >= 0 && dz < CELL;
		if (insideX && insideZ) {
			return StreetDecor.isRoad(dx, dz, mask);
		}
		if (insideX) {
			if (dx < ROAD_MIN || dx > ROAD_MAX) {
				return false;   // outside the through band: the neighbour has pavement here
			}
			if (dz < 0) {
				return dz >= -REACH && (mask & CityLayout.NORTH) != 0;
			}
			return dz < CELL + REACH && (mask & CityLayout.SOUTH) != 0;
		}
		if (insideZ) {
			if (dz < ROAD_MIN || dz > ROAD_MAX) {
				return false;
			}
			if (dx < 0) {
				return dx >= -REACH && (mask & CityLayout.WEST) != 0;
			}
			return dx < CELL + REACH && (mask & CityLayout.EAST) != 0;
		}
		return false;   // diagonally outside: always the neighbour's pavement corner
	}
}
