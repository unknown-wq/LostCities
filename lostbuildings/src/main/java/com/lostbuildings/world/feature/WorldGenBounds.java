package com.lostbuildings.world.feature;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelReader;

/**
 * The single place that knows how far this mod may reach out of the chunk it is decorating.
 *
 * <p><b>The invariant.</b> During the {@code minecraft:features} step a feature is handed a
 * {@link WorldGenRegion} whose <em>write zone</em> is the generating chunk ±{@link #WRITE_RADIUS}
 * chunk — a 48×48 block window. Every position this mod derives from the feature origin, for a read
 * as much as for a write, has to be proven to sit inside that window <em>before</em> it is handed to
 * the level. Outside it the game either logs {@code Detected unsafe terrain read during worldgen}
 * (block and heightmap reads, block writes) or throws
 * {@code IllegalStateException: Requested chunk unavailable during world generation} (biome reads).
 *
 * <p><b>Why block reads only warn while biome reads crash.</b> The FEATURES {@code ChunkStep}
 * declares {@code STRUCTURE_STARTS} out to radius 8 and {@code CARVERS} out to radius 1, and
 * {@code WorldGenRegion.getChunk} hands a chunk back only when the status the caller asks for is at
 * or before the status guaranteed at that distance. A block or heightmap read asks for
 * {@code EMPTY}, which every radius satisfies, so it succeeds — with a warning — as far out as
 * radius 8. A biome read asks for {@code BIOMES}, which sorts <em>after</em>
 * {@code STRUCTURE_STARTS}, so from radius 2 outwards {@code getChunk} falls through to the throw.
 *
 * <p>That asymmetry is also why {@link LevelReader#hasChunk} on its own is not a sufficient guard:
 * it mirrors the radius-8 dependency rule and happily returns {@code true} for a chunk whose biomes
 * do not exist yet. It is used here only as a second line of defence behind the write-window test.
 *
 * <p><b>Biome fuzz — the trap that caused the crash.</b> {@code BiomeManager.getBiome(pos)} does not
 * sample {@code pos}. It computes {@code i = pos.getX() - 2}, takes the quart cell
 * {@code l = i >> 2}, and then lets a seed-derived "fiddled distance" pick either {@code l} or
 * {@code l + 1} on each axis before converting back with {@code chunk = quart >> 2}. Quart {@code l}
 * can start as low as block {@code x - }{@value #BIOME_SAMPLE_REACH_LOW} and quart {@code l + 1} can
 * end as high as block {@code x + }{@value #BIOME_SAMPLE_REACH_HIGH}, so the chunk that actually
 * gets loaded lies anywhere in {@code [(x - 2) >> 4, (x + 5) >> 4]}. A probe sitting on a chunk's
 * <em>minimum</em> block edge therefore resolves into the previous chunk, and one sitting on the
 * maximum edge into the next one — always one chunk further out than the caller intended.
 *
 * <p>Consequence: a probe offset of exactly ±16 blocks from an arbitrary origin does <em>not</em>
 * stay inside the origin chunk ±1. Use {@link #chunkCenterBlock(int)} to anchor biome probes in the
 * middle of a chunk, which leaves 8 blocks of slack on every side and is safe by construction.
 */
public final class WorldGenBounds {

	/** Chunks the FEATURES step may touch around the generating chunk ({@code ChunkStep} write radius). */
	public static final int WRITE_RADIUS = 1;

	/** Blocks below a biome probe that {@code BiomeManager} may still resolve into (the {@code -2}). */
	public static final int BIOME_SAMPLE_REACH_LOW = 2;
	/** Blocks above a biome probe that the fuzzy {@code l + 1} quart corner may still resolve into. */
	public static final int BIOME_SAMPLE_REACH_HIGH = 5;

	/** Blocks per chunk along one axis. */
	private static final int CHUNK_SIZE = 16;

	private WorldGenBounds() {
	}

	/** Chunk coordinate a block coordinate falls in. */
	public static int chunkOf(int blockCoord) {
		return blockCoord >> 4;
	}

	/**
	 * Block coordinate of the middle of a chunk. Biome probes are anchored here rather than on the
	 * chunk's corner so that the ±5 block fuzz in {@code BiomeManager} cannot push the resolved
	 * chunk into a neighbour.
	 */
	public static int chunkCenterBlock(int chunkCoord) {
		return (chunkCoord << 4) + CHUNK_SIZE / 2;
	}

	/** Whether a chunk lies inside the write window around the generating chunk. */
	public static boolean holdsChunk(int chunkX, int chunkZ, int centerChunkX, int centerChunkZ) {
		return Math.abs(chunkX - centerChunkX) <= WRITE_RADIUS
				&& Math.abs(chunkZ - centerChunkZ) <= WRITE_RADIUS;
	}

	/** Whether a block column lies inside the write window around the generating chunk. */
	public static boolean holdsBlock(int x, int z, int centerChunkX, int centerChunkZ) {
		return holdsChunk(chunkOf(x), chunkOf(z), centerChunkX, centerChunkZ);
	}

	/**
	 * Whether a <em>biome</em> probe at this column is safe: not just the column itself, but the
	 * whole {@code [x - 2, x + 5]} × {@code [z - 2, z + 5]} span that {@code BiomeManager}'s fuzzy
	 * quart lookup can end up reading. Checking the two extremes per axis is enough because
	 * {@link #chunkOf} is monotone, so everything between them lands in a chunk between the two.
	 */
	public static boolean holdsBiomeSample(int x, int z, int centerChunkX, int centerChunkZ) {
		return holdsBlock(x - BIOME_SAMPLE_REACH_LOW, z - BIOME_SAMPLE_REACH_LOW, centerChunkX, centerChunkZ)
				&& holdsBlock(x + BIOME_SAMPLE_REACH_HIGH, z + BIOME_SAMPLE_REACH_HIGH, centerChunkX, centerChunkZ);
	}

	/**
	 * Pull a block coordinate back into the write window. Used for terrain probes, where dropping a
	 * sample would change the answer (a group's shared ground level is a min/max over its probes)
	 * but nudging it to the nearest legal column does not.
	 */
	public static int clampBlockToWindow(int blockCoord, int centerChunkCoord) {
		int min = (centerChunkCoord - WRITE_RADIUS) << 4;
		int max = ((centerChunkCoord + WRITE_RADIUS) << 4) + CHUNK_SIZE - 1;
		return Math.clamp(blockCoord, min, max);
	}

	/**
	 * Whether the level can serve a block read/write at this position without complaining.
	 *
	 * <p>For the {@link WorldGenRegion} handed to a feature this is the game's own write-zone test,
	 * which is exactly the invariant documented above. Any other {@link LevelReader} (a real
	 * {@code ServerLevel} in a test harness, say) has no such window, so fall back to
	 * {@link LevelReader#hasChunk}.
	 *
	 * <p>Use this where the generating chunk is not threaded through the call chain; prefer
	 * {@link #holdsBlock} where it is, because that needs no instanceof and no level at all.
	 */
	public static boolean canRead(LevelReader level, BlockPos pos) {
		if (level instanceof WorldGenRegion region) {
			return region.isWithinWriteZone(pos);
		}
		return level.hasChunk(chunkOf(pos.getX()), chunkOf(pos.getZ()));
	}

	/**
	 * Whether a biome probe at this column can be answered. Skipping is always the right response to
	 * a {@code false} here: {@code getBiome} has no "unavailable" return value, it throws.
	 *
	 * <p>Both guards are applied. The write-window test is the one that actually matters — it is the
	 * only one that accounts for {@code BIOMES} status being unavailable from radius 2 outwards —
	 * while {@link LevelReader#hasChunk} catches the case of a level that is not a
	 * {@link WorldGenRegion} at all, and costs nothing.
	 */
	public static boolean canSampleBiome(LevelReader level, int x, int z, int centerChunkX, int centerChunkZ) {
		if (!holdsBiomeSample(x, z, centerChunkX, centerChunkZ)) {
			return false;
		}
		return level.hasChunk(chunkOf(x - BIOME_SAMPLE_REACH_LOW), chunkOf(z - BIOME_SAMPLE_REACH_LOW))
				&& level.hasChunk(chunkOf(x + BIOME_SAMPLE_REACH_HIGH), chunkOf(z + BIOME_SAMPLE_REACH_HIGH));
	}
}
