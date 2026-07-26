package com.lostbuildings.engine;

import net.minecraft.world.level.block.Rotation;

/**
 * Rotation/mirror transform for a building part. The railway (RailShape) table from the
 * original Lost Cities Transform is intentionally not ported (no rails in this mod).
 */
public enum Transform {
    ROTATE_NONE(Rotation.NONE),
    ROTATE_90(Rotation.CLOCKWISE_90),
    ROTATE_180(Rotation.CLOCKWISE_180),
    ROTATE_270(Rotation.COUNTERCLOCKWISE_90),
    MIRROR_X(Rotation.CLOCKWISE_180),
    MIRROR_Z(Rotation.CLOCKWISE_180),
    MIRROR_90_X(Rotation.CLOCKWISE_90);

    private final Rotation mcRotation;

    Transform(Rotation mcRotation) {
        this.mcRotation = mcRotation;
    }

    public Rotation getMcRotation() {
        return mcRotation;
    }

    public static Transform randomRotation() {
        return switch ((int) (Math.random() * 4)) {
            case 0 -> ROTATE_NONE;
            case 1 -> ROTATE_90;
            case 2 -> ROTATE_180;
            case 3 -> ROTATE_270;
            default -> ROTATE_NONE;
        };
    }

    public Transform getOpposite() {
        return switch (this) {
            case ROTATE_NONE -> ROTATE_NONE;
            case ROTATE_270 -> ROTATE_90;
            case ROTATE_180 -> ROTATE_180;
            case ROTATE_90 -> ROTATE_270;
            case MIRROR_X -> MIRROR_X;
            case MIRROR_Z -> MIRROR_Z;
            case MIRROR_90_X -> MIRROR_90_X;
        };
    }

    /** 16x16 convenience overload — prefer {@link #rotateX(int, int, int, int)}. */
    public int rotateX(int x, int z) {
        return rotateX(x, z, 16, 16);
    }

    /** 16x16 convenience overload — prefer {@link #rotateZ(int, int, int, int)}. */
    public int rotateZ(int x, int z) {
        return rotateZ(x, z, 16, 16);
    }

    /**
     * Rotate a part-local x coordinate. The mirror axis is derived from the real part size: several
     * parts are not 16x16 (building_front1/2/3 are 2-3 wide, stairs1/2 are 2 wide, stairsbig and
     * stairsnormal are 6 wide) and the hardcoded {@code 15 - x} used to shove them out of place.
     */
    public int rotateX(int x, int z, int xSize, int zSize) {
        return switch (this) {
            case ROTATE_NONE -> x;
            case ROTATE_90 -> (zSize - 1) - z;
            case ROTATE_180 -> (xSize - 1) - x;
            case ROTATE_270 -> z;
            case MIRROR_X -> (xSize - 1) - x;
            case MIRROR_Z -> x;
            case MIRROR_90_X -> z;
        };
    }

    /** Rotate a part-local z coordinate. See {@link #rotateX(int, int, int, int)}. */
    public int rotateZ(int x, int z, int xSize, int zSize) {
        return switch (this) {
            case ROTATE_NONE -> z;
            case ROTATE_90 -> x;
            case ROTATE_180 -> (zSize - 1) - z;
            case ROTATE_270 -> (xSize - 1) - x;
            case MIRROR_X -> z;
            case MIRROR_Z -> (zSize - 1) - z;
            case MIRROR_90_X -> x;
        };
    }
}
