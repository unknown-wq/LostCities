package com.lostbuildings.engine;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.lostbuildings.LostBuildings;
import com.lostbuildings.engine.codec.BuildingPartRE;
import com.lostbuildings.engine.codec.BuildingRE;
import com.lostbuildings.engine.codec.CityStyleRE;
import com.lostbuildings.engine.codec.ConditionRE;
import com.lostbuildings.engine.codec.MultiBuildingRE;
import com.lostbuildings.engine.codec.PaletteRE;
import com.lostbuildings.engine.codec.StyleRE;
import com.lostbuildings.engine.codec.VariantRE;
import com.lostbuildings.engine.codec.WorldStyleRE;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * Loads all Lost Cities building assets from the resource manager. Assets live under
 * data/lostbuildings/lostcities/{variants,palettes,parts,buildings,conditions,styles,
 * multibuildings,citystyles,worldstyles}/*.json and are decoded with the RE codecs. This replaces
 * the Forge datapack-registry mechanism.
 *
 * <p>The last three directories are the wave-2 addition (PORT #9 / #1). They may legitimately be
 * empty — nothing in the engine requires a city style to exist — so a world with no city styles
 * loads and generates exactly as before.
 *
 * <p><b>What is actually consulted (wave 3).</b> {@code multibuildings/} is the source of truth for
 * the quadrants of a 2×2 landmark ({@code LostCityConfig.quadrantOf}), and {@code citystyles/}
 * decides which palette style a city is built from ({@code StyleSelector.styleOfCityStyle}).
 * {@code worldstyles/} is <b>loaded but not consulted</b>: everything in it is either expressed
 * elsewhere in this port (city frequency is a vanilla {@code StructureSet}, the scattered table is
 * the {@code scattered} / {@code oil_rig} structures) or belongs to the phase-4 systems (subway,
 * highways, city spheres). It is parsed so that the format stays honest and so phase 4 has the data
 * ready — do not add code that reads it without first deciding it beats the datapack path.
 */
public class AssetLoader {

    public static final String MODID = "lostbuildings";
    private static final String ROOT = "lostcities";

    public static Assets load(ResourceManager rm) {
        Assets assets = new Assets();
        // Number of assets skipped in this call (broken JSON, bad codec data, ...).
        AtomicInteger failed = new AtomicInteger();

        // Variants first (palettes/parts/buildings resolve variant references against them).
        forEach(rm, failed, "variants", VariantRE.CODEC, (name, re) -> {
            re.setRegistryName(Identifier.fromNamespaceAndPath(ROOT, name));
            assets.putVariant(name, re);
        });
        Map<String, VariantRE> variants = assets.getVariants();

        forEach(rm, failed, "palettes", PaletteRE.CODEC, (name, re) -> {
            re.setRegistryName(Identifier.fromNamespaceAndPath(ROOT, name));
            Palette palette = new Palette(Identifier.fromNamespaceAndPath(ROOT, name));
            palette.parsePaletteArray(re, variants);
            assets.putPalette(name, palette);
        });

        forEach(rm, failed, "parts", BuildingPartRE.CODEC, (name, re) -> {
            re.setRegistryName(Identifier.fromNamespaceAndPath(ROOT, name));
            assets.putPart(name, new BuildingPart(re, variants));
        });

        forEach(rm, failed, "buildings", BuildingRE.CODEC, (name, re) -> {
            re.setRegistryName(Identifier.fromNamespaceAndPath(ROOT, name));
            assets.putBuilding(name, new Building(re, variants));
        });

        forEach(rm, failed, "conditions", ConditionRE.CODEC, (name, re) -> {
            re.setRegistryName(Identifier.fromNamespaceAndPath(ROOT, name));
            assets.putCondition(name, re);
        });

        forEach(rm, failed, "styles", StyleRE.CODEC, (name, re) -> {
            re.setRegistryName(Identifier.fromNamespaceAndPath(ROOT, name));
            assets.putStyle(name, new Style(re));
        });

        // City-level assets (PORT #9 / #1). These directories are allowed to be empty: they are
        // filled by agent G in wave 2 and nothing in the engine requires them to exist.
        forEach(rm, failed, "multibuildings", MultiBuildingRE.CODEC, (name, re) -> {
            re.setRegistryName(Identifier.fromNamespaceAndPath(ROOT, name));
            assets.putMultiBuilding(name, re);
        });

        forEach(rm, failed, "citystyles", CityStyleRE.CODEC, (name, re) -> {
            re.setRegistryName(Identifier.fromNamespaceAndPath(ROOT, name));
            assets.putCityStyle(name, re);
        });

        forEach(rm, failed, "worldstyles", WorldStyleRE.CODEC, (name, re) -> {
            re.setRegistryName(Identifier.fromNamespaceAndPath(ROOT, name));
            assets.putWorldStyle(name, re);
        });

        // Resolve every deferred refpalette and flatten the city-style inheritance chains now, while
        // we are still single-threaded, so the assets are effectively immutable by the time worldgen
        // touches them.
        assets.resolveReferences();

        LostBuildings.LOGGER.info(
                "[lostbuildings] Assets loaded: {} variants, {} palettes, {} parts, {} buildings, {} conditions, {} styles, {} citystyles, {} worldstyles, {} multibuildings ({} file(s) skipped due to errors)",
                assets.getVariants().size(), assets.getPalettes().size(), assets.getParts().size(),
                assets.getBuildings().size(), assets.getConditions().size(), assets.getStyles().size(),
                assets.getCityStyles().size(), assets.getWorldStyles().size(), assets.getMultiBuildings().size(),
                failed.get());

        return assets;
    }

    private static <T> void forEach(ResourceManager rm, AtomicInteger failed, String category, Codec<T> codec, BiConsumer<String, T> consumer) {
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
                // A single broken file (quite possibly from somebody else's datapack) must not stop
                // the server from starting: log it and carry on without that asset.
                failed.incrementAndGet();
                LostBuildings.LOGGER.error("[lostbuildings] Failed to load asset '{}' - skipping it", id, e);
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
