package com.lostbuildings.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the building-role tables (IMPROVEMENTS #5, loot half) and the wave-2 shape of
 * {@link PlaceSettings}, including the promise that the pre-wave-2 constructor still produces a
 * pristine building.
 *
 * <p>Pure tables and arithmetic — no Minecraft class is touched.
 */
class EngineBuildingRoleTest {

	// --- the enum itself ---

	@Test
	void unknownRoleNamesFallBackToDwellingInsteadOfThrowing() {
		assertSame(BuildingRole.RESIDENTIAL, BuildingRole.byName(null));
		assertSame(BuildingRole.RESIDENTIAL, BuildingRole.byName(""));
		assertSame(BuildingRole.RESIDENTIAL, BuildingRole.byName("   "));
		assertSame(BuildingRole.RESIDENTIAL, BuildingRole.byName("cathedral"));
	}

	@Test
	void roleNamesRoundTripThroughTheirSerializedForm() {
		for (BuildingRole role : BuildingRole.values()) {
			assertSame(role, BuildingRole.byName(role.getSerializedName()));
			assertSame(role, BuildingRole.byName(role.name()));
			assertSame(role, BuildingRole.byName(" " + role.getSerializedName().toUpperCase() + " "));
		}
	}

	// --- the storey tier ---

	@Test
	void cellarsRankAboveTheLowerFloors() {
		assertEquals(0, BuildingRole.tier(0));
		assertEquals(1, BuildingRole.tier(1));
		assertEquals(5, BuildingRole.tier(5));
		// A sealed basement beats the second floor: nobody looted it.
		assertEquals(3, BuildingRole.tier(-1));
		assertEquals(4, BuildingRole.tier(-2));
		assertTrue(BuildingRole.tier(-1) > BuildingRole.tier(2));
	}

	// --- signature loot ---

	@Test
	void onlySpecialistRolesHaveSignatureLoot() {
		assertNull(BuildingRole.RESIDENTIAL.signatureLoot());
		assertNotNull(BuildingRole.LIBRARY.signatureLoot());
		assertNotNull(BuildingRole.SHOP.signatureLoot());
		assertNotNull(BuildingRole.TOWER.signatureLoot());
		assertEquals(0.0f, BuildingRole.RESIDENTIAL.signatureChance(0));
		assertEquals(0.0f, BuildingRole.RESIDENTIAL.signatureChance(9));
	}

	@Test
	void signatureChanceRisesWithTheStorey() {
		float previous = -1.0f;
		for (int floor = 0; floor <= 6; floor++) {
			float chance = BuildingRole.TOWER.signatureChance(floor);
			assertTrue(chance > previous, "signature chance did not rise on floor " + floor);
			assertTrue(chance <= 1.0f, "probabilities must stay in [0, 1], got " + chance);
			previous = chance;
		}
		assertTrue(BuildingRole.TOWER.signatureChance(-1) > BuildingRole.TOWER.signatureChance(2),
				"a cellar must outrank the second floor, like tier() says");
	}

	// --- weights ---

	@Test
	void anUnknownLootTableIsNeitherBoostedNorSuppressed() {
		for (BuildingRole role : BuildingRole.values()) {
			assertEquals(1.0f, role.lootWeight("minecraft:chests/spawn_bonus_chest"));
			assertEquals(1.0f, role.lootWeight(null));
			assertEquals(1.0f, role.mobWeight("minecraft:creeper"));
			assertEquals(1.0f, role.mobWeight(null));
		}
	}

	@Test
	void eachRoleFavoursItsOwnLoot() {
		assertTrue(BuildingRole.LIBRARY.lootWeight("minecraft:chests/stronghold_library")
				> BuildingRole.RESIDENTIAL.lootWeight("minecraft:chests/stronghold_library"));
		assertTrue(BuildingRole.SHOP.lootWeight("minecraft:chests/village/village_weaponsmith")
				> BuildingRole.LIBRARY.lootWeight("minecraft:chests/village/village_weaponsmith"));
		assertTrue(BuildingRole.TOWER.lootWeight("minecraft:chests/end_city_treasure")
				> BuildingRole.SHOP.lootWeight("minecraft:chests/end_city_treasure"));
		assertTrue(BuildingRole.RESIDENTIAL.lootWeight("lostbuildings:chests/lostcitychest") > 1.0f);
	}

	@Test
	void weightsAreAlwaysPositiveSoNothingIsSilentlyUnreachable() {
		String[] tables = {
				"minecraft:chests/stronghold_library", "minecraft:chests/village/village_weaponsmith",
				"minecraft:chests/end_city_treasure", "lostbuildings:chests/lostcitychest",
				"minecraft:chests/buried_treasure", "minecraft:chests/jungle_temple"
		};
		for (BuildingRole role : BuildingRole.values()) {
			for (String table : tables) {
				assertTrue(role.lootWeight(table) > 0.0f, role + " suppressed " + table);
			}
			for (String mob : new String[]{"minecraft:zombie", "minecraft:blaze", "minecraft:witch"}) {
				assertTrue(role.mobWeight(mob) > 0.0f, role + " suppressed " + mob);
			}
		}
	}

	// --- spawner promotion ---

	@Test
	void theGroundFloorNeverPromotesASpawner() {
		for (BuildingRole role : BuildingRole.values()) {
			assertEquals(0.0f, role.hardMobChance(0), role + " promoted a ground-floor spawner");
		}
	}

	@Test
	void theClimbGetsNastierAndTheLibraryStaysQuiet() {
		for (BuildingRole role : BuildingRole.values()) {
			float low = role.hardMobChance(1);
			float high = role.hardMobChance(8);
			assertTrue(high > low, role + " did not escalate with height");
			assertTrue(high <= 1.0f, role + " produced a probability above 1: " + high);
		}
		assertTrue(BuildingRole.TOWER.hardMobChance(5) > BuildingRole.LIBRARY.hardMobChance(5));
	}

	// --- PlaceSettings, the frozen contract ---

	@Test
	void thePreWave2ConstructorStillMeansAnUndamagedBuilding() {
		PlaceSettings s = new PlaceSettings(2, 6, true, true, true, 63);
		assertEquals(0, s.cellars());
		assertEquals(0.0f, s.damageChance());
		assertSame(BuildingRole.RESIDENTIAL, s.role());
		assertTrue(s.isIntact());
		assertEquals(new PlaceSettings(2, 6, true, true, true, 63, 0, 0.0f, BuildingRole.RESIDENTIAL), s);
	}

	@Test
	void settingsAreNormalisedRatherThanTrusted() {
		PlaceSettings s = new PlaceSettings(-3, -9, true, false, true, 63, 17, 4.0f, null);
		assertEquals(0, s.minFloors());
		assertEquals(0, s.maxFloors());
		assertEquals(PlaceSettings.MAX_CELLARS, s.cellars());
		assertEquals(1.0f, s.damageChance());
		assertSame(BuildingRole.RESIDENTIAL, s.role());

		assertEquals(0.0f, new PlaceSettings(1, 1, true, true, true, 63, -1, -2.0f, null).damageChance());
		assertEquals(0, new PlaceSettings(1, 1, true, true, true, 63, -1, 0.0f, null).cellars());
	}

	@Test
	void withersChangeOneThingAndKeepTheRest() {
		PlaceSettings base = PlaceSettings.intact(2, 6, 63);
		assertTrue(base.lighting() && base.spawners() && base.loot());
		assertTrue(base.isIntact());

		PlaceSettings damaged = base.withDamage(0.5f);
		assertEquals(0.5f, damaged.damageChance());
		assertFalse(damaged.isIntact());
		assertEquals(base.minFloors(), damaged.minFloors());
		assertEquals(base.maxFloors(), damaged.maxFloors());
		assertEquals(base.waterLevel(), damaged.waterLevel());

		PlaceSettings cellared = damaged.withCellars(2).withRole(BuildingRole.LIBRARY);
		assertEquals(2, cellared.cellars());
		assertSame(BuildingRole.LIBRARY, cellared.role());
		assertEquals(0.5f, cellared.damageChance());

		PlaceSettings taller = cellared.withFloors(7, 7);
		assertEquals(7, taller.minFloors());
		assertEquals(7, taller.maxFloors());
		assertEquals(2, taller.cellars());
		assertSame(BuildingRole.LIBRARY, taller.role());
	}
}
