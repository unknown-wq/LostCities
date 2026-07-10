package com.lostbuildings.engine;

import com.lostbuildings.engine.codec.BlockEntry;
import com.lostbuildings.engine.codec.DataTools;
import com.lostbuildings.engine.codec.PaletteEntry;
import com.lostbuildings.engine.codec.PaletteRE;
import com.lostbuildings.engine.codec.VariantRE;
import com.lostbuildings.engine.util.Tools;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A palette of materials as used by building parts.
 */
public class Palette {

    private final Identifier name;
    private final Map<Character, PE> palette = new HashMap<>();
    private final Map<BlockState, BlockState> damaged = new HashMap<>();

    public Palette(Identifier name) {
        this.name = name;
    }

    public Palette(String name) {
        this.name = DataTools.fromName(name);
    }

    public void merge(Palette other) {
        palette.putAll(other.palette);
        damaged.putAll(other.damaged);
    }

    public String getName() {
        return DataTools.toName(name);
    }

    public Identifier getId() {
        return name;
    }

    public Map<BlockState, BlockState> getDamaged() {
        return damaged;
    }

    public Map<Character, PE> getPalette() {
        return palette;
    }

    /**
     * Parse a palette definition. Variant references are resolved against the supplied variant map.
     */
    public void parsePaletteArray(PaletteRE paletteRE, Map<String, VariantRE> variants) {
        for (PaletteEntry entry : paletteRE.getPaletteEntries()) {
            Character c = entry.getChr().charAt(0);
            BlockState dmg = null;
            if (entry.getDamaged() != null) {
                dmg = Tools.stringToState(entry.getDamaged());
            }
            Info info = new Info(entry.getMob(), entry.getLoot(), entry.getTorch() != null && entry.getTorch(),
                    entry.getTag());

            if (entry.getBlock() != null) {
                BlockState state = Tools.stringToState(entry.getBlock());
                palette.put(c, new PE(state, info));
                if (dmg != null) {
                    damaged.put(state, dmg);
                }
            } else if (entry.getVariant() != null) {
                String variantName = DataTools.normalize(entry.getVariant());
                VariantRE variant = variants.get(variantName);
                if (variant == null) {
                    throw new RuntimeException("Cannot find variant: '" + entry.getVariant() + "'!");
                }
                List<Pair<Integer, BlockState>> blocks = new ArrayList<>();
                for (BlockEntry ob : variant.getBlocks()) {
                    BlockState state = Tools.stringToState(ob.block());
                    blocks.add(Pair.of(ob.random(), state));
                    if (dmg != null) {
                        damaged.put(state, dmg);
                    }
                }
                addMappingViaState(c, blocks, info);
            } else if (entry.getFrompalette() != null) {
                palette.put(c, new PE(entry.getFrompalette(), info));
            } else if (entry.getBlocks() != null) {
                List<Pair<Integer, BlockState>> blocks = new ArrayList<>();
                for (BlockEntry ob : entry.getBlocks()) {
                    BlockState state = Tools.stringToState(ob.block());
                    blocks.add(Pair.of(ob.random(), state));
                    if (dmg != null) {
                        damaged.put(state, dmg);
                    }
                }
                addMappingViaState(c, blocks, info);
            } else {
                throw new RuntimeException("Illegal palette " + name + "!");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void addMappingViaState(char c, List<Pair<Integer, BlockState>> randomBlocks, Info info) {
        palette.put(c, new PE(randomBlocks.toArray(new Pair[0]), info));
    }

    public record Info(String mobId, String loot, boolean isTorch, CompoundTag tag) {
        public boolean isSpecial() {
            return mobId != null || loot != null || isTorch || tag != null;
        }
    }

    public record PE(Object blocks, Info info) {
    }
}
