package com.lostbuildings.engine.codec;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;
import java.util.Optional;

/**
 * A biome filter as it appears in the Lost Cities data: two lists of biome ids or {@code #tag}
 * references.
 *
 * <p>The engine keeps them as <b>raw strings</b> and does not resolve them. Resolution needs a
 * registry lookup and a biome read, and biome reads during worldgen are exactly what
 * {@code WorldGenBounds} exists to forbid; deciding <em>where</em> a style applies is also agent G's
 * half of PORT #9 (PHASE3-PLAN §5.5 / §6.8). This type exists so that G has the data to work with.
 *
 * @param ifAny     the biome must match at least one of these (empty list = no constraint)
 * @param excluding the biome must match none of these
 */
public record BiomeMatcher(List<String> ifAny, List<String> excluding) {

	public static final BiomeMatcher ANY = new BiomeMatcher(List.of(), List.of());

	public static final Codec<BiomeMatcher> CODEC = RecordCodecBuilder.create(instance ->
			instance.group(
					Codec.STRING.listOf().optionalFieldOf("if_any").forGetter(l -> optional(l.ifAny)),
					Codec.STRING.listOf().optionalFieldOf("excluding").forGetter(l -> optional(l.excluding))
			).apply(instance, (ifAny, excluding) ->
					new BiomeMatcher(ifAny.orElse(List.of()), excluding.orElse(List.of()))));

	public BiomeMatcher {
		ifAny = ifAny == null ? List.of() : List.copyOf(ifAny);
		excluding = excluding == null ? List.of() : List.copyOf(excluding);
	}

	public boolean isAny() {
		return ifAny.isEmpty() && excluding.isEmpty();
	}

	private static Optional<List<String>> optional(List<String> list) {
		return list == null || list.isEmpty() ? Optional.empty() : Optional.of(list);
	}
}
