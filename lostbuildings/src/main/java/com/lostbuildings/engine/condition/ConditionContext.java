package com.lostbuildings.engine.condition;

import com.lostbuildings.engine.BuildingRole;

/**
 * Where in a building the engine currently is — everything a {@code ConditionTest} can ask about.
 *
 * <p>Before wave 2 the engine answered "yes" to every condition on a loot/mob selector and only
 * honoured the vertical ones on a part reference. This record is what makes an honest answer
 * possible (IMPROVEMENTS #2): it is threaded from {@code BuildingEngine.generateBuilding} down into
 * the block loop, so a chest on the fifth floor and a chest in the cellar genuinely resolve their
 * condition differently.
 *
 * <p>Pure data, no Minecraft types — {@link ConditionMatcher} is unit-tested against it.
 *
 * @param floor        storey index: 0 = ground floor, positive = upward, negative = cellar
 * @param topFloor     index of the building's top storey
 * @param cellars      how many cellar storeys this building has
 * @param partName     bare name of the part being placed, or {@code null} when not inside a part
 * @param buildingName bare name of the building, or {@code null}
 * @param chunkX       chunk X of the cell
 * @param chunkZ       chunk Z of the cell
 * @param role         the building's role, used to bias loot and spawner weights
 */
public record ConditionContext(int floor, int topFloor, int cellars,
                               String partName, String buildingName,
                               int chunkX, int chunkZ, BuildingRole role) {

	public ConditionContext {
		role = role == null ? BuildingRole.RESIDENTIAL : role;
	}

	public boolean isTop() {
		return floor == topFloor;
	}

	public boolean isGround() {
		return floor == 0;
	}

	public boolean isCellar() {
		return floor < 0;
	}

	/** The same context on a different storey — the engine walks storeys, everything else is fixed. */
	public ConditionContext atFloor(int newFloor) {
		return new ConditionContext(newFloor, topFloor, cellars, partName, buildingName, chunkX, chunkZ, role);
	}

	/** The same context, now inside a named part. */
	public ConditionContext inPart(String newPartName) {
		return new ConditionContext(floor, topFloor, cellars, newPartName, buildingName, chunkX, chunkZ, role);
	}
}
