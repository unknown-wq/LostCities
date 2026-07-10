package com.lostbuildings.engine;

/**
 * Settings that control how a single building is placed.
 */
public record PlaceSettings(int minFloors, int maxFloors, boolean lighting, boolean spawners, boolean loot, int waterLevel) {
}
