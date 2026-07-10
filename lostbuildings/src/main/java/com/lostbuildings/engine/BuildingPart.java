package com.lostbuildings.engine;

import com.lostbuildings.engine.codec.BuildingPartRE;
import com.lostbuildings.engine.codec.DataTools;
import com.lostbuildings.engine.codec.PartMeta;
import com.lostbuildings.engine.codec.VariantRE;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * A structure part.
 */
public class BuildingPart implements IBuildingPart {

    private final Identifier name;

    // Data per height level
    private final String[] slices;

    // Dimension (should be less than 16x16)
    private final int xSize;
    private final int zSize;

    // Optimized version organized as xSize*zSize vertical strings
    private char[][] vslices = null;

    private Palette localPalette = null;
    private String refPaletteName;

    private final Map<String, Object> metadata = new HashMap<>();

    public BuildingPart(BuildingPartRE object, Map<String, VariantRE> variants) {
        name = object.getRegistryName();
        xSize = object.getxSize();
        zSize = object.getzSize();
        slices = object.getSlices();
        if (object.getLocalPalette() != null) {
            localPalette = new Palette("__local__" + name.getPath());
            localPalette.parsePaletteArray(object.getLocalPalette(), variants);
        } else if (object.getRefPaletteName() != null) {
            refPaletteName = object.getRefPaletteName();
        }
        if (object.getMetadata() != null) {
            for (PartMeta meta : object.getMetadata()) {
                String key = meta.key();
                if (meta.i() != null) {
                    metadata.put(key, meta.i());
                } else if (meta.f() != null) {
                    metadata.put(key, meta.f());
                } else if (meta.bool() != null) {
                    metadata.put(key, meta.bool());
                } else if (meta.chr() != null) {
                    metadata.put(key, meta.chr().charAt(0));
                } else if (meta.str() != null) {
                    metadata.put(key, meta.str());
                }
            }
        }
    }

    @Override
    public Character getMetaChar(String key) {
        return (Character) metadata.get(key);
    }

    @Override
    public Integer getMetaInteger(String key) {
        return (Integer) metadata.get(key);
    }

    @Override
    public boolean getMetaBoolean(String key) {
        Object o = metadata.get(key);
        return o instanceof Boolean ? (Boolean) o : false;
    }

    @Override
    public Float getMetaFloat(String key) {
        return (Float) metadata.get(key);
    }

    @Override
    public String getMetaString(String key) {
        return (String) metadata.get(key);
    }

    public String getRefPaletteName() {
        return refPaletteName;
    }

    @Override
    public String getName() {
        return DataTools.toName(name);
    }

    public Identifier getId() {
        return name;
    }

    /**
     * Vertical slices, organized by z*xSize+x.
     */
    @Override
    public char[][] getVslices() {
        if (vslices == null) {
            vslices = new char[xSize * zSize][];
            for (int x = 0; x < xSize; x++) {
                for (int z = 0; z < zSize; z++) {
                    StringBuilder vs = new StringBuilder();
                    boolean empty = true;
                    for (int y = 0; y < slices.length; y++) {
                        Character c = getC(x, y, z);
                        vs.append(c);
                        if (c != ' ') {
                            empty = false;
                        }
                    }
                    if (empty) {
                        vslices[z * xSize + x] = null;
                    } else {
                        vslices[z * xSize + x] = vs.toString().toCharArray();
                    }
                }
            }
        }
        return vslices;
    }

    @Override
    public char[] getVSlice(int x, int z) {
        return getVslices()[z * xSize + x];
    }

    @Override
    public Palette getLocalPalette(Assets assets) {
        if (localPalette == null && refPaletteName != null) {
            localPalette = assets.getPalette(DataTools.normalize(refPaletteName));
        }
        return localPalette;
    }

    @Override
    public int getSliceCount() {
        return slices.length;
    }

    public String getSlice(int i) {
        return slices[i];
    }

    public String[] getSlices() {
        return slices;
    }

    @Override
    public int getXSize() {
        return xSize;
    }

    @Override
    public int getZSize() {
        return zSize;
    }

    public Character getPaletteChar(int x, int y, int z) {
        return slices[y].charAt(z * xSize + x);
    }

    public Character getC(int x, int y, int z) {
        return slices[y].charAt(z * xSize + x);
    }
}
