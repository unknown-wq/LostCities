package com.lostbuildings.engine;

import com.lostbuildings.LostBuildings;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * More efficient representation of a palette useful for generating parts.
 *
 * <p>Palette characters are printable ASCII, so both the block table and the extra information are
 * flat arrays indexed by the character itself: the old {@code HashMap<Character, ...>} boxed a
 * Character for every single block placed (hundreds of thousands per building group).
 *
 * <p>Instances are immutable once constructed and are shared between worldgen threads.
 */
public class CompiledPalette {

    /** Palette characters must be printable ASCII; everything is indexed by the char value. */
    private static final int SIZE = 128;

    /** Number of entries in the expanded random-block table of a weighted palette entry. */
    private static final int RANDOM_TABLE_SIZE = 128;

    /** Either a {@link BlockState} or a {@code BlockState[RANDOM_TABLE_SIZE]}, indexed by char. */
    private final Object[] palette = new Object[SIZE];
    private final Palette.Info[] information = new Palette.Info[SIZE];
    private final Map<BlockState, BlockState> damagedToBlock = new HashMap<>();

    public CompiledPalette(CompiledPalette other, Palette... palettes) {
        System.arraycopy(other.palette, 0, this.palette, 0, SIZE);
        System.arraycopy(other.information, 0, this.information, 0, SIZE);
        this.damagedToBlock.putAll(other.damagedToBlock);
        addPalettes(palettes);
    }

    public CompiledPalette(Palette... palettes) {
        addPalettes(palettes);
    }

    /**
     * Bounds check for a palette character. Characters outside the printable ASCII range cannot be
     * indexed; they are reported once and then ignored instead of blowing up chunk generation.
     */
    private static boolean inRange(char c, Object owner) {
        if (c < SIZE) {
            return true;
        }
        LostBuildings.LOGGER.warn("[lostbuildings] Palette character '{}' (0x{}) in {} is outside the supported ASCII range - ignored",
                c, Integer.toHexString(c), owner);
        return false;
    }

    private int addEntries(BlockState[] randomBlocks, int idx, BlockState c, int cnt) {
        for (int i = 0; i < cnt; i++) {
            if (idx >= randomBlocks.length) {
                return idx;
            }
            randomBlocks[idx++] = c;
        }
        return idx;
    }

    @SuppressWarnings("unchecked")
    private void addPalettes(Palette[] palettes) {
        // First add the straight palette entries
        for (Palette p : palettes) {
            if (p != null) {
                for (Map.Entry<Character, Palette.PE> entry : p.getPalette().entrySet()) {
                    char key = entry.getKey();
                    if (!inRange(key, p.getName())) {
                        continue;
                    }
                    Palette.PE pe = entry.getValue();
                    if (pe.blocks() instanceof BlockState) {
                        palette[key] = pe.blocks();
                    } else if (pe.blocks() instanceof Pair[]) {
                        Pair<Integer, BlockState>[] r = (Pair<Integer, BlockState>[]) pe.blocks();
                        BlockState[] randomBlocks = new BlockState[RANDOM_TABLE_SIZE];
                        int idx = 0;
                        for (Pair<Integer, BlockState> pair : r) {
                            idx = addEntries(randomBlocks, idx, pair.getRight(), pair.getLeft());
                            if (idx >= randomBlocks.length) {
                                break;
                            }
                        }
                        palette[key] = randomBlocks;
                        if (idx < randomBlocks.length) {
                            throw new RuntimeException("Invalid palette entry for '" + key + "'! Not enough blocks in the random list (factor should go up to " + RANDOM_TABLE_SIZE + ")");
                        }
                    } else if (!(pe.blocks() instanceof String)) {
                        if (pe.blocks() == null) {
                            throw new RuntimeException("Invalid palette entry for '" + key + "'!");
                        }
                        palette[key] = pe.blocks();
                    }
                    // Remove information for this character here. If we need it again we will add it below
                    information[key] = null;
                }
            }
        }

        boolean dirty = true;
        while (dirty) {
            dirty = false;
            // Now add the palette entries that refer to other palette entries
            for (Palette p : palettes) {
                if (p != null) {
                    for (Map.Entry<Character, Palette.PE> entry : p.getPalette().entrySet()) {
                        char key = entry.getKey();
                        if (key >= SIZE) {
                            continue;
                        }
                        Palette.PE pe = entry.getValue();
                        if (pe.blocks() instanceof String blocks && !blocks.isEmpty()) {
                            char c = blocks.charAt(0);
                            if (c < SIZE && palette[c] != null && palette[key] == null) {
                                palette[key] = palette[c];
                                information[key] = null;
                                dirty = true;
                            }
                        }
                    }
                }
            }
        }

        for (Palette p : palettes) {
            if (p != null) {
                damagedToBlock.putAll(p.getDamaged());
                for (Map.Entry<Character, Palette.PE> entry : p.getPalette().entrySet()) {
                    char key = entry.getKey();
                    if (key >= SIZE) {
                        continue;
                    }
                    Palette.PE pe = entry.getValue();
                    if (pe.info().isSpecial()) {
                        information[key] = pe.info();
                    }
                }
            }
        }
    }

    public Set<Character> getCharacters() {
        Set<Character> result = new LinkedHashSet<>();
        for (int i = 0; i < SIZE; i++) {
            if (palette[i] != null) {
                result.add((char) i);
            }
        }
        return result;
    }

    public boolean isDefined(Character c) {
        return c != null && c < SIZE && palette[c] != null;
    }

    public boolean isSimple(char c) {
        return c < SIZE && palette[c] instanceof BlockState;
    }

    // Same as get(c, RandomSource) but with a java.util.Random.
    public BlockState get(char c, Random rand) {
        if (c >= SIZE) {
            return null;
        }
        Object o = palette[c];
        if (o instanceof BlockState state) {
            return state;
        } else if (o == null) {
            return null;
        }
        return ((BlockState[]) o)[rand.nextInt(RANDOM_TABLE_SIZE)];
    }

    /**
     * Look up the block for a palette character. Variant entries pick from the expanded random table
     * using the caller's seeded {@link RandomSource}: the old static {@code fastrand128()} counter
     * made building contents both unreproducible for a given world seed and racy between the
     * (many) worldgen threads.
     */
    public BlockState get(char c, RandomSource rand) {
        if (c >= SIZE) {
            return null;
        }
        Object o = palette[c];
        if (o instanceof BlockState state) {
            return state;
        } else if (o == null) {
            return null;
        }
        return ((BlockState[]) o)[rand.nextInt(RANDOM_TABLE_SIZE)];
    }

    public Set<BlockState> getAll(char c) {
        if (c >= SIZE) {
            return Collections.emptySet();
        }
        Object o = palette[c];
        if (o instanceof BlockState state) {
            return Collections.singleton(state);
        } else if (o == null) {
            return Collections.emptySet();
        }
        // The random table contains duplicates by design, so Set.of() would throw here.
        return new LinkedHashSet<>(java.util.Arrays.asList((BlockState[]) o));
    }

    public BlockState canBeDamagedToIronBars(BlockState b) {
        return damagedToBlock.get(b);
    }

    /** Non-boxing variant used by the generation hot loop. */
    public Palette.Info getInfo(char c) {
        return c >= SIZE ? null : information[c];
    }

    public Palette.Info getInfo(Character c) {
        return c == null ? null : getInfo(c.charValue());
    }

    @Nullable
    public Character find(BlockState state) {
        for (int i = 0; i < SIZE; i++) {
            Object o = palette[i];
            if (o instanceof BlockState s) {
                if (s == state) {
                    return (char) i;
                }
            } else if (o != null) {
                for (BlockState randomBlock : (BlockState[]) o) {
                    if (randomBlock == state) {
                        return (char) i;
                    }
                }
            }
        }
        return null;
    }
}
