package com.lostbuildings.world.gen;

import com.lostbuildings.engine.Assets;
import com.lostbuildings.engine.Style;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;

/**
 * Chooses which building {@link Style} to use for a given building site.
 *
 * <p>This is the single seam between placement ({@code GroupBuildingPlacement}, which decides
 * <em>where</em> a building goes) and styling (which decides <em>what materials</em> it is made of).
 * Placement calls {@link #pick} once per building; the returned style is then fed to
 * {@code BuildingEngine.buildPalette} to compile a concrete palette.
 *
 * <p>The default implementation just returns the {@code standard} style so the mod behaves exactly
 * as before. Biome / temperature aware selection (desert styles in hot biomes, etc.) is layered in
 * here without any placement code having to change.
 */
public final class StyleSelector {

    private StyleSelector() {}

    /** Fallback style name — always present in data ({@code styles/standard.json}). */
    public static final String DEFAULT_STYLE = "standard";

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
        Style style = assets.getStyle(DEFAULT_STYLE);
        return style;
    }
}
