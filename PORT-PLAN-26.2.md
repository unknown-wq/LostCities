# PORT-PLAN-26.2 — Lost Cities buildings → standalone Fabric 26.2 mod (`lostbuildings`)

> **Audience:** coding agents (Opus-class). This file is the LAW. Read §0–§4 before
> touching a file; then only your own section in §5. Supersedes `migration-plan.md`.
> The orchestrator runs the loop in §10 and NEVER asks the user anything.

---

## 0. STOP — the facts that matter

1. **Goal:** extract the **building generation** of Lost Cities (this repo, Forge 1.20)
   into a **new standalone Fabric 26.2 mod** `lostbuildings` that generates **small groups
   (2–5) of intact simple buildings** (`building1`–`building8`, with foundations, fit to
   terrain) in **one custom biome** injected into the overworld via **Biolith** — exactly
   like the sibling mod `desolation` does. Everything else (cities, streets, highways,
   railways, spheres, damage, corridors, commands, GUI, profiles, network) is **NOT ported**.
2. **Repo layout** (this repo, branch `claude/lostsystems-port-plan-rwhs9t`):
   ```
   /home/user/LostCities/
   ├── PORT-PLAN-26.2.md      # this file — the law (repo root)
   ├── 1.21/                  # the ORIGINAL Forge Lost Cities mod (porting SOURCE)
   │   └── src/main/java/mcjty/lostcities/…  +  src/main/resources/data/lostcities/…
   └── lostbuildings/         # the NEW Fabric 26.2 mod (Agent A creates it; own Gradle project)
   ```
   The new mod lives in **`lostbuildings/`** (own `settings.gradle`, own Gradle project).
   The original Forge sources under **`1.21/`** are your porting source — grep them,
   **never edit them**. (`1.21/` is just where the old repo now sits; the source is Forge
   **1.20**, but the folder name is fixed — do not rename it.)
3. **Target is `26.2`** (year.drop scheme; never write "1.26.2"). 26.1+ is **unobfuscated**:
   NO mappings line in Gradle, NO yarn names. **Java 25**, Loom `1.17.13`, Gradle 9.6.x.
4. **Lost Cities 1.20 source is ALREADY in Mojang names** (Forge uses official mappings).
   There is no yarn translation step. But 1.20→26.2 vanilla APIs still changed — the two
   biggest: `ResourceLocation` → **`net.minecraft.resources.Identifier`**
   (`Identifier.fromNamespaceAndPath(ns, path)`; `Identifier.of` does NOT exist), and
   `registryAccess().registryOrThrow(...)` → `lookupOrThrow(...)`. **Verify every vanilla
   signature against `/opt/mc-src/` or working desolation code — never against memory.**
5. **Two sibling repos are fully ported to 26.2 and are your reference law:**
   - `/home/user/desolation` — the STRUCTURAL TEMPLATE. Its worldgen classes are the
     exact patterns to copy (see §2).
   - `/home/user/Fabric-LuckyTNTMod` — the toolchain (vendored Gradle in `gradle-dist/`)
     and general 26.2 rename knowledge (`PORT-MOD-26.2.md` §4, `PORT-CHEATSHEET.md`).

---

## 1. Toolchain — DO THIS FIRST, NO DOWNLOADS

The container is fresh: **Java 25 and Gradle 9.6.1 are NOT installed**, `/opt` only has
Gradle 8.14.3 (too old — cannot run on Java 25). `./gradlew` CANNOT download its
distribution (egress proxy → HTTP 403 on GitHub release assets). **NEVER run `./gradlew`.**

**The Gradle 9.6.1 distribution is VENDORED in the LuckyTNT repo** — do NOT download it,
do NOT re-vendor it into this repo. It lives as a multi-volume RAR at
**`/home/user/Fabric-LuckyTNTMod/gradle-dist/`** (`install.sh` + `part1..5.rar`). That
sibling repo is in-scope and present in this environment. Set up once (orchestrator, step 0):

```sh
sudo apt-get install -y openjdk-25-jdk-headless unrar   # unrar is required by install.sh
/home/user/Fabric-LuckyTNTMod/gradle-dist/install.sh    # reassembles RAR → unzips to /opt/gradle-9.6.1
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
/opt/gradle-9.6.1/bin/gradle --version                  # must print 9.6.1 before anything else
```
If `/home/user/Fabric-LuckyTNTMod/` is somehow absent in a future session, the repo is in
scope via `add_repo unknown-wq/fabric-luckytntmod` — clone it and use its `gradle-dist/`.

All builds: `cd /home/user/LostCities/lostbuildings && JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 /opt/gradle-9.6.1/bin/gradle <task> --no-daemon 2>&1 | tee /tmp/errors.txt`
**One Gradle invocation at a time in this checkout — never in parallel.**
Maven repos (Fabric, Terraformers) ARE reachable through the proxy; deps resolve normally.

**Version pins (copy from `desolation/gradle.properties` — they are proven):**

| | value |
|---|---|
| minecraft | `26.2` |
| fabric-loader | `0.19.3` |
| fabric-api | `0.154.2+26.2` |
| biolith | `com.terraformersmc:biolith-fabric:3.6.0-alpha.9` |
| loom | `1.17.13` (`id 'net.fabricmc.fabric-loom'`) |
| java | 25 (`options.release = 25`, `VERSION_25`) |
| mappings | **none** (unobfuscated; `officialMojangMappings()` would ERROR) |

**Decompiled 26.2 source:** orchestrator runs `genSources` ONCE (in `/home/user/desolation`
or in `lostbuildings/` after the skeleton exists), unzips the
`minecraft-merged-*-sources.jar` from `.gradle/loom-cache/minecraftMaven/**` to
**`/opt/mc-src/`**, records that in `PORT-STATUS.md`. Everyone else only ever
`grep -rn <symbol> /opt/mc-src/` — never re-generate.

---

## 2. References, in priority order (copy, don't invent)

1. **`desolation/src/main/java/raltsmc/desolation/`** — for every worldgen pattern there
   is a working 26.2 file. Exact map:
   | Need | Copy from |
   |---|---|
   | build.gradle / gradle.properties / settings.gradle | repo root (drop geckolib, cloth-config, modmenu; KEEP fabric-api + biolith) |
   | Feature registration | `world/feature/DesolationFeatures.java` — `Registry.register(BuiltInRegistries.FEATURE, Identifier.fromNamespaceAndPath(...), feature)` |
   | Custom `Feature` + `Codec` config | `world/feature/ScatteredFeature.java` + `ScatteredFeatureConfig.java` (RecordCodecBuilder, `place(FeaturePlaceContext<C>)`) |
   | Configured features | `world/feature/DesolationConfiguredFeatures.java` — `FeatureUtils.register(context, KEY, feature, config)` in `bootstrap(BootstrapContext<ConfiguredFeature<?,?>>)` |
   | Placed features | `world/feature/DesolationPlacedFeatures.java` — `PlacementUtils.register(...)`, `RarityFilter`/`InSquarePlacement.spread()`/`PlacementUtils.HEIGHTMAP`/`BiomeFilter.biome()` |
   | Biome creation (26.2 `EnvironmentAttributes` API!) | `world/biome/BiomeCreator.java` + `registry/DesolationBiomes.java` |
   | Biolith injection | `world/gen/world/DesolationBiolithGeneration.java` — `BiomePlacement.replaceOverworld(Biomes.X, KEY, chance)`; sub-biomes via `CriterionBuilder` |
   | Datagen | `data/DesolationDatagen.java` + `data/DesolationDynamicRegistryProvider.java` (`FabricDynamicRegistryProvider`, `registryBuilder.add(Registries.CONFIGURED_FEATURE/PLACED_FEATURE/BIOME, ...)`) |
   | Heightmap query | `world.getHeightmapPos(Heightmap.Types.WORLD_SURFACE_WG, pos)` |
   | setBlock during worldgen | flags as desolation uses them: `world.setBlock(pos, state, 19)` (foliage-like) / `..., 4)` — never flag 1 (no neighbor updates in worldgen) |
   | Chest loot at gen time | `world/structure/AshTinkerBaseGenerator.handleDataMarker` — `((ChestBlockEntity) be).setLootTable(KEY)` |
   | fabric.mod.json shape | root resources (entrypoints `main` + `fabric-datagen`; depends `fabricloader >=0.19.3`, `minecraft >=26.2 <26.3`, `java >=25`, biolith) |
2. **`/opt/mc-src/`** — decompiled 26.2. Ground truth for every vanilla signature
   (`setBlockEntityNbt`, `RandomizableContainerBlockEntity.setLootTable`, `SpawnData`,
   `BlockStateParser`, `StructureVoid` handling…).
3. **`Fabric-LuckyTNTMod/PORT-CHEATSHEET.md` + `PORT-MOD-26.2.md` §4** — recurring-error
   fixes and the verified rename table (useful even though our source is Forge-named).
4. **`1.21/src/main/java/mcjty/lostcities/`** (in this repo) — the code being ported (§4).

**Rule: never invent a signature.** If you can't confirm it in (1)–(2), grep harder or
apply §9. Do not trust training memory — it predates the 26.x rewrites.

---

## 3. Frozen contracts (so agents B and C never block on each other)

Package root `com.lostbuildings`, modid `lostbuildings`. Each agent edits ONLY its own
files (§5). Cross-agent types are used per the signatures below; if you need a type that
another agent owns, write against this contract — integration (Agent D) reconciles.

- **Data location:** `lostbuildings/src/main/resources/data/lostbuildings/lostcities/{buildings,parts,palettes,variants,conditions,styles}/`
  — JSON copied **1:1, contents unedited**. Name resolution (incl. `lostcities:` prefixes
  inside JSON) is handled by the ported `DataTools` normalization, not by editing data.
- **Engine (owner: Agent B):**
  - `engine/AssetLoader.java` → `static Assets load(ResourceManager rm)`;
    `Assets` = maps name→`Building`/`BuildingPart`/`Palette`/`Variant`/`Condition`/`Style`.
  - `engine/BuildingEngine.java` →
    `CompiledPalette buildPalette(Assets a, RandomSource rand, Style style)` and
    `void generateBuilding(WorldGenLevel level, BlockPos origin, RandomSource rand, Transform t, Building b, CompiledPalette pal, PlaceSettings s)`.
  - `PlaceSettings` (record): `int minFloors, maxFloors; boolean lighting, spawners, loot; int waterLevel`.
- **Feature (registration: Agent A; real placement: Agent C):**
  - `world/feature/LostBuildingConfig.java` (record + `Codec`):
    `List<String> buildings; int minFloors, maxFloors; boolean foundation; int groupMin, groupMax, spacing`.
  - `world/feature/LostBuildingFeature.java` — `Feature<LostBuildingConfig>`; `place()`
    delegates to interface `world/feature/BuildingPlacement`:
    `boolean place(FeaturePlaceContext<LostBuildingConfig> ctx, Assets assets, BuildingEngine engine)`.
  - Agent A ships a **stub** `BuildingPlacement` impl (places 1 marker block); Agent C
    ships `GroupBuildingPlacement` in a separate file. D swaps them.
- **Asset access point:** `LostBuildings.ASSETS` — loaded in
  `ServerLifecycleEvents.SERVER_STARTING` (`server.getResourceManager()`); desolation uses
  the same event for its Biolith surface rules (earlier callbacks fire too early — proven).
  Datagen must NOT need `ASSETS`.

---

## 4. Verified fact sheet — the source code being ported (recon done — trust these)

All paths below are relative to **`1.21/src/main/java/mcjty/lostcities/`**. Line numbers
verified against HEAD.

**Classes ported nearly as-is** (Agent B): `worldgen/lost/cityassets/`:
`BuildingPart` (180 ln), `IBuildingPart` (29), `Palette` (130), `CompiledPalette` (230,
**clean** — no core deps), `Building` (170, clean); `worldgen/lost/Transform.java` (213).
Codecs `worldgen/lost/regassets/`: `BuildingRE` (139), `PaletteRE` (42), `BuildingPartRE`
(106), **`VariantRE` (40), `ConditionRE` (39), `StyleRE` (39)** ← these three were missing
from the old plan but are required to decode `variants/`, `conditions/`, `styles/`;
plus `regassets/data/`: `PaletteEntry`, `PartRef`, `BlockEntry`, `PartMeta`, `DataTools`,
`ConditionTest`. All are plain Mojang-`Codec` classes — port with import fixes only.

**Methods extracted & rewritten** (Agent B — this is the real work, a rewrite not a copy):
from `worldgen/LostCityTerrainFeature.java` (2327 ln):
- `generatePart` @ **1723–1814**. Strip: `profile.EDITMODE` + `EditModeData` @1726–1727.
  Replace the `driver` (ChunkDriver) write path with `WorldGenLevel.setBlock(pos, state, flags)`.
- `generateBuilding` @ **2129–2192**. Strip: `Corridors.generateCorridorConnections` @2181,
  `part2Map`, cellars-optional, `ChunkHeightmap`, `driver.actuallyGenerate`. The kept core:
  floor loop → pick part per floor → `generatePart`.
- Handlers to inline (they sit right below): `handleBlockEntity` @1837 (uses
  `ForgeRegistries.BLOCK_ENTITY_TYPES` @1828/@1848 → **`BuiltInRegistries.BLOCK_ENTITY_TYPE`**),
  `handleSpawner` @1860, `handleLoot` @1883, `handleTodo` @1896 (off-thread `GlobalTodo` →
  execute **inline synchronously**). Verify the 26.2 forms of `setBlockEntityNbt` /
  `setLootTable` / `SpawnData` in `/opt/mc-src` before writing them.
- `ChunkDriver.correct(BlockState)` @242 (`worldgen/ChunkDriver.java`, 499 ln) — adapt into
  a small per-block "connectable states" fixer (stairs/fences/walls/STRUCTURE_VOID); the
  rest of ChunkDriver (section internals) is NOT ported.

**Forge coupling points to replace** (all of them — verified exhaustive for these files):
| Where | What | Replacement |
|---|---|---|
| `varia/Tools.java` @52,@73 (`stateToString`/`stringToState`) | `ForgeRegistries.BLOCKS` | `BuiltInRegistries.BLOCK` |
| `varia/Tools.java` @65 (`stringToState`, the `[...]` path) | `WorldTools.getOverworld().holderLookup(Registries.BLOCK)` | pass a `HolderLookup<Block>`/use `BuiltInRegistries.BLOCK` lookup; check `BlockStateParser` in `/opt/mc-src` |
| `Palette.java` @84 (variant branch) | `ServerLifecycleHooks.getCurrentServer()…` + `AssetRegistries.VARIANTS` | resolve from the `Assets` object passed in |
| `LostCityTerrainFeature` @1828,@1848 | `ForgeRegistries.BLOCK_ENTITY_TYPES` | `BuiltInRegistries.BLOCK_ENTITY_TYPE` |
| `AssetRegistries`/`RegistryAssetRegistry` (Forge datapack registries) | whole mechanism | **new `AssetLoader`**: iterate `ResourceManager` JSONs under `data/lostbuildings/lostcities/**`, `Codec.parse(JsonOps.INSTANCE, …)` per RE codec — there is nothing to reuse here, write it against the RE codecs |
| `Transform.transform(RailShape)` @70–205 (~136 ln) | railway-only | DELETE; keep the rest of Transform (~77 ln, rail-free) |

**Data inventory** (`1.21/src/main/resources/data/lostcities/lostcities/`): buildings 34
(building1–8 present), parts 186, palettes 29, variants 12, conditions 3
(`chestloot`,`easymobs`,`hardmobs`), styles 4 (`standard.json` present).
⚠ Correction to the old plan: there are **NO `building6_*/7_*/8_*` part files** —
building6→building5_1..4+top1x1_2..5, building7→building4_*+top4_*, building8→building1_*+top1x1_*.
They need variants `deepslate`, `blackstone`, `stoneandbrick` (all inside the 12).
**Agent C: copy ALL six data folders 1:1** (simplest, refs guaranteed to resolve); the
feature config just only lists `building1..building8`.

---

## 5. Agents — roles, files, done-criteria

Every agent: read §0–§4 + `PORT-STATUS.md` first; edit ONLY your files; NO Gradle runs
unless your section says so; follow §6–§9; write raw findings into `PORT-STATUS.md`.

### Agent A — skeleton, worldgen wiring, biome + Biolith, datagen (runs FIRST, alone)
**Files:** everything under `lostbuildings/` except `engine/**`, `GroupBuildingPlacement`,
`Foundation`, and `data/lostbuildings/**`: build files, `fabric.mod.json`,
`LostBuildings.java`, `registry/ModFeatures.java`,
`world/feature/{LostBuildingConfig, LostBuildingFeature, BuildingPlacement + stub, ModConfiguredFeatures, ModPlacedFeatures}.java`,
`world/biome/{ModBiomes, LostCityBiomeCreator}.java`, `world/gen/BiolithGeneration.java`,
`data/{ModDatagen, ModDynamicRegistryProvider}.java`.
**Tasks:** copy desolation's build setup (§2 row 1) → green `gradle build` on the empty
skeleton; then features/config per §3 contracts, stub placement (1 marker block); biome
`lost_city` (neutral plains-like; copy `BiomeCreator` shape — 26.2 `EnvironmentAttributes`);
`generationSettings.addFeature(GenerationStep.Decoration.SURFACE_STRUCTURES, PLACED_KEY)`;
Biolith `BiomePlacement.replaceOverworld(Biomes.PLAINS/FOREST…, LOST_CITY, chance)` from
`onInitialize`; datagen registry set (CONFIGURED_FEATURE/PLACED_FEATURE/BIOME).
Placement modifiers: `RarityFilter.onAverageOnceEvery(N)` + `InSquarePlacement.spread()` +
`PlacementUtils.HEIGHTMAP` + `BiomeFilter.biome()` (clustering happens inside `place()`, Agent C).
**Agent A MAY run Gradle** (it is alone in the checkout).
**Done:** `gradle build` and `gradle runDatagen` green with the stub feature; commit.

### Agent B — engine port (parallel with C after A's skeleton exists)
**Files:** `engine/**` only (`codec/` REs per §4, cityassets classes, `Transform`,
`AssetLoader`, `Assets`, `Style`, `BuildingEngine`, `PlaceSettings`, `BlockStates`,
`util/Tools.java`).
**Tasks:** port per §4 in this order: REs+data → Tools → Transform (minus rail table) →
Palette/CompiledPalette/Building/BuildingPart → AssetLoader → BuildingEngine (extracted
methods, §4). `BuildingInfo` becomes a thin local shim (only what `generatePart` actually
touches — verify by grep, do not port the real class). NO Gradle — code to the contracts;
D compiles. Self-check without Gradle: `javac`-free review pass + grep your own TODOs.
**Done:** all `engine/**` files written, every Forge point from §4's table replaced,
zero references to `mcjty.*`, `net.minecraftforge.*`, `BuildingInfo` (beyond the shim),
`LostCityProfile`, `EditModeData`, `Corridors`, `ChunkDriver`, `GlobalTodo`. Commit.

### Agent C — data + group placement + foundation (parallel with B)
**Files:** `src/main/resources/data/lostbuildings/lostcities/**` (copied),
`world/feature/GroupBuildingPlacement.java`, `world/feature/Foundation.java`.
**Tasks:** (1) copy all six data folders 1:1 from
`1.21/src/main/resources/data/lostcities/lostcities/` (§4 inventory — whole folders, no edits).
(2) `GroupBuildingPlacement implements BuildingPlacement`: group size `groupMin..groupMax`,
lay 2–5 sites around origin at `spacing` (grid/ring + rand jitter); per site:
`getHeightmapPos(WORLD_SURFACE_WG, …)`, random `Transform` rotation, pick building from
`config.buildings`, `engine.buildPalette(...)`, `engine.generateBuilding(...)`;
bbox anti-overlap inside the group. (3) `Foundation`: pillar/fill down to ground under the
footprint (analog of the old `fillToGround`) + clear the volume above. NO Gradle.
**Done:** data in place, both classes written against §3 contracts. Commit.

### Agent D — integration sweeper + smoke test (after B and C)
**Files:** anything that fails to compile (all agents' files), `PORT-STATUS.md`.
**Tasks:** swap A's stub for `GroupBuildingPlacement`; wire `LostBuildings.ASSETS`
(SERVER_STARTING) into the feature; then the loop:
`gradle compileJava` → first ~30 errors → open ONLY failing lines → fix → repeat.
Then `gradle build`, `gradle runDatagen`. Then the single smoke test:
```sh
cd lostbuildings && mkdir -p run && echo "eula=true" > run/eula.txt
JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 /opt/gradle-9.6.1/bin/gradle runServer --no-daemon
# success = "Done (N.NNNs)!" with no /ERROR] lines; spawn chunk gen exercises worldgen
```
If stdin forwards to the server console, additionally send
`locate biome lostbuildings:lost_city` and record the answer in `PORT-STATUS.md`;
if stdin doesn't forward, boot-green suffices — do not fight it.
**Done:** compile, build, datagen, server boot all green; `PORT-STATUS.md` complete.

---

## 6. Token economy — MANDATORY for every agent

1. **Environment first, no downloads** (§1). Never `./gradlew`, never download Gradle/JDK
   from GitHub. Verify `gradle --version` before any build.
2. **Work error-driven, never file-driven.** Don't read files "for context". Compile →
   take first ~30 errors → Read ONLY the failing lines (offset/limit) → fix → recompile.
3. **Copy, don't compose.** Every worldgen/registration/datagen pattern exists in
   desolation. Open the mapped file (§2), mirror it, substitute names. If you are writing
   a shape that desolation doesn't have, stop and check §4/§9 first.
4. **`/opt/mc-src` is grep-only.** Never regenerate sources; never read whole decompiled
   files — grep the symbol, read ±20 lines.
5. **One Gradle at a time, and only where your section allows it** (A and D). B and C
   never compile; that is D's job.
6. **One smoke test total** (D). Nobody else boots a server or client.
7. Don't re-read this plan repeatedly — grep it (`grep -n <term> PORT-PLAN-26.2.md`).

## 7. Rules for agents (Opus discipline)

- **DO** copy the desolation pattern first; adapt second. **DON'T** design new shapes.
- **DO** verify every vanilla signature in `/opt/mc-src` (it changed in 26.x; your memory
  is stale by definition).
- **DO** keep diffs small and mechanical; commit per §5 done-criterion with a clear message.
- **DON'T** edit files outside your §5 list — parallel agents clobber otherwise.
- **DON'T** edit the original `src/main/java/mcjty/**` or the copied JSON data.
- **DON'T** invent method names, registry keys, or Biolith calls. Unsure after two greps →
  apply §9 and move on.
- **DON'T** write `ResourceLocation`, yarn names, or `Identifier.of` — see §0.4.
- **DON'T** ask the user anything. There is no user. Decide per this plan, log in
  `PORT-STATUS.md`.

## 8. PORT-STATUS.md (created by orchestrator, updated by everyone)

Sections: **Toolchain** (gradle path, JAVA_HOME, `/opt/mc-src` ready y/n) · **Checklist**
(A/B/C/D done-criteria as checkboxes) · **Contract deviations** (any signature an agent
had to change vs §3 — D reads this first) · **Disabled content** (§9 log) ·
**Verification** (compile/build/datagen/runServer results, locate-biome output).

## 9. Rule: too hard? Scale it down, keep the code, log it

If something resists **two honest attempts**, do NOT block the build and do NOT delete
code. Downgrade along this ladder and log in `PORT-STATUS.md`:

- Spawner NBT won't port → skip spawners (`PlaceSettings.spawners=false` path), houses
  still generate.
- Loot-table binding won't port → place chests empty; log.
- `correct()` connectables won't port → place stairs/fences uncorrected; log (cosmetic).
- Foundation logic fights terrain → simple dirt/stone column fill under footprint.
- Biolith sub-biome API resists → `replaceOverworld` only.
- A whole building JSON fails to decode → drop it from the config list (keep ≥4 buildings).
- Anything else → comment the broken body with
  `// TODO(port-26.2): DISABLED — <one line why>` + `/* original */`, keep it compiling.

Build-green with fewer features beats feature-complete red. EVERY cut goes in
`PORT-STATUS.md` → "Disabled content".

## 10. Orchestrator — fully autonomous loop (no user in the loop)

Branch: `claude/lostsystems-port-plan-rwhs9t` (this repo). Push after every phase
(`git push -u origin <branch>`; on network failure retry ×4 with 2/4/8/16s backoff).

- **Step 0 (orchestrator itself):** toolchain per §1; `genSources` → `/opt/mc-src`;
  create `PORT-STATUS.md` per §8; commit + push.
- **Phase 1:** Agent A alone → both A criteria green → commit/push.
- **Phase 2:** Agents B and C in parallel (disjoint files, zero Gradle) → commit/push.
- **Phase 3:** Agent D integration + smoke test.
- **The loop:** while D's compile/build/datagen/runServer is red → collect the error list
  → spawn a **fresh sweeper agent** (D-role) with that list and §9 authority → repeat.
  Escalate to §9 downgrades on anything that survives two sweepers. No iteration limit
  other than: every cycle must either reduce the error count or apply a §9 cut.
- **After green:** final commit + push; `PORT-STATUS.md` shows all checkboxes and every cut.

**DONE =** `gradle build` + `runDatagen` green; dedicated server boots to `Done (…)!`
with zero `/ERROR]` lines while generating spawn chunks; everything pushed; status file
complete. In-game visual pass (house groups, foundations, variety, loot) is the only step
left to a human with a client.

---

## 11. Phase 2 — ONLY after Phase 1 is green (multi-story buildings + streets)

Do NOT start this until §10 DONE holds (server boots green with the simple building groups).
Phase 2 is a separate orchestrator pass with its own agents, committed on the same PR branch.
Requested scope: **taller multi-story buildings** and **streets between the buildings in a group**.

### 11a. Multi-story buildings
The Lost Cities data + the ported engine are already floor-based: `Building` carries min/max
floors and `generateBuilding` stacks a part per floor + a top part. So multi-story is mostly
a **config/enable** step, not new engine work:
1. Raise the floor range: `LostBuildingConfig.maxFloors` (and the `PlaceSettings` fed to the
   engine) from the Phase-1 conservative value to the buildings' real max (grep each
   `1.21/src/main/resources/data/lostcities/lostcities/buildings/building*.json` for
   `minfloors`/`maxfloors`/`maxcellars`). Feed per-building max, don't hardcode one number.
2. Verify the engine's floor loop actually iterates floors (not clamped to 1 by a Phase-1
   §9 cut — check `PORT-STATUS.md` "Disabled content"). If a §9 cut disabled the loop,
   re-port it now against `/opt/mc-src`.
3. Foundation/clear-volume (`Foundation.java`) must clear the FULL stacked height, not one
   segment — pass the real building height (floors × slice height).
4. Verify in-world: buildings rise multiple floors, top part caps them, no floating/clipping.

### 11b. Streets between buildings
Phase 1 deliberately cut the Lost Cities city/street/highway engine (`City`, `Highway`,
`Railway`, `Corridors`). Do NOT try to port that whole system. Two options — pick the
cheaper one that looks right, log the choice in `PORT-STATUS.md`:
- **Preferred (lightweight):** in `GroupBuildingPlacement`, after placing the group, connect
  building footprints with simple roads — lay a 2–3 wide path of a street block
  (e.g. `Blocks.STONE`/`GRAVEL`/a palette "street" material) along the grid lines between
  sites, at the foundation top height, with a 1-block curb/lamp optional. This is new code
  in `GroupBuildingPlacement`/a small `Streets.java`, not an engine port.
- **Faithful (only if the lightweight look is unacceptable):** port the minimal street piece
  from `1.21/.../worldgen/lost/` street parts + palettes (grep `street`, `Highway` in the
  data) and place a straight street segment between buildings. Heavier; still avoid the full
  city grid/`BuildingInfo` graph.

Constraints unchanged: one biome, Biolith-injected, small groups; NO full city grid, NO
highways/railways, NO damage. Same rules §6–§9 (copy desolation patterns, verify in
`/opt/mc-src`, scale down + log if it resists). Update `PORT-STATUS.md` and the PR when green.
