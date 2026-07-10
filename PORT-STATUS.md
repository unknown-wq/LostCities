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
- [ ] Agent A — skeleton, build files, feature/config/biome/Biolith/datagen wiring (deps resolve; stub feature)
- [ ] Agent B — engine/** ported to 26.2 against §3 contracts
- [ ] Agent C — data copied 1:1 + GroupBuildingPlacement + Foundation
- [ ] Agent D — integration, compileJava GREEN, build GREEN, runDatagen GREEN, runServer boots `Done (…)!`

## Contract deviations (D reads first)

_(agents record any §3 signature they had to change here)_

## Disabled content (§9)

_(every scaled-down / commented-out feature logged here: file, what, why)_

## Verification

_(compile / build / runDatagen / runServer results; /locate biome output)_
