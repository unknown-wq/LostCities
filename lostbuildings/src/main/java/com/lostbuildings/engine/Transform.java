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

    public int rotateX(int x, int z) {
        return switch (this) {
            case ROTATE_NONE -> x;
            case ROTATE_90 -> 15 - z;
            case ROTATE_180 -> 15 - x;
            case ROTATE_270 -> z;
            case MIRROR_X -> 15 - x;
            case MIRROR_Z -> x;
            case MIRROR_90_X -> z;
        };
    }

    public int rotateZ(int x, int z) {
        return switch (this) {
            case ROTATE_NONE -> z;
            case ROTATE_90 -> x;
            case ROTATE_180 -> 15 - z;
            case ROTATE_270 -> 15 - x;
            case MIRROR_X -> z;
            case MIRROR_Z -> 15 - z;
            case MIRROR_90_X -> x;
        };
    }
}
