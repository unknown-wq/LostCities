package com.lostbuildings.engine.damage;

/**
 * One damage sphere — the reduced port of {@code mcjty.lostcities.worldgen.lost.Explosion}.
 *
 * <p>Deliberately holds plain {@code int}s instead of a {@code BlockPos}: this class and
 * {@link DamageArea} are the damage <em>maths</em>, and keeping them free of Minecraft types is what
 * lets CI test them (and prove the determinism guarantee) without bootstrapping the game.
 *
 * <p>The damage curve is the original's: {@code 3 * (radius - distance) / radius}, i.e. 0 at the
 * surface of the sphere and 3 at its centre, so the middle of a blast is "certainly destroyed"
 * (any chance above 1) and the rim only nibbles.
 */
public record Explosion(int radius, int centerX, int centerY, int centerZ) {

	public Explosion {
		if (radius < 1) {
			radius = 1;
		}
	}

	public int sqRadius() {
		return radius * radius;
	}

	/**
	 * How much damage this sphere does at a point. 0 means "outside the blast, do not touch".
	 */
	public float damageAt(int x, int y, int z) {
		long dx = (long) x - centerX;
		long dy = (long) y - centerY;
		long dz = (long) z - centerZ;
		long sq = dx * dx + dy * dy + dz * dz;
		if (sq >= sqRadius()) {
			return 0.0f;
		}
		double d = Math.sqrt((double) sq);
		return (float) (3.0 * (radius - d) / radius);
	}

	/**
	 * Whether the sphere reaches into an axis-aligned box at all — the cheap rejection the original
	 * did with {@code GeometryTools.squaredDistanceBoxPoint}, inlined so that no Minecraft AABB is
	 * needed.
	 */
	public boolean intersects(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		long dx = axisDistance(centerX, minX, maxX);
		long dy = axisDistance(centerY, minY, maxY);
		long dz = axisDistance(centerZ, minZ, maxZ);
		return dx * dx + dy * dy + dz * dz <= (long) sqRadius();
	}

	private static long axisDistance(int v, int min, int max) {
		if (v < min) {
			return min - (long) v;
		}
		if (v > max) {
			return v - (long) max;
		}
		return 0L;
	}
}
