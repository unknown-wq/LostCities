package com.lostbuildings.world.structure;

import com.lostbuildings.engine.util.Tools;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The blocks an abandoned car is actually made of.
 *
 * <p>Unlike the rest of {@code src/test}, this class boots Minecraft — {@code SharedConstants} plus
 * {@code Bootstrap} — because that is the only way to find out whether a block id still exists. MC
 * 26.2 folded the sixteen dyed variants of every block into a {@code ColorCollection}, so
 * {@code Blocks.RED_CONCRETE} is gone and the compiler catches that one; what the compiler cannot
 * catch is a state that silently resolves to air, and a car made of air is exactly the kind of
 * failure that only shows up by flying to a city and looking at it.
 *
 * <p>No world is needed: {@link StreetCarPlacer#build} hands its writes to a {@link
 * StreetCarPlacer.Sink}, so a test can collect the whole vehicle into a map and inspect it.
 */
class StreetCarPlacerTest {

	private static final int GROUND_Y = 64;

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static Map<BlockPos, BlockState> render(StreetCars.Car car) {
		Map<BlockPos, BlockState> blocks = new HashMap<>();
		StreetCarPlacer.build(car, GROUND_Y, (x, y, z, state) -> blocks.put(new BlockPos(x, y, z), state));
		return blocks;
	}

	private static StreetCars.Car car(StreetCars.Model model, StreetCars.Colour colour, int decay) {
		return new StreetCars.Car(model, StreetCars.Axis.X, true, 0, 0, colour, decay);
	}

	/** The bootstrap really did run: a hand-written state parses to the block it names. */
	@Test
	void minecraftIsBootstrapped() {
		assertEquals(Blocks.SMOOTH_STONE_SLAB,
				Tools.stringToState("minecraft:smooth_stone_slab[type=bottom]").getBlock());
		assertEquals(Blocks.IRON_BARS, Tools.stringToState("minecraft:iron_bars").getBlock());
	}

	/**
	 * Every model, in every colour, at every combination of decay, must be made of real blocks.
	 *
	 * <p>{@code Tools.stringToState} falls back to air for a block it cannot find and only logs a
	 * warning, and {@code Blocks.X} fields that survive a rename can still be the wrong thing. This
	 * walks the entire palette rather than a sample, because it is cheap and because a colour nobody
	 * looked at is exactly where a bad id would hide.
	 */
	@Test
	void everyModelColourAndDecayIsMadeOfRealBlocks() {
		int rendered = 0;
		for (StreetCars.Model model : StreetCars.Model.values()) {
			for (StreetCars.Colour colour : StreetCars.Colour.values()) {
				for (int decay = 0; decay < 32; decay++) {
					Map<BlockPos, BlockState> blocks = render(car(model, colour, decay));
					assertFalse(blocks.isEmpty(), model + "/" + colour + "/" + decay + " rendered nothing");
					for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
						BlockState state = entry.getValue();
						assertNotNull(state);
						assertFalse(state.isAir(),
								model + "/" + colour + " placed air at " + entry.getKey()
										+ " — a block id did not resolve");
					}
					rendered++;
				}
			}
		}
		assertEquals(StreetCars.Model.values().length * StreetCars.Colour.values().length * 32, rendered);
	}

	/** A car stands on the carriageway, one block below the pavement top, and no taller than its model. */
	@Test
	void aCarStandsOnTheCarriageway() {
		for (StreetCars.Model model : StreetCars.Model.values()) {
			Map<BlockPos, BlockState> blocks = render(car(model, StreetCars.Colour.RED, 0));
			int min = Integer.MAX_VALUE;
			int max = Integer.MIN_VALUE;
			for (BlockPos pos : blocks.keySet()) {
				min = Math.min(min, pos.getY());
				max = Math.max(max, pos.getY());
			}
			assertEquals(GROUND_Y, min, model + " does not rest its wheels on the road surface");
			assertTrue(max <= GROUND_Y + model.height() - 1, model + " is taller than it claims");
		}
	}

	/** Nothing may be written outside the car's own footprint — a car must not repaint the road. */
	@Test
	void nothingIsWrittenOutsideTheFootprint() {
		StreetCars.Car car = new StreetCars.Car(StreetCars.Model.BUS, StreetCars.Axis.Z, false,
				-3, 40, StreetCars.Colour.YELLOW, StreetCars.RUSTED);
		for (BlockPos pos : render(car).keySet()) {
			assertTrue(car.covers(pos.getX(), pos.getZ()), pos + " is outside " + car);
		}
	}

	/** Bonnet and boot slope up towards the cabin, and swap ends when the car is turned around. */
	@Test
	void theBonnetAndBootSlopeTowardsTheCabin() {
		StreetCars.Car east = new StreetCars.Car(StreetCars.Model.SALOON, StreetCars.Axis.X, true,
				0, 0, StreetCars.Colour.BLUE, 0);
		BlockState bonnet = StreetCarPlacer.stateAt(east, 0, 0, 1);
		BlockState boot = StreetCarPlacer.stateAt(east, 3, 0, 1);
		assertEquals(Direction.EAST, bonnet.getValue(BlockStateProperties.HORIZONTAL_FACING),
				"a stair's raised half is on its facing side, so the bonnet must face the cabin");
		assertEquals(Direction.WEST, boot.getValue(BlockStateProperties.HORIZONTAL_FACING));

		StreetCars.Car west = new StreetCars.Car(StreetCars.Model.SALOON, StreetCars.Axis.X, false,
				0, 0, StreetCars.Colour.BLUE, 0);
		assertEquals(Direction.WEST,
				StreetCarPlacer.stateAt(west, 3, 0, 1).getValue(BlockStateProperties.HORIZONTAL_FACING));

		StreetCars.Car south = new StreetCars.Car(StreetCars.Model.SALOON, StreetCars.Axis.Z, true,
				0, 0, StreetCars.Colour.BLUE, 0);
		assertEquals(Direction.SOUTH,
				StreetCarPlacer.stateAt(south, 0, 0, 1).getValue(BlockStateProperties.HORIZONTAL_FACING),
				"a car on the Z axis slopes along Z");
	}

	/** Rust dulls the bodywork; a clean car keeps its concrete. */
	@Test
	void rustTurnsConcreteIntoTerracotta() {
		Set<BlockState> clean = new HashSet<>(render(car(StreetCars.Model.SALOON,
				StreetCars.Colour.RED, 0)).values());
		Set<BlockState> rusted = new HashSet<>(render(car(StreetCars.Model.SALOON,
				StreetCars.Colour.RED, StreetCars.RUSTED)).values());
		assertTrue(clean.contains(Blocks.CONCRETE.pick(DyeColor.RED)
				.defaultBlockState()));
		assertFalse(rusted.contains(Blocks.CONCRETE.pick(DyeColor.RED)
				.defaultBlockState()), "rusted bodywork must not still be clean concrete");
		assertTrue(rusted.contains(Blocks.DYED_TERRACOTTA.pick(DyeColor.RED)
				.defaultBlockState()));
	}

	/** Smashed glazing leaves a hole; cobwebs fill it when the car has been standing long enough. */
	@Test
	void smashedGlazingLeavesAHoleOrACobweb() {
		Map<BlockPos, BlockState> intact = render(car(StreetCars.Model.SALOON, StreetCars.Colour.CYAN, 0));
		assertTrue(intact.containsValue(Blocks.GLASS_PANE.defaultBlockState()), "an intact car is glazed");

		Map<BlockPos, BlockState> smashed = render(car(StreetCars.Model.SALOON, StreetCars.Colour.CYAN,
				StreetCars.SMASHED));
		assertFalse(smashed.containsValue(Blocks.GLASS_PANE.defaultBlockState()));
		assertFalse(smashed.containsValue(Blocks.COBWEB.defaultBlockState()));
		assertTrue(smashed.size() < intact.size(), "smashing the glass has to remove blocks");

		Map<BlockPos, BlockState> webbed = render(car(StreetCars.Model.SALOON, StreetCars.Colour.CYAN,
				StreetCars.SMASHED | StreetCars.COBWEBBED));
		assertTrue(webbed.containsValue(Blocks.COBWEB.defaultBlockState()));
	}

	/** Losing the wheels drops the tail onto the road. */
	@Test
	void aWheellessCarSitsOnItsTail() {
		Map<BlockPos, BlockState> whole = render(car(StreetCars.Model.SALOON, StreetCars.Colour.GRAY, 0));
		Map<BlockPos, BlockState> jacked = render(car(StreetCars.Model.SALOON, StreetCars.Colour.GRAY,
				StreetCars.WHEELLESS));
		assertEquals(whole.size() - 2, jacked.size(), "exactly the two tail wheels go missing");
		assertTrue(whole.containsKey(new BlockPos(3, GROUND_Y, 0)));
		assertFalse(jacked.containsKey(new BlockPos(3, GROUND_Y, 0)));
		assertTrue(jacked.containsKey(new BlockPos(0, GROUND_Y, 0)), "the front wheels stay on");
	}

	/** An open door hangs out of the flank as a trapdoor, on the side the stencil put it. */
	@Test
	void anOpenDoorHangsOutOfTheFlank() {
		StreetCars.Car shut = car(StreetCars.Model.SALOON, StreetCars.Colour.GREEN, 0);
		StreetCars.Car open = car(StreetCars.Model.SALOON, StreetCars.Colour.GREEN, StreetCars.DOOR_OPEN);
		BlockState shutPanel = StreetCarPlacer.stateAt(shut, 2, 1, 1);
		BlockState openPanel = StreetCarPlacer.stateAt(open, 2, 1, 1);
		assertEquals(Blocks.CONCRETE.pick(DyeColor.GREEN), shutPanel.getBlock());
		assertEquals(Blocks.IRON_TRAPDOOR, openPanel.getBlock());
		assertTrue(openPanel.getValue(BlockStateProperties.OPEN));
		assertEquals(Direction.SOUTH, openPanel.getValue(BlockStateProperties.HORIZONTAL_FACING),
				"the door on the v=1 flank of an X-axis car swings out to the south");
	}

	/** A burnt-out shell is blackened stone with no paint and no glass anywhere on it. */
	@Test
	void aBurntOutShellIsBlackened() {
		for (StreetCars.Colour colour : StreetCars.Colour.values()) {
			Map<BlockPos, BlockState> blocks = render(car(StreetCars.Model.BURNT, colour, 0));
			assertTrue(blocks.containsValue(Blocks.BLACKSTONE.defaultBlockState()),
					"a burnt shell should be blackstone");
			assertFalse(blocks.containsValue(Blocks.GLASS_PANE.defaultBlockState()),
					"nothing survives a fire glazed");
			for (BlockState state : blocks.values()) {
				assertFalse(state.getBlock() == Blocks.CONCRETE.pick(DyeColor.WHITE)
								|| state.getBlock() == Blocks.DYED_TERRACOTTA.pick(DyeColor.WHITE),
						colour + " burnt shell still shows bodywork paint");
			}
		}
	}

	/** The van's grille and the wreck's crushed roof are the details that tell them apart. */
	@Test
	void theVanHasAGrilleAndTheWreckACrushedRoof() {
		assertTrue(render(car(StreetCars.Model.VAN, StreetCars.Colour.WHITE, 0))
				.containsValue(Blocks.IRON_BARS.defaultBlockState()));
		assertTrue(render(car(StreetCars.Model.WRECK, StreetCars.Colour.WHITE, 0)).values().stream()
				.anyMatch(state -> state.getBlock() == Blocks.SMOOTH_STONE_SLAB));
		assertFalse(render(car(StreetCars.Model.SALOON, StreetCars.Colour.WHITE, 0))
				.containsValue(Blocks.IRON_BARS.defaultBlockState()), "a saloon has no lorry grille");
	}
}
