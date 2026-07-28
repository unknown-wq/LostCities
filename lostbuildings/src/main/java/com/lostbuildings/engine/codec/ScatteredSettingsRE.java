package com.lostbuildings.engine.codec;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;
import java.util.Optional;

/**
 * A world style's table of scattered (outside-the-city) structures: cabin, radio tower, oil rig.
 *
 * <p>Loaded here so that agent G has the data for PORT #2 (PHASE3-PLAN §6.7); the engine itself
 * never places a scattered structure. Placement in phase 3 is a vanilla {@code StructureSet}, so
 * {@code areasize} and {@code chance} are advisory — kept because they carry the author's intent
 * about how common these are.
 */
public record ScatteredSettingsRE(int areaSize, float chance, int weightNone, List<Reference> list) {

	public static final Codec<ScatteredSettingsRE> CODEC = RecordCodecBuilder.create(instance ->
			instance.group(
					Codec.INT.optionalFieldOf("areasize", 8).forGetter(ScatteredSettingsRE::areaSize),
					Codec.FLOAT.optionalFieldOf("chance", 0.0f).forGetter(ScatteredSettingsRE::chance),
					Codec.INT.optionalFieldOf("weightnone", 0).forGetter(ScatteredSettingsRE::weightNone),
					Codec.list(Reference.CODEC).optionalFieldOf("list").forGetter(l -> Optional.of(l.list))
			).apply(instance, (areaSize, chance, weightNone, list) ->
					new ScatteredSettingsRE(areaSize, chance, weightNone, list.orElse(List.of()))));

	public ScatteredSettingsRE {
		list = list == null ? List.of() : List.copyOf(list);
	}

	/**
	 * One scattered structure candidate.
	 *
	 * @param name          the building (or multi-building) to place
	 * @param weight        relative likelihood against the other candidates and {@code weightnone}
	 * @param maxHeightDiff how uneven the ground under it may be
	 * @param biomes        where it may appear
	 */
	public record Reference(String name, int weight, int maxHeightDiff, BiomeMatcher biomes) {

		public static final Codec<Reference> CODEC = RecordCodecBuilder.create(instance ->
				instance.group(
						Codec.STRING.fieldOf("name").forGetter(Reference::name),
						Codec.INT.optionalFieldOf("weight", 1).forGetter(Reference::weight),
						Codec.INT.optionalFieldOf("maxheightdiff", 3).forGetter(Reference::maxHeightDiff),
						BiomeMatcher.CODEC.optionalFieldOf("biomes").forGetter(l -> l.biomes.isAny()
								? Optional.<BiomeMatcher>empty() : Optional.of(l.biomes))
				).apply(instance, (name, weight, maxHeightDiff, biomes) ->
						new Reference(name, weight, maxHeightDiff, biomes.orElse(BiomeMatcher.ANY))));

		public Reference {
			biomes = biomes == null ? BiomeMatcher.ANY : biomes;
		}
	}
}
