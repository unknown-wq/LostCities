package com.lostbuildings.engine.util;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.function.Function;

public class Tools {

    /**
     * Parse a block description ("minecraft:oak_planks" or "minecraft:oak_stairs[facing=north]")
     * into a BlockState. Uses the vanilla block registry as the holder lookup.
     */
    public static BlockState stringToState(String s) {
        if (s.contains("[")) {
            try {
                BlockStateParser.BlockResult parsed =
                        BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, new StringReader(s), false);
                return parsed.blockState();
            } catch (CommandSyntaxException e) {
                throw new RuntimeException("Cannot parse block state: '" + s + "'!", e);
            }
        }
        Block value = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(s)).orElse(null);
        if (value == null) {
            throw new RuntimeException("Cannot find block: '" + s + "'!");
        }
        return value.defaultBlockState();
    }

    public static <T> T getRandomFromList(RandomSource random, List<T> list, Function<T, Float> weightGetter) {
        if (list.isEmpty()) {
            return null;
        }
        float totalweight = 0;
        for (T pair : list) {
            totalweight += weightGetter.apply(pair);
        }
        float r = random.nextFloat() * totalweight;
        for (T pair : list) {
            r -= weightGetter.apply(pair);
            if (r <= 0) {
                return pair;
            }
        }
        return list.get(list.size() - 1);
    }
}
