package com.lostbuildings.engine.codec;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * One weighted candidate in a city style's selector list ("this city style uses building3 with
 * weight 0.2").
 *
 * <p>Reduced port: the original also carried {@code minSpawnDistance}/{@code maxSpawnDistance}/
 * {@code feather}, which fade object types in as you travel away from spawn. Nothing in the shipped
 * data sets them (the original's own codec hard-coded the getters to constants), and distance-from-
 * spawn gating belongs to the layout pass, not to the engine — so they are dropped rather than
 * carried as dead weight.
 */
public record ObjectSelector(float factor, String value) {

	public static final Codec<ObjectSelector> CODEC = RecordCodecBuilder.create(instance ->
			instance.group(
					Codec.FLOAT.fieldOf("factor").forGetter(ObjectSelector::factor),
					Codec.STRING.fieldOf("value").forGetter(ObjectSelector::value)
			).apply(instance, ObjectSelector::new));
}
