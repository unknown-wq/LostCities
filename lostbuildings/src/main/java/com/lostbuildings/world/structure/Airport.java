package com.lostbuildings.world.structure;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the city's airfield is — a <em>pure function</em> of the {@link CityLayout.Plan} and the
 * world seed, with no Minecraft in sight, exactly like {@link StreetDecor} and {@link StreetCars}.
 * {@code AirportPiece} turns what this class decides into block writes; everything about
 * <em>where</em> the airport is and <em>which way round</em> it lies is decided here and is
 * therefore unit-testable in a plain JVM.
 *
 * <h2>One airport per city, on the outskirts</h2>
 *
 * <p>A city gets exactly one airfield and it is always on the edge, because that is where an airport
 * goes and because a runway through the middle of a downtown would have to demolish the downtown.
 *
 * <p><b>"Edge" is defined on the plan, not on the config.</b> The city is a square of cells centred
 * on {@code (originChunkX, originChunkZ)}; a cell's <em>ring</em> is its Chebyshev distance from that
 * centre, {@code max(|dx|, |dz|)}, and the edge is the outermost ring the plan actually reaches —
 * {@link #outerRing(CityLayout.Plan)}, a max over the emitted cells rather than a re-derivation of
 * {@code citySize}. Reading it off the plan means the airport cannot drift out of the city if the
 * layout ever stops emitting a full square, and it keeps this class dependent on nothing but its
 * argument.
 *
 * <p>The airfield takes {@link #LENGTH} cells of that ring in a row, so the runway is 48 blocks long
 * rather than the 16 a single cell would allow. Which ring side and how far along it are two hashes
 * of the city's own origin chunk, so a city always rebuilds its airport in the same place, and two
 * cities never agree by construction.
 *
 * <h2>Why a strip of cells and not one</h2>
 *
 * <p>{@code StreetCars} had to derive everything from absolute world coordinates because a car may
 * straddle a cell boundary that neither cell knows the other's contents of. This has no such problem:
 * the whole strip is decided <em>here</em>, at plan time, by code that can see the entire city, and
 * each cell is then handed its own 16×16 part. A piece still writes only inside its own chunk — it
 * simply already knows which of the three segments it is. So the cross-cell cost is one extra field
 * in the NBT, not a coordinate-derivation scheme.
 *
 * <p>The three parts are laid in strip order, {@code a b c}: the two ends carry the threshold
 * markings and the middle carries the terminal. Which physical end is {@code a} does not matter —
 * both ends are thresholds — which is what makes the rotation below free.
 *
 * <h2>Rotation</h2>
 *
 * <p>The parts are drawn once, with the runway along {@code +x} and the terminal on the low-z side.
 * The quarter turn is chosen per edge so that the runway always runs <em>parallel</em> to the city
 * edge and the terminal always ends up on the side of the runway that faces the city — its back wall
 * against the outermost city cell, its glazed front looking out over the tarmac.
 */
public final class Airport {

	/** Cells the airfield occupies, in a row: 3 × 16 = a 48-block runway. */
	public static final int LENGTH = 3;

	/** The parts laid along the strip, in order. Ends are thresholds, the middle has the terminal. */
	public static final String PART_THRESHOLD_START = "airport_runway_a";
	public static final String PART_TERMINAL = "airport_runway_b";
	public static final String PART_THRESHOLD_END = "airport_runway_c";

	private static final String[] PARTS = {PART_THRESHOLD_START, PART_TERMINAL, PART_THRESHOLD_END};

	/** Salts, so the two rolls stay independent of each other and of every layout roll. */
	private static final int SALT_SIDE = 0x4A17;
	private static final int SALT_ALONG = 0x4B02;

	/** Which edge of the city square the airfield lies on. */
	public enum Side {
		/** Smallest Z; the runway runs along X. */
		NORTH(2),
		/** Largest X; the runway runs along Z. */
		EAST(3),
		/** Largest Z; the runway runs along X. */
		SOUTH(0),
		/** Smallest X; the runway runs along Z. */
		WEST(1);

		/**
		 * Clockwise quarter turns applied to every part on this side, indexing
		 * {@code engine.Transform.values()}.
		 *
		 * <p>Chosen so the part-local low-z strip — the terminal — lands on the side of the cell that
		 * faces the city centre: {@code ROTATE_180} maps part z to {@code 15 - z} (north edge, city to
		 * the south), {@code ROTATE_NONE} leaves it (south edge), {@code ROTATE_90} maps part z to
		 * world {@code 15 - z} on the X axis (west edge, city to the east) and {@code ROTATE_270} maps
		 * it to world x (east edge).
		 */
		private final int quarterTurns;

		Side(int quarterTurns) {
			this.quarterTurns = quarterTurns;
		}

		public int quarterTurns() {
			return this.quarterTurns;
		}

		/** Whether the runway runs along the X axis on this side. */
		public boolean runwayAlongX() {
			return this == NORTH || this == SOUTH;
		}
	}

	/**
	 * One cell of the airfield.
	 *
	 * @param chunkX       absolute chunk X of the cell
	 * @param chunkZ       absolute chunk Z of the cell
	 * @param partName     the {@code airport_*} part stamped on it
	 * @param quarterTurns clockwise quarter turns, indexing {@code engine.Transform.values()}
	 */
	public record Segment(int chunkX, int chunkZ, String partName, int quarterTurns) {
	}

	/** An empty airfield — a city too small to hold one. */
	private static final Airport NONE = new Airport(null, List.of());

	private final Side side;
	private final List<Segment> segments;

	private Airport(Side side, List<Segment> segments) {
		this.side = side;
		this.segments = segments;
	}

	/**
	 * The one airfield of the city this plan describes.
	 *
	 * <p>Pure: the same plan and seed always give the same answer, on any JVM and in any order.
	 */
	public static Airport forPlan(CityLayout.Plan plan, long seed) {
		if (plan == null || plan.isEmpty()) {
			return NONE;
		}
		int ring = outerRing(plan);
		if (ring < 1) {
			return NONE;    // a one-cell city: there is no edge to put an airfield on
		}
		int originX = plan.originChunkX();
		int originZ = plan.originChunkZ();

		Side side = Side.values()[(int) Math.floorMod(CityLayout.hash(seed, originX, originZ, SALT_SIDE),
				Side.values().length)];
		// How far along the edge the strip's middle cell sits. Kept one cell in from each corner so
		// the whole strip stays on the ring it was chosen from instead of turning the corner.
		int span = 2 * ring - 1;
		int along = -(ring - 1)
				+ (int) Math.floorMod(CityLayout.hash(seed, originX, originZ, SALT_ALONG), span);

		List<Segment> segments = new ArrayList<>(LENGTH);
		for (int i = 0; i < LENGTH; i++) {
			int offset = along + i - LENGTH / 2;
			int chunkX = switch (side) {
				case NORTH, SOUTH -> originX + offset;
				case EAST -> originX + ring;
				case WEST -> originX - ring;
			};
			int chunkZ = switch (side) {
				case EAST, WEST -> originZ + offset;
				case NORTH -> originZ - ring;
				case SOUTH -> originZ + ring;
			};
			segments.add(new Segment(chunkX, chunkZ, PARTS[i], side.quarterTurns()));
		}
		return new Airport(side, List.copyOf(segments));
	}

	/**
	 * The outermost ring the plan reaches — the Chebyshev radius of the city in cells.
	 *
	 * <p>A cell is an <em>edge</em> cell exactly when its ring equals this.
	 */
	public static int outerRing(CityLayout.Plan plan) {
		int ring = 0;
		for (CityLayout.Cell cell : plan.cells()) {
			ring = Math.max(ring, Math.max(Math.abs(cell.chunkX() - plan.originChunkX()),
					Math.abs(cell.chunkZ() - plan.originChunkZ())));
		}
		return ring;
	}

	/** The cells the airfield occupies, in strip order. Empty when the city is too small for one. */
	public List<Segment> segments() {
		return this.segments;
	}

	/** Which edge the airfield is on, or {@code null} when there is none. */
	public Side side() {
		return this.side;
	}

	/** Whether this city has an airfield at all. */
	public boolean exists() {
		return !this.segments.isEmpty();
	}

	/**
	 * Whether the airfield has taken this cell over. The caller skips such cells: the airfield
	 * replaces whatever the layout put on the edge rather than being built on top of it.
	 */
	public boolean claims(CityLayout.Cell cell) {
		return cell != null && claims(cell.chunkX(), cell.chunkZ());
	}

	/** {@link #claims(CityLayout.Cell)} by coordinate. */
	public boolean claims(int chunkX, int chunkZ) {
		for (Segment segment : this.segments) {
			if (segment.chunkX() == chunkX && segment.chunkZ() == chunkZ) {
				return true;
			}
		}
		return false;
	}
}
