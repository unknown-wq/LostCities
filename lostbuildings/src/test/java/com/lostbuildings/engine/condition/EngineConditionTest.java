package com.lostbuildings.engine.condition;

import com.lostbuildings.engine.BuildingRole;
import com.lostbuildings.engine.codec.ConditionPart;
import com.mojang.datafixers.util.Either;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the honest condition resolution (IMPROVEMENTS #2) that replaced the weights-only pick
 * recorded in {@code PORT-STATUS.md} → "Disabled content".
 *
 * <p>The two halves are tested separately: {@link ConditionMatcher} decides whether a candidate is
 * usable at a position at all, {@link ConditionResolver} decides which of the survivors wins. Both
 * are pure — no Minecraft class is touched, so this runs in a plain JVM.
 */
class EngineConditionTest {

	// --- builders (ConditionPart's constructor is codec-shaped, so wrap it) ---

	private static final Optional<Boolean> NO_BOOL = Optional.empty();
	private static final Optional<Integer> NO_INT = Optional.empty();
	private static final Optional<Either<List<String>, String>> NO_SET = Optional.empty();

	private static ConditionPart part(float factor, String value, Optional<String> range) {
		return new ConditionPart(factor, value, NO_BOOL, NO_BOOL, NO_BOOL, NO_BOOL, NO_BOOL,
				NO_INT, NO_INT, NO_INT, NO_SET, NO_SET, NO_SET, NO_SET, range);
	}

	private static ConditionPart plain(float factor, String value) {
		return part(factor, value, Optional.empty());
	}

	private static ConditionPart ranged(float factor, String value, String range) {
		return part(factor, value, Optional.of(range));
	}

	private static ConditionPart flagged(float factor, String value, Optional<Boolean> top,
	                                     Optional<Boolean> ground, Optional<Boolean> cellar) {
		return new ConditionPart(factor, value, top, ground, cellar, NO_BOOL, NO_BOOL,
				NO_INT, NO_INT, NO_INT, NO_SET, NO_SET, NO_SET, NO_SET, Optional.empty());
	}

	private static ConditionPart inPart(float factor, String value, String partName) {
		return new ConditionPart(factor, value, NO_BOOL, NO_BOOL, NO_BOOL, NO_BOOL, NO_BOOL,
				NO_INT, NO_INT, NO_INT, NO_SET, Optional.of(Either.right(partName)), NO_SET, NO_SET,
				Optional.empty());
	}

	private static ConditionPart onFloor(float factor, String value, int floor) {
		return new ConditionPart(factor, value, NO_BOOL, NO_BOOL, NO_BOOL, NO_BOOL, NO_BOOL,
				Optional.of(floor), NO_INT, NO_INT, NO_SET, NO_SET, NO_SET, NO_SET, Optional.empty());
	}

	/** A 4-storey building with one cellar, at chunk (3, 52). */
	private static ConditionContext at(int floor) {
		return new ConditionContext(floor, 4, 1, "building1_3", "building1", 3, 52, BuildingRole.RESIDENTIAL);
	}

	// --- matching ---

	@Test
	void verticalFlagsAreHonoured() {
		ConditionPart topOnly = flagged(1, "top", Optional.of(true), NO_BOOL, NO_BOOL);
		assertTrue(ConditionMatcher.matches(topOnly, at(4)), "floor 4 is the top of a 4-storey building");
		assertFalse(ConditionMatcher.matches(topOnly, at(3)));

		ConditionPart groundOnly = flagged(1, "ground", NO_BOOL, Optional.of(true), NO_BOOL);
		assertTrue(ConditionMatcher.matches(groundOnly, at(0)));
		assertFalse(ConditionMatcher.matches(groundOnly, at(2)));
		assertFalse(ConditionMatcher.matches(groundOnly, at(-1)));

		ConditionPart cellarOnly = flagged(1, "cellar", NO_BOOL, NO_BOOL, Optional.of(true));
		assertTrue(ConditionMatcher.matches(cellarOnly, at(-1)));
		assertTrue(ConditionMatcher.matches(cellarOnly, at(-2)));
		assertFalse(ConditionMatcher.matches(cellarOnly, at(0)));

		ConditionPart notCellar = flagged(1, "above", NO_BOOL, NO_BOOL, Optional.of(false));
		assertTrue(ConditionMatcher.matches(notCellar, at(1)));
		assertFalse(ConditionMatcher.matches(notCellar, at(-1)));
	}

	@Test
	void anUnconstrainedCandidateMatchesEverywhere() {
		ConditionPart any = plain(1, "anywhere");
		for (int floor = -2; floor <= 6; floor++) {
			assertTrue(ConditionMatcher.matches(any, at(floor)), "floor " + floor);
		}
	}

	@Test
	void rangesAreInclusiveAndUnderstandCellars() {
		// Both ranges are copied straight out of the shipped chestloot.json.
		ConditionPart high = ranged(8, "lostbuildings:chests/lostcitychest", "4,100");
		assertFalse(ConditionMatcher.matches(high, at(3)));
		assertTrue(ConditionMatcher.matches(high, at(4)), "the range is inclusive at the low end");
		assertTrue(ConditionMatcher.matches(high, at(100)), "and at the high end");
		assertFalse(ConditionMatcher.matches(high, at(101)));

		ConditionPart deep = ranged(8, "lostbuildings:chests/lostcitychest", "-100,-3");
		assertFalse(ConditionMatcher.matches(deep, at(-1)));
		assertTrue(ConditionMatcher.matches(deep, at(-3)));
	}

	@Test
	void aMalformedRangeIsIgnoredRatherThanFatal() {
		assertTrue(ConditionMatcher.inRange("not,a,range", 3));
		assertTrue(ConditionMatcher.inRange("5", 3));
		assertTrue(ConditionMatcher.inRange(null, 3));
		// Reversed bounds are accepted the way a human would mean them.
		assertTrue(ConditionMatcher.inRange("6,2", 4));
	}

	@Test
	void exactFloorAndPartNamesAreHonoured() {
		assertTrue(ConditionMatcher.matches(onFloor(1, "v", 2), at(2)));
		assertFalse(ConditionMatcher.matches(onFloor(1, "v", 2), at(3)));

		assertTrue(ConditionMatcher.matches(inPart(1, "v", "building1_3"), at(1)));
		assertFalse(ConditionMatcher.matches(inPart(1, "v", "rail_dungeon1"), at(1)));
		// The namespace a data reference happens to carry must not defeat the match.
		assertTrue(ConditionMatcher.matches(inPart(1, "v", "lostcities:building1_3"), at(1)));
	}

	@Test
	void aNullConditionMatchesEverything() {
		assertTrue(ConditionMatcher.matches(null, at(0)));
	}

	// --- resolution ---

	@Test
	void candidatesThatDoNotFitArePickedOutOfTheBag() {
		List<ConditionPart> values = List.of(
				ranged(8, "lostbuildings:chests/lostcitychest", "4,100"),
				plain(1, "minecraft:chests/simple_dungeon"));
		assertEquals(1, ConditionResolver.countMatching(values, at(0)));
		assertEquals(2, ConditionResolver.countMatching(values, at(5)));

		// On the ground floor the city chest is simply not available, whatever the roll.
		for (float roll = 0.0f; roll < 1.0f; roll += 0.05f) {
			assertEquals("minecraft:chests/simple_dungeon",
					ConditionResolver.select(values, at(0), ConditionResolver.Kind.LOOT, roll));
		}
		// High up its weight of 8 against 1 makes it the overwhelming favourite.
		assertEquals("lostbuildings:chests/lostcitychest",
				ConditionResolver.select(values, at(5), ConditionResolver.Kind.LOOT, 0.5f));
	}

	@Test
	void nothingSuitableResolvesToNullRatherThanToSomethingWrong() {
		List<ConditionPart> onlyDeep = List.of(ranged(8, "deep", "-100,-3"));
		assertNull(ConditionResolver.select(onlyDeep, at(2), ConditionResolver.Kind.LOOT, 0.5f));
		assertNull(ConditionResolver.select(List.of(), at(2), ConditionResolver.Kind.LOOT, 0.5f));
		assertNull(ConditionResolver.select(null, at(2), ConditionResolver.Kind.LOOT, 0.5f));
	}

	@Test
	void theRoleChangesWhichChestYouFind() {
		List<ConditionPart> values = List.of(
				plain(1, "minecraft:chests/simple_dungeon"),
				plain(1, "minecraft:chests/stronghold_library"));
		ConditionContext dwelling = at(2);
		ConditionContext library = new ConditionContext(2, 4, 1, "p", "b", 3, 52, BuildingRole.LIBRARY);

		// Same roll, same candidates: only the role differs.
		assertEquals("minecraft:chests/simple_dungeon",
				ConditionResolver.select(values, dwelling, ConditionResolver.Kind.LOOT, 0.4f));
		assertEquals("minecraft:chests/stronghold_library",
				ConditionResolver.select(values, library, ConditionResolver.Kind.LOOT, 0.4f));
	}

	@Test
	void thePlainKindIgnoresTheRoleEntirely() {
		List<ConditionPart> values = List.of(
				plain(1, "minecraft:chests/simple_dungeon"),
				plain(1, "minecraft:chests/stronghold_library"));
		ConditionContext library = new ConditionContext(2, 4, 1, "p", "b", 3, 52, BuildingRole.LIBRARY);
		assertEquals(ConditionResolver.select(values, at(2), ConditionResolver.Kind.PLAIN, 0.4f),
				ConditionResolver.select(values, library, ConditionResolver.Kind.PLAIN, 0.4f));
	}

	@Test
	void aSingleCandidateAlwaysWins() {
		List<ConditionPart> values = List.of(plain(1, "only"));
		for (float roll = 0.0f; roll < 1.0f; roll += 0.1f) {
			assertEquals("only", ConditionResolver.select(values, at(1), ConditionResolver.Kind.LOOT, roll));
		}
	}

	@Test
	void everyRollLandsOnSomeCandidate() {
		List<ConditionPart> values = List.of(plain(1, "a"), plain(2, "b"), plain(3, "c"));
		for (float roll = -0.5f; roll <= 1.5f; roll += 0.05f) {
			assertTrue(List.of("a", "b", "c")
					.contains(ConditionResolver.select(values, at(1), ConditionResolver.Kind.LOOT, roll)));
		}
	}

	// --- context bookkeeping ---

	@Test
	void theContextKnowsWhereItIs() {
		ConditionContext ctx = at(0);
		assertTrue(ctx.isGround());
		assertFalse(ctx.isTop());
		assertFalse(ctx.isCellar());
		assertTrue(ctx.atFloor(4).isTop());
		assertTrue(ctx.atFloor(-1).isCellar());
		assertEquals("other", ctx.inPart("other").partName());
		assertEquals(BuildingRole.RESIDENTIAL,
				new ConditionContext(0, 0, 0, null, null, 0, 0, null).role(),
				"a null role must not blow up deep inside chunk generation");
	}
}
