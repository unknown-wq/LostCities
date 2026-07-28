package com.lostbuildings.world.structure;

import com.lostbuildings.world.feature.WorldGenFlags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.List;

/**
 * Turns the cars {@link StreetCars} decides on into blocks.
 *
 * <p>The split is the same one {@link StreetDecor} and {@code StreetPiece} already use: all of the
 * arithmetic — which car, where, which way round, how far gone — is pure and lives next door, and
 * this class does nothing but map a stencil character onto a {@link BlockState} and write it. That
 * keeps the interesting half unit-testable without a world, and it keeps the block palette in one
 * readable table.
 *
 * <p><b>Where a car sits.</b> The street tiles raise the pavement one block above the carriageway,
 * so the road surface block is at {@code groundY - 1} and its top face is {@code groundY}. A car
 * therefore stands with its wheels at {@code groundY} — one block below the pavement top — and is
 * two or three blocks tall, comfortably inside the six blocks of headroom {@code StreetPiece}
 * reserves above ground.
 *
 * <p><b>Writes are clipped, never skipped.</b> A car that straddles a cell boundary is returned by
 * both cells (see {@link StreetCars}); each one writes the blocks that land inside its own chunk and
 * silently drops the rest, so the two halves meet exactly.
 */
public final class StreetCarPlacer {

	/**
	 * The dye each {@link StreetCars.Colour} maps onto, in enum order.
	 *
	 * <p>MC 26.2 folded the sixteen dyed variants of a block into one {@code ColorCollection} picked
	 * by {@link DyeColor}, so there is no {@code Blocks.RED_CONCRETE} field any more — bodywork is
	 * {@code Blocks.CONCRETE.pick(dye)} and rust is {@code Blocks.DYED_TERRACOTTA.pick(dye)}.
	 */
	private static final DyeColor[] DYE = {
			DyeColor.WHITE, DyeColor.LIGHT_GRAY, DyeColor.GRAY, DyeColor.RED,
			DyeColor.ORANGE, DyeColor.YELLOW, DyeColor.LIME, DyeColor.GREEN,
			DyeColor.CYAN, DyeColor.BLUE, DyeColor.BROWN, DyeColor.BLACK
	};

	/** Roof and bumper trim — a shade off the bodywork, so a car is not one flat slab of colour. */
	private static final DyeColor[] TRIM_DYE = {
			DyeColor.LIGHT_GRAY, DyeColor.GRAY, DyeColor.BLACK, DyeColor.GRAY,
			DyeColor.BROWN, DyeColor.ORANGE, DyeColor.GREEN, DyeColor.GRAY,
			DyeColor.BLUE, DyeColor.GRAY, DyeColor.BLACK, DyeColor.GRAY
	};

	/**
	 * Bonnet and boot slopes. Concrete has no stairs in vanilla, so each colour borrows the stone
	 * family that sits closest to it — quartz for white, deepslate for blue, brick for red.
	 */
	private static final Block[] SLOPE = {
			Blocks.SMOOTH_QUARTZ_STAIRS, Blocks.POLISHED_DIORITE_STAIRS, Blocks.POLISHED_ANDESITE_STAIRS,
			Blocks.BRICK_STAIRS, Blocks.SMOOTH_RED_SANDSTONE_STAIRS, Blocks.SMOOTH_SANDSTONE_STAIRS,
			Blocks.MOSSY_STONE_BRICK_STAIRS, Blocks.PRISMARINE_BRICK_STAIRS, Blocks.PRISMARINE_STAIRS,
			Blocks.POLISHED_DEEPSLATE_STAIRS, Blocks.MUD_BRICK_STAIRS, Blocks.POLISHED_BLACKSTONE_STAIRS
	};

	private static final BlockState WHEEL = Blocks.CONCRETE.pick(DyeColor.BLACK).defaultBlockState();
	private static final BlockState GLAZING = Blocks.GLASS_PANE.defaultBlockState();
	private static final BlockState GRILLE = Blocks.IRON_BARS.defaultBlockState();
	private static final BlockState COBWEB = Blocks.COBWEB.defaultBlockState();
	private static final BlockState PANEL = Blocks.SMOOTH_STONE_SLAB.defaultBlockState()
			.setValue(SlabBlock.TYPE, SlabType.BOTTOM);
	private static final BlockState CHARRED_PANEL = Blocks.BLACKSTONE_SLAB.defaultBlockState()
			.setValue(SlabBlock.TYPE, SlabType.BOTTOM);
	private static final BlockState CHARRED_BODY = Blocks.BLACKSTONE.defaultBlockState();
	private static final BlockState CHARRED_TRIM = Blocks.POLISHED_BLACKSTONE.defaultBlockState();
	private static final Block CHARRED_SLOPE = Blocks.BLACKSTONE_STAIRS;
	private static final BlockState DOOR = Blocks.IRON_TRAPDOOR.defaultBlockState();

	private StreetCarPlacer() {
	}

	/**
	 * Place every abandoned car that reaches into this street cell, at the default traffic density.
	 *
	 * <p>The one call a street piece needs. Everything it takes is already in scope inside
	 * {@code StreetPiece.decorate}.
	 *
	 * @param level     the world being generated
	 * @param chunkBox  the writable area for this chunk; writes outside it are dropped
	 * @param seed      the world seed — {@code level.getSeed()}
	 * @param cellMinX  world X of the cell's north-west corner, {@code cellMinX()}
	 * @param cellMinZ  world Z of the cell's north-west corner, {@code cellMinZ()}
	 * @param groundY   the city's ground level; a car's wheels rest on this Y
	 * @param mask      the cell's road-connectivity mask, {@code neighbourMask()}
	 */
	public static void place(WorldGenLevel level, BoundingBox chunkBox, long seed,
	                         int cellMinX, int cellMinZ, int groundY, int mask) {
		place(level, chunkBox, seed, cellMinX, cellMinZ, groundY, mask, StreetCars.DEFAULT_DENSITY);
	}

	/**
	 * As {@link #place(WorldGenLevel, BoundingBox, long, int, int, int, int)} but with the traffic
	 * density turned up or down; {@code 0} places nothing.
	 */
	public static void place(WorldGenLevel level, BoundingBox chunkBox, long seed,
	                         int cellMinX, int cellMinZ, int groundY, int mask, double density) {
		List<StreetCars.Car> cars = StreetCars.carsIn(seed, cellMinX, cellMinZ, mask, density);
		if (cars.isEmpty()) {
			return;
		}
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (StreetCars.Car car : cars) {
			build(car, groundY, (x, y, z, state) -> {
				cursor.set(x, y, z);
				if (chunkBox.isInside(cursor)) {
					level.setBlock(cursor, state, WorldGenFlags.SET_BLOCK);
				}
			});
		}
	}

	/** Where a built car's blocks go. Exists so the stencil can be tested without a world. */
	@FunctionalInterface
	public interface Sink {
		void accept(int x, int y, int z, BlockState state);
	}

	/**
	 * Walk one car's stencil and hand every block it consists of to {@code sink}, in absolute world
	 * coordinates. Cells the model leaves empty are not reported at all — a car never carves.
	 *
	 * @param groundY the Y the wheels rest on
	 */
	public static void build(StreetCars.Car car, int groundY, Sink sink) {
		for (int x = car.minX(); x < car.minX() + car.sizeX(); x++) {
			for (int z = car.minZ(); z < car.minZ() + car.sizeZ(); z++) {
				for (int h = 0; h < car.model().height(); h++) {
					BlockState state = stateAt(car, x, z, h);
					if (state != null) {
						sink.accept(x, groundY + h, z, state);
					}
				}
			}
		}
	}

	/**
	 * The block on one column of one car at height {@code h}, or {@code null} for "leave the world
	 * alone here" — either because the model has nothing there or because decay took it away.
	 */
	public static BlockState stateAt(StreetCars.Car car, int x, int z, int h) {
		char c = car.at(x, z, h);
		if (c == StreetCars.NOTHING) {
			return null;
		}
		boolean burnt = car.model().burntOut();
		int colour = car.colour().ordinal();
		return switch (c) {
			case StreetCars.WHEEL -> car.has(StreetCars.WHEELLESS)
					&& car.noseOffset(x, z) == car.model().length() - 1 ? null : WHEEL;
			case StreetCars.BODY -> bodywork(car, burnt, colour);
			case StreetCars.TRIM -> burnt ? CHARRED_TRIM
					: Blocks.CONCRETE.pick(TRIM_DYE[colour]).defaultBlockState();
			case StreetCars.BONNET -> slope(car, burnt, tailward(car));
			case StreetCars.BOOT -> slope(car, burnt, tailward(car).getOpposite());
			case StreetCars.GLASS -> car.has(StreetCars.SMASHED)
					? (car.has(StreetCars.COBWEBBED) ? COBWEB : null) : GLAZING;
			case StreetCars.GRILLE -> GRILLE;
			case StreetCars.CRUSHED -> burnt ? CHARRED_PANEL : PANEL;
			case StreetCars.DOOR -> car.has(StreetCars.DOOR_OPEN)
					? DOOR.setValue(BlockStateProperties.HORIZONTAL_FACING, outward(car, x, z))
					.setValue(BlockStateProperties.OPEN, Boolean.TRUE)
					: bodywork(car, burnt, colour);
			default -> null;
		};
	}

	/** A panel of bodywork: clean concrete, dull terracotta once it has rusted, char if it burned. */
	private static BlockState bodywork(StreetCars.Car car, boolean burnt, int colour) {
		if (burnt) {
			return CHARRED_BODY;
		}
		return car.has(StreetCars.RUSTED)
				? Blocks.DYED_TERRACOTTA.pick(DYE[colour]).defaultBlockState()
				: Blocks.CONCRETE.pick(DYE[colour]).defaultBlockState();
	}

	/**
	 * A bonnet or boot stair. A stair's raised half is on the side its {@code facing} points at, so a
	 * bonnet faces the cabin behind it and a boot faces the cabin in front of it — both slope up
	 * towards the middle of the car, which is what makes the silhouette read as a car rather than a
	 * brick.
	 */
	private static BlockState slope(StreetCars.Car car, boolean burnt, Direction facing) {
		Block block = burnt ? CHARRED_SLOPE : SLOPE[car.colour().ordinal()];
		return block.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
	}

	/** The direction from the car's nose towards its tail, in world terms. */
	private static Direction tailward(StreetCars.Car car) {
		if (car.axis() == StreetCars.Axis.X) {
			return car.forward() ? Direction.EAST : Direction.WEST;
		}
		return car.forward() ? Direction.SOUTH : Direction.NORTH;
	}

	/** The direction out of the car's flank on the side this column sits on. */
	private static Direction outward(StreetCars.Car car, int x, int z) {
		boolean far = car.sideOffset(x, z) == 1;
		if (car.axis() == StreetCars.Axis.X) {
			return far ? Direction.SOUTH : Direction.NORTH;
		}
		return far ? Direction.EAST : Direction.WEST;
	}
}
