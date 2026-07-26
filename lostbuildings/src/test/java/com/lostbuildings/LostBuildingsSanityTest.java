package com.lostbuildings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Minimal sanity test that proves the JUnit 5 harness is wired up and that the mod's own
 * (Minecraft-free) constants are sane.
 *
 * <p>Deliberately does NOT touch {@code com.lostbuildings.engine.**}: those classes are under
 * active rewrite and any assertion here would just be churn. Real engine tests come later.
 */
class LostBuildingsSanityTest {

	@Test
	void modIdIsPresent() {
		assertFalse(LostBuildings.MOD_ID.isBlank(), "MOD_ID must not be blank");
		assertEquals("lostbuildings", LostBuildings.MOD_ID);
	}
}
