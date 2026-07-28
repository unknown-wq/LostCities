package com.lostbuildings.world.structure.piece;

import com.lostbuildings.engine.BlockStates;
import com.lostbuildings.engine.BuildingPart;
import com.lostbuildings.engine.CompiledPalette;
import com.lostbuildings.engine.Palette;
import com.lostbuildings.engine.Transform;
import com.lostbuildings.world.feature.WorldGenFlags;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Stamps a single {@code BuildingPart} into the world, clipped to a piece's writable chunk box.
 *
 * <p><b>Why this is not in the engine.</b> {@code BuildingEngine.generateBuilding} is the entry
 * point for a <em>building</em>: it picks a storey count, walks the floor list, tracks connectable
 * blocks and runs a correction pass over them. Streets, parks and bridges are none of those things —
 * they are one flat part laid at one height. The engine's own per-part writer is private and, more
 * to the point, it clips against {@code WorldGenBounds} (the feature-era 48×48 write window) rather
 * than against the {@code chunkBox} a structure piece is handed. This class is the structure-era
 * equivalent: the same palette semantics, clipped to the box the piece may legally write in.
 *
 * <p><b>Palette semantics kept from the engine.</b> An unknown character, {@code air} and
 * {@code structure_void} all mean "leave the world alone here" — that is how the shipped tiles
 * express pavement, which must not overwrite the base course laid underneath. Rotation is applied to
 * both the coordinates and the block state, so stairs and slabs turn with the tile.
 *
 * <p><b>Deliberately simpler than the engine</b> (§9): a decorative part gets no spawners and no
 * loot-table NBT. Every shipped {@code street_*}, {@code park_*}, {@code fountain*} and
 * {@code bridge_*} part is plain geometry plus torches, so nothing is lost today, and a datapack
 * that puts a chest in a park gets an empty chest instead of a silent worldgen failure.
 */
public final class PartPlacer {

	private PartPlacer() {
	}

	/**
	 * Place one part.
	 *
	 * @param level    worldgen level
	 * @param chunkBox the writable area handed to the piece; nothing is written outside it
	 * @param part     the part to stamp
	 * @param originX  world X of the part's local {@code (0, *, 0)} corner
	 * @param originY  world Y of the part's bottom slice
	 * @param originZ  world Z of the part's local {@code (0, *, 0)} corner
	 * @param transform clockwise rotation applied to the part
	 * @param palette  compiled palette to resolve characters through
	 * @param rand     seeded random for weighted palette entries
	 * @return the Y level directly above the part
	 */
	public static int place(WorldGenLevel level, BoundingBox chunkBox, BuildingPart part,
	                        int originX, int originY, int originZ, Transform transform,
	                        CompiledPalette palette, RandomSource rand) {
		if (part == null || palette == null) {
			return originY;
		}
		int xSize = part.getXSize();
		int zSize = part.getZSize();
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

		for (int x = 0; x < xSize; x++) {
			for (int z = 0; z < zSize; z++) {
				char[] column = part.getVSlice(x, z);
				if (column == null) {
					continue;
				}
				int rx = transform.rotateX(x, z, xSize, zSize);
				int rz = transform.rotateZ(x, z, xSize, zSize);
				for (int y = 0; y < column.length; y++) {
					char c = column[y];
					BlockState state = palette.get(c, rand);
					if (state == null || state == BlockStates.AIR || state == BlockStates.STRUCTURE_VOID) {
						continue;   // unknown, air or void: leave whatever is already there
					}
					Palette.Info info = palette.getInfo(c);
					if (info != null && info.mobId() != null && !info.mobId().isEmpty()) {
						continue;   // no spawners in street furniture (see class javadoc)
					}
					if (transform != Transform.ROTATE_NONE) {
						state = state.rotate(transform.getMcRotation());
					}
					cursor.set(originX + rx, originY + y, originZ + rz);
					if (!chunkBox.isInside(cursor)) {
						continue;
					}
					BlockState corrected = BlockStates.correct(level, cursor, state);
					if (corrected == null) {
						continue;
					}
					level.setBlock(cursor, corrected, WorldGenFlags.SET_BLOCK);
				}
			}
		}
		return originY + part.getSliceCount();
	}
}
