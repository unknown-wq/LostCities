package com.lostbuildings.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Structural checks on the shipped {@code data/lostbuildings/lostcities} datapack.
 *
 * <p><b>Why this exists.</b> Every failure mode in this file is <em>silent at runtime</em>. A row
 * that is 15 characters instead of 16 does not throw: {@code BuildingPartRE} concatenates the rows
 * of a slice into one flat string and indexes into it, so one short row shifts every subsequent
 * cell by one and the building comes out diagonally smeared. A storey part with 5 slices does not
 * throw either: {@code BuildingEngine} advances by a fixed {@code FLOORHEIGHT = 6} per storey and
 * ignores the part's own height, so you get a one-block gap between floors. An undefined palette
 * character logs one warning and becomes air. None of that fails a build, none of it fails the
 * engine tests, and all of it is only visible by flying to a city and looking at it.
 *
 * <p>Until this class existed the checks were throwaway Python scripts run by hand, which meant a
 * malformed part could ship green. Read the datapack off the module's own {@code src/main/resources}
 * rather than the classpath: the failure messages then name the file a person can actually open, and
 * the test does not depend on {@code processResources} having run.
 *
 * <p>Deliberately plain JVM code with no Minecraft and no codecs, like the rest of {@code src/test}.
 * Gson is used only because it already ships as a Minecraft dependency and {@code AssetLoader} parses
 * with it too.
 */
class DatapackGeometryTest {

	/** Storeys are stacked at this pitch regardless of a part's real height. */
	private static final int FLOOR_HEIGHT = 6;

	/** {@code CompiledPalette.RANDOM_TABLE_SIZE}: a weighted entry expands into this many slots. */
	private static final int RANDOM_TABLE_SIZE = 128;

	/**
	 * Buildings exempt from the six-slice rule, with the reason. <b>These are known pre-existing
	 * defects in files this test's author was not allowed to edit, not decisions.</b>
	 *
	 * <p>{@code cabin} (8 slices), {@code radiotower} (24) and the four {@code oilrig*} quadrants
	 * (21-22) are the "scattered" structures placed by {@code ScatteredStructure}. Each is a single
	 * unconditioned part ref in a building that declares {@code minfloors == maxfloors == 1}. That
	 * does not exempt them from the storey loop: {@code BuildingEngine.generateBuilding} runs
	 * {@code for (f = -cellars; f <= floors; f++)}, so with {@code floors == 1} the same tall part is
	 * stamped twice, at {@code y} and at {@code y + 6} — which is why {@code BuildingPiece.clearHeight}
	 * documents the volume as {@code (floors + 1) * FLOOR_HEIGHT}. Fixing it means either giving these
	 * buildings {@code minfloors: 0} or marking the single part {@code "top": true}; both are edits to
	 * {@code buildings/*.json}.
	 */
	private static final Set<String> MONOLITHIC_SCATTERED_BUILDINGS = Set.of(
			"cabin", "radiotower", "oilrig00", "oilrig01", "oilrig10", "oilrig11");

	/**
	 * Buildings exempt from the undefined-character check, with the reason. <b>Also a known
	 * pre-existing defect, not a decision.</b>
	 *
	 * <p>{@code palettes/oilrig.json} defines the oil rig's whole material vocabulary
	 * ({@code M q I 0 [ ...}) but no building attaches it: the four {@code oilrig*} buildings declare
	 * neither {@code refpalette} nor a local {@code palette}, and no style lists {@code oilrig} in a
	 * random-palette group. So those characters resolve to nothing and the rig generates as mostly
	 * air. The one-line fix is {@code "refpalette": "oilrig"} on each {@code buildings/oilrig*.json}.
	 */
	private static final Set<String> BUILDINGS_MISSING_THEIR_PALETTE = Set.of(
			"oilrig00", "oilrig01", "oilrig10", "oilrig11");

	private static final Path LOSTCITIES = locateDatapack();

	// ------------------------------------------------------------------ the checks

	/**
	 * Every part parses and is a true rectangular prism: each slice has exactly {@code zsize} rows and
	 * each row exactly {@code xsize} characters.
	 */
	@Test
	void everySliceIsZsizeRowsOfXsizeCharacters() {
		List<Path> parts = jsonFilesIn("parts");
		assertFalse(parts.isEmpty(), "no parts found under " + LOSTCITIES.resolve("parts"));
		for (Path file : parts) {
			JsonObject part = json(file);
			int xsize = intField(file, part, "xsize");
			int zsize = intField(file, part, "zsize");
			JsonArray slices = array(file, part, "slices");
			assertTrue(slices.size() > 0, where(file) + ": has no slices at all");
			for (int s = 0; s < slices.size(); s++) {
				final int sliceIndex = s;
				JsonArray rows = asArray(file, slices.get(s), "slice " + s);
				assertEquals(zsize, rows.size(),
						() -> where(file) + " slice " + sliceIndex + ": " + rows.size()
								+ " rows, but zsize is " + zsize
								+ " (the codec flattens a slice into one string, so a wrong row count "
								+ "shifts every cell after it)");
				for (int z = 0; z < rows.size(); z++) {
					final int rowIndex = z;
					String row = rows.get(z).getAsString();
					assertEquals(xsize, row.length(),
							() -> where(file) + " slice " + sliceIndex + " row " + rowIndex + ": "
									+ row.length() + " characters, but xsize is " + xsize
									+ " -> \"" + row + "\"");
				}
			}
		}
	}

	/** Every part named by a building's {@code parts} or {@code parts2} list actually ships. */
	@Test
	void everyPartReferencedByABuildingExists() {
		List<Path> buildings = jsonFilesIn("buildings");
		assertFalse(buildings.isEmpty(), "no buildings found under " + LOSTCITIES.resolve("buildings"));
		for (Path file : buildings) {
			JsonObject building = json(file);
			for (String list : List.of("parts", "parts2")) {
				for (JsonObject ref : partRefs(building, list)) {
					String name = ref.get("part").getAsString();
					assertTrue(Files.isRegularFile(partFile(name)),
							where(file) + " " + list + ": references part '" + name
									+ "' but parts/" + name + ".json does not exist");
				}
			}
		}
	}

	/**
	 * Every part in a building's storey stack — referenced from {@code parts} without
	 * {@code "top": true} — has exactly {@link #FLOOR_HEIGHT} slices.
	 *
	 * <p>Tops are excluded because nothing is stacked above them, and {@code parts2} is excluded
	 * because an overlay is drawn over a storey rather than as one. Both are legitimately shorter in
	 * the shipped data (for example {@code top4_3} is a single slice).
	 */
	@Test
	void everyStoreyPartHasExactlySixSlices() {
		for (Path file : jsonFilesIn("buildings")) {
			String buildingName = assetName(file);
			JsonObject building = json(file);
			boolean exempt = MONOLITHIC_SCATTERED_BUILDINGS.contains(buildingName);
			boolean sawViolation = false;
			for (JsonObject ref : partRefs(building, "parts")) {
				if (isTrue(ref, "top")) {
					continue;
				}
				String name = ref.get("part").getAsString();
				Path part = partFile(name);
				if (!Files.isRegularFile(part)) {
					continue;   // reported by everyPartReferencedByABuildingExists
				}
				int slices = array(part, json(part), "slices").size();
				if (slices == FLOOR_HEIGHT) {
					continue;
				}
				sawViolation = true;
				assertTrue(exempt, where(file) + ": storey part '" + name + "' has " + slices
						+ " slices, but the engine stacks storeys every " + FLOOR_HEIGHT
						+ " blocks and ignores a part's own height"
						+ (slices < FLOOR_HEIGHT ? " -> a gap between floors" : " -> the extra"
						+ " slices are overwritten by the storey above"));
			}
			assertFalse(exempt && !sawViolation, staleExemption(buildingName,
					"MONOLITHIC_SCATTERED_BUILDINGS", "its storey parts are now all "
							+ FLOOR_HEIGHT + " slices"));
		}
	}

	/**
	 * Every weighted {@code blocks} list fills the 128-slot random table exactly once.
	 *
	 * <p>{@code CompiledPalette} writes each entry into {@code random} <em>literal consecutive
	 * slots</em> in list order and throws at asset-load time if the table is not full, so the weights
	 * must sum to at least 128 — the trailing {@code "random": 1000} idiom is "fill the rest", not a
	 * rare fallback. It also stops as soon as the table is full, so an entry whose predecessors
	 * already reached 128 gets zero slots and is dead data.
	 */
	@Test
	void everyWeightedBlockListFillsTheRandomTableExactlyOnce() {
		int checked = 0;
		for (Map.Entry<String, JsonArray> entry : everyWeightedBlockList().entrySet()) {
			String origin = entry.getKey();
			JsonArray blocks = entry.getValue();
			assertTrue(blocks.size() > 0, origin + ": empty \"blocks\" list");
			int used = 0;
			for (int i = 0; i < blocks.size(); i++) {
				JsonObject block = blocks.get(i).getAsJsonObject();
				assertTrue(block.has("random"),
						origin + " block " + i + ": no \"random\" weight (the codec requires one)");
				int weight = block.get("random").getAsInt();
				assertTrue(weight > 0, origin + " block " + i + ": weight " + weight + " is not positive");
				assertTrue(used < RANDOM_TABLE_SIZE,
						origin + " block " + i + " (" + describeBlock(block) + "): the entries before it"
								+ " already fill all " + RANDOM_TABLE_SIZE + " slots, so it can never be"
								+ " picked");
				used += weight;
			}
			assertTrue(used >= RANDOM_TABLE_SIZE,
					origin + ": weights sum to " + used + ", which leaves the " + RANDOM_TABLE_SIZE
							+ "-slot random table short - CompiledPalette throws \"Not enough blocks in"
							+ " the random list\" at asset load and the whole datapack fails");
			checked++;
		}
		assertTrue(checked > 0, "found no weighted block lists at all - the walk is broken");
	}

	/**
	 * Every character a building's parts use resolves to something.
	 *
	 * <p>An undefined character is not an error at runtime: {@code BuildingEngine} logs one warning
	 * and places air, so the building ships full of holes.
	 *
	 * <p>Resolution is deliberately conservative so this cannot cry wolf. A style is a list of
	 * <em>groups</em> and one palette is chosen per group, so a character is only guaranteed if
	 * <em>every</em> palette in its group defines it; a building must work in <em>every</em> style
	 * that can host it, so the guaranteed sets of its hosting styles are intersected. On top of that
	 * come the building's {@code refpalette}, its local palette and the part's own local palette, in
	 * the order {@code CompiledPalette} layers them. The hosting styles come from the citystyles that
	 * select the building (following {@code inherit} and expanding {@code multibuildings}), or, for a
	 * building a worldstyle scatters outside the cities, from every style a citystyle names.
	 *
	 * <p>Standalone parts — streets, parks, fountains, bridges, rails — are <b>not</b> checked: they
	 * are placed by their own structure piece against a palette that also carries citystyle-supplied
	 * characters ({@code S}, {@code b}, {@code B}, {@code y}, {@code w}) which live in no palette
	 * file. Only parts reachable from a building are checked, because only those resolve against a
	 * chain this test can reconstruct exactly.
	 */
	@Test
	void everyCharacterUsedByABuildingIsDefined() {
		CityStyles cityStyles = CityStyles.load();
		Map<String, Set<Character>> guaranteedByStyle = new TreeMap<>();
		int checkedBuildings = 0;
		for (Path file : jsonFilesIn("buildings")) {
			String buildingName = assetName(file);
			JsonObject building = json(file);
			Set<String> styles = cityStyles.stylesHosting(buildingName);
			if (styles.isEmpty()) {
				continue;   // named by no citystyle and no worldstyle: nothing to resolve against
			}
			Set<Character> defined = null;
			for (String style : styles) {
				Set<Character> guaranteed = guaranteedByStyle.computeIfAbsent(style,
						DatapackGeometryTest::guaranteedCharacters);
				if (defined == null) {
					defined = new TreeSet<>(guaranteed);
				} else {
					defined.retainAll(guaranteed);
				}
			}
			if (building.has("refpalette")) {
				defined.addAll(charactersOf(json(paletteFile(building.get("refpalette").getAsString()))));
			}
			defined.addAll(charactersOf(building));
			defined.add(' ');

			boolean exempt = BUILDINGS_MISSING_THEIR_PALETTE.contains(buildingName);
			boolean sawViolation = false;
			for (String list : List.of("parts", "parts2")) {
				for (JsonObject ref : partRefs(building, list)) {
					String partName = ref.get("part").getAsString();
					Path partPath = partFile(partName);
					if (!Files.isRegularFile(partPath)) {
						continue;   // reported by everyPartReferencedByABuildingExists
					}
					JsonObject part = json(partPath);
					Set<Character> known = new TreeSet<>(defined);
					known.addAll(charactersOf(part));
					JsonArray slices = array(partPath, part, "slices");
					for (int s = 0; s < slices.size(); s++) {
						JsonArray rows = slices.get(s).getAsJsonArray();
						for (int z = 0; z < rows.size(); z++) {
							String row = rows.get(z).getAsString();
							for (int x = 0; x < row.length(); x++) {
								char c = row.charAt(x);
								if (known.contains(c)) {
									continue;
								}
								sawViolation = true;
								assertTrue(exempt, where(partPath) + " slice " + s + " row " + z
										+ " column " + x + ": character '" + c + "' is not defined by"
										+ " any palette " + buildingName + " resolves against "
										+ styles + " - it silently becomes air. Row: \"" + row + "\"");
							}
						}
					}
				}
			}
			assertFalse(exempt && !sawViolation, staleExemption(buildingName,
					"BUILDINGS_MISSING_THEIR_PALETTE", "all of its characters now resolve"));
			checkedBuildings++;
		}
		assertTrue(checkedBuildings > 0, "no building could be mapped to a style - the walk is broken");
	}

	// ------------------------------------------------------------------ datapack model

	/** The citystyle graph, flattened: which styles can host which buildings. */
	private record CityStyles(Map<String, Set<String>> byBuilding) {

		static CityStyles load() {
			Map<String, JsonObject> files = new LinkedHashMap<>();
			for (Path file : jsonFilesIn("citystyles")) {
				files.put(assetName(file), json(file));
			}
			Map<String, Set<String>> byBuilding = new TreeMap<>();
			Set<String> everyCityStyle = new TreeSet<>();
			for (Map.Entry<String, JsonObject> entry : files.entrySet()) {
				String style = inheritedString(files, entry.getKey(), "style");
				if (style == null) {
					continue;   // a fragment such as citystyle_config, named by no city
				}
				everyCityStyle.add(style);
				for (String building : selectorValues(files, entry.getKey(), "buildings")) {
					byBuilding.computeIfAbsent(building, k -> new TreeSet<>()).add(style);
				}
				for (String multi : selectorValues(files, entry.getKey(), "multibuildings")) {
					for (String quadrant : quadrantsOf(multi)) {
						byBuilding.computeIfAbsent(quadrant, k -> new TreeSet<>()).add(style);
					}
				}
			}
			// Scattered structures (cabin, radiotower, oil rig) stand outside a city, so no citystyle
			// selects them - a worldstyle's "scattered" list does. They still take their palette from a
			// style: ScatteredStructure asks StyleSelector.styleFor(biome), which returns the style of
			// whatever citystyle that biome would have used. So any of them can host one.
			for (Path file : jsonFilesIn("worldstyles")) {
				JsonObject scattered = json(file).getAsJsonObject("scattered");
				if (scattered == null || !scattered.has("list")) {
					continue;
				}
				for (JsonElement element : scattered.getAsJsonArray("list")) {
					String name = element.getAsJsonObject().get("name").getAsString();
					for (String building : scatteredBuildings(name)) {
						byBuilding.computeIfAbsent(building, k -> new TreeSet<>()).addAll(everyCityStyle);
					}
				}
			}
			return new CityStyles(byBuilding);
		}

		/** A scattered entry names either one building or a whole multibuilding layout. */
		private static List<String> scatteredBuildings(String name) {
			if (Files.isRegularFile(LOSTCITIES.resolve("buildings").resolve(name + ".json"))) {
				return List.of(name);
			}
			if (Files.isRegularFile(LOSTCITIES.resolve("multibuildings").resolve(name + ".json"))) {
				return quadrantsOf(name);
			}
			return fail("a worldstyle scatters '" + name + "' but neither buildings/" + name
					+ ".json nor multibuildings/" + name + ".json exists");
		}

		Set<String> stylesHosting(String building) {
			return byBuilding.getOrDefault(building, Set.of());
		}

		/** Follows {@code inherit} until the field is found. */
		private static String inheritedString(Map<String, JsonObject> files, String name, String field) {
			for (String at = name; at != null; ) {
				JsonObject o = files.get(at);
				if (o == null) {
					return null;
				}
				if (o.has(field)) {
					return o.get(field).getAsString();
				}
				at = o.has("inherit") ? o.get("inherit").getAsString() : null;
			}
			return null;
		}

		/** Accumulates a selector list down the whole {@code inherit} chain. */
		private static List<String> selectorValues(Map<String, JsonObject> files, String name, String kind) {
			List<String> values = new ArrayList<>();
			for (String at = name; at != null; ) {
				JsonObject o = files.get(at);
				if (o == null) {
					break;
				}
				JsonObject selectors = o.getAsJsonObject("selectors");
				if (selectors != null && selectors.has(kind)) {
					for (JsonElement e : selectors.getAsJsonArray(kind)) {
						values.add(e.getAsJsonObject().get("value").getAsString());
					}
				}
				at = o.has("inherit") ? o.get("inherit").getAsString() : null;
			}
			return values;
		}
	}

	private static List<String> quadrantsOf(String multiBuilding) {
		Path file = LOSTCITIES.resolve("multibuildings").resolve(multiBuilding + ".json");
		if (!Files.isRegularFile(file)) {
			return fail("a citystyle selects multibuilding '" + multiBuilding + "' but " + file
					+ " does not exist");
		}
		List<String> quadrants = new ArrayList<>();
		for (JsonElement row : array(file, json(file), "buildings")) {
			for (JsonElement cell : row.getAsJsonArray()) {
				quadrants.add(cell.getAsString());
			}
		}
		return quadrants;
	}

	/**
	 * Characters a style is <em>guaranteed</em> to define: the union over its random-palette groups
	 * of the characters common to every palette in that group. A character defined by only some
	 * palettes of a group is missing whenever one of the others is rolled.
	 */
	private static Set<Character> guaranteedCharacters(String styleName) {
		Path file = LOSTCITIES.resolve("styles").resolve(styleName + ".json");
		if (!Files.isRegularFile(file)) {
			return fail("a citystyle names style '" + styleName + "' but " + file + " does not exist");
		}
		Set<Character> guaranteed = new TreeSet<>();
		for (JsonElement groupElement : array(file, json(file), "randompalettes")) {
			Set<Character> common = null;
			for (JsonElement selector : groupElement.getAsJsonArray()) {
				String palette = selector.getAsJsonObject().get("palette").getAsString();
				Set<Character> chars = charactersOf(json(paletteFile(palette)));
				if (common == null) {
					common = new TreeSet<>(chars);
				} else {
					common.retainAll(chars);
				}
			}
			if (common != null) {
				guaranteed.addAll(common);
			}
		}
		return guaranteed;
	}

	/**
	 * Every weighted {@code blocks} list in the datapack, keyed by a human-readable origin. Covers
	 * shared palettes, {@code variants/} (a variant file <em>is</em> a blocks list and goes through
	 * the same table) and the local palettes hanging off buildings and parts.
	 */
	private static Map<String, JsonArray> everyWeightedBlockList() {
		Map<String, JsonArray> lists = new LinkedHashMap<>();
		for (Path file : jsonFilesIn("variants")) {
			JsonObject variant = json(file);
			if (variant.has("blocks")) {
				lists.put(where(file), variant.getAsJsonArray("blocks"));
			}
		}
		for (Path file : jsonFilesIn("palettes")) {
			collectWeighted(lists, file, json(file));
		}
		for (String dir : List.of("buildings", "parts")) {
			for (Path file : jsonFilesIn(dir)) {
				JsonObject owner = json(file);
				if (owner.has("palette")) {
					collectWeighted(lists, file, owner.getAsJsonObject("palette"));
				}
			}
		}
		return lists;
	}

	/** Pulls the {@code blocks} lists out of one {@code {"palette": [...]}} container. */
	private static void collectWeighted(Map<String, JsonArray> into, Path file, JsonObject container) {
		JsonArray entries = container.getAsJsonArray("palette");
		if (entries == null) {
			return;
		}
		for (JsonElement element : entries) {
			JsonObject entry = element.getAsJsonObject();
			if (entry.has("blocks")) {
				into.put(where(file) + " char '" + entry.get("char").getAsString() + "'",
						entry.getAsJsonArray("blocks"));
			}
		}
	}

	/** The characters an object's palette defines, whether it is a palette file or a local palette. */
	private static Set<Character> charactersOf(JsonObject owner) {
		JsonArray entries = owner.has("palette") && owner.get("palette").isJsonObject()
				? owner.getAsJsonObject("palette").getAsJsonArray("palette")
				: owner.getAsJsonArray("palette");
		Set<Character> chars = new LinkedHashSet<>();
		if (entries == null) {
			return chars;
		}
		for (JsonElement element : entries) {
			String c = element.getAsJsonObject().get("char").getAsString();
			if (!c.isEmpty()) {
				chars.add(c.charAt(0));
			}
		}
		return chars;
	}

	private static List<JsonObject> partRefs(JsonObject building, String list) {
		JsonArray refs = building.getAsJsonArray(list);
		List<JsonObject> result = new ArrayList<>();
		if (refs != null) {
			for (JsonElement element : refs) {
				result.add(element.getAsJsonObject());
			}
		}
		return result;
	}

	private static boolean isTrue(JsonObject o, String field) {
		return o.has(field) && o.get(field).getAsBoolean();
	}

	private static String describeBlock(JsonObject block) {
		return block.has("block") ? block.get("block").getAsString() : block.toString();
	}

	private static String staleExemption(String name, String constant, String because) {
		return "'" + name + "' is listed in " + constant + " but " + because
				+ ". The defect is fixed: delete it from the exemption list so the check stays honest.";
	}

	// ------------------------------------------------------------------ plumbing

	/**
	 * The datapack directory, found by walking up from the working directory. Gradle runs tests with
	 * the module as the working directory, but resolving it this way also works from the repository
	 * root and from an IDE.
	 */
	private static Path locateDatapack() {
		Path start = Path.of("").toAbsolutePath();
		String suffix = "src/main/resources/data/lostbuildings/lostcities";
		for (Path dir = start; dir != null; dir = dir.getParent()) {
			for (Path candidate : List.of(dir.resolve(suffix), dir.resolve("lostbuildings").resolve(suffix))) {
				if (Files.isDirectory(candidate)) {
					return candidate;
				}
			}
		}
		return fail("could not find " + suffix + " anywhere at or above " + start);
	}

	private static Path partFile(String name) {
		return LOSTCITIES.resolve("parts").resolve(name + ".json");
	}

	private static Path paletteFile(String name) {
		Path file = LOSTCITIES.resolve("palettes").resolve(name + ".json");
		if (!Files.isRegularFile(file)) {
			return fail("palette '" + name + "' is referenced but " + file + " does not exist");
		}
		return file;
	}

	private static List<Path> jsonFilesIn(String directory) {
		Path dir = LOSTCITIES.resolve(directory);
		assertTrue(Files.isDirectory(dir), "missing datapack directory " + dir);
		try (Stream<Path> files = Files.list(dir)) {
			return files.filter(p -> p.getFileName().toString().endsWith(".json"))
					.sorted(Comparator.comparing(Path::toString))
					.toList();
		} catch (IOException e) {
			return fail("could not list " + dir, e);
		}
	}

	/** {@code parts/building3_ground.json} — short enough to read, long enough to find. */
	private static String where(Path file) {
		return LOSTCITIES.relativize(file).toString().replace('\\', '/');
	}

	private static String assetName(Path file) {
		String name = file.getFileName().toString();
		return name.substring(0, name.length() - ".json".length());
	}

	private static JsonObject json(Path file) {
		String text;
		try {
			text = Files.readString(file, StandardCharsets.UTF_8);
		} catch (IOException e) {
			return fail("could not read " + where(file), e);
		}
		try {
			JsonElement parsed = JsonParser.parseString(text);
			assertTrue(parsed.isJsonObject(), where(file) + ": top level is not a JSON object");
			return parsed.getAsJsonObject();
		} catch (RuntimeException e) {
			return fail(where(file) + ": is not valid JSON (" + e.getMessage() + ")", e);
		}
	}

	private static int intField(Path file, JsonObject o, String field) {
		assertTrue(o.has(field), where(file) + ": no \"" + field + "\"");
		return o.get(field).getAsInt();
	}

	private static JsonArray array(Path file, JsonObject o, String field) {
		JsonArray a = o.getAsJsonArray(field);
		assertNotNull(a, where(file) + ": no \"" + field + "\" array");
		return a;
	}

	private static JsonArray asArray(Path file, JsonElement element, String what) {
		assertTrue(element.isJsonArray(), where(file) + ": " + what + " is not an array");
		return element.getAsJsonArray();
	}
}
