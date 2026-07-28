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
 * Picks the Lost Cities <em>style</em> (the set of palettes a building is built from) and the
 * <em>climate</em> (what weathering the finished building gets) from the surroundings, instead of
 * always using the grey-brick {@code standard} style.
 *
 * <p><b>Wave 2 (IMPROVEMENTS #9, second half of PORT #9).</b> The table below is the port of what the
 * original expressed as {@code worldstyles/standard.json}'s {@code citystyles} list: a set of
 * biome predicates, each naming the city style to build with. The original's indirection — a
 * {@code citystyle} that names a {@code style} plus street/park block choices — is deliberately
 * collapsed into "biome → style name" here, because in this port the street and park blocks already
 * come from the structure's own datapack config rather than from the city style. The shipped
 * {@code citystyles/} files are in the resources for the codecs that read them; what actually
 * decides a city's look is this table plus {@code data/lostbuildings/lostcities/styles/}.
 *
 * <p>Style names must exist under {@code data/lostbuildings/lostcities/styles/}; the shipped set is
 * {@code standard}, {@code standard_border}, {@code desert}, {@code outside}, and the two wave-2
 * additions {@code snowy} and {@code swamp} (both built from palettes that were already there — no
 * new blocks, just a different draw from the same bag).
 *
 * <p><b>Climate is separate from style</b> on purpose. A style changes what a building is made of,
 * which has to be decided once for the whole city or it comes out patchwork. Climate changes what
 * has grown on it since, which is a per-piece surface pass: snow settles on the roofs in the north,
 * moss creeps over them in a swamp. Both are sampled at structure-assembly time from the biome
 * source, so no piece ever reads a biome during generation — see {@link WorldGenBounds} for why that
 * matters.
 *
 * <p><b>Sampling window.</b> {@link #select} is the feature-era entry point and still probes the
 * world directly; during the FEATURES step a feature may only touch the origin chunk ±1 chunk, and
 * reading a biome further out throws {@code IllegalStateException: Requested chunk unavailable
 * during world generation}. Probes are therefore anchored on chunk <em>centres</em>: eight blocks of
 * slack on every side, far more than {@code BiomeManager}'s seed fuzz can consume, and each one is
 * still run past {@link WorldGenBounds#canSampleBiome} and skipped rather than trusted.
 */
public final class StyleSelector {

	/** Fallback style — {@code styles/standard.json}. */
	public static final String DEFAULT_STYLE = "standard";
	/** Sand / sandstone style — {@code styles/desert.json}. */
	public static final String DESERT_STYLE = "desert";
	/** Pale quartz-and-silver style for the cold biomes — {@code styles/snowy.json}. */
	public static final String SNOWY_STYLE = "snowy";
	/** Grey, gloomy style for wetlands — {@code styles/swamp.json}. */
	public static final String SWAMP_STYLE = "swamp";

	/** What has happened to a building since the city was abandoned. */
	public enum Climate {
		/** Nothing in particular. */
		TEMPERATE,
		/** Snow settles on every flat surface. */
		SNOWY,
		/** Moss creeps over the roofs. */
		SWAMPY
	}

	/**
	 * Vanilla has no {@code #minecraft:is_desert} / {@code is_swamp} / {@code is_snowy};
	 * {@code #c:*} is the conventional cross-mod family, and the vanilla cases are matched
	 * explicitly alongside them.
	 */
	private static final TagKey<Biome> CONVENTIONAL_IS_DESERT = conventional("is_desert");
	private static final TagKey<Biome> CONVENTIONAL_IS_SWAMP = conventional("is_swamp");
	private static final TagKey<Biome> CONVENTIONAL_IS_SNOWY = conventional("is_snowy");

	private static TagKey<Biome> conventional(String name) {
		return TagKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("c", name));
	}

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

	/**
	 * Style for one biome, or null when the biome carries no opinion.
	 *
	 * <p>Order matters: deserts and badlands win over everything (they are the most visually
	 * distinctive), then wetlands, then the cold biomes — a snowy taiga next to a swamp reads as
	 * swamp only if it is genuinely wet.
	 */
	public static String styleFor(Holder<Biome> biome) {
		if (isDesert(biome)) {
			return DESERT_STYLE;
		}
		if (isSwamp(biome)) {
			return SWAMP_STYLE;
		}
		if (isSnowy(biome)) {
			return SNOWY_STYLE;
		}
		return null;
	}

	/** What weathering a building in this biome gets. Never null. */
	public static Climate climateFor(Holder<Biome> biome) {
		if (isSnowy(biome)) {
			return Climate.SNOWY;
		}
		if (isSwamp(biome)) {
			return Climate.SWAMPY;
		}
		return Climate.TEMPERATE;
	}

	private static boolean isDesert(Holder<Biome> biome) {
		return biome.is(BiomeTags.IS_BADLANDS)
				|| biome.is(Biomes.DESERT)
				|| biome.is(BiomeTags.HAS_VILLAGE_DESERT)
				|| biome.is(CONVENTIONAL_IS_DESERT);
	}

	private static boolean isSwamp(Holder<Biome> biome) {
		return biome.is(Biomes.SWAMP)
				|| biome.is(Biomes.MANGROVE_SWAMP)
				|| biome.is(CONVENTIONAL_IS_SWAMP);
	}

	private static boolean isSnowy(Holder<Biome> biome) {
		return biome.is(BiomeTags.HAS_VILLAGE_SNOWY)
				|| biome.is(BiomeTags.HAS_IGLOO)
				|| biome.is(BiomeTags.SPAWNS_SNOW_FOXES)
				|| biome.is(CONVENTIONAL_IS_SNOWY);
	}
}
