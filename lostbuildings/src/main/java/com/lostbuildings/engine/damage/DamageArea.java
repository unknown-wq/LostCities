package com.lostbuildings.engine.damage;

import java.util.ArrayList;
import java.util.List;

/**
 * The damage an individual building took — the reduced port of
 * {@code mcjty.lostcities.worldgen.lost.DamageArea} (IMPROVEMENTS #1 + PORT #5).
 *
 * <h2>What was cut, and why</h2>
 * The original scanned a ring of neighbouring chunks for explosions, because a Lost Cities blast is
 * a city-wide event that eats through several chunks at once. A wave-2 city is a checkerboard of
 * independent {@code StructurePiece}s that each write only inside their own chunk, so a cross-chunk
 * crater cannot be expressed here at all (PHASE3-PLAN §7). The blast is therefore scoped to the
 * building's own cell: same sphere maths, same damage curve, one cell's worth of it. The separate
 * "mini explosion" tier, the {@code EXPLOSIONS_IN_CITIES_ONLY} filter, the per-city-style explosion
 * chance and the whole-subchunk {@code isCompletelyDestroyed} fast path are gone with it.
 *
 * <h2>Determinism</h2>
 * The blast layout comes from a SplitMix64 stream seeded with nothing but the world seed and the
 * cell coordinate. It deliberately does <b>not</b> draw from the {@code RandomSource} that generates
 * the building, for two reasons: the layout must not depend on how many blocks happened to be placed
 * before it, and — the wave-2 escape hatch — a {@code damageChance} of 0 must leave that random
 * stream untouched so an undamaged building is bit-for-bit what the mod generated before wave 2.
 * {@link #NONE} is what that case returns, and it never allocates or draws anything.
 *
 * <p>Pure arithmetic, no Minecraft types: see {@code EngineDamageAreaTest}.
 */
public final class DamageArea {

	/** Above this damage a block is destroyed outright rather than swapped for a damaged variant. */
	public static final float BLOCK_DAMAGE_CHANCE = 0.7f;

	/** Chance that a block inside the "swap" band becomes its damaged variant rather than air. */
	public static final float DAMAGED_VARIANT_CHANCE = 0.7f;

	/** Smallest blast that is worth generating. */
	private static final int MIN_RADIUS = 5;
	/** Largest blast a single cell may hold: a bit under a chunk diagonal, so it stays local. */
	private static final int MAX_RADIUS = 17;

	/** At most this many spheres per building — two overlapping craters already read as a ruin. */
	private static final int MAX_EXPLOSIONS = 2;

	/**
	 * How fast per-storey weathering grows with height. The top of a tower has been in the weather
	 * for a century; the ground floor has been sheltered by everything above it.
	 */
	private static final float FLOOR_RAMP = 0.06f;

	/** Cap on the weathering term alone, so even a fully-rotten roof keeps some structure. */
	private static final float MAX_WEATHERING = 0.45f;

	/** A building that took no damage at all. Shared, immutable, and free. */
	public static final DamageArea NONE = new DamageArea(List.of(), 0.0f, 0);

	private final List<Explosion> explosions;
	private final float damageChance;
	private final int groundY;

	private DamageArea(List<Explosion> explosions, float damageChance, int groundY) {
		this.explosions = explosions;
		this.damageChance = damageChance;
		this.groundY = groundY;
	}

	/**
	 * Lay out the damage for one building.
	 *
	 * @param worldSeed    the world seed (the only external entropy allowed)
	 * @param cellChunkX   chunk X of the cell the building fills
	 * @param cellChunkZ   chunk Z of the cell
	 * @param groundY      Y of the building's ground floor
	 * @param floors       storeys above ground
	 * @param cellars      storeys below ground
	 * @param floorHeight  blocks per storey
	 * @param damageChance 0 = pristine (returns {@link #NONE}), 1 = ruin
	 */
	public static DamageArea forBuilding(long worldSeed, int cellChunkX, int cellChunkZ, int groundY,
	                                     int floors, int cellars, int floorHeight, float damageChance) {
		if (damageChance <= 0.0f) {
			return NONE;
		}
		float chance = Math.min(damageChance, 1.0f);
		long state = mix(worldSeed ^ (cellChunkX * 0x9E3779B97F4A7C15L) ^ (cellChunkZ * 0xC2B2AE3D27D4EB4FL) ^ 0x0DA3AL);

		int minX = cellChunkX << 4;
		int minZ = cellChunkZ << 4;
		int lowest = groundY - cellars * floorHeight;
		int highest = groundY + (floors + 1) * floorHeight;

		List<Explosion> list = new ArrayList<>(MAX_EXPLOSIONS);
		for (int i = 0; i < MAX_EXPLOSIONS; i++) {
			state = next(state);
			if (unitFloat(state) >= chance) {
				continue;
			}
			state = next(state);
			// Radius scales with the damage setting: a lightly damaged city gets scratches, a
			// heavily damaged one gets craters.
			int span = MAX_RADIUS - MIN_RADIUS;
			int radius = MIN_RADIUS + (int) (unitFloat(state) * (1 + span * chance));
			state = next(state);
			int cx = minX + (int) (unitFloat(state) * 16);
			state = next(state);
			int cz = minZ + (int) (unitFloat(state) * 16);
			state = next(state);
			// Blast centres are biased upward (squared roll): roofs and top storeys collapse far
			// more often than ground floors, which is also what makes the ruin readable from afar.
			float t = unitFloat(state);
			int cy = lowest + (int) ((highest - lowest) * (1.0f - t * t));
			list.add(new Explosion(Math.min(radius, MAX_RADIUS), cx, cy, cz));
		}
		if (list.isEmpty()) {
			// Still weathered, just not cratered — keep the object so the per-storey ramp applies.
			return new DamageArea(List.of(), chance, groundY);
		}
		return new DamageArea(List.copyOf(list), chance, groundY);
	}

	/** True when this area can damage nothing at all; the hot loop skips everything on false. */
	public boolean isIntact() {
		return damageChance <= 0.0f;
	}

	public List<Explosion> explosions() {
		return explosions;
	}

	public boolean hasExplosions() {
		return !explosions.isEmpty();
	}

	/**
	 * Weathering that applies to a whole storey regardless of any blast: rises with height, which is
	 * the "probability rising with floor height" the plan asks for.
	 *
	 * @param floor storey index, 0 = ground floor, negative = cellar
	 */
	public float weathering(int floor) {
		if (damageChance <= 0.0f || floor < 0) {
			return 0.0f;      // cellars are sheltered; nothing rots down there
		}
		return Math.min(MAX_WEATHERING, damageChance * FLOOR_RAMP * floor);
	}

	/**
	 * Total damage at a world position on a given storey: the blast spheres plus the storey's
	 * weathering. 0 means "leave this block exactly as the palette placed it".
	 */
	public float damageAt(int x, int y, int z, int floor) {
		if (damageChance <= 0.0f) {
			return 0.0f;
		}
		float damage = weathering(floor);
		for (Explosion explosion : explosions) {
			damage += explosion.damageAt(x, y, z);
		}
		return damage;
	}

	/**
	 * A rough 0..1 measure of how wrecked the building is, used to decide how much rubble to strew
	 * across its ground floor.
	 */
	public float rubbleFactor() {
		if (damageChance <= 0.0f) {
			return 0.0f;
		}
		float factor = damageChance * 0.3f;
		for (Explosion explosion : explosions) {
			factor += 0.2f * explosion.radius() / (float) MAX_RADIUS;
		}
		return Math.min(1.0f, factor);
	}

	/** Y of the ground floor this area was laid out around (0 for {@link #NONE}). */
	public int groundY() {
		return groundY;
	}

	// --- SplitMix64: a self-contained, allocation-free, perfectly reproducible stream. ---

	private static long next(long state) {
		return mix(state + 0x9E3779B97F4A7C15L);
	}

	private static long mix(long z) {
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	/** The top 24 bits of a mixed state as a float in [0, 1). */
	private static float unitFloat(long state) {
		return (mix(state) >>> 40) / (float) (1 << 24);
	}
}
