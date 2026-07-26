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
 * ±1 chunk; reading a biome further out hits a chunk whose biomes have not been generated yet and
 * throws {@code IllegalStateException: Requested chunk unavailable during world generation}.
 *
 * <p>Probes used to be expressed as ±16 <em>block</em> offsets from the raw feature origin, on the
 * theory that ±16 blocks can never cross more than one chunk border. As block arithmetic that is
 * true — but {@code BiomeManager.getBiome} does not read the position it is handed. It subtracts 2
 * from each axis and then lets a seed-derived fuzz pick the next quart cell up, so the chunk it
 * actually loads lies in {@code [(x - 2) >> 4, (x + 5) >> 4]}. A probe landing on a chunk's minimum
 * block edge resolved into the <em>previous</em> chunk and one on the maximum edge into the next —
 * two chunks out from the origin, and a crash. See {@link WorldGenBounds} for the full derivation.
 *
 * <p>Probes are therefore anchored on chunk <em>centres</em>: eight blocks of slack on every side,
 * far more than the fuzz can consume, so every probe is safe by construction. Each one is still run
 * past {@link WorldGenBounds#canSampleBiome} and skipped rather than trusted, because a probe that
 * cannot be answered must not take chunk generation down with it.
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

	/**
	 * Origin chunk plus the 8 neighbours one chunk out, as <em>chunk</em> offsets. The order is
	 * fixed and the geometry depends on nothing but the origin's chunk, so the style a given
	 * position resolves to is stable for a given seed.
	 */
	private static final int[][] PROBE_CHUNKS = {
			{0, 0},
			{1, 0}, {-1, 0}, {0, 1}, {0, -1},
			{1, 1}, {1, -1}, {-1, 1}, {-1, -1}
	};

	private StyleSelector() {
	}

	/** The style name to build this group from. Never null; falls back to {@link #DEFAULT_STYLE}. */
	public static String select(WorldGenLevel level, BlockPos origin) {
		int centerChunkX = WorldGenBounds.chunkOf(origin.getX());
		int centerChunkZ = WorldGenBounds.chunkOf(origin.getZ());
		BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
		for (int[] column : probeColumns(centerChunkX, centerChunkZ)) {
			if (!WorldGenBounds.canSampleBiome(level, column[0], column[1], centerChunkX, centerChunkZ)) {
				continue;   // unreachable by construction; skipping beats throwing if it ever is not
			}
			probe.set(column[0], origin.getY(), column[1]);
			String style = styleFor(level.getBiome(probe));
			if (style != null) {
				return style;
			}
		}
		return DEFAULT_STYLE;
	}

	/**
	 * The columns {@link #select} probes, in probe order: the centre block of the origin chunk
	 * followed by the centres of its 8 neighbours. Pure geometry, so tests can assert both the
	 * ordering (which fixes the style result) and that every column is a legal biome probe.
	 */
	static int[][] probeColumns(int centerChunkX, int centerChunkZ) {
		int[][] columns = new int[PROBE_CHUNKS.length][2];
		for (int i = 0; i < PROBE_CHUNKS.length; i++) {
			columns[i][0] = WorldGenBounds.chunkCenterBlock(centerChunkX + PROBE_CHUNKS[i][0]);
			columns[i][1] = WorldGenBounds.chunkCenterBlock(centerChunkZ + PROBE_CHUNKS[i][1]);
		}
		return columns;
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
