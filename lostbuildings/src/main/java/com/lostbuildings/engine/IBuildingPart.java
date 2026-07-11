package com.lostbuildings.engine;

public interface IBuildingPart {
    Character getMetaChar(String key);

    Integer getMetaInteger(String key);

    boolean getMetaBoolean(String key);

    Float getMetaFloat(String key);

    String getMetaString(String key);

    String getName();

    char[][] getVslices();

    char[] getVSlice(int x, int z);

    Palette getLocalPalette(Assets assets);

    int getSliceCount();

    int getXSize();

    int getZSize();
}
