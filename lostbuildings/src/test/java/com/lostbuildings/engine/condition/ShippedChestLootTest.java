package com.lostbuildings.engine.condition;

import com.lostbuildings.engine.BuildingRole;
import com.lostbuildings.engine.codec.ConditionPart;
import com.mojang.datafixers.util.Either;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards the two halves of "a chest in a lost city has loot" that can be checked without a running
 * Minecraft: the ids the shipped {@code chestloot} condition offers must actually resolve to files
 * that ship in the jar, and the condition must never come up empty-handed on a storey a chest can
 * stand on.
 *
 * <p><b>Why this exists.</b> Chests generated in cities were empty in game. The cause turned out to
 * be a worldgen write-ordering bug in {@code BuildingEngine} — the loot NBT was written before the
 * block, and {@code WorldGenRegion.setBlock} then overwrote it with vanilla's {@code "DUMMY"}
 * placeholder — which no pure test can reach. But the two failure modes that <em>look identical
 * from inside the game</em>, and that a data edit can reintroduce at any time, are both testable
 * here: a loot id that names a table the mod does not ship (nothing but a warning at runtime), and
 * a condition whose candidates are all filtered out at a given storey (no loot table set at all).
 * Both produce exactly the same symptom: a chest you open and find empty.
 *
 * <p>Reads the shipped JSON straight off the classpath rather than restating it, so the assertions
 * cannot drift away from the data they are about. Parsing is a deliberately small regex scan: it
 * keeps the test in a plain JVM, with no Minecraft and no codec, like the rest of {@code src/test}.
 */
class ShippedChestLootTest {

	private static final String CONDITION = "/data/lostbuildings/lostcities/conditions/chestloot.json";
	private static final String LOOT_DIR = "/data/lostbuildings/loot_table/";

	/** One {@code { ... }} object inside the condition's {@code values} array. */
	private static final Pattern ENTRY = Pattern.compile("\\{[^{}]*}");
	private static final Pattern STRING_FIELD = Pattern.compile("\"%s\"\\s*:\\s*\"([^\"]*)\"");
	private static final Pattern NUMBER_FIELD = Pattern.compile("\"%s\"\\s*:\\s*([0-9.]+)");
	/** {@code namespace:path}, both in the character set a vanilla Identifier accepts. */
	private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

	private static String read(String resource) {
		try (InputStream in = ShippedChestLootTest.class.getResourceAsStream(resource)) {
			assertNotNull(in, "resource missing from the classpath: " + resource);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			return fail("could not read " + resource, e);
		}
	}

	private static Optional<String> string(String entry, String field) {
		Matcher m = Pattern.compile(String.format(STRING_FIELD.pattern(), field)).matcher(entry);
		return m.find() ? Optional.of(m.group(1)) : Optional.empty();
	}

	private static float number(String entry, String field) {
		Matcher m = Pattern.compile(String.format(NUMBER_FIELD.pattern(), field)).matcher(entry);
		return m.find() ? Float.parseFloat(m.group(1)) : 0.0f;
	}

	/** The shipped condition, as the objects {@link ConditionResolver} actually consumes. */
	private static List<ConditionPart> shippedChestLoot() {
		String json = read(CONDITION);
		List<ConditionPart> parts = new ArrayList<>();
		Matcher entries = ENTRY.matcher(json);
		while (entries.find()) {
			String entry = entries.group();
			Optional<String> value = string(entry, "value");
			if (value.isEmpty()) {
				continue;
			}
			Optional<Either<List<String>, String>> inpart =
					string(entry, "inpart").map(Either::right);
			parts.add(new ConditionPart(number(entry, "factor"), value.get(),
					Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
					Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
					Optional.empty(), inpart, Optional.empty(), Optional.empty(),
					string(entry, "range")));
		}
		assertFalse(parts.isEmpty(), "parsed no candidates out of " + CONDITION);
		return parts;
	}

	/** An ordinary block of flats: 4 storeys, one cellar, no special role. */
	private static ConditionContext at(int floor) {
		return new ConditionContext(floor, 4, 1, "building1_3", "building1", 3, 52, BuildingRole.RESIDENTIAL);
	}

	@Test
	void everyOfferedIdIsAWellFormedIdentifier() {
		for (ConditionPart part : shippedChestLoot()) {
			String id = part.getValue();
			assertTrue(ID.matcher(id).matches(),
					"'" + id + "' is not a valid resource location, so Identifier.tryParse "
							+ "returns null and the chest silently gets no loot table");
		}
	}

	/**
	 * The mod's own tables must live where the id says they do. In 26.2 the data directory is
	 * {@code loot_table} (singular), so {@code lostbuildings:chests/lostcitychest} is
	 * {@code data/lostbuildings/loot_table/chests/lostcitychest.json} — get that wrong and the
	 * server logs one warning and hands out an empty chest.
	 */
	@Test
	void theModsOwnTablesShipAtThePathTheirIdNames() {
		int checked = 0;
		for (ConditionPart part : shippedChestLoot()) {
			String id = part.getValue();
			if (!id.startsWith("lostbuildings:")) {
				continue;   // vanilla tables are the game's problem, not ours
			}
			String path = LOOT_DIR + id.substring("lostbuildings:".length()) + ".json";
			try (InputStream in = ShippedChestLootTest.class.getResourceAsStream(path)) {
				assertNotNull(in, "chestloot offers '" + id + "' but " + path + " does not ship");
			} catch (IOException e) {
				fail("could not open " + path, e);
			}
			checked++;
		}
		assertTrue(checked > 0, "the condition no longer offers any of the mod's own tables");
	}

	/**
	 * A chest can appear on any storey of any building, so the condition has to have something to
	 * give at every one of them — including the storeys where the mod's own city chest is filtered
	 * out by its {@code range}, and including the cellars added in wave 2. A {@code null} here is
	 * not a fallback: {@code BuildingEngine} sets no loot table at all and the chest is empty.
	 */
	@Test
	void resolutionNeverComesUpEmptyOnAnyStorey() {
		List<ConditionPart> values = shippedChestLoot();
		for (BuildingRole role : BuildingRole.values()) {
			for (int floor = -3; floor <= 8; floor++) {
				ConditionContext ctx = new ConditionContext(floor, 4, 3, "building1_3", "building1",
						3, 52, role);
				assertTrue(ConditionResolver.countMatching(values, ctx) > 0,
						"no candidate matches at storey " + floor + " for " + role);
				for (float roll = 0.0f; roll < 1.0f; roll += 0.05f) {
					String picked = ConditionResolver.select(values, ctx, ConditionResolver.Kind.LOOT, roll);
					assertNotNull(picked,
							"storey " + floor + ", role " + role + ", roll " + roll + ": no loot table");
					assertFalse(picked.isEmpty(), "storey " + floor + ": empty loot table id");
				}
			}
		}
	}

	/** On a middle storey of a plain block of flats the roll must still land on a real table. */
	@Test
	void aResidentialMiddleStoreyGetsARealTable() {
		List<ConditionPart> values = shippedChestLoot();
		String picked = ConditionResolver.select(values, at(2), ConditionResolver.Kind.LOOT, 0.5f);
		assertNotNull(picked, "a chest on storey 2 of a residential building got no loot table");
		assertTrue(ID.matcher(picked).matches(), picked);
	}
}
