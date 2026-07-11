package com.lostbuildings.engine.codec;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class ConditionRE {

    public static final Codec<ConditionRE> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.list(ConditionPart.CODEC).fieldOf("values").forGetter(l -> l.values)
            ).apply(instance, ConditionRE::new));

    private Identifier name;
    private final List<ConditionPart> values;

    public ConditionRE(List<ConditionPart> values) {
        this.values = values;
    }

    public List<ConditionPart> getValues() {
        return values;
    }

    public ConditionRE setRegistryName(Identifier name) {
        this.name = name;
        return this;
    }

    @Nullable
    public Identifier getRegistryName() {
        return name;
    }
}
