package com.lostbuildings.engine;

import com.lostbuildings.world.feature.WorldGenBounds;
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
 *
 * <p><b>Neighbour reads are bounded.</b> A pane or wall sitting on the outer edge of a building
 * looks one block further out, and for a building placed in one of the outer cells of the lattice
 * that block is two chunks from the chunk being generated — outside the FEATURES write window (see
 * {@link WorldGenBounds}). Minecraft logged an unsafe-read warning for every one of those, and
 * every shipped part that puts glass or a fence on its footprint border produced hundreds of them
 * per building across the two correction passes.
 *
 * <p>Such a neighbour is treated as <b>air, i.e. "no connection"</b>. Nothing is lost by that: a
 * chunk two out is only guaranteed to have reached {@code STRUCTURE_STARTS}, so it holds no terrain
 * and certainly no neighbouring building — there was never anything there to connect to. How much
 * further along it happens to be depends on which chunks the server is generating in parallel, so
 * reading it was not only unsafe but non-reproducible; answering with a constant makes the result
 * independent of generation order and therefore deterministic.
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
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            return state.setValue(StairBlock.SHAPE, getShapeProperty(level, m, state, pos));
        }
        return state;
    }

    /**
     * The block one step in {@code dir}, or {@link #AIR} when that block is outside the window the
     * worldgen region will answer for. See the class javadoc for why air is the correct answer.
     */
    private static BlockState neighbour(WorldGenLevel level, BlockPos.MutableBlockPos m, BlockPos pos, Direction dir) {
        m.setWithOffset(pos, dir);
        if (!WorldGenBounds.canRead(level, m)) {
            return AIR;
        }
        return level.getBlockState(m);
    }

    private static boolean isBlockStairs(BlockState state) {
        return state.getBlock() instanceof StairBlock;
    }

    private static boolean isDifferentStairs(WorldGenLevel level, BlockPos.MutableBlockPos m, BlockState state,
                                             BlockPos pos, Direction face) {
        BlockState blockstate = neighbour(level, m, pos, face);
        return !isBlockStairs(blockstate)
                || blockstate.getValue(StairBlock.FACING) != state.getValue(StairBlock.FACING)
                || blockstate.getValue(StairBlock.HALF) != state.getValue(StairBlock.HALF);
    }

    private static StairsShape getShapeProperty(WorldGenLevel level, BlockPos.MutableBlockPos m,
                                                BlockState state, BlockPos pos) {
        Direction direction = state.getValue(StairBlock.FACING);
        BlockState blockstate = neighbour(level, m, pos, direction);
        if (isBlockStairs(blockstate) && state.getValue(StairBlock.HALF) == blockstate.getValue(StairBlock.HALF)) {
            Direction direction1 = blockstate.getValue(StairBlock.FACING);
            if (direction1.getAxis() != state.getValue(StairBlock.FACING).getAxis()
                    && isDifferentStairs(level, m, state, pos, direction1.getOpposite())) {
                if (direction1 == direction.getCounterClockWise()) {
                    return StairsShape.OUTER_LEFT;
                }
                return StairsShape.OUTER_RIGHT;
            }
        }

        BlockState blockstate1 = neighbour(level, m, pos, direction.getOpposite());
        if (isBlockStairs(blockstate1) && state.getValue(StairBlock.HALF) == blockstate1.getValue(StairBlock.HALF)) {
            Direction direction2 = blockstate1.getValue(StairBlock.FACING);
            if (direction2.getAxis() != state.getValue(StairBlock.FACING).getAxis()
                    && isDifferentStairs(level, m, state, pos, direction2)) {
                if (direction2 == direction.getCounterClockWise()) {
                    return StairsShape.INNER_LEFT;
                }
                return StairsShape.INNER_RIGHT;
            }
        }

        return StairsShape.STRAIGHT;
    }
}
