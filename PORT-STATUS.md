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
- [ ] Agent D — integration, compileJava GREEN, build GREEN, runDatagen GREEN, runServer boots `Done (…)!`

## Contract deviations (D reads first — RECONCILE THESE)

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

## Verification

_(compile / build / runDatagen / runServer results; /locate biome output)_
