package com.lostbuildings.engine.codec;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A city style: which palette style, which buildings, how ruined — the datapack object that decides
 * what an entire city looks like (PORT #9). Loaded from
 * {@code data/lostbuildings/lostcities/citystyles/*.json}.
 *
 * <h2>Reduced port</h2>
 * The original carried seven settings blocks; three of them describe systems that phase 3 does not
 * have: {@code corridorblocks} (subway corridors), {@code railblocks} (the railway) and
 * {@code sphereblocks} (city spheres) — all three are explicitly deferred to phase 4 by
 * PHASE3-PLAN §1. DFU record codecs ignore unknown fields, so the shipped JSON still loads
 * unchanged, those blocks are simply not read. {@code generalblocks} and {@code stuff_tags} go the
 * same way: nothing in wave 2 or wave 3 consumes them.
 *
 * <p>What is kept is everything the city needs to be built: the palette style, the explosion
 * chance (which is exactly {@code PlaceSettings.damageChance} at the city level), the floor/cellar
 * settings, the street and park block characters, and the selector lists.
 */
public class CityStyleRE {

	public static final Codec<CityStyleRE> CODEC = RecordCodecBuilder.create(instance ->
			instance.group(
					Codec.FLOAT.optionalFieldOf("explosionchance").forGetter(l -> Optional.ofNullable(l.explosionChance)),
					Codec.STRING.optionalFieldOf("style").forGetter(l -> Optional.ofNullable(l.style)),
					Codec.STRING.optionalFieldOf("inherit").forGetter(l -> Optional.ofNullable(l.inherit)),
					BuildingSettingsRE.CODEC.optionalFieldOf("buildingsettings").forGetter(l -> Optional.ofNullable(l.buildingSettings)),
					StreetSettingsRE.CODEC.optionalFieldOf("streetblocks").forGetter(l -> Optional.ofNullable(l.streetSettings)),
					ParkSettingsRE.CODEC.optionalFieldOf("parkblocks").forGetter(l -> Optional.ofNullable(l.parkSettings)),
					SelectorsRE.CODEC.optionalFieldOf("selectors").forGetter(l -> Optional.ofNullable(l.selectors))
			).apply(instance, CityStyleRE::new));

	private Identifier name;

	private final Float explosionChance;
	private final String style;
	private final String inherit;
	private final BuildingSettingsRE buildingSettings;
	private final StreetSettingsRE streetSettings;
	private final ParkSettingsRE parkSettings;
	private final SelectorsRE selectors;

	public CityStyleRE(Optional<Float> explosionChance,
	                   Optional<String> style,
	                   Optional<String> inherit,
	                   Optional<BuildingSettingsRE> buildingSettings,
	                   Optional<StreetSettingsRE> streetSettings,
	                   Optional<ParkSettingsRE> parkSettings,
	                   Optional<SelectorsRE> selectors) {
		this.explosionChance = explosionChance.orElse(null);
		this.style = style.orElse(null);
		this.inherit = inherit.orElse(null);
		this.buildingSettings = buildingSettings.orElse(null);
		this.streetSettings = streetSettings.orElse(null);
		this.parkSettings = parkSettings.orElse(null);
		this.selectors = selectors.orElse(null);
	}

	@Nullable
	public Float getExplosionChance() {
		return explosionChance;
	}

	@Nullable
	public String getStyle() {
		return style;
	}

	/** Name of the city style this one extends, or {@code null}. Resolved by {@code Assets}. */
	@Nullable
	public String getInherit() {
		return inherit;
	}

	@Nullable
	public BuildingSettingsRE getBuildingSettings() {
		return buildingSettings;
	}

	@Nullable
	public StreetSettingsRE getStreetSettings() {
		return streetSettings;
	}

	@Nullable
	public ParkSettingsRE getParkSettings() {
		return parkSettings;
	}

	@Nullable
	public SelectorsRE getSelectors() {
		return selectors;
	}

	public CityStyleRE setRegistryName(Identifier name) {
		this.name = name;
		return this;
	}

	@Nullable
	public Identifier getRegistryName() {
		return name;
	}

	/**
	 * Fold this style onto the one it inherits from: anything this style does not define is taken
	 * from the parent. That is the whole of the shipped inheritance chain
	 * ({@code citystyle_standard} → {@code citystyle_common} → {@code citystyle_config}).
	 */
	public CityStyleRE inheritFrom(@Nullable CityStyleRE parent) {
		if (parent == null) {
			return this;
		}
		CityStyleRE merged = new CityStyleRE(
				Optional.ofNullable(explosionChance != null ? explosionChance : parent.explosionChance),
				Optional.ofNullable(style != null ? style : parent.style),
				Optional.empty(),   // the chain is already resolved
				Optional.ofNullable(BuildingSettingsRE.merge(buildingSettings, parent.buildingSettings)),
				Optional.ofNullable(StreetSettingsRE.merge(streetSettings, parent.streetSettings)),
				Optional.ofNullable(ParkSettingsRE.merge(parkSettings, parent.parkSettings)),
				Optional.ofNullable(SelectorsRE.merge(selectors, parent.selectors)));
		merged.name = this.name;
		return merged;
	}

	/** Every selector list of this style, keyed the way the JSON names them (diagnostics/tests). */
	public Map<String, List<ObjectSelector>> selectorLists() {
		return selectors == null ? Map.of() : selectors.asMap();
	}
}
