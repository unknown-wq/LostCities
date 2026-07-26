package com.lostbuildings.engine;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.StructureVoidBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.level.block.state.properties.WallSide;
import net.minecraft.core.Direction;

/**
 * Common block states and per-block "connectable state" correction. Connection flags for
 * fences/panes/bars/walls and the shape of stairs are derived from the blocks already present in
 * the world.
 */
public final class BlockStates {

    public static final BlockState AIR = Blocks.AIR.defaultBlockState();
    public static final BlockState WATER = Blocks.WATER.defaultBlockState();
    public static final BlockState STRUCTURE_VOID = Blocks.STRUCTURE_VOID.defaultBlockState();
    public static final BlockState DIRT = Blocks.DIRT.defaultBlockState();

    private BlockStates() {
    }

    private static boolean canAttach(BlockState state) {
        if (state.isAir()) {
            return false;
        }
        if (state.canOcclude()) {
            return true;
        }
        return !Block.isExceptionForConnection(state);
    }

    private static WallSide canAttachWall(BlockState state) {
        return canAttach(state) ? WallSide.LOW : WallSide.NONE;
    }

    /**
     * Correct a connectable block state against its neighbours in the world.
     * Returns null for STRUCTURE_VOID (meaning: leave whatever is already there).
     */
    public static BlockState correct(WorldGenLevel level, BlockPos pos, BlockState state) {
        Block block = state.getBlock();
        if (block instanceof StructureVoidBlock) {
            return null;
        }
        // Hot path: this runs for every block a building places, so bail out before touching the
        // world for anything that has no connection state at all (that is ~99% of the blocks).
        if (block instanceof CrossCollisionBlock) {
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            return state.setValue(CrossCollisionBlock.WEST, canAttach(neighbour(level, m, pos, Direction.WEST)))
                    .setValue(CrossCollisionBlock.EAST, canAttach(neighbour(level, m, pos, Direction.EAST)))
                    .setValue(CrossCollisionBlock.NORTH, canAttach(neighbour(level, m, pos, Direction.NORTH)))
                    .setValue(CrossCollisionBlock.SOUTH, canAttach(neighbour(level, m, pos, Direction.SOUTH)));
        }
        if (block instanceof WallBlock) {
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            return state.setValue(WallBlock.WEST, canAttachWall(neighbour(level, m, pos, Direction.WEST)))
                    .setValue(WallBlock.EAST, canAttachWall(neighbour(level, m, pos, Direction.EAST)))
                    .setValue(WallBlock.NORTH, canAttachWall(neighbour(level, m, pos, Direction.NORTH)))
                    .setValue(WallBlock.SOUTH, canAttachWall(neighbour(level, m, pos, Direction.SOUTH)));
        }
        if (block instanceof StairBlock) {
            return state.setValue(StairBlock.SHAPE, getShapeProperty(level, state, pos));
        }
        return state;
    }

    private static BlockState neighbour(WorldGenLevel level, BlockPos.MutableBlockPos m, BlockPos pos, Direction dir) {
        return level.getBlockState(m.setWithOffset(pos, dir));
    }

    private static boolean isBlockStairs(BlockState state) {
        return state.getBlock() instanceof StairBlock;
    }

    private static boolean isDifferentStairs(WorldGenLevel level, BlockState state, BlockPos pos, Direction face) {
        BlockState blockstate = level.getBlockState(pos.relative(face));
        return !isBlockStairs(blockstate)
                || blockstate.getValue(StairBlock.FACING) != state.getValue(StairBlock.FACING)
                || blockstate.getValue(StairBlock.HALF) != state.getValue(StairBlock.HALF);
    }

    private static StairsShape getShapeProperty(WorldGenLevel level, BlockState state, BlockPos pos) {
        Direction direction = state.getValue(StairBlock.FACING);
        BlockState blockstate = level.getBlockState(pos.relative(direction));
        if (isBlockStairs(blockstate) && state.getValue(StairBlock.HALF) == blockstate.getValue(StairBlock.HALF)) {
            Direction direction1 = blockstate.getValue(StairBlock.FACING);
            if (direction1.getAxis() != state.getValue(StairBlock.FACING).getAxis()
                    && isDifferentStairs(level, state, pos, direction1.getOpposite())) {
                if (direction1 == direction.getCounterClockWise()) {
                    return StairsShape.OUTER_LEFT;
                }
                return StairsShape.OUTER_RIGHT;
            }
        }

        BlockState blockstate1 = level.getBlockState(pos.relative(direction.getOpposite()));
        if (isBlockStairs(blockstate1) && state.getValue(StairBlock.HALF) == blockstate1.getValue(StairBlock.HALF)) {
            Direction direction2 = blockstate1.getValue(StairBlock.FACING);
            if (direction2.getAxis() != state.getValue(StairBlock.FACING).getAxis()
                    && isDifferentStairs(level, state, pos, direction2)) {
                if (direction2 == direction.getCounterClockWise()) {
                    return StairsShape.INNER_LEFT;
                }
                return StairsShape.INNER_RIGHT;
            }
        }

        return StairsShape.STRAIGHT;
    }
}
