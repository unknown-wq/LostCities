package com.lostbuildings.world.feature;

import com.lostbuildings.LostBuildings;
import com.lostbuildings.engine.Assets;
import com.lostbuildings.engine.codec.CityStyleRE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Picks the Lost Cities <em>style</em> (the set of palettes a building is built from) and the
 * <em>climate</em> (what weathering the finished building gets) from the surroundings, instead of
 * always using the grey-brick {@code standard} style.
 *
 * <p><b>Wave 2 (IMPROVEMENTS #9, second half of PORT #9).</b> The table below is the port of what the
 * original expressed as {@code worldstyles/standard.json}'s {@code citystyles} list: a set of
 * biome predicates, each naming the city style to build with.
 *
 * <p><b>Wave 3 — one source of truth for a city's look.</b> The biome table answers with a
 * <em>city style</em> name, and the palette style is then read out of
 * {@code data/lostbuildings/lostcities/citystyles/&lt;name&gt;.json} through {@link Assets}. Wave 2
 * shipped this as two half-connected halves: the {@code citystyles/} files were copied and loaded by
 * the engine's codecs but nothing consulted them, while this table named a {@code styles/} file
 * directly. Both are datapack-visible, so integration kept the one that goes through the data
 * ({@code biome → citystyle → style}) and deleted the shortcut. A datapack can now repaint every
 * desert city by editing one line of {@code citystyle_desert.json}.
 *
 * <p>The rest of a city style — {@code streetblocks}, {@code parkblocks}, the {@code selectors}
 * lists — is deliberately <em>not</em> consulted: in this port the building list, the street material
 * and the park parts are fields of the structure's own codec (IMPROVEMENTS #10), and a city style
 * naming them too would be a second, competing source for the same setting. {@code LostCityConfig}
 * owns those; a city style owns the palette.
 *
 * <p>City style names must exist under {@code citystyles/} and their {@code style} must exist under
 * {@code data/lostbuildings/lostcities/styles/}; the shipped styles are {@code standard},
 * {@code standard_border}, {@code desert}, {@code outside}, and the two wave-2 additions
 * {@code snowy} and {@code swamp} (both built from palettes that were already there — no new blocks,
 * just a different draw from the same bag).
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

	/** Fallback style — {@code styles/standard.json}. Used when a city style cannot be resolved. */
	public static final String DEFAULT_STYLE = "standard";

	/** Ordinary grey-brick city — {@code citystyles/citystyle_standard.json}. */
	public static final String DEFAULT_CITY_STYLE = "citystyle_standard";
	/** Sand / sandstone city — {@code citystyles/citystyle_desert.json}. */
	public static final String DESERT_CITY_STYLE = "citystyle_desert";
	/** Pale quartz-and-silver city for the cold biomes — {@code citystyles/citystyle_snowy.json}. */
	public static final String SNOWY_CITY_STYLE = "citystyle_snowy";
	/** Grey, gloomy city for wetlands — {@code citystyles/citystyle_swamp.json}. */
	public static final String SWAMP_CITY_STYLE = "citystyle_swamp";

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

	/**
	 * The style name to build from, sampled from the level around {@code origin}. Never null; falls
	 * back to {@link #DEFAULT_STYLE}.
	 *
	 * <p>The structures do not use this — they sample the biome source at assembly time, when no
	 * chunk exists to read. It is kept as the level-based entry point required by the frozen contract
	 * (PHASE3-PLAN §3) and as the one place that knows how to probe a biome safely during generation.
	 */
	public static String select(WorldGenLevel level, BlockPos origin) {
		int centerChunkX = WorldGenBounds.chunkOf(origin.getX());
		int centerChunkZ = WorldGenBounds.chunkOf(origin.getZ());
		BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
		for (int[] column : probeColumns(centerChunkX, centerChunkZ)) {
			if (!WorldGenBounds.canSampleBiome(level, column[0], column[1], centerChunkX, centerChunkZ)) {
				continue;   // unreachable by construction; skipping beats throwing if it ever is not
			}
			probe.set(column[0], origin.getY(), column[1]);
			String cityStyle = opinionatedCityStyleFor(level.getBiome(probe));
			if (cityStyle != null) {
				return styleOfCityStyle(cityStyle);
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
	 * The city style for one biome. Never null: a biome with no opinion gets
	 * {@link #DEFAULT_CITY_STYLE}.
	 *
	 * <p>Order matters: deserts and badlands win over everything (they are the most visually
	 * distinctive), then wetlands, then the cold biomes — a snowy taiga next to a swamp reads as
	 * swamp only if it is genuinely wet.
	 */
	public static String cityStyleFor(Holder<Biome> biome) {
		String opinion = opinionatedCityStyleFor(biome);
		return opinion == null ? DEFAULT_CITY_STYLE : opinion;
	}

	/** The same table, answering null when the biome carries no opinion at all. */
	private static String opinionatedCityStyleFor(Holder<Biome> biome) {
		if (isDesert(biome)) {
			return DESERT_CITY_STYLE;
		}
		if (isSwamp(biome)) {
			return SWAMP_CITY_STYLE;
		}
		if (isSnowy(biome)) {
			return SNOWY_CITY_STYLE;
		}
		return null;
	}

	/**
	 * The palette style a city in this biome is built from: the biome's city style, resolved through
	 * {@code citystyles/}. Never null — an unknown or unloaded city style falls back to
	 * {@link #DEFAULT_STYLE} rather than aborting a chunk.
	 */
	public static String styleFor(Holder<Biome> biome) {
		return styleOfCityStyle(cityStyleFor(biome));
	}

	/**
	 * The {@code style} field of a city style, or {@link #DEFAULT_STYLE}.
	 *
	 * <p>Reads {@link LostBuildings#ASSETS}, which is published before the first chunk generates (the
	 * datapack reload listener, with a server-starting fallback). If it is somehow not there yet the
	 * answer is the default style — the same thing a missing style resolves to anyway, so a city can
	 * never come out half in one palette and half in another because of load ordering.
	 */
	public static String styleOfCityStyle(String cityStyleName) {
		Assets assets = LostBuildings.ASSETS;
		CityStyleRE cityStyle = assets == null ? null : assets.getCityStyle(cityStyleName);
		String style = cityStyle == null ? null : cityStyle.getStyle();
		if (style == null || style.isEmpty()) {
			if (assets != null && REPORTED_MISSING_CITY_STYLES.add(cityStyleName)) {
				LostBuildings.LOGGER.warn(
						"[lostbuildings] City style '{}' is missing or names no style - falling back to '{}'",
						cityStyleName, DEFAULT_STYLE);
			}
			return DEFAULT_STYLE;
		}
		return style;
	}

	/** City styles already reported as missing, so the warning is logged once and not per city. */
	private static final Set<String> REPORTED_MISSING_CITY_STYLES = ConcurrentHashMap.newKeySet();

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
