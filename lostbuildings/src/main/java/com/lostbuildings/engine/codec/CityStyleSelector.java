package com.lostbuildings.engine.codec;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Optional;

/**
 * "In these biomes, use this city style, with this weight." One entry of a world style's
 * {@code citystyles} list.
 */
public record CityStyleSelector(float factor, String cityStyle, BiomeMatcher biomes) {

	public static final Codec<CityStyleSelector> CODEC = RecordCodecBuilder.create(instance ->
			instance.group(
					Codec.FLOAT.fieldOf("factor").forGetter(CityStyleSelector::factor),
					Codec.STRING.fieldOf("citystyle").forGetter(CityStyleSelector::cityStyle),
					BiomeMatcher.CODEC.optionalFieldOf("biomes").forGetter(l -> l.biomes.isAny()
							? Optional.<BiomeMatcher>empty() : Optional.of(l.biomes))
			).apply(instance, (factor, cityStyle, biomes) ->
					new CityStyleSelector(factor, cityStyle, biomes.orElse(BiomeMatcher.ANY))));

	public CityStyleSelector {
		biomes = biomes == null ? BiomeMatcher.ANY : biomes;
	}
}
