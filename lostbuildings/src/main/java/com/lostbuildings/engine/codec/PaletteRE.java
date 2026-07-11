package com.lostbuildings.engine.codec;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A palette of materials as used by building parts.
 */
public class PaletteRE {

    public static final Codec<PaletteRE> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.list(PaletteEntry.CODEC).fieldOf("palette").forGetter(l -> l.paletteEntries)
            ).apply(instance, PaletteRE::new));

    private Identifier name;
    private final List<PaletteEntry> paletteEntries = new ArrayList<>();

    public PaletteRE(List<PaletteEntry> entries) {
        paletteEntries.addAll(entries);
    }

    public List<PaletteEntry> getPaletteEntries() {
        return paletteEntries;
    }

    public PaletteRE setRegistryName(Identifier name) {
        this.name = name;
        return this;
    }

    @Nullable
    public Identifier getRegistryName() {
        return name;
    }
}
