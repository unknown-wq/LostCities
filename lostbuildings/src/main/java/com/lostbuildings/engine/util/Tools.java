package com.lostbuildings.engine.util;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class Tools {

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("LostBuildings/Tools");

    /**
     * Vanilla block ids that were renamed between the 1.20 source data and MC 26.2.
     * Applied (base id only) before registry lookup so the 1:1-copied palettes still resolve.
     * TODO(port-26.2): extend if the boot log reports more unresolved blocks.
     */
    private static final Map<String, String> RENAMES = Map.of(
            "minecraft:chain", "minecraft:iron_chain"
    );

    /**
     * Parse a block description ("minecraft:oak_planks" or "minecraft:oak_stairs[facing=north]")
     * into a BlockState. Uses the vanilla block registry as the holder lookup. Unknown blocks
     * (renamed/removed vanilla ids) do NOT crash worldgen — they log once and fall back to AIR.
     */
    public static BlockState stringToState(String s) {
        // Legacy Lost Cities palettes use the pre-1.13 "block@meta" metadata form. The old
        // mod resolved these via BlockStateData.upgradeBlock (absent from the ported source).
        // The whole dataset contains exactly one such entry, so map the known case and fall
        // back to the base block for any other @meta form. TODO(port-26.2): if more legacy
        // metadata surfaces, port a fuller upgrade table.
        if (s.indexOf('@') >= 0) {
            s = upgradeLegacyMeta(s);
        }
        // Apply vanilla renames to the base id (before any [state] suffix).
        int br = s.indexOf('[');
        String base = br >= 0 ? s.substring(0, br) : s;
        String rest = br >= 0 ? s.substring(br) : "";
        String renamed = RENAMES.get(base);
        if (renamed != null) {
            base = renamed;
            s = base + rest;
        }
        if (br >= 0) {
            try {
                BlockStateParser.BlockResult parsed =
                        BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, new StringReader(s), false);
                return parsed.blockState();
            } catch (CommandSyntaxException e) {
                LOGGER.warn("Cannot parse block state '{}' — falling back to AIR", s);
                return Blocks.AIR.defaultBlockState();
            }
        }
        Block value = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(s)).orElse(null);
        if (value == null) {
            LOGGER.warn("Cannot find block '{}' — falling back to AIR", s);
            return Blocks.AIR.defaultBlockState();
        }
        return value.defaultBlockState();
    }

    /**
     * Convert a legacy pre-1.13 "block@meta" description to a modern block id. Only the one
     * form present in the shipped data is mapped explicitly; anything else drops the metadata
     * and resolves to the base block (cosmetic downgrade at worst).
     */
    private static String upgradeLegacyMeta(String s) {
        return switch (s) {
            case "minecraft:red_sandstone@2" -> "minecraft:smooth_red_sandstone";
            default -> s.substring(0, s.indexOf('@'));
        };
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
