package com.lostbuildings.engine;

import java.util.Locale;
import java.util.Map;

/**
 * What a building in a lost city <em>was</em>, before the city was lost (IMPROVEMENTS #5, loot half).
 *
 * <p>The role is decided by the city layout (agent G / {@code CityLayout}) and travels to the engine
 * inside {@link PlaceSettings}. The engine uses it for exactly two things — which loot table a chest
 * ends up with and how dangerous a spawner is — because those are the only role effects that need no
 * new building data. Role-specific <em>parts</em> would need new JSON and are deliberately out of
 * scope for wave 2.
 *
 * <p><b>This class must stay Minecraft-free.</b> It is pure tables and arithmetic so that CI can
 * test it without bootstrapping the game (see {@code EngineBuildingRoleTest}). It also must not be
 * referenced from {@code world/structure/CityLayout} — that class has the same constraint and is
 * owned by another agent (PHASE3-PLAN §3).
 */
public enum BuildingRole {

	/**
	 * The default: an ordinary block of flats. No signature loot; its chests are whatever the
	 * {@code chestloot} condition rolls, which is what the mod did before roles existed.
	 */
	RESIDENTIAL(null, 0.0f,
			Map.of("lostcitychest", 1.5f,
					"shipwreck_supply", 1.2f),
			Map.of()),

	/** A shop or a workshop: tools, weapons, trade goods; not especially well guarded. */
	SHOP("minecraft:chests/village/village_weaponsmith", 0.35f,
			Map.of("village", 3.0f,
					"lostcitychest", 1.2f,
					"pillager_outpost", 1.3f),
			Map.of("zombie", 1.4f)),

	/** A library or an archive: books and enchanted gear, and hardly any mobs. */
	LIBRARY("minecraft:chests/stronghold_library", 0.45f,
			Map.of("stronghold", 4.0f,
					"jungle_temple", 1.5f,
					"end_city_treasure", 1.3f),
			Map.of("spider", 1.6f, "blaze", 0.4f)),

	/** An office tower: the higher you climb the better the loot and the worse the welcome. */
	TOWER("minecraft:chests/pillager_outpost", 0.30f,
			Map.of("lostcitychest", 2.0f,
					"end_city_treasure", 2.0f,
					"buried_treasure", 1.5f),
			Map.of("blaze", 1.8f, "witch", 1.4f, "skeleton", 1.3f));

	/** Loot table forced on a chest of this role when the signature roll succeeds; may be null. */
	private final String signatureLoot;
	/** Base probability of taking {@link #signatureLoot} instead of rolling the condition. */
	private final float signatureChance;
	/** Substring of a loot table id → weight multiplier when this role rolls a chest. */
	private final Map<String, Float> lootBoosts;
	/** Substring of a mob id → weight multiplier when this role rolls a spawner. */
	private final Map<String, Float> mobBoosts;

	BuildingRole(String signatureLoot, float signatureChance,
	             Map<String, Float> lootBoosts, Map<String, Float> mobBoosts) {
		this.signatureLoot = signatureLoot;
		this.signatureChance = signatureChance;
		this.lootBoosts = lootBoosts;
		this.mobBoosts = mobBoosts;
	}

	/** Parse a role name from data/NBT; anything unknown (or null) falls back to {@link #RESIDENTIAL}. */
	public static BuildingRole byName(String name) {
		if (name == null || name.isBlank()) {
			return RESIDENTIAL;
		}
		for (BuildingRole role : values()) {
			if (role.name().equalsIgnoreCase(name.trim())) {
				return role;
			}
		}
		return RESIDENTIAL;
	}

	/** Lower-case name, the form used in NBT and in the structure codec. */
	public String getSerializedName() {
		return name().toLowerCase(Locale.ROOT);
	}

	public String signatureLoot() {
		return signatureLoot;
	}

	/**
	 * Probability that a chest takes this role's signature loot table outright.
	 *
	 * <p>Rises with the storey: the shop floor of a tower holds shop-grade junk, the top office
	 * holds what the last people up there were hoarding. Cellars count as one storey "up" because a
	 * sealed basement is exactly where the good stuff survived.
	 *
	 * @param floor storey index, 0 = ground, negative = cellar
	 */
	public float signatureChance(int floor) {
		if (signatureLoot == null) {
			return 0.0f;
		}
		return clamp01(signatureChance * (1.0f + 0.12f * tier(floor)));
	}

	/** Weight multiplier for a loot table id when this role rolls a chest. Never zero. */
	public float lootWeight(String lootTable) {
		return boost(lootBoosts, lootTable);
	}

	/** Weight multiplier for a mob id when this role rolls a spawner. Never zero. */
	public float mobWeight(String mobId) {
		return boost(mobBoosts, mobId);
	}

	/**
	 * Chance that an "easy" spawner is upgraded to the hard variant on this storey.
	 *
	 * <p>This is the honest, data-free half of "higher floor → nastier spawner": the palette
	 * character fixes which condition a spawner uses, so the only way to make height matter without
	 * rewriting every part is to promote the condition itself.
	 */
	public float hardMobChance(int floor) {
		float base = switch (this) {
			case LIBRARY -> 0.05f;
			case RESIDENTIAL -> 0.08f;
			case SHOP -> 0.10f;
			case TOWER -> 0.15f;
		};
		return clamp01(base * tier(floor));
	}

	/**
	 * Danger/value tier of a storey: 0 on the ground, +1 per storey up, and cellars start at 2
	 * (a basement that nobody looted in a hundred years beats the second floor).
	 */
	public static int tier(int floor) {
		if (floor < 0) {
			return 2 - floor;   // -1 -> 3, -2 -> 4
		}
		return floor;
	}

	private static float boost(Map<String, Float> table, String value) {
		if (value == null || table.isEmpty()) {
			return 1.0f;
		}
		float result = 1.0f;
		for (Map.Entry<String, Float> entry : table.entrySet()) {
			if (value.contains(entry.getKey())) {
				result *= entry.getValue();
			}
		}
		return result;
	}

	private static float clamp01(float v) {
		return v < 0.0f ? 0.0f : Math.min(v, 1.0f);
	}
}
