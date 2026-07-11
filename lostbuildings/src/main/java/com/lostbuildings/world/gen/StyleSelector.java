package com.lostbuildings.world.gen;

import com.lostbuildings.engine.Assets;
import com.lostbuildings.engine.Style;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;

/**
 * Chooses which building {@link Style} to use for a given building site.
 *
 * <p>This is the single seam between placement ({@code GroupBuildingPlacement}, which decides
 * <em>where</em> a building goes) and styling (which decides <em>what materials</em> it is made of).
 * Placement calls {@link #pick} once per building; the returned style is then fed to
 * {@code BuildingEngine.buildPalette} to compile a concrete palette.
 *
 * <p>Selection is climate-aware: the biome at the build site is queried and its base temperature /
 * precipitation are mapped to a style, so hot &amp; dry regions (deserts, savanna, badlands) build in
 * the sandy {@code desert} style while temperate regions build in the brick/glass {@code standard}
 * style. A small amount of per-building randomness is mixed in so a single region is not perfectly
 * uniform. Any style that cannot be resolved falls back to {@code standard}, so this never returns
 * null and never throws.
 */
public final class StyleSelector {

    private StyleSelector() {}

    /** Fallback style name — always present in data ({@code styles/standard.json}). */
    public static final String DEFAULT_STYLE = "standard";

    /** Sandy style used in hot, dry climates ({@code styles/desert.json}). */
    public static final String DESERT_STYLE = "desert";

    /**
     * Temperature at/above which a biome counts as "hot". Deserts, savanna and badlands sit at 2.0;
     * plains/forest are ~0.7–0.8, so 1.0 cleanly separates hot climates from temperate ones.
     */
    private static final float HOT_TEMPERATURE = 1.0F;

    /**
     * Chance to flip to the "other" style for a single building, so a region gets some visual mix
     * instead of being 100% uniform. Kept low so selection stays mostly climate-driven.
     */
    private static final float MIX_CHANCE = 0.15F;

    /**
     * Pick a building style for the site at {@code site}.
     *
     * @param level  worldgen level (for biome / temperature queries)
     * @param site   NW-bottom corner of the building footprint
     * @param rand   random source
     * @param assets loaded Lost Cities assets (style lookup)
     * @return a non-null {@link Style}; falls back to {@code standard} if a chosen style is missing
     */
    public static Style pick(WorldGenLevel level, BlockPos site, RandomSource rand, Assets assets) {
        // Climate-driven choice: hot & dry biomes get the desert style, everything else the standard style.
        String chosen = isHotAndDry(level, site) ? DESERT_STYLE : DEFAULT_STYLE;

        // Sprinkle in a little variety so a single settlement is not perfectly uniform: occasionally
        // build the opposite style. Selection stays mostly climate-driven (see MIX_CHANCE).
        if (rand.nextFloat() < MIX_CHANCE) {
            chosen = chosen.equals(DESERT_STYLE) ? DEFAULT_STYLE : DESERT_STYLE;
        }

        Style style = assets.getStyle(chosen);
        if (style == null) {
            // Chosen style missing from data — fall back to the always-present standard style.
            style = assets.getStyle(DEFAULT_STYLE);
        }
        return style;
    }

    /**
     * Reads the biome at the site and classifies its climate as hot &amp; dry (desert-like) or not.
     * A biome counts as hot &amp; dry when its base temperature is high and it has no precipitation —
     * which matches deserts, savanna and badlands but not snowy or temperate biomes. Any failure to
     * read the biome is treated as "not hot &amp; dry" so the safe {@code standard} style is used.
     */
    private static boolean isHotAndDry(WorldGenLevel level, BlockPos site) {
        try {
            Holder<Biome> holder = level.getBiome(site);
            Biome biome = holder.value();
            return biome.getBaseTemperature() >= HOT_TEMPERATURE && !biome.hasPrecipitation();
        } catch (Exception e) {
            return false;
        }
    }
}
