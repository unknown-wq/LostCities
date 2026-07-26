package com.lostbuildings.engine;

import com.lostbuildings.LostBuildings;
import com.lostbuildings.engine.codec.ConditionRE;
import com.lostbuildings.engine.codec.DataTools;
import com.lostbuildings.engine.codec.VariantRE;

import java.util.HashMap;
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

    /**
     * Resolve every deferred {@code refpalette} reference now that all palettes are loaded. Doing
     * this as a load-time pass keeps {@link BuildingPart} and {@link Building} read-only during
     * worldgen (they used to resolve — and publish — the reference lazily from several threads).
     */
    public void resolveReferences() {
        for (BuildingPart part : parts.values()) {
            part.resolveLocalPalette(this);
        }
        for (Building building : buildings.values()) {
            building.resolveLocalPalette(this);
        }
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
        return buildings.size() + parts.size() + palettes.size() + variants.size() + conditions.size() + styles.size();
    }
}
