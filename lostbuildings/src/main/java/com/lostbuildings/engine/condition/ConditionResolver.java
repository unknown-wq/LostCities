package com.lostbuildings.engine.condition;

import com.lostbuildings.engine.BuildingRole;
import com.lostbuildings.engine.codec.ConditionPart;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Picks a concrete value (a loot table id, a mob id) out of a condition's list of candidates.
 *
 * <p>Two things changed against the ported implementation, and both are IMPROVEMENTS #2:
 * <ol>
 *   <li>Candidates whose {@code ConditionTest} does not match the current position are
 *       <b>discarded</b> instead of being rolled anyway ({@link ConditionMatcher}). The shipped
 *       {@code chestloot} condition, for instance, only offers the mod's own city chest on storey 4
 *       and above or in a cellar (a {@code range} of {@code "4,100"} / {@code "-100,-3"}), and
 *       offers the rail-dungeon chest only inside the rail-dungeon parts. Before wave 2 both were
 *       simply in the bag everywhere.</li>
 *   <li>The remaining weights are multiplied by the {@link BuildingRole}'s taste, so a library
 *       really does hand out stronghold-library loot and a tower really does escalate.</li>
 * </ol>
 *
 * <p>The selection itself is a pure function of the candidate list, the context and a single roll
 * in {@code [0, 1)}; the {@link RandomSource} overload exists only to draw that roll, so the number
 * of values taken from the building's random stream is exactly one — the same as before.
 */
public final class ConditionResolver {

	/** Which kind of value is being resolved; decides which of the role's weight tables applies. */
	public enum Kind {
		LOOT,
		MOB,
		/** No role bias at all — plain weighted pick among the matching candidates. */
		PLAIN
	}

	private ConditionResolver() {
	}

	/**
	 * Resolve using the building's random source. Draws exactly one float, and only when there is
	 * something to choose from.
	 */
	@Nullable
	public static String resolve(List<ConditionPart> values, ConditionContext ctx, Kind kind, RandomSource rand) {
		if (values == null || values.isEmpty()) {
			return null;
		}
		return select(values, ctx, kind, rand.nextFloat());
	}

	/**
	 * The pure half: given a roll in {@code [0, 1)}, which candidate wins.
	 *
	 * <p>Returns {@code null} when the condition has nothing that fits here — the caller then leaves
	 * the block without loot / without a spawner rather than inventing something.
	 */
	@Nullable
	public static String select(List<ConditionPart> values, ConditionContext ctx, Kind kind, float roll) {
		if (values == null || values.isEmpty()) {
			return null;
		}
		List<ConditionPart> candidates = new ArrayList<>(values.size());
		List<Float> weights = new ArrayList<>(values.size());
		float total = 0.0f;
		for (ConditionPart part : values) {
			if (!ConditionMatcher.matches(part, ctx)) {
				continue;
			}
			float weight = part.getFactor() * bias(kind, ctx.role(), part.getValue());
			if (weight <= 0.0f) {
				continue;
			}
			candidates.add(part);
			weights.add(weight);
			total += weight;
		}
		if (candidates.isEmpty() || total <= 0.0f) {
			return null;
		}
		float r = clamp01(roll) * total;
		for (int i = 0; i < candidates.size(); i++) {
			r -= weights.get(i);
			if (r <= 0.0f) {
				return candidates.get(i).getValue();
			}
		}
		return candidates.get(candidates.size() - 1).getValue();
	}

	/** How many of a condition's candidates are usable at this position (diagnostics and tests). */
	public static int countMatching(List<ConditionPart> values, ConditionContext ctx) {
		if (values == null) {
			return 0;
		}
		int n = 0;
		for (ConditionPart part : values) {
			if (ConditionMatcher.matches(part, ctx)) {
				n++;
			}
		}
		return n;
	}

	private static float bias(Kind kind, BuildingRole role, String value) {
		return switch (kind) {
			case LOOT -> role.lootWeight(value);
			case MOB -> role.mobWeight(value);
			case PLAIN -> 1.0f;
		};
	}

	private static float clamp01(float v) {
		if (v < 0.0f) {
			return 0.0f;
		}
		return v >= 1.0f ? 0.99999f : v;
	}
}
