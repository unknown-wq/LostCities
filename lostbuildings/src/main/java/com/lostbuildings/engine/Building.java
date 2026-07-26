package com.lostbuildings.engine;

import com.lostbuildings.engine.codec.BuildingRE;
import com.lostbuildings.engine.codec.DataTools;
import com.lostbuildings.engine.codec.PartRef;
import com.lostbuildings.engine.codec.VariantRE;
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
     * Simplified condition test. Only the vertical-position conditions relevant to intact
     * standalone buildings are evaluated (top / ground / cellar / floor / range). Other
     * conditions (inpart, inbuilding, inbiome, chunkx/z, belowpart, issphere, isbuilding) are
     * treated as satisfied.
     */
    private static boolean matches(PartRef ref, boolean isTop, boolean isGround, boolean isCellar, int floor) {
        if (ref.getTop() != null && ref.getTop() != isTop) {
            return false;
        }
        if (ref.getGround() != null && ref.getGround() != isGround) {
            return false;
        }
        if (ref.getCellar() != null && ref.getCellar() != isCellar) {
            return false;
        }
        if (ref.getFloor() != null && ref.getFloor() != floor) {
            return false;
        }
        if (ref.getRange() != null) {
            String[] split = ref.getRange().split(",");
            try {
                int l1 = Integer.parseInt(split[0].trim());
                int l2 = Integer.parseInt(split[1].trim());
                if (floor < l1 || floor > l2) {
                    return false;
                }
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                // Ignore malformed ranges
            }
        }
        return true;
    }

    private static String pick(List<PartRef> list, RandomSource random, boolean isTop, boolean isGround, boolean isCellar, int floor) {
        List<String> partNames = new ArrayList<>();
        for (PartRef ref : list) {
            if (matches(ref, isTop, isGround, isCellar, floor)) {
                partNames.add(ref.getPart());
            }
        }
        if (partNames.isEmpty()) {
            return null;
        }
        return partNames.get(random.nextInt(partNames.size()));
    }

    public String getRandomPart(RandomSource random, boolean isTop, boolean isGround, boolean isCellar, int floor) {
        return pick(parts, random, isTop, isGround, isCellar, floor);
    }

    public String getRandomPart2(RandomSource random, boolean isTop, boolean isGround, boolean isCellar, int floor) {
        return pick(parts2, random, isTop, isGround, isCellar, floor);
    }
}
