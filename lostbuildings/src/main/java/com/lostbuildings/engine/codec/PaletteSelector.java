package com.lostbuildings.engine.codec;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * A weighted selector for a palette (used by a style).
 */
public record PaletteSelector(float factor, String palette) {

    public static final Codec<PaletteSelector> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.FLOAT.fieldOf("factor").forGetter(PaletteSelector::factor),
                    Codec.STRING.fieldOf("palette").forGetter(PaletteSelector::palette)
            ).apply(instance, PaletteSelector::new));
}
