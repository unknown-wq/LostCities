package com.lostbuildings.engine.damage;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the damage maths (IMPROVEMENTS #1 / PORT #5).
 *
 * <p>Two things matter more than anything else here and both are asserted below:
 * <ul>
 *   <li><b>{@code damageChance == 0} is a perfect no-op.</b> It is the wave-2 regression escape
 *       hatch — an undamaged building has to be exactly what the mod generated before.</li>
 *   <li><b>Determinism.</b> Same seed and same cell ⇒ byte-identical blast layout, whatever order
 *       chunks happen to generate in.</li>
 * </ul>
 *
 * <p>Pure arithmetic — no Minecraft class is touched, so this runs in a plain JVM.
 */
class EngineDamageAreaTest {

	private static final long SEED = 0x5EEDL;
	private static final int GROUND_Y = 64;
	private static final int FLOOR_HEIGHT = 6;

	private static DamageArea area(float damageChance) {
		return DamageArea.forBuilding(SEED, 3, 52, GROUND_Y, 4, 1, FLOOR_HEIGHT, damageChance);
	}

	// --- the escape hatch ---

	@Test
	void zeroDamageChanceIsTheSharedNoDamageObject() {
		assertSame(DamageArea.NONE, area(0.0f));
		assertSame(DamageArea.NONE, area(-1.0f));
	}

	@Test
	void anIntactAreaDamagesNothingAnywhere() {
		DamageArea intact = area(0.0f);
		assertTrue(intact.isIntact());
		assertFalse(intact.hasExplosions());
		assertEquals(0.0f, intact.rubbleFactor());
		for (int floor = -2; floor <= 8; floor++) {
			assertEquals(0.0f, intact.weathering(floor), "weathering on floor " + floor);
			for (int x = 48; x < 64; x += 5) {
				for (int y = GROUND_Y - 12; y < GROUND_Y + 40; y += 7) {
					assertEquals(0.0f, intact.damageAt(x, y, 832, floor));
				}
			}
		}
	}

	// --- determinism ---

	@Test
	void sameSeedAndCellGiveTheSameBlast() {
		DamageArea a = area(0.6f);
		DamageArea b = area(0.6f);
		assertEquals(a.explosions(), b.explosions());
		assertEquals(a.rubbleFactor(), b.rubbleFactor());
		for (int y = GROUND_Y; y < GROUND_Y + 30; y += 3) {
			assertEquals(a.damageAt(56, y, 840, 2), b.damageAt(56, y, 840, 2));
		}
	}

	@Test
	void differentCellsGetDifferentBlasts() {
		Set<List<Explosion>> layouts = new HashSet<>();
		for (int cx = 0; cx < 8; cx++) {
			for (int cz = 0; cz < 8; cz++) {
				layouts.add(DamageArea.forBuilding(SEED, cx, cz, GROUND_Y, 4, 0, FLOOR_HEIGHT, 0.8f).explosions());
			}
		}
		// 64 cells must not all collapse onto one layout; a couple of collisions are fine.
		assertTrue(layouts.size() > 30, "expected varied blast layouts, got " + layouts.size());
	}

	@Test
	void theWorldSeedReachesTheBlastLayout() {
		Set<List<Explosion>> layouts = new HashSet<>();
		for (long seed = 1L; seed <= 16L; seed++) {
			List<Explosion> explosions =
					DamageArea.forBuilding(seed, 3, 52, GROUND_Y, 4, 0, FLOOR_HEIGHT, 0.9f).explosions();
			assertNotNull(explosions);
			layouts.add(explosions);
		}
		assertTrue(layouts.size() > 8, "the world seed barely changed the blast: " + layouts.size() + " of 16");
	}

	// --- the blast itself ---

	@Test
	void blastsStayInsideTheirOwnCell() {
		// A piece may only write inside its own chunk; a blast centre outside the cell would mean
		// the building is damaged by something it cannot see.
		for (int cx = -4; cx <= 4; cx++) {
			for (int cz = -4; cz <= 4; cz++) {
				DamageArea a = DamageArea.forBuilding(SEED, cx, cz, GROUND_Y, 5, 2, FLOOR_HEIGHT, 1.0f);
				for (Explosion e : a.explosions()) {
					assertTrue(e.centerX() >= (cx << 4) && e.centerX() < (cx << 4) + 16,
							"blast centre X outside cell " + cx + ": " + e.centerX());
					assertTrue(e.centerZ() >= (cz << 4) && e.centerZ() < (cz << 4) + 16,
							"blast centre Z outside cell " + cz + ": " + e.centerZ());
				}
			}
		}
	}

	@Test
	void moreDamageChanceMeansMoreDamage() {
		float light = totalDamage(area(0.2f));
		float heavy = totalDamage(area(1.0f));
		assertTrue(heavy >= light, "heavier damage produced less total damage (" + heavy + " < " + light + ")");
	}

	private static float totalDamage(DamageArea a) {
		float sum = 0.0f;
		for (int x = 48; x < 64; x++) {
			for (int z = 832; z < 848; z++) {
				for (int y = GROUND_Y; y < GROUND_Y + 30; y++) {
					sum += a.damageAt(x, y, z, 2);
				}
			}
		}
		return sum;
	}

	// --- the per-storey ramp ---

	@Test
	void weatheringRisesWithHeightAndSparesCellars() {
		DamageArea a = area(1.0f);
		assertEquals(0.0f, a.weathering(0), "the ground floor is sheltered by everything above it");
		assertEquals(0.0f, a.weathering(-1), "cellars do not weather");
		assertEquals(0.0f, a.weathering(-2));
		float previous = 0.0f;
		for (int floor = 1; floor <= 6; floor++) {
			float w = a.weathering(floor);
			assertTrue(w > previous, "weathering did not rise from floor " + (floor - 1) + " to " + floor);
			previous = w;
		}
	}

	@Test
	void weatheringIsCapped() {
		DamageArea a = area(1.0f);
		assertTrue(a.weathering(1000) <= 0.5f, "weathering must stay bounded, got " + a.weathering(1000));
	}

	@Test
	void weatheringScalesWithTheDamageSetting() {
		assertTrue(area(1.0f).weathering(5) > area(0.25f).weathering(5));
	}

	// --- sphere geometry ---

	@Test
	void damageIsThreeAtTheCentreAndZeroAtTheRim() {
		Explosion e = new Explosion(10, 0, 0, 0);
		assertEquals(3.0f, e.damageAt(0, 0, 0), 1e-6f);
		assertEquals(0.0f, e.damageAt(10, 0, 0), "a point on the surface takes no damage");
		assertEquals(0.0f, e.damageAt(11, 0, 0));
		assertEquals(0.0f, e.damageAt(0, 0, 40));
		assertTrue(e.damageAt(5, 0, 0) > 0.0f && e.damageAt(5, 0, 0) < 3.0f);
	}

	@Test
	void damageFallsOffWithDistance() {
		Explosion e = new Explosion(12, 4, 70, -8);
		float previous = Float.MAX_VALUE;
		for (int d = 0; d < 12; d++) {
			float dmg = e.damageAt(4 + d, 70, -8);
			assertTrue(dmg < previous, "damage did not fall off at distance " + d);
			previous = dmg;
		}
	}

	@Test
	void radiusIsNeverDegenerate() {
		assertEquals(1, new Explosion(0, 0, 0, 0).radius());
		assertEquals(1, new Explosion(-5, 0, 0, 0).radius());
	}

	@Test
	void intersectsAgreesWithDamageAt() {
		Explosion e = new Explosion(8, 0, 64, 0);
		assertTrue(e.intersects(-4, 60, -4, 4, 68, 4), "a box around the centre must intersect");
		assertTrue(e.intersects(4, 64, 0, 20, 80, 16), "a box touching the sphere must intersect");
		assertFalse(e.intersects(100, 64, 100, 116, 80, 116), "a distant box must not intersect");
	}
}
