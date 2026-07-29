package com.lostbuildings.registry;

import com.lostbuildings.LostBuildings;
import com.lostbuildings.world.structure.piece.AirportPiece;
import com.lostbuildings.world.structure.piece.BridgePiece;
import com.lostbuildings.world.structure.piece.BuildingPiece;
import com.lostbuildings.world.structure.piece.ParkPiece;
import com.lostbuildings.world.structure.piece.StreetPiece;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;

/**
 * Registers the mod's {@code StructurePieceType}s.
 *
 * <p>Unlike a feature, a structure survives a server restart: its pieces are written into the
 * chunk's {@code structures} NBT when the start is created and read back when the chunk is finally
 * populated. Every piece class therefore needs an id in {@code BuiltInRegistries.STRUCTURE_PIECE}
 * and a {@code (CompoundTag)} constructor — without the registration, reloading a world logs
 * "Unknown structure piece id" and the city silently never finishes building.
 *
 * <p>{@code ContextlessType} is the variant of the loader interface for pieces that need nothing
 * but the tag (no template manager, no registry access), which is all of ours.
 *
 * <p>There is deliberately no {@code scattered} piece type: a scattered building is placed as an
 * ordinary {@link BuildingPiece}, so that there is exactly one code path — and one NBT format — for
 * "a building stands here".
 */
public final class ModStructurePieceTypes {

	public static final StructurePieceType BUILDING = (StructurePieceType.ContextlessType) BuildingPiece::new;
	public static final StructurePieceType STREET = (StructurePieceType.ContextlessType) StreetPiece::new;
	public static final StructurePieceType PARK = (StructurePieceType.ContextlessType) ParkPiece::new;
	public static final StructurePieceType BRIDGE = (StructurePieceType.ContextlessType) BridgePiece::new;
	public static final StructurePieceType AIRPORT = (StructurePieceType.ContextlessType) AirportPiece::new;

	private ModStructurePieceTypes() {
	}

	public static void init() {
		register("building", BUILDING);
		register("street", STREET);
		register("park", PARK);
		register("bridge", BRIDGE);
		register("airport", AIRPORT);
	}

	private static void register(String name, StructurePieceType type) {
		Registry.register(BuiltInRegistries.STRUCTURE_PIECE,
				Identifier.fromNamespaceAndPath(LostBuildings.MOD_ID, name), type);
	}
}
