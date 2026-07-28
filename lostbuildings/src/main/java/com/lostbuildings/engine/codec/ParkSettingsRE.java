package com.lostbuildings.engine.codec;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * A city style's park settings. Consumed by agent G's {@code ParkPiece} (PHASE3-PLAN §6.3); the
 * engine only loads it.
 */
public record ParkSettingsRE(@Nullable Float parkChance, @Nullable Boolean parkBorder,
                             @Nullable Boolean parkElevation, @Nullable Character elevation,
                             @Nullable Character grass) {

	public static final Codec<ParkSettingsRE> CODEC = RecordCodecBuilder.create(instance ->
			instance.group(
					Codec.FLOAT.optionalFieldOf("parkchance").forGetter(l -> Optional.ofNullable(l.parkChance)),
					Codec.BOOL.optionalFieldOf("parkborder").forGetter(l -> Optional.ofNullable(l.parkBorder)),
					Codec.BOOL.optionalFieldOf("parkelevation").forGetter(l -> Optional.ofNullable(l.parkElevation)),
					Codec.STRING.optionalFieldOf("elevation").forGetter(l -> DataTools.toNullable(l.elevation)),
					Codec.STRING.optionalFieldOf("grass").forGetter(l -> DataTools.toNullable(l.grass))
			).apply(instance, (chance, border, elevated, elevation, grass) ->
					new ParkSettingsRE(chance.orElse(null), border.orElse(null), elevated.orElse(null),
							DataTools.getNullableChar(elevation), DataTools.getNullableChar(grass))));

	@Nullable
	public static ParkSettingsRE merge(@Nullable ParkSettingsRE child, @Nullable ParkSettingsRE parent) {
		if (child == null) {
			return parent;
		}
		if (parent == null) {
			return child;
		}
		return new ParkSettingsRE(
				child.parkChance != null ? child.parkChance : parent.parkChance,
				child.parkBorder != null ? child.parkBorder : parent.parkBorder,
				child.parkElevation != null ? child.parkElevation : parent.parkElevation,
				child.elevation != null ? child.elevation : parent.elevation,
				child.grass != null ? child.grass : parent.grass);
	}
}
