package com.lostbuildings.engine;

import com.lostbuildings.LostBuildings;
import com.lostbuildings.engine.codec.CityStyleRE;
import com.lostbuildings.engine.codec.ConditionRE;
import com.lostbuildings.engine.codec.DataTools;
import com.lostbuildings.engine.codec.MultiBuildingRE;
import com.lostbuildings.engine.codec.VariantRE;
import com.lostbuildings.engine.codec.WorldStyleRE;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of all Lost Cities building assets loaded from the resource pack.
 * Assets are keyed by their bare name (a leading "lostcities:" namespace is stripped).
 */
public class Assets {

    private final Map<String, Building> buildings = new HashMap<>();
    private final Map<String, BuildingPart> parts = new HashMap<>();
    private final Map<String, Palette> palettes = new HashMap<>();
    private final Map<String, VariantRE> variants = new HashMap<>();
    private final Map<String, ConditionRE> conditions = new HashMap<>();
    private final Map<String, Style> styles = new TreeMap<>();
    private final Map<String, CityStyleRE> cityStyles = new TreeMap<>();
    private final Map<String, WorldStyleRE> worldStyles = new TreeMap<>();
    private final Map<String, MultiBuildingRE> multiBuildings = new TreeMap<>();

    /** Style names already reported as missing (so the warning is logged once, not per chunk). */
    private final Set<String> reportedMissingStyles = ConcurrentHashMap.newKeySet();

    public Map<String, VariantRE> getVariants() {
        return variants;
    }

    public Map<String, Building> getBuildings() {
        return buildings;
    }

    public Map<String, BuildingPart> getParts() {
        return parts;
    }

    public Map<String, Palette> getPalettes() {
        return palettes;
    }

    public Map<String, ConditionRE> getConditions() {
        return conditions;
    }

    public Map<String, Style> getStyles() {
        return styles;
    }

    public Map<String, CityStyleRE> getCityStyles() {
        return cityStyles;
    }

    public Map<String, WorldStyleRE> getWorldStyles() {
        return worldStyles;
    }

    public Map<String, MultiBuildingRE> getMultiBuildings() {
        return multiBuildings;
    }

    /**
     * Resolve every deferred reference now that everything is loaded. Doing this as a load-time pass
     * keeps {@link BuildingPart} and {@link Building} read-only during worldgen (they used to
     * resolve — and publish — the reference lazily from several threads), and it flattens the city
     * style {@code inherit} chains so that a lookup during worldgen is a single map hit.
     */
    public void resolveReferences() {
        for (BuildingPart part : parts.values()) {
            part.resolveLocalPalette(this);
        }
        for (Building building : buildings.values()) {
            building.resolveLocalPalette(this);
        }
        resolveCityStyleInheritance();
    }

    /**
     * Flatten {@code citystyle_standard → citystyle_common → citystyle_config} into one object per
     * style. Cycles (a hand-written datapack can make one) are broken and reported rather than
     * hanging the server on a load loop.
     */
    private void resolveCityStyleInheritance() {
        Map<String, CityStyleRE> resolved = new HashMap<>(cityStyles.size());
        for (Map.Entry<String, CityStyleRE> entry : cityStyles.entrySet()) {
            resolved.put(entry.getKey(), flatten(entry.getKey(), entry.getValue(), new HashSet<>()));
        }
        cityStyles.putAll(resolved);
    }

    private CityStyleRE flatten(String key, CityStyleRE style, Set<String> seen) {
        if (style == null || style.getInherit() == null || !seen.add(key)) {
            if (style != null && style.getInherit() != null) {
                LostBuildings.LOGGER.warn("[lostbuildings] City style '{}' inherits in a cycle - the chain is cut here", key);
            }
            return style;
        }
        String parentKey = DataTools.normalize(style.getInherit());
        CityStyleRE parent = cityStyles.get(parentKey);
        if (parent == null) {
            LostBuildings.LOGGER.warn("[lostbuildings] City style '{}' inherits from unknown '{}' - ignored", key, parentKey);
            return style;
        }
        return style.inheritFrom(flatten(parentKey, parent, seen));
    }

    public void putBuilding(String name, Building building) {
        buildings.put(name, building);
    }

    public void putPart(String name, BuildingPart part) {
        parts.put(name, part);
    }

    public void putPalette(String name, Palette palette) {
        palettes.put(name, palette);
    }

    public void putVariant(String name, VariantRE variant) {
        variants.put(name, variant);
    }

    public void putCondition(String name, ConditionRE condition) {
        conditions.put(name, condition);
    }

    public void putStyle(String name, Style style) {
        styles.put(name, style);
    }

    public void putCityStyle(String name, CityStyleRE cityStyle) {
        cityStyles.put(name, cityStyle);
    }

    public void putWorldStyle(String name, WorldStyleRE worldStyle) {
        worldStyles.put(name, worldStyle);
    }

    public void putMultiBuilding(String name, MultiBuildingRE multiBuilding) {
        multiBuildings.put(name, multiBuilding);
    }

    public Building getBuilding(String name) {
        return buildings.get(DataTools.normalize(name));
    }

    public BuildingPart getPart(String name) {
        return parts.get(DataTools.normalize(name));
    }

    public Palette getPalette(String name) {
        return palettes.get(DataTools.normalize(name));
    }

    public VariantRE getVariant(String name) {
        return variants.get(DataTools.normalize(name));
    }

    public ConditionRE getCondition(String name) {
        return conditions.get(DataTools.normalize(name));
    }

    /** A fully flattened city style (its {@code inherit} chain is already folded in), or null. */
    public CityStyleRE getCityStyle(String name) {
        return cityStyles.get(DataTools.normalize(name));
    }

    public WorldStyleRE getWorldStyle(String name) {
        return worldStyles.get(DataTools.normalize(name));
    }

    public MultiBuildingRE getMultiBuilding(String name) {
        return multiBuildings.get(DataTools.normalize(name));
    }

    /**
     * Look up a style. Never returns {@code null}: a missing style is reported once and replaced by
     * the first available style (or, if there are none at all, by {@link Style#empty(String)}).
     * Callers run deep inside chunk generation where an NPE would abort the whole chunk.
     */
    public Style getStyle(String name) {
        String key = DataTools.normalize(name);
        Style style = styles.get(key);
        if (style != null) {
            return style;
        }
        Style fallback = styles.isEmpty() ? Style.empty(key) : styles.values().iterator().next();
        if (reportedMissingStyles.add(key)) {
            LostBuildings.LOGGER.warn("[lostbuildings] Unknown style '{}' (known: {}) - falling back to '{}'",
                    key, styles.keySet(), fallback.getName());
        }
        return fallback;
    }

    public int size() {
        return buildings.size() + parts.size() + palettes.size() + variants.size() + conditions.size() + styles.size()
                + cityStyles.size() + worldStyles.size() + multiBuildings.size();
    }
}
