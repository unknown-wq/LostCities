# Lost Cities → Fabric 26.2 (`lostbuildings`)

A standalone **Fabric 26.2** mod that ports the **building generation** of
[Lost Cities](https://github.com/McJtyMods/LostCities) (Forge 1.20) into a small,
self-contained world-gen mod: groups of **multi-story buildings connected by streets**,
generated inside one custom biome that is injected into the vanilla overworld via
[Biolith](https://github.com/TerraformersMC/Biolith).

## Repo layout

| Path | What |
|---|---|
| `lostbuildings/` | The **new Fabric 26.2 mod** (Java 25, Mojang mappings, Loom 1.17). |
| `dist/` | Prebuilt jar — `lostbuildings-1.0.0-mc26.2.jar`. |
| `1.21/` | The **original Forge Lost Cities** mod, kept read-only as the porting source. |
| `PORT-PLAN-26.2.md` | The execution plan (verified facts, agents A–D, autonomous loop). |
| `PORT-STATUS.md` | Live status — contract deviations, disabled content, verification table. |

## Install

Requires Minecraft **26.2**, Fabric Loader ≥ 0.19.3, Fabric API `0.154.2+26.2`, and
**Biolith** `3.6.0-alpha.9`. Drop `dist/lostbuildings-1.0.1-mc26.2.jar` into `mods/`.
Find the biome in-game with `/locate biome lostbuildings:lost_city`.

## Build from source

```sh
cd lostbuildings
JAVA_HOME=/path/to/jdk-25 gradle build      # jar → lostbuildings/build/libs/
```
(Needs Java 25 + Gradle 9.x — MC 26.2 requires them. The vendored Gradle 9.6.1 dist used
during development lives in the sibling LuckyTNT repo's `gradle-dist/`.)

---

## ✅ What's done

- **Engine port** (`engine/**`, 27 files): Lost Cities' asset codecs → Mojang `Codec`;
  `BuildingPart` / `Palette` / `CompiledPalette` / `Building` / `Transform`; a new
  `AssetLoader` (reads the JSON data via `ResourceManager` + `Codec.parse`); `BuildingEngine`
  with `generatePart` / `generateBuilding` rewritten to write through
  `WorldGenLevel.setBlock` (loot / spawner / block-entity handlers inlined). All Forge
  coupling replaced with vanilla `BuiltInRegistries` / `BlockStateParser`.
- **Building data** copied 1:1 from the original: buildings 34, parts 186, palettes 29,
  variants 12, conditions 3, styles 4.
- **World generation**: a custom `Feature<LostBuildingConfig>` (+ config codec), configured
  & placed features, a `lost_city` biome, and **Biolith `replaceOverworld`** injection into
  plains / forest / savanna. Datagen wired for the dynamic registries.
- **Placement & shape**: `GroupBuildingPlacement` lays **small groups (2–5)** of buildings
  in a ring-grid, terrain-fit via the heightmap with bbox anti-overlap; `Foundation` fills a
  support column down to the ground and clears the volume above.
- **Multi-story buildings** — buildings rise **2–6 floors** with a capping top part.
- **Streets** — 3-wide stone-brick paths follow the terrain between buildings, skipping
  building footprints and water.
- **Buildings are chunk-aligned** — each 16×16 building is snapped to a whole chunk cell inside
  the feature's write window (a checkerboard of up to 5 chunks around the origin), so a building
  never straddles a chunk border and can no longer be clipped down to a single wall.
- **Connectable blocks corrected in a second pass** — stained-glass panes, iron bars, fences,
  walls and stairs are re-fitted to their neighbours after the whole building is placed, so
  adjacent panes/bars now connect.
- **Verified GREEN** (Java 25 / Gradle 9.6.1): `compileJava`, `build`, `runDatagen`, and a
  dedicated-server `runServer` boot to `Done (…)!` with **zero errors** while generating
  spawn chunks; the biome registers and Biolith-injects.

## ❌ What's NOT done / out of scope

- **Cities as a whole** — no city grid, districts, city styles, spheres, scattered/predefined
  buildings, or multi-buildings. Only standalone building groups.
- **Highways, railways, monorails, corridors** between/under buildings (only the lightweight
  surface streets above are generated).
- **Damage / ruins / rubble** — buildings generate **intact & clean**; the whole damage engine,
  explosions, and debris are not ported.
- **Cellars** (`cellars = 0`), and the deferred POI / lighting / sapling passes.
- **Commands, GUI, config screens, in-game editor, player data, networking, world profiles.**
- **Condition filters simplified** — loot/mob picks are factor-weighted only; part-selection
  honors `top/ground/cellar/floor/range` and treats `inpart/inbiome/chunkx/…` as satisfied.
- **A few block-id fixups** were needed for 1.20→26.2 (`chain`→`iron_chain`,
  `red_sandstone@2`→`smooth_red_sandstone`); unresolved ids fall back to AIR with a warning
  (0 hit at boot). Full list in `PORT-STATUS.md` → *Disabled content*.

## Left to a human with a client

The dedicated server boots clean, but headless can't observe visuals. In a real client,
check: building-group density & variety, the multi-story rise with capping tops (no
floating/clipping), foundations vs terrain, the 3-wide streets through the gaps, and
chest-loot / spawner behavior.

### Fixed in 1.0.1
- Buildings no longer generate clipped (only one wall) — they were straddling chunk borders
  and the out-of-chunk parts were dropped; buildings are now chunk-aligned.
- Stained-glass panes / iron bars now connect to their neighbours (second correction pass).
