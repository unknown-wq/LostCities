package com.lostbuildings.engine.condition;

import com.lostbuildings.engine.codec.ConditionTest;

import java.util.Set;

/**
 * Evaluates a {@code ConditionTest} — the {@code top / ground / cellar / floor / range / …} block
 * that data attaches to a part reference or to a loot/mob selector — against a
 * {@link ConditionContext}.
 *
 * <p>This is the honest resolution that {@code PORT-STATUS.md} → "Disabled content" recorded as
 * missing ("Condition (loot/mob) resolution: factor-weighted pick only; position/part filters
 * ignored"). What is honoured now:
 *
 * <table>
 *   <tr><th>field</th><th>behaviour</th></tr>
 *   <tr><td>{@code top}</td><td>storey == the building's top storey</td></tr>
 *   <tr><td>{@code ground}</td><td>storey == 0</td></tr>
 *   <tr><td>{@code cellar}</td><td>storey &lt; 0</td></tr>
 *   <tr><td>{@code floor}</td><td>storey equality (negative values address cellars)</td></tr>
 *   <tr><td>{@code range}</td><td>{@code "a,b"} inclusive storey range</td></tr>
 *   <tr><td>{@code inpart}</td><td>name of the part currently being placed</td></tr>
 *   <tr><td>{@code inbuilding}</td><td>name of the building being placed</td></tr>
 *   <tr><td>{@code chunkx} / {@code chunkz}</td><td>the cell's chunk coordinate</td></tr>
 *   <tr><td>{@code isbuilding}</td><td>always true — the engine only ever builds buildings</td></tr>
 *   <tr><td>{@code issphere}</td><td>always false — city spheres are deferred to phase 4</td></tr>
 * </table>
 *
 * <p><b>Still ignored</b> (treated as satisfied, and logged as a §9 cut): {@code belowpart}, which
 * would need the engine to remember the previous storey's part across the whole stack, and
 * {@code inbiome}, which cannot be answered without a biome read — and biome reads during worldgen
 * are exactly what {@code WorldGenBounds} exists to prevent.
 *
 * <p>Pure logic, no Minecraft types: {@code EngineConditionTest} covers it.
 */
public final class ConditionMatcher {

	private ConditionMatcher() {
	}

	public static boolean matches(ConditionTest test, ConditionContext ctx) {
		if (test == null) {
			return true;
		}
		if (!matchesBoolean(test.getTop(), ctx.isTop())) {
			return false;
		}
		if (!matchesBoolean(test.getGround(), ctx.isGround())) {
			return false;
		}
		if (!matchesBoolean(test.getCellar(), ctx.isCellar())) {
			return false;
		}
		// The engine has no city spheres (phase 4) and never generates anything that is not a
		// building, so these two have a definite answer rather than a shrug.
		if (!matchesBoolean(test.getIsbuilding(), true)) {
			return false;
		}
		if (!matchesBoolean(test.getIssphere(), false)) {
			return false;
		}
		if (test.getFloor() != null && test.getFloor() != ctx.floor()) {
			return false;
		}
		if (test.getChunkx() != null && test.getChunkx() != ctx.chunkX()) {
			return false;
		}
		if (test.getChunkz() != null && test.getChunkz() != ctx.chunkZ()) {
			return false;
		}
		if (!inRange(test.getRange(), ctx.floor())) {
			return false;
		}
		if (!matchesName(test.getInpart(), ctx.partName())) {
			return false;
		}
		return matchesName(test.getInbuilding(), ctx.buildingName());
	}

	private static boolean matchesBoolean(Boolean expected, boolean actual) {
		return expected == null || expected == actual;
	}

	/**
	 * A {@code "a,b"} storey range, inclusive at both ends. A malformed range is ignored rather
	 * than fatal — a broken third-party datapack must never stop a chunk from generating.
	 */
	public static boolean inRange(String range, int floor) {
		if (range == null) {
			return true;
		}
		int comma = range.indexOf(',');
		if (comma < 0) {
			return true;
		}
		try {
			int low = Integer.parseInt(range.substring(0, comma).trim());
			int high = Integer.parseInt(range.substring(comma + 1).trim());
			if (low > high) {
				int tmp = low;
				low = high;
				high = tmp;
			}
			return floor >= low && floor <= high;
		} catch (NumberFormatException e) {
			return true;
		}
	}

	/**
	 * Set membership, tolerant of the {@code lostcities:} namespace that data references carry.
	 * A null set means "no constraint"; a constraint against an unknown name never matches.
	 */
	private static boolean matchesName(Set<String> allowed, String name) {
		if (allowed == null || allowed.isEmpty()) {
			return true;
		}
		if (name == null) {
			return false;
		}
		for (String candidate : allowed) {
			if (candidate == null) {
				continue;
			}
			if (candidate.equals(name) || strip(candidate).equals(strip(name))) {
				return true;
			}
		}
		return false;
	}

	private static String strip(String name) {
		int colon = name.indexOf(':');
		return colon < 0 ? name : name.substring(colon + 1);
	}
}
