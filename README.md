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
| `dist/` | **Untracked** (git-ignored) local output dir — see *Install* below. |
| `1.21/` | The **original Forge Lost Cities** mod, kept read-only as the porting source. |
| `PORT-PLAN-26.2.md` | The execution plan (verified facts, agents A–D, autonomous loop). |
| `PORT-STATUS.md` | Live status — contract deviations, disabled content, verification table. |

## Install

Requires Minecraft **26.2**, Fabric Loader ≥ 0.19.3, Fabric API `0.154.2+26.2`, and
**Biolith** `3.6.0-alpha.9`. Current mod version: **1.0.1** (`lostbuildings/gradle.properties`
is the single source of truth).

The jar is **not committed to this repository** — a 400 KB binary per release would live in
git history forever. Get it either way:

* **Download** — grab `lostbuildings-<version>-mc26.2.jar` from the repo's
  [GitHub Releases](../../releases) page, or from the `lostbuildings-jar` artifact of a
  successful `build` workflow run.
* **Build it yourself** — see below; the jar lands in `lostbuildings/build/libs/`.

Drop the jar into `mods/`. Find the biome in-game with
`/locate biome lostbuildings:lost_city`.

## Build from source

The repo ships a **Gradle wrapper** (9.6.1), so no Gradle installation is needed — only a
JDK 25 (MC 26.2 requires it):

```sh
cd lostbuildings
JAVA_HOME=/path/to/jdk-25 ./gradlew build       # jar → lostbuildings/build/libs/
JAVA_HOME=/path/to/jdk-25 ./gradlew runDatagen  # regenerates src/main/generated/
```

`build` also runs the JUnit 5 unit tests (`src/test/java`). CI
(`.github/workflows/build.yml`) runs `build` + `runDatagen` on temurin 25 and fails if
`runDatagen` leaves an uncommitted diff under `lostbuildings/src/main/generated`.

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
- **Placement & shape**: `GroupBuildingPlacement` lays a small group of buildings on chunk
  cells around the feature origin. The whole group shares **one ground level** (lowest cell
  corner, snapped to a multiple of the 6-block floor height), so a group on a slope still
  reads as one city block instead of five houses at five heights. `Foundation` pillars each
  footprint down to solid ground with **the building's own filler block**, excavates the
  volume above it, and drains any liquid inside the footprint up to sea level.
- **Group cells are exclusive** — `CellLattice` assigns every chunk cell to exactly one
  candidate group via a hash of `(seed, chunkX, chunkZ)`, so two groups fired from nearby
  chunks can no longer both claim a cell and interpenetrate. Deterministic per seed, no
  mutable state. This is a palliative: because ownership is exclusive, a group now wins
  **one cell on average** instead of 2–5, so building density is lower than before. The real
  fix (a per-chunk placement grid) is still to come.
- **Style follows the surroundings** — `StyleSelector` probes the biome at the origin and one
  chunk out and switches to the `desert` style in badlands / desert neighbourhoods instead of
  always using grey `standard` bricks.
- **Multi-story buildings** — buildings rise **2–6 floors** with a capping top part. The
  cleared volume is sized from the storey count actually rolled for that building, not from
  the config maximum.
- **Streets** — 3-wide stone-brick paths at the group's shared ground level, carried on a
  short embankment where the terrain falls away, skipping building footprints.
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

Specifically **unverified** in the worldgen changes below — all of them are visual:
the new vegetation/animals in the biome, the shared group ground level on a slope, the
filler-block foundations, water displacement inside a submerged building, the desert style
switch, and street embankments.

One known interaction is **not** fixed: a group's streets route through the orthogonal
chunks between its own cells, and `Streets` only skips *its own* footprints — so a street
can still clip a neighbouring group's building. Fixing that properly needs the per-chunk
placement grid.

### Worldgen fixes (unreleased)
- The biome is no longer a bald wasteland — grass, flowers, plains trees, mushrooms and the
  usual extra vegetation are generated, and farm animals spawn.
- Biolith replaces **5 %** (was 15 %) of plains/forest/savanna, so far less of the biome map
  is repainted for the same amount of content.
- Buildings no longer generate flooded — liquid inside the footprint is cleared and drained
  up to sea level (`PlaceSettings.waterLevel` was previously passed in and never read).
- Foundations use the building's filler block instead of a cobblestone stump.
- Group cells are exclusive (see `CellLattice`), so groups can no longer interpenetrate.
- `setBlock` flags are one named constant (`WorldGenFlags.SET_BLOCK`) and no longer request
  neighbour updates during worldgen; the hardcoded `19` did, contradicting its own comment.

### Fixed in 1.0.1
- Buildings no longer generate clipped (only one wall) — they were straddling chunk borders
  and the out-of-chunk parts were dropped; buildings are now chunk-aligned.
- Stained-glass panes / iron bars now connect to their neighbours (second correction pass).
