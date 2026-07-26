package com.lostbuildings.world.feature;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;

/**
 * Picks the Lost Cities <em>style</em> (the set of palettes a building is built from) from the
 * surroundings, instead of always using the grey-brick {@code standard} style.
 *
 * <p>Style names must exist under {@code data/lostbuildings/lostcities/styles/}; the shipped set is
 * {@code standard}, {@code standard_border}, {@code desert}, {@code outside}.
 *
 * <p><b>Sampling window.</b> During the FEATURES step a feature may only touch the origin chunk
 * ±1 chunk; reading a biome further out would hit a chunk that is not in the {@code WorldGenRegion}
 * and throw. Every probe offset below is therefore ≤16 blocks, which can never leave that window.
 *
 * <p><b>Caveat.</b> The biome at the origin is normally {@code lostbuildings:lost_city} itself
 * (Biolith replaces a slice of the host biome, and the feature only runs inside the replacement),
 * so the useful signal comes from the probes that land just outside the patch. Deep inside a large
 * patch every probe still returns {@code lost_city} and the default style is used.
 */
public final class StyleSelector {

	/** Fallback style — {@code styles/standard.json}. */
	public static final String DEFAULT_STYLE = "standard";
	/** Sand / sandstone style — {@code styles/desert.json}. */
	public static final String DESERT_STYLE = "desert";

	/**
	 * Vanilla has no {@code #minecraft:is_desert}; {@code #c:is_desert} is the conventional
	 * cross-mod tag, and {@code minecraft:desert} is matched explicitly for the vanilla case.
	 */
	private static final TagKey<Biome> CONVENTIONAL_IS_DESERT =
			TagKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("c", "is_desert"));

	/** Origin plus the 8 neighbours one chunk out. Fixed order ⇒ the result is seed-stable. */
	private static final int[][] PROBES = {
			{0, 0},
			{16, 0}, {-16, 0}, {0, 16}, {0, -16},
			{16, 16}, {16, -16}, {-16, 16}, {-16, -16}
	};

	private StyleSelector() {
	}

	/** The style name to build this group from. Never null; falls back to {@link #DEFAULT_STYLE}. */
	public static String select(WorldGenLevel level, BlockPos origin) {
		BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
		for (int[] offset : PROBES) {
			probe.set(origin.getX() + offset[0], origin.getY(), origin.getZ() + offset[1]);
			String style = styleFor(level.getBiome(probe));
			if (style != null) {
				return style;
			}
		}
		return DEFAULT_STYLE;
	}

	/** Style for one biome, or null when the biome carries no opinion. */
	public static String styleFor(Holder<Biome> biome) {
		if (biome.is(BiomeTags.IS_BADLANDS)
				|| biome.is(Biomes.DESERT)
				|| biome.is(BiomeTags.HAS_VILLAGE_DESERT)
				|| biome.is(CONVENTIONAL_IS_DESERT)) {
			return DESERT_STYLE;
		}
		return null;
	}
}
