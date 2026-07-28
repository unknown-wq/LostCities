package com.lostbuildings.engine.codec;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Optional;

/**
 * "Cities are this much more (or less) likely in these biomes." One entry of a world style's
 * {@code citybiomemultipliers} list — the shipped data uses it to keep cities out of oceans and
 * rivers.
 */
public record CityBiomeMultiplier(float multiplier, BiomeMatcher biomes) {

	public static final Codec<CityBiomeMultiplier> CODEC = RecordCodecBuilder.create(instance ->
			instance.group(
					Codec.FLOAT.fieldOf("multiplier").forGetter(CityBiomeMultiplier::multiplier),
					BiomeMatcher.CODEC.optionalFieldOf("biomes").forGetter(l -> l.biomes.isAny()
							? Optional.<BiomeMatcher>empty() : Optional.of(l.biomes))
			).apply(instance, (multiplier, biomes) ->
					new CityBiomeMultiplier(multiplier, biomes.orElse(BiomeMatcher.ANY))));

	public CityBiomeMultiplier {
		biomes = biomes == null ? BiomeMatcher.ANY : biomes;
	}
}
