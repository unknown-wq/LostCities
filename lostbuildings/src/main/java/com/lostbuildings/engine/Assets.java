package com.lostbuildings.engine;

import com.lostbuildings.engine.codec.ConditionRE;
import com.lostbuildings.engine.codec.DataTools;
import com.lostbuildings.engine.codec.VariantRE;

import java.util.HashMap;
import java.util.Map;

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
    private final Map<String, Style> styles = new HashMap<>();

    public Map<String, VariantRE> getVariants() {
        return variants;
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

    public Style getStyle(String name) {
        return styles.get(DataTools.normalize(name));
    }

    public int size() {
        return buildings.size() + parts.size() + palettes.size() + variants.size() + conditions.size() + styles.size();
    }
}
