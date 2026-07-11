package com.lostbuildings.engine;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.lostbuildings.engine.codec.BuildingPartRE;
import com.lostbuildings.engine.codec.BuildingRE;
import com.lostbuildings.engine.codec.ConditionRE;
import com.lostbuildings.engine.codec.PaletteRE;
import com.lostbuildings.engine.codec.StyleRE;
import com.lostbuildings.engine.codec.VariantRE;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Loads all Lost Cities building assets from the resource manager. Assets live under
 * data/lostbuildings/lostcities/{variants,palettes,parts,buildings,conditions,styles}/*.json
 * and are decoded with the RE codecs. This replaces the Forge datapack-registry mechanism.
 */
public class AssetLoader {

    public static final String MODID = "lostbuildings";
    private static final String ROOT = "lostcities";

    public static Assets load(ResourceManager rm) {
        Assets assets = new Assets();

        // Variants first (palettes/parts/buildings resolve variant references against them).
        forEach(rm, "variants", VariantRE.CODEC, (name, re) -> {
            re.setRegistryName(Identifier.fromNamespaceAndPath(ROOT, name));
            assets.putVariant(name, re);
        });
        Map<String, VariantRE> variants = assets.getVariants();

        forEach(rm, "palettes", PaletteRE.CODEC, (name, re) -> {
            re.setRegistryName(Identifier.fromNamespaceAndPath(ROOT, name));
            Palette palette = new Palette(Identifier.fromNamespaceAndPath(ROOT, name));
            palette.parsePaletteArray(re, variants);
            assets.putPalette(name, palette);
        });

        forEach(rm, "parts", BuildingPartRE.CODEC, (name, re) -> {
            re.setRegistryName(Identifier.fromNamespaceAndPath(ROOT, name));
            assets.putPart(name, new BuildingPart(re, variants));
        });

        forEach(rm, "buildings", BuildingRE.CODEC, (name, re) -> {
            re.setRegistryName(Identifier.fromNamespaceAndPath(ROOT, name));
            assets.putBuilding(name, new Building(re, variants));
        });

        forEach(rm, "conditions", ConditionRE.CODEC, (name, re) -> {
            re.setRegistryName(Identifier.fromNamespaceAndPath(ROOT, name));
            assets.putCondition(name, re);
        });

        forEach(rm, "styles", StyleRE.CODEC, (name, re) -> {
            re.setRegistryName(Identifier.fromNamespaceAndPath(ROOT, name));
            assets.putStyle(name, new Style(re));
        });

        return assets;
    }

    private static <T> void forEach(ResourceManager rm, String category, Codec<T> codec, BiConsumer<String, T> consumer) {
        String directory = ROOT + "/" + category;
        Map<Identifier, Resource> resources = rm.listResources(directory,
                id -> id.getNamespace().equals(MODID) && id.getPath().endsWith(".json"));
        for (Map.Entry<Identifier, Resource> entry : resources.entrySet()) {
            Identifier id = entry.getKey();
            String name = stem(id.getPath());
            try (BufferedReader reader = entry.getValue().openAsReader()) {
                JsonElement json = JsonParser.parseReader(reader);
                T value = codec.parse(JsonOps.INSTANCE, json).getOrThrow();
                consumer.accept(name, value);
            } catch (IOException | RuntimeException e) {
                throw new RuntimeException("Failed to load Lost Cities asset '" + id + "'!", e);
            }
        }
    }

    private static String stem(String path) {
        int slash = path.lastIndexOf('/');
        String file = slash >= 0 ? path.substring(slash + 1) : path;
        if (file.endsWith(".json")) {
            file = file.substring(0, file.length() - ".json".length());
        }
        return file;
    }
}
