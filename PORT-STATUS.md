# PORT-STATUS — Lost Cities buildings → Fabric 26.2 (`lostbuildings`)

Live status of the autonomous port. Law: `PORT-PLAN-26.2.md`. Original Forge source is at
`1.21/` (read-only, porting source). New mod is built at `lostbuildings/` (repo root).

## Toolchain / environment (ready)

- Gradle: **`/opt/gradle-9.6.1/bin/gradle`** (vendored from `Fabric-LuckyTNTMod/gradle-dist/`). NEVER `./gradlew`.
- Java 25: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64` (installed; `java-1.25.0-openjdk-amd64` also present).
- Decompiled MC 26.2 sources: unpacked to **`/opt/mc-src/`** via `genSources` in `desolation` (grep only; do NOT regenerate).
- Build (ONE at a time, never parallel in this checkout):
  ```sh
  cd /home/user/LostCities/lostbuildings && JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 \
    /opt/gradle-9.6.1/bin/gradle compileJava --no-daemon 2>&1 | tee /tmp/errors.txt
  ```
- References: `grep -rn <symbol> /opt/mc-src/` and mirror `/home/user/desolation/src/main/java/raltsmc/desolation/`.

## Progress checklist (§5)

- [x] Step 0 — toolchain (JDK 25 + Gradle 9.6.1), genSources → /opt/mc-src, this file
- [x] Agent A — skeleton, build files, feature/config/biome/Biolith/datagen wiring (deps resolve; stub feature)
- [x] Agent B — engine/** ported to 26.2 against §3 contracts (27 files; forbidden-symbol grep clean)
- [x] Agent C — data copied 1:1 (34/186/29/12/3/4) + GroupBuildingPlacement + Foundation
- [x] Agent D — integration, compileJava GREEN, build GREEN, runDatagen GREEN, runServer boots `Done (…)!`

## Contract deviations — RESOLVED by Agent D

- **#1 Engine construction:** DONE. `LostBuildings.ENGINE` is now a non-final field built as
  `new BuildingEngine(ASSETS)` inside the `SERVER_STARTING` callback (right after `ASSETS`
  loads). No-arg ctor removed from the wiring.
- **#2 Assets accessors:** No change needed — `Assets` already exposes `getBuilding(String)`
  and `getStyle(String)` (both `DataTools.normalize`-keyed, nullable), exactly what
  `GroupBuildingPlacement` calls. Contract already agreed.
- **#3 Stub → real placement:** DONE. `LostBuildingFeature.PLACEMENT = new GroupBuildingPlacement()`.
- **#4/#5:** Unchanged (accepted as documented).

## Contract deviations (original list — RECONCILE THESE)

1. **`BuildingEngine` is INSTANCE-based** (Agent B): construct `new BuildingEngine(Assets)`,
   and `buildPalette`/`generateBuilding` are instance methods. But **Agent A wired
   `LostBuildings.ENGINE = new BuildingEngine()` (no-arg)**. FIX: build the engine AFTER
   `ASSETS` loads — in the `SERVER_STARTING` callback do `ENGINE = new BuildingEngine(ASSETS)`
   (ASSETS is loaded in that same callback via `AssetLoader.load(server.getResourceManager())`).
2. **`Assets` accessors** (Agent C assumed `getBuilding(String)` / `getStyle("standard")` →
   nullable): Agent B's `Assets` stores compiled `Building`/`BuildingPart`/`Palette`/`Style`
   keyed by BARE name (leading `lostcities:` stripped via `DataTools.normalize`), and stores
   `VariantRE`/`ConditionRE` directly (no separate Variant/Condition classes). VERIFY the
   real accessor names in `engine/Assets.java` and make `GroupBuildingPlacement` match; if
   the names differ, either add the accessors C used or update C's call sites (D's choice).
3. **`buildPalette(Assets, RandomSource, Style)`** is instance (not static) — C already calls
   `engine.buildPalette(assets, rand, style)`, which is fine once the engine instance exists.
4. Agent A left no accesswidener (not needed). Placed feature uses
   `RarityFilter.onAverageOnceEvery(20)`; Biolith `replaceOverworld` PLAINS/FOREST/SAVANNA @0.15.
5. Agent B avoided `SpawnData` (writes `{entity:{id}}` NBT); loot via
   `RandomizableContainerBlockEntity.setLootTable(ResourceKey.create(Registries.LOOT_TABLE, id))`.

## Disabled content (§9)

Agent B (engine), inherent to the intact-simple-building scope — non-blocking:
- Condition (loot/mob) resolution: factor-weighted pick only; position/part filters ignored.
- Part-selection conditions: only `top/ground/cellar/floor/range` honored;
  `inpart/inbuilding/inbiome/chunkx/chunkz/issphere/isbuilding` treated as satisfied.
- `STRUCTURE_VOID` = passthrough (leave existing block); no cellars (cellars=0); no
  damage/rubble; no POI/lighting/sapling deferral. Stairs/fences/walls ARE corrected via
  `BlockStates.correct` (neighbour-read, no `updateShape` — 26.2 signature changed).
Agent C (foundation): cobblestone column fill (the §9 "simple fill" tier by design).

Agent D (integration), block-id resolution in `engine/util/Tools.stringToState`:
- **Legacy `@meta` block ids:** the 1.20 data had exactly one pre-1.13 metadata id,
  `minecraft:red_sandstone@2`. Mapped explicitly to `minecraft:smooth_red_sandstone`
  (the old mod's dropped `BlockStateData.upgradeBlock` is absent from the ported source);
  any other unseen `@meta` form strips the metadata to its base block.
- **Vanilla renames (1.20 → 26.2):** `minecraft:chain` → `minecraft:iron_chain` (renamed
  in 26.x alongside copper chains) via a `RENAMES` map. This was the only genuine rename in
  the dataset (verified: no AIR-fallback warnings during a full spawn-chunk boot).
- **Unresolved blocks no longer crash worldgen:** `stringToState` now logs a warning and
  falls back to `AIR` for any block id / state it cannot resolve, instead of throwing
  (which previously aborted `AssetLoader` and the whole server). Cosmetic-safe per §9.
  Boot log showed ZERO such fallbacks, so no visual holes are expected from this.

## Verification

All GREEN (Agent D, 2026-07-10; Java 25 / Gradle 9.6.1, `--no-daemon`):

| Task | Result |
|---|---|
| `gradle compileJava` | **GREEN** (BUILD SUCCESSFUL) |
| `gradle build` | **GREEN** (BUILD SUCCESSFUL — jar assembled, mixins/resources applied) |
| `gradle runDatagen` | **GREEN** — emitted `worldgen/configured_feature/lost_building.json`, `worldgen/placed_feature/lost_building.json`, `worldgen/biome/lost_city.json` |
| `gradle runServer` | **GREEN** — `Done (5.600s)! For help, type "help"` with **0 `/ERROR]` lines**; spawn chunks generated (worldgen path exercised); clean shutdown |

- **`/locate biome lostbuildings:lost_city`** →
  `The nearest lostbuildings:lost_city is at [608, 125, -576] (837 blocks away)`.
  Confirms the custom biome is registered AND Biolith-injected into the overworld.
- **Block resolution:** 0 "falling back to AIR" warnings across a full spawn-chunk boot —
  every palette block id resolved (after the `chain`→`iron_chain` and `red_sandstone@2`
  fixes logged above).

### For a human with a client (in-world visual pass — the only remaining step, §10)
- Confirm building GROUPS actually spawn inside a `lost_city` biome (feature placement fires
  probabilistically; boot exercised the path with no errors, but visual density/variety was
  not observed headless).
- Check foundations sit on terrain (no floating/clipping), building variety across
  building1–8, corrected stairs/fences, and chest loot / spawner behaviour.
