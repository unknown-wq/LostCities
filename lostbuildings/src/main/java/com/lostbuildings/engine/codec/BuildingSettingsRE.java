package com.lostbuildings.engine.codec;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * A city style's building settings: the floor/cellar range and how likely a plot is built on at all.
 *
 * <p>Every field is nullable on purpose — "not specified" has to survive the inheritance merge, and
 * a boxed {@code null} is what says "take the parent's value" (see {@link #merge}).
 */
public record BuildingSettingsRE(@Nullable Integer minFloors, @Nullable Integer maxFloors,
                                 @Nullable Integer minCellars, @Nullable Integer maxCellars,
                                 @Nullable Float buildingChance) {

	public static final Codec<BuildingSettingsRE> CODEC = RecordCodecBuilder.create(instance ->
			instance.group(
					Codec.INT.optionalFieldOf("minfloors").forGetter(l -> Optional.ofNullable(l.minFloors)),
					Codec.INT.optionalFieldOf("maxfloors").forGetter(l -> Optional.ofNullable(l.maxFloors)),
					Codec.INT.optionalFieldOf("mincellars").forGetter(l -> Optional.ofNullable(l.minCellars)),
					Codec.INT.optionalFieldOf("maxcellars").forGetter(l -> Optional.ofNullable(l.maxCellars)),
					Codec.FLOAT.optionalFieldOf("buildingchance").forGetter(l -> Optional.ofNullable(l.buildingChance))
			).apply(instance, (a, b, c, d, e) -> new BuildingSettingsRE(
					a.orElse(null), b.orElse(null), c.orElse(null), d.orElse(null), e.orElse(null))));

	/** Field-by-field override: whatever {@code child} defines wins, the rest comes from the parent. */
	@Nullable
	public static BuildingSettingsRE merge(@Nullable BuildingSettingsRE child, @Nullable BuildingSettingsRE parent) {
		if (child == null) {
			return parent;
		}
		if (parent == null) {
			return child;
		}
		return new BuildingSettingsRE(
				child.minFloors != null ? child.minFloors : parent.minFloors,
				child.maxFloors != null ? child.maxFloors : parent.maxFloors,
				child.minCellars != null ? child.minCellars : parent.minCellars,
				child.maxCellars != null ? child.maxCellars : parent.maxCellars,
				child.buildingChance != null ? child.buildingChance : parent.buildingChance);
	}
}
