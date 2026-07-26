package com.lostbuildings.engine;

import com.lostbuildings.engine.codec.DataTools;
import com.lostbuildings.engine.codec.PaletteSelector;
import com.lostbuildings.engine.codec.StyleRE;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * A building style: a list of random-palette choice groups. When a compiled palette is built
 * one palette is chosen (weighted by factor) from each group and the results are merged.
 */
public class Style {

    private final Identifier name;
    private final List<List<PaletteSelector>> randomPaletteChoices;

    public Style(StyleRE object) {
        this.name = object.getRegistryName();
        this.randomPaletteChoices = object.getRandomPaletteChoices() == null
                ? List.of() : List.copyOf(object.getRandomPaletteChoices());
    }

    private Style(Identifier name, List<List<PaletteSelector>> randomPaletteChoices) {
        this.name = name;
        this.randomPaletteChoices = randomPaletteChoices;
    }

    /**
     * A style with no palette choices. Used as a last-resort fallback so that a missing/misspelled
     * style name degrades into "a building with an empty palette" (plus a warning) instead of an
     * NPE inside chunk generation.
     */
    public static Style empty(String name) {
        Identifier id = Identifier.tryParse(name);
        if (id == null) {
            id = Identifier.fromNamespaceAndPath("lostbuildings", "missing_style");
        }
        return new Style(id, List.of());
    }

    public String getName() {
        return DataTools.toName(name);
    }

    public Identifier getId() {
        return name;
    }

    public List<List<PaletteSelector>> getRandomPaletteChoices() {
        return randomPaletteChoices;
    }
}
