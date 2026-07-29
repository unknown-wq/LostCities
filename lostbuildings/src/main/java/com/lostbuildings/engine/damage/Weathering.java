package com.lostbuildings.engine.damage;

import com.lostbuildings.engine.BlockStates;
import com.lostbuildings.engine.CompiledPalette;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Turns a damage number into an actual block swap — the world-facing half of {@link DamageArea}.
 *
 * <p>Three outcomes, cheapest first:
 * <ol>
 *   <li><b>Survives.</b> Most blocks, most of the time.</li>
 *   <li><b>Weathers.</b> The block is replaced by a cracked / mossy variant of itself, keeping all
 *       of its block-state properties (a stone-brick stair becomes a <em>mossy</em> stone-brick
 *       stair facing the same way). This is the "edge blocks swapped to a damaged palette variant"
 *       the plan asks for, and it is what makes light damage read as age rather than as holes.</li>
 *   <li><b>Breaks.</b> The palette's own {@code damaged} entry (the shipped data maps every wall
 *       material to {@code iron_bars}), or, failing that, air — water below sea level, so a blown
 *       hole in a seaside building floods like the original did.</li>
 * </ol>
 *
 * <p>The original gated this on the {@code lostcities:not_breakable} / {@code easy_breakable} block
 * tags. Those tags are not part of the ported data (and block tags are agent G's territory), so the
 * two lists are hard-coded here from the same intent: nothing indestructible is ever eaten, and
 * glass-like blocks shatter far more readily than masonry.
 */
public final class Weathering {

	/** Glass and friends take this much extra damage — the original's EASY_BREAKABLE tag, inlined. */
	private static final float EASY_BREAKABLE_FACTOR = 2.5f;

	/** Probability that a block inside the "light damage" band weathers instead of breaking. */
	private static final float WEATHER_CHANCE = 0.6f;

	private Weathering() {
	}

	/**
	 * Apply damage to one block.
	 *
	 * @param state      the block the palette wants to place
	 * @param damage     total damage at this position ({@link DamageArea#damageAt})
	 * @param y          world Y (decides whether a destroyed block floods)
	 * @param waterLevel sea level
	 * @param palette    the building's compiled palette, for its {@code damaged} mapping
	 * @param rand       the building's random source — the ONLY entropy used here, so that the same
	 *                   seed always produces the same ruin
	 * @return the block to place; {@link BlockStates#AIR} means "destroyed"
	 */
	public static BlockState damage(BlockState state, float damage, int y, int waterLevel,
	                                CompiledPalette palette, RandomSource rand) {
		if (damage <= 0.0f || state == null) {
			return state;      // pristine: not a single number drawn from rand
		}
		if (isIndestructible(state)) {
			return state;
		}
		float effective = isEasyBreakable(state) ? damage * EASY_BREAKABLE_FACTOR : damage;
		if (rand.nextFloat() > effective) {
			return state;      // survived
		}

		if (effective < DamageArea.BLOCK_DAMAGE_CHANCE) {
			BlockState weathered = weather(state);
			if (weathered != null && rand.nextFloat() < WEATHER_CHANCE) {
				return weathered;
			}
			BlockState damaged = palette.canBeDamagedToIronBars(state);
			if (damaged != null && rand.nextFloat() < DamageArea.DAMAGED_VARIANT_CHANCE) {
				return damaged;
			}
		}
		return y <= waterLevel ? BlockStates.WATER : BlockStates.AIR;
	}

	/**
	 * The cracked / mossy twin of a block, preserving every property the two blocks share, or
	 * {@code null} when this material has no aged variant.
	 */
	@Nullable
	public static BlockState weather(BlockState state) {
		Block replacement = Table.MAP.get(state.getBlock());
		if (replacement == null) {
			return null;
		}
		BlockState result = replacement.defaultBlockState();
		for (Property<?> property : state.getProperties()) {
			result = copyProperty(state, result, property);
		}
		return result;
	}

	private static <T extends Comparable<T>> BlockState copyProperty(BlockState from, BlockState to, Property<T> property) {
		if (!to.hasProperty(property)) {
			return to;
		}
		return to.setValue(property, from.getValue(property));
	}

	private static boolean isIndestructible(BlockState state) {
		if (state.isAir() || state.liquid()) {
			return true;
		}
		Block block = state.getBlock();
		return block == Blocks.BEDROCK || block == Blocks.BARRIER || block == Blocks.OBSIDIAN
				|| block == Blocks.STRUCTURE_VOID || block == Blocks.STRUCTURE_BLOCK;
	}

	private static boolean isEasyBreakable(BlockState state) {
		Block block = state.getBlock();
		return block instanceof net.minecraft.world.level.block.TransparentBlock
				|| block instanceof net.minecraft.world.level.block.IronBarsBlock
				|| block == Blocks.GLASS
				|| block == Blocks.GLASS_PANE;
	}

	/**
	 * Lazily-built block → aged-block table. Held in a nested class so that merely loading
	 * {@link Weathering} does not touch {@link Blocks}: the unit tests run without a Minecraft
	 * bootstrap and static access to the block registry would blow up there.
	 */
	private static final class Table {

		private static final Map<Block, Block> MAP = build();

		private static Map<Block, Block> build() {
			Map<Block, Block> map = new HashMap<>();
			// Stone bricks are what almost every shipped palette is made of; give them both
			// variants so a ruin is speckled rather than uniformly mossy.
			map.put(Blocks.STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS);
			map.put(Blocks.INFESTED_STONE_BRICKS, Blocks.INFESTED_CRACKED_STONE_BRICKS);
			map.put(Blocks.CHISELED_STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS);
			map.put(Blocks.STONE_BRICK_STAIRS, Blocks.MOSSY_STONE_BRICK_STAIRS);
			map.put(Blocks.STONE_BRICK_SLAB, Blocks.MOSSY_STONE_BRICK_SLAB);
			map.put(Blocks.STONE_BRICK_WALL, Blocks.MOSSY_STONE_BRICK_WALL);

			map.put(Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE);
			map.put(Blocks.COBBLESTONE_STAIRS, Blocks.MOSSY_COBBLESTONE_STAIRS);
			map.put(Blocks.COBBLESTONE_SLAB, Blocks.MOSSY_COBBLESTONE_SLAB);
			map.put(Blocks.COBBLESTONE_WALL, Blocks.MOSSY_COBBLESTONE_WALL);

			map.put(Blocks.NETHER_BRICKS, Blocks.CRACKED_NETHER_BRICKS);
			map.put(Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS);
			map.put(Blocks.DEEPSLATE_BRICKS, Blocks.CRACKED_DEEPSLATE_BRICKS);
			map.put(Blocks.DEEPSLATE_TILES, Blocks.CRACKED_DEEPSLATE_TILES);
			return Map.copyOf(map);
		}
	}
}
