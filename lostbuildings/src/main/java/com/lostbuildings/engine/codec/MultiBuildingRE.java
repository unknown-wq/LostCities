package com.lostbuildings.engine.codec;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A multi-building: a grid of ordinary buildings that are generated as one object (PORT #1).
 *
 * <p>The shipped data is all 2×2 ({@code center00..11}, {@code library00..11}, {@code shopping*},
 * {@code town*}), but the format is a general {@code dimx × dimz} grid and is ported as one.
 *
 * <p>Placement is agent G's (PHASE3-PLAN §6.4): the engine loads the definition and answers "which
 * building goes in quadrant (x, z)", nothing more. It never places one itself, because a
 * multi-building spans several chunk cells and a {@code StructurePiece} may only write inside its
 * own chunk (§7).
 */
public class MultiBuildingRE {

	public static final Codec<MultiBuildingRE> CODEC = RecordCodecBuilder.create(instance ->
			instance.group(
					Codec.INT.fieldOf("dimx").forGetter(l -> l.dimX),
					Codec.INT.fieldOf("dimz").forGetter(l -> l.dimZ),
					Codec.list(Codec.list(Codec.STRING)).fieldOf("buildings").forGetter(l -> l.buildings)
			).apply(instance, MultiBuildingRE::new));

	private Identifier name;
	private final int dimX;
	private final int dimZ;
	/** Outer list is the X column, inner list is the Z row — the original's own layout. */
	private final List<List<String>> buildings;

	public MultiBuildingRE(int dimX, int dimZ, List<List<String>> buildings) {
		this.dimX = dimX;
		this.dimZ = dimZ;
		this.buildings = buildings == null ? List.of() : List.copyOf(buildings);
	}

	public int getDimX() {
		return dimX;
	}

	public int getDimZ() {
		return dimZ;
	}

	public List<List<String>> getBuildings() {
		return buildings;
	}

	/**
	 * The building name in quadrant {@code (x, z)}, or {@code null} when the definition is short or
	 * the quadrant is out of range. Never throws: a hand-edited datapack must not kill worldgen.
	 */
	@Nullable
	public String getBuilding(int x, int z) {
		if (x < 0 || z < 0 || x >= buildings.size()) {
			return null;
		}
		List<String> column = buildings.get(x);
		if (column == null || z >= column.size()) {
			return null;
		}
		return column.get(z);
	}

	public MultiBuildingRE setRegistryName(Identifier name) {
		this.name = name;
		return this;
	}

	@Nullable
	public Identifier getRegistryName() {
		return name;
	}
}
