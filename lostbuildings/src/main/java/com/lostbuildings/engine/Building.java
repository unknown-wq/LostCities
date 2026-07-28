package com.lostbuildings.engine;

import com.lostbuildings.engine.codec.BuildingRE;
import com.lostbuildings.engine.codec.DataTools;
import com.lostbuildings.engine.codec.PartRef;
import com.lostbuildings.engine.codec.VariantRE;
import com.lostbuildings.engine.condition.ConditionContext;
import com.lostbuildings.engine.condition.ConditionMatcher;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Building {

    private final Identifier name;

    private final int minFloors;
    private final int minCellars;
    private final int maxFloors;
    private final int maxCellars;
    private final Boolean allowDoors;
    private final Boolean allowFillers;
    private final Boolean overrideFloors;
    private final char fillerBlock;
    private final Character rubbleBlock;
    private final float prefersLonely;

    private Palette localPalette = null;
    private final String refPaletteName;

    private final List<PartRef> parts = new ArrayList<>();
    private final List<PartRef> parts2 = new ArrayList<>();

    public Building(BuildingRE object, Map<String, VariantRE> variants) {
        name = object.getRegistryName();
        minFloors = object.getMinFloors();
        minCellars = object.getMinCellars();
        maxFloors = object.getMaxFloors();
        maxCellars = object.getMaxCellars();
        allowDoors = object.getAllowDoors();
        allowFillers = object.getAllowFillers();
        overrideFloors = object.getOverrideFloors();
        prefersLonely = object.getPrefersLonely();
        fillerBlock = object.getFillerBlock();
        rubbleBlock = object.getRubbleBlock();
        if (object.getLocalPalette() != null) {
            Palette local = new Palette("__local__" + object.getRegistryName().getPath());
            local.parsePaletteArray(object.getLocalPalette(), variants);
            localPalette = local;
            refPaletteName = null;
        } else {
            refPaletteName = object.getRefPaletteName();
        }
        if (object.getParts() != null) {
            parts.addAll(object.getParts());
        }
        if (object.getParts2() != null) {
            parts2.addAll(object.getParts2());
        }
    }

    public String getName() {
        return DataTools.toName(name);
    }

    public Identifier getId() {
        return name;
    }

    /** Load-time pass: resolve a {@code refpalette} reference once, before the assets go live. */
    void resolveLocalPalette(Assets assets) {
        if (localPalette == null && refPaletteName != null) {
            localPalette = assets.getPalette(DataTools.normalize(refPaletteName));
        }
    }

    public Palette getLocalPalette(Assets assets) {
        return localPalette;
    }

    public float getPrefersLonely() {
        return prefersLonely;
    }

    public int getMaxFloors() {
        return maxFloors;
    }

    public int getMaxCellars() {
        return maxCellars;
    }

    public int getMinFloors() {
        return minFloors;
    }

    public int getMinCellars() {
        return minCellars;
    }

    public Boolean getAllowDoors() {
        return allowDoors;
    }

    public Boolean getAllowFillers() {
        return allowFillers;
    }

    public Boolean getOverrideFloors() {
        return overrideFloors;
    }

    public char getFillerBlock() {
        return fillerBlock;
    }

    @Nullable
    public Character getRubbleBlock() {
        return rubbleBlock;
    }

    /**
     * Pick a part for a position. Conditions are evaluated by {@link ConditionMatcher}, which since
     * wave 2 honours {@code inpart}/{@code inbuilding}/{@code chunkx}/{@code chunkz}/{@code
     * issphere} as well as the vertical ones; see that class for what is still treated as satisfied.
     */
    private static String pick(List<PartRef> list, RandomSource random, ConditionContext ctx) {
        List<String> partNames = new ArrayList<>();
        for (PartRef ref : list) {
            if (ConditionMatcher.matches(ref, ctx)) {
                partNames.add(ref.getPart());
            }
        }
        if (partNames.isEmpty()) {
            return null;
        }
        return partNames.get(random.nextInt(partNames.size()));
    }

    /**
     * Position-aware part selection (the wave-2 form). The context knows the storey, the building's
     * top storey and its cellar count, so a cellar or a top floor is asked for honestly rather than
     * described by three loose booleans.
     */
    public String getRandomPart(RandomSource random, ConditionContext ctx) {
        return pick(parts, random, ctx);
    }

    public String getRandomPart2(RandomSource random, ConditionContext ctx) {
        return pick(parts2, random, ctx);
    }

    /**
     * Pre-wave-2 form, kept so nothing outside the engine has to change. The booleans are folded
     * back into a {@link ConditionContext}: {@code isCellar} is expressed as a negative storey and
     * {@code isTop} by declaring the current storey to be the top one.
     */
    public String getRandomPart(RandomSource random, boolean isTop, boolean isGround, boolean isCellar, int floor) {
        return pick(parts, random, contextFor(isTop, isGround, isCellar, floor));
    }

    public String getRandomPart2(RandomSource random, boolean isTop, boolean isGround, boolean isCellar, int floor) {
        return pick(parts2, random, contextFor(isTop, isGround, isCellar, floor));
    }

    private ConditionContext contextFor(boolean isTop, boolean isGround, boolean isCellar, int floor) {
        int f = floor;
        if (isCellar && f >= 0) {
            f = -1 - f;
        } else if (isGround) {
            f = 0;
        }
        return new ConditionContext(f, isTop ? f : f + 1, isCellar ? 1 : 0, null, getName(), 0, 0, null);
    }
}
