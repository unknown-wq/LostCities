package com.lostbuildings.engine.codec;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

public class BuildingRE {

    public static final Codec<BuildingRE> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.STRING.optionalFieldOf("refpalette").forGetter(l -> Optional.ofNullable(l.refPaletteName)),
                    PaletteRE.CODEC.optionalFieldOf("palette").forGetter(l -> Optional.ofNullable(l.localPalette)),
                    Codec.STRING.fieldOf("filler").forGetter(l -> Character.toString(l.fillerBlock)),
                    Codec.STRING.optionalFieldOf("rubble").forGetter(l -> Optional.ofNullable(l.rubbleBlock)),
                    Codec.INT.optionalFieldOf("mincellars").forGetter(l -> l.minCellars == -1 ? Optional.<Integer>empty() : Optional.of(l.minCellars)),
                    Codec.INT.optionalFieldOf("minfloors").forGetter(l -> l.minFloors == -1 ? Optional.<Integer>empty() : Optional.of(l.minFloors)),
                    Codec.INT.optionalFieldOf("maxcellars").forGetter(l -> l.maxCellars == -1 ? Optional.<Integer>empty() : Optional.of(l.maxCellars)),
                    Codec.INT.optionalFieldOf("maxfloors").forGetter(l -> l.maxFloors == -1 ? Optional.<Integer>empty() : Optional.of(l.maxFloors)),
                    Codec.BOOL.optionalFieldOf("allowDoors").forGetter(l -> Optional.ofNullable(l.getAllowDoors())),
                    Codec.BOOL.optionalFieldOf("allowFillers").forGetter(l -> Optional.ofNullable(l.getAllowFillers())),
                    Codec.BOOL.optionalFieldOf("overrideFloors").forGetter(l -> Optional.ofNullable(l.getOverrideFloors())),
                    Codec.FLOAT.optionalFieldOf("preferslonely").forGetter(l -> l.prefersLonely == 0 ? Optional.<Float>empty() : Optional.of(l.prefersLonely)),
                    Codec.list(PartRef.CODEC).fieldOf("parts").forGetter(l -> l.parts),
                    Codec.list(PartRef.CODEC).optionalFieldOf("parts2").forGetter(l -> Optional.ofNullable(l.parts2))
            ).apply(instance, BuildingRE::new));

    private Identifier name;

    private int minFloors = -1;
    private int minCellars = -1;
    private int maxFloors = -1;
    private int maxCellars = -1;
    private Boolean allowDoors = true;
    private Boolean allowFillers = true;
    private Boolean overrideFloors = false;
    private final char fillerBlock;
    private final String rubbleBlock;
    private float prefersLonely = 0.0f;

    private PaletteRE localPalette = null;
    private final String refPaletteName;

    private final List<PartRef> parts;
    private final List<PartRef> parts2;

    public BuildingRE(Optional<String> refpalette, Optional<PaletteRE> locpalette, String filler, Optional<String> rubble,
                      Optional<Integer> minCellars, Optional<Integer> minFloors, Optional<Integer> maxCellars, Optional<Integer> maxFloors,
                      Optional<Boolean> allowDoors, Optional<Boolean> allowFillers, Optional<Boolean> overrideFloors,
                      Optional<Float> prefersLonely, List<PartRef> partRefs, Optional<List<PartRef>> partRefs2) {
        this.refPaletteName = refpalette.map(String::intern).orElse(null);
        this.localPalette = locpalette.orElse(null);
        this.fillerBlock = filler.charAt(0);
        this.rubbleBlock = rubble.map(String::intern).orElse(null);
        this.minCellars = minCellars.orElse(-1);
        this.maxCellars = maxCellars.orElse(-1);
        this.minFloors = minFloors.orElse(-1);
        this.maxFloors = maxFloors.orElse(-1);
        this.allowDoors = allowDoors.orElse(true);
        this.allowFillers = allowFillers.orElse(true);
        this.overrideFloors = overrideFloors.orElse(false);
        this.prefersLonely = prefersLonely.orElse(0.0f);
        this.parts = partRefs;
        this.parts2 = partRefs2.orElse(null);
    }

    public BuildingRE setRegistryName(Identifier name) {
        this.name = name;
        return this;
    }

    @Nullable
    public Identifier getRegistryName() {
        return name;
    }

    public int getMinFloors() {
        return minFloors;
    }

    public int getMinCellars() {
        return minCellars;
    }

    public int getMaxFloors() {
        return maxFloors;
    }

    public int getMaxCellars() {
        return maxCellars;
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

    public Character getRubbleBlock() {
        return rubbleBlock == null ? null : rubbleBlock.charAt(0);
    }

    public float getPrefersLonely() {
        return prefersLonely;
    }

    public PaletteRE getLocalPalette() {
        return localPalette;
    }

    public String getRefPaletteName() {
        return refPaletteName;
    }

    public List<PartRef> getParts() {
        return parts;
    }

    public List<PartRef> getParts2() {
        return parts2;
    }
}
