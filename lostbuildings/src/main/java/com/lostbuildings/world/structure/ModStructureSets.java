package com.lostbuildings.world.structure;

import com.lostbuildings.LostBuildings;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;

import java.util.List;

/**
 * Where lost cities and scattered structures are allowed to appear — the {@code spacing}/
 * {@code separation} grids that replaced {@code RarityFilter.onAverageOnceEvery(6)}.
 *
 * <p><b>Why the rarity filter had to go.</b> A rarity filter rolls independently in every chunk, so
 * two cities could land on top of each other; that is precisely the overlap {@code CellLattice} was
 * invented to paper over, at the cost of two thirds of the mod's output. A structure set instead
 * partitions the world into {@code spacing}×{@code spacing} chunk grid cells and puts at most one
 * city in each, jittered by {@code spacing - separation} chunks.
 *
 * <p><b>The invariant to keep when retuning:</b> {@code CITY_SEPARATION >= city_size}. A city is
 * {@code city_size} chunks wide; if two starts can come closer than that, their pieces write into
 * the same chunk and interpenetrate — the exact bug the migration was meant to end. Wave 2 widened
 * the default city from 5 cells to 9 (the block grid needs two cells per building lot to fit a
 * street between the lots), so the numbers here moved with it.
 *
 * <p>Density check: one city of ~21 built lots per {@value #CITY_SPACING}² = 196 chunks is about one
 * building per 9 chunks — the same as before the migration, and the same as wave 1's 5-cell city on
 * a 10-chunk grid. The buildings are simply gathered into fewer, larger, properly laid-out quarters.
 *
 * <p>Scattered structures use their own, much tighter grid: they are one chunk each, they are the
 * thing that makes the space <em>between</em> cities feel inhabited, and they cannot overlap a city
 * by construction — a city stands in the {@code lost_city} biome, and the scattered biome tags are
 * ordinary overworld biomes.
 */
public final class ModStructureSets {

	/** Grid cell size, in chunks: at most one city per {@code CITY_SPACING}² chunks. */
	public static final int CITY_SPACING = 14;
	/** Minimum distance between two cities, in chunks. Must be {@code >= city_size}. */
	public static final int CITY_SEPARATION = 10;
	/** Salt: any value works, it only has to differ from other structure sets'. */
	private static final int CITY_SALT = 0x105C1719;

	/** Grid for the scattered buildings — frequent, but never two in adjacent chunks. */
	public static final int SCATTERED_SPACING = 12;
	public static final int SCATTERED_SEPARATION = 5;
	private static final int SCATTERED_SALT = 0x105C2A03;

	public static final ResourceKey<StructureSet> LOST_CITIES = key("lost_cities");
	public static final ResourceKey<StructureSet> SCATTERED = key("scattered");

	private ModStructureSets() {
	}

	private static ResourceKey<StructureSet> key(String name) {
		return ResourceKey.create(Registries.STRUCTURE_SET, Identifier.fromNamespaceAndPath(LostBuildings.MOD_ID, name));
	}

	public static void bootstrap(BootstrapContext<StructureSet> context) {
		HolderGetter<Structure> structures = context.lookup(Registries.STRUCTURE);

		context.register(LOST_CITIES, new StructureSet(
				structures.getOrThrow(ModStructures.LOST_CITY),
				new RandomSpreadStructurePlacement(CITY_SPACING, CITY_SEPARATION, RandomSpreadType.LINEAR, CITY_SALT)));

		// One set, two structures: the grid decides *where* something scattered stands, the biome
		// list on each structure decides *what* can stand there. The oil rig is weighted low because
		// it only ever resolves in deep ocean, and losing the roll there simply means empty water.
		context.register(SCATTERED, new StructureSet(
				List.of(StructureSet.entry(structures.getOrThrow(ModStructures.SCATTERED), 4),
						StructureSet.entry(structures.getOrThrow(ModStructures.OIL_RIG), 1)),
				new RandomSpreadStructurePlacement(SCATTERED_SPACING, SCATTERED_SEPARATION,
						RandomSpreadType.LINEAR, SCATTERED_SALT)));
	}
}
