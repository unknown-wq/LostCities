package com.lostbuildings.engine;

/**
 * Settings that control how a single building is placed.
 *
 * <p><b>Wave-2 extension (PHASE3-PLAN §3).</b> The three fields the plan froze — {@code cellars},
 * {@code damageChance} and {@code role} — are <em>appended</em> to the record, so every existing
 * positional call site keeps its meaning, and the pre-wave-2 six-argument form still compiles
 * through the convenience constructor below. That form produces exactly what the mod did before:
 * no cellars, no damage, {@link BuildingRole#RESIDENTIAL}.
 *
 * @param minFloors    lowest storey count (0 = ground floor only); pass {@code min == max} when the
 *                     caller has already decided the height, which the placement pass must do
 *                     because it clears the volume before the engine runs
 * @param maxFloors    highest storey count
 * @param lighting     place torches / light sources from the palette
 * @param spawners     place mob spawners from the palette
 * @param loot         attach loot tables to containers from the palette
 * @param waterLevel   sea level; damaged blocks below it flood instead of turning to air
 * @param cellars      how many storeys to dig <em>below</em> the ground floor (0 = none, capped at
 *                     {@link #MAX_CELLARS}). The building's own {@code mincellars}/{@code maxcellars}
 *                     still win over this number, exactly like the floor range does
 * @param damageChance 0 = a pristine building (bit-for-bit what the mod generated before wave 2),
 *                     1 = a ruin. Drives both the explosion spheres and the per-storey weathering
 * @param role         what the building used to be; selects loot and spawner flavour
 */
public record PlaceSettings(int minFloors, int maxFloors, boolean lighting, boolean spawners, boolean loot,
                            int waterLevel, int cellars, float damageChance, BuildingRole role) {

	/** Hard cap on cellars. Two storeys down is as deep as the shipped parts (and Foundation) go. */
	public static final int MAX_CELLARS = 2;

	public PlaceSettings {
		minFloors = Math.max(0, minFloors);
		maxFloors = Math.max(minFloors, maxFloors);
		cellars = Math.clamp(cellars, 0, MAX_CELLARS);
		damageChance = damageChance < 0.0f ? 0.0f : Math.min(damageChance, 1.0f);
		role = role == null ? BuildingRole.RESIDENTIAL : role;
	}

	/**
	 * The pre-wave-2 shape, kept so that a call site written against the old six-field record still
	 * compiles and still generates exactly the same intact building.
	 */
	public PlaceSettings(int minFloors, int maxFloors, boolean lighting, boolean spawners, boolean loot,
	                     int waterLevel) {
		this(minFloors, maxFloors, lighting, spawners, loot, waterLevel, 0, 0.0f, BuildingRole.RESIDENTIAL);
	}

	/** A fully-featured, undamaged building: lighting, spawners and loot on, no cellars. */
	public static PlaceSettings intact(int minFloors, int maxFloors, int waterLevel) {
		return new PlaceSettings(minFloors, maxFloors, true, true, true, waterLevel);
	}

	public PlaceSettings withFloors(int min, int max) {
		return new PlaceSettings(min, max, lighting, spawners, loot, waterLevel, cellars, damageChance, role);
	}

	public PlaceSettings withCellars(int newCellars) {
		return new PlaceSettings(minFloors, maxFloors, lighting, spawners, loot, waterLevel, newCellars, damageChance, role);
	}

	public PlaceSettings withDamage(float newDamageChance) {
		return new PlaceSettings(minFloors, maxFloors, lighting, spawners, loot, waterLevel, cellars, newDamageChance, role);
	}

	public PlaceSettings withRole(BuildingRole newRole) {
		return new PlaceSettings(minFloors, maxFloors, lighting, spawners, loot, waterLevel, cellars, damageChance, newRole);
	}

	/** True when this building is to be generated pristine — the wave-2 regression escape hatch. */
	public boolean isIntact() {
		return damageChance <= 0.0f;
	}
}
