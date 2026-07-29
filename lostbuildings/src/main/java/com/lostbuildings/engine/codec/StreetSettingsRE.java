package com.lostbuildings.engine.codec;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * A city style's street settings: the palette characters a street is paved with and how wide it is.
 *
 * <p>Consumed by agent G's {@code StreetPiece} (PHASE3-PLAN §6.2/§6.5); the engine only loads it.
 * The original's {@code parts} block (which named the street tiles by hand) is not ported —
 * G selects those from the neighbour mask instead, which is the whole point of §6.2.
 */
public record StreetSettingsRE(@Nullable Integer width,
                               @Nullable Character street, @Nullable Character streetBase,
                               @Nullable Character streetVariant, @Nullable Character border,
                               @Nullable Character wall,
                               @Nullable Float fountainChance, @Nullable Float frontChance) {

	public static final Codec<StreetSettingsRE> CODEC = RecordCodecBuilder.create(instance ->
			instance.group(
					Codec.INT.optionalFieldOf("width").forGetter(l -> Optional.ofNullable(l.width)),
					Codec.STRING.optionalFieldOf("street").forGetter(l -> DataTools.toNullable(l.street)),
					Codec.STRING.optionalFieldOf("streetbase").forGetter(l -> DataTools.toNullable(l.streetBase)),
					Codec.STRING.optionalFieldOf("streetvariant").forGetter(l -> DataTools.toNullable(l.streetVariant)),
					Codec.STRING.optionalFieldOf("border").forGetter(l -> DataTools.toNullable(l.border)),
					Codec.STRING.optionalFieldOf("wall").forGetter(l -> DataTools.toNullable(l.wall)),
					Codec.FLOAT.optionalFieldOf("fountainchance").forGetter(l -> Optional.ofNullable(l.fountainChance)),
					Codec.FLOAT.optionalFieldOf("frontchance").forGetter(l -> Optional.ofNullable(l.frontChance))
			).apply(instance, (width, street, base, variant, border, wall, fountain, front) ->
					new StreetSettingsRE(width.orElse(null),
							DataTools.getNullableChar(street), DataTools.getNullableChar(base),
							DataTools.getNullableChar(variant), DataTools.getNullableChar(border),
							DataTools.getNullableChar(wall),
							fountain.orElse(null), front.orElse(null))));

	@Nullable
	public static StreetSettingsRE merge(@Nullable StreetSettingsRE child, @Nullable StreetSettingsRE parent) {
		if (child == null) {
			return parent;
		}
		if (parent == null) {
			return child;
		}
		return new StreetSettingsRE(
				child.width != null ? child.width : parent.width,
				child.street != null ? child.street : parent.street,
				child.streetBase != null ? child.streetBase : parent.streetBase,
				child.streetVariant != null ? child.streetVariant : parent.streetVariant,
				child.border != null ? child.border : parent.border,
				child.wall != null ? child.wall : parent.wall,
				child.fountainChance != null ? child.fountainChance : parent.fountainChance,
				child.frontChance != null ? child.frontChance : parent.frontChance);
	}
}
