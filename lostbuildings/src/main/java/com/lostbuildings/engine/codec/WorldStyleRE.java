package com.lostbuildings.engine.codec;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * A world style: which city styles a world may use, and where (PORT #9). Loaded from
 * {@code data/lostbuildings/lostcities/worldstyles/*.json}.
 *
 * <h2>Reduced port</h2>
 * Kept: {@code outsidestyle}, {@code citystyles}, {@code citybiomemultipliers} and
 * {@code scattered} — everything a wave-3 city needs to choose its look, plus the scattered-
 * structure table agent G needs for PORT #2.
 *
 * <p>Dropped (unknown fields are ignored by the codec, so the shipped JSON still loads):
 * {@code cityspheres} (phase 4), {@code multisettings} and {@code settings} — the first describes
 * the original's multi-chunk "city area" walker, the second the railway/highway heights, and both
 * are answered in phase 3 by {@code LostCityConfig} + the vanilla {@code StructureSet} spacing
 * instead. {@code parts} likewise: part selection is the engine's own job.
 */
public class WorldStyleRE {

	public static final Codec<WorldStyleRE> CODEC = RecordCodecBuilder.create(instance ->
			instance.group(
					Codec.STRING.optionalFieldOf("outsidestyle").forGetter(l -> Optional.ofNullable(l.outsideStyle)),
					Codec.list(CityStyleSelector.CODEC).fieldOf("citystyles").forGetter(l -> l.cityStyles),
					Codec.list(CityBiomeMultiplier.CODEC).optionalFieldOf("citybiomemultipliers")
							.forGetter(l -> l.cityBiomeMultipliers.isEmpty()
									? Optional.<List<CityBiomeMultiplier>>empty() : Optional.of(l.cityBiomeMultipliers)),
					ScatteredSettingsRE.CODEC.optionalFieldOf("scattered").forGetter(l -> Optional.ofNullable(l.scattered))
			).apply(instance, WorldStyleRE::new));

	private Identifier name;
	private final String outsideStyle;
	private final List<CityStyleSelector> cityStyles;
	private final List<CityBiomeMultiplier> cityBiomeMultipliers;
	private final ScatteredSettingsRE scattered;

	public WorldStyleRE(Optional<String> outsideStyle,
	                    List<CityStyleSelector> cityStyles,
	                    Optional<List<CityBiomeMultiplier>> cityBiomeMultipliers,
	                    Optional<ScatteredSettingsRE> scattered) {
		this.outsideStyle = outsideStyle.orElse(null);
		this.cityStyles = cityStyles == null ? List.of() : List.copyOf(cityStyles);
		this.cityBiomeMultipliers = cityBiomeMultipliers.map(List::copyOf).orElse(List.of());
		this.scattered = scattered.orElse(null);
	}

	/** Palette style used outside cities (street furniture, scattered structures). */
	@Nullable
	public String getOutsideStyle() {
		return outsideStyle;
	}

	public List<CityStyleSelector> getCityStyles() {
		return cityStyles;
	}

	public List<CityBiomeMultiplier> getCityBiomeMultipliers() {
		return cityBiomeMultipliers;
	}

	@Nullable
	public ScatteredSettingsRE getScattered() {
		return scattered;
	}

	public WorldStyleRE setRegistryName(Identifier name) {
		this.name = name;
		return this;
	}

	@Nullable
	public Identifier getRegistryName() {
		return name;
	}
}
