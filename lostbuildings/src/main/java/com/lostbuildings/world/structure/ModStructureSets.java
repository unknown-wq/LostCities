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

/**
 * Where lost cities are allowed to appear — the {@code spacing}/{@code separation} grid that
 * replaces {@code RarityFilter.onAverageOnceEvery(6)}.
 *
 * <p><b>Why the rarity filter had to go.</b> A rarity filter rolls independently in every chunk, so
 * two cities could land on top of each other; that is precisely the overlap {@code CellLattice} was
 * invented to paper over, at the cost of two thirds of the mod's output. A structure set instead
 * partitions the world into {@value #SPACING}×{@value #SPACING} chunk grid cells and puts at most
 * one city in each, jittered by {@code spacing - separation} chunks. Two cities are therefore never
 * closer than {@value #SEPARATION} chunks.
 *
 * <p><b>The invariant to keep when retuning:</b> {@code separation >= city_size}. A city is
 * {@code city_size} chunks wide; if two starts can come closer than that, their pieces write into
 * the same chunk and interpenetrate — the exact bug the migration is meant to end. The shipped
 * numbers leave one chunk of slack over the default {@code city_size} of 5.
 *
 * <p>Density check against the old behaviour: the feature attempted a group in 1 chunk in 6 of the
 * {@code lost_city} biome and won about one cell per attempt, i.e. roughly one building per 9
 * biome chunks. One city of ~13 buildings per {@value #SPACING}² = 100 chunks is about one per 8.
 * Same order of magnitude, but concentrated into blocks instead of scattered singles — and with no
 * firing that builds nothing.
 */
public final class ModStructureSets {

	/** Grid cell size, in chunks: at most one city per {@code SPACING}² chunks. */
	public static final int SPACING = 10;
	/** Minimum distance between two cities, in chunks. Must be {@code >= city_size}. */
	public static final int SEPARATION = 6;
	/** Salt: any value works, it only has to differ from other structure sets'. */
	private static final int SALT = 0x105C1719;

	public static final ResourceKey<StructureSet> LOST_CITIES = ResourceKey.create(Registries.STRUCTURE_SET,
			Identifier.fromNamespaceAndPath(LostBuildings.MOD_ID, "lost_cities"));

	private ModStructureSets() {
	}

	public static void bootstrap(BootstrapContext<StructureSet> context) {
		HolderGetter<Structure> structures = context.lookup(Registries.STRUCTURE);

		context.register(LOST_CITIES, new StructureSet(
				structures.getOrThrow(ModStructures.LOST_CITY),
				new RandomSpreadStructurePlacement(SPACING, SEPARATION, RandomSpreadType.LINEAR, SALT)));
	}
}
