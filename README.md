# Lost Cities → Fabric 26.2 (`lostbuildings`)

A standalone **Fabric 26.2** mod that ports the **city generation** of
[Lost Cities](https://github.com/McJtyMods/LostCities) (Forge 1.20) into a small,
self-contained world-gen mod.

It generates **ruined cities** as real `Structure`s: a 9×9 grid of chunk cells, 144×144 blocks,
with multi-storey buildings on the lots, a connected lattice of **tiled streets** between them
(kerbs, lamp posts, potholes), **parks and fountains**, **bridges** where a street crosses water,
and — in about a third of cities — a **2×2 landmark** at the centre (town hall, library, shopping
centre, antenna tower). Buildings come **ruined**: cracked and mossy blocks, blast spheres, rubble,
plus **cellars**, loot and spawners that get better and nastier the higher (or deeper) you go.
Outside the cities there are **scattered structures** — a cabin, a radio tower, and an **oil rig
standing in deep ocean**. Cities are painted into the vanilla overworld via
[Biolith](https://github.com/TerraformersMC/Biolith); everything is findable with
`/locate structure`.

## Repo layout

| Path | What |
|---|---|
| `lostbuildings/` | The **new Fabric 26.2 mod** (Java 25, Mojang mappings, Loom 1.17). |
| `dist/` | **Untracked** (git-ignored) local output dir — see *Install* below. |
| `1.21/` | The **original Forge Lost Cities** mod, kept read-only as the porting source. |
| `gradle-dist/` | Vendored **Gradle 9.6.1** distribution for offline builds (see its README). |
| `PORT-PLAN-26.2.md` | Phase-2 execution plan (verified facts, agents A–D, autonomous loop). |
| `PORT-STATUS.md` | Phase-2 status — contract deviations, disabled content, verification table. |
| `PHASE3-PLAN.md` | Phase-3 plan: `Feature` → `Structure` migration + the top-10 improvements. |
| `PHASE3-STATUS.md` | Phase-3 status — what was built, what was cut, and what was measured in-world. |

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

Drop the jar into `mods/`. Find things in-game with:

```
/locate structure lostbuildings:lost_city    # a city
/locate structure lostbuildings:scattered    # a cabin or a radio tower
/locate structure lostbuildings:oil_rig      # the rig, deep ocean only
/locate biome lostbuildings:lost_city        # the biome cities are painted into
```

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

### Cities are real structures

The mod used to generate as a `Feature`, which may only write inside the generating chunk ±1 —
a 48×48 block window. That single limit is what kept the port to "a few houses and a path":
a street could not leave the window, a 2×2 building barely fit, and the cell-ownership
workaround made about a third of firings build nothing at all.

It is now a vanilla `Structure` + `StructurePiece`. The layout is decided once, in the
`structure_starts` step; each piece is written when *its own* chunk generates. That bought the
whole list below at once, and it makes subways and highways possible later.

- **City grid** — `CityLayout`, a pure function of (seed, city chunk) with **no Minecraft imports
  at all**, so it is fully unit-tested. A city is 9×9 chunk cells (144×144 blocks); a lot sits
  where both offsets from the centre are even, and every remaining cell is street, which gives a
  properly connected orthogonal street lattice with real intersections.
- **Street tiles** — the shipped `street_straight / bend / t / all / end` parts are chosen from a
  bitmask of road neighbours, replacing the hand-laid paving. Plus kerbs, **lamp posts** every
  8 blocks, and occasional potholes.
- **Parks and fountains** — a quarter of lots become `park_plants / park_pool / park_trees /
  park_fountain` instead of a house.
- **2×2 landmarks** — `center`, `library`, `shopping`, `shopping_open`, `townhall` are placed as
  one four-quadrant building sharing a seed, a storey count and their edges. Roughly one city in
  three is a "downtown" and gets one at its centre.
- **Bridges** — a street cell whose ground falls away (river, lake, ravine) becomes a
  `bridge_open` / `bridge_covered` deck instead of being abandoned.
- **Block silhouette** — storey counts fall off towards the city edge, so a city has a skyline
  instead of a uniform height.
- **Scattered structures** — `cabin` and `radiotower` on land, and an `oilrig` that stands in
  **deep ocean** (its own structure, its own biome tag, no foundation pass).
- **`/locate structure`** works for all three: `lost_city`, `scattered`, `oil_rig`.

### Buildings are ruined, furnished and inhabited

- **Damage and weathering** — blast spheres eat blocks and the survivors are replaced with their
  aged variant (`cracked_*`, `mossy_*`), more likely the higher the floor; rubble on the ground
  floor. Driven by `damage_chance`; **`0` reproduces bit-identical intact buildings** (the
  explosion layout runs off its own hash stream, so it never perturbs the building's RNG).
- **Cellars** — storeys below ground, dug out of the foundation fill, dark, with their own loot.
- **Honest loot and mob conditions** — `top / ground / cellar / floor / range / inpart /
  inbuilding / chunkx / chunkz` are actually evaluated instead of being weighted-and-ignored, so
  the good chest really is on the fourth floor and the rail loot really is in the rail dungeon.
- **Building roles** — residential / shop / library / tower, each with its own loot tables and
  spawner tier.
- **Biome styling** — a city picks one palette for the whole city from its biome (`standard`,
  `desert`, `snowy`, `swamp`), and a separate *climate* pass settles snow on roofs in the north
  and moss on them in a swamp. The biome is sampled **once**, at structure-assembly time, so no
  piece ever reads a biome during generation.

### Configurable from a datapack

Nothing above is a Java constant. The structure's own codec carries the building list, floor
range, foundation toggle, city size, density, cellars, damage chance, street block/width/tiles/
lamps/potholes/bridges, park list and chance, landmark list and downtown chance — and the target
biomes are a tag (`#lostbuildings:has_structure/lost_city`). On top of that,
`lostcities/citystyles/*.json` decides which palette a city style resolves to, and
`lostcities/multibuildings/*.json` decides which four buildings make up a landmark.

### Verified

`build` (89 unit tests), `runDatagen` and a dedicated-server `runServer` are green: the server
boots to `Done (5.662s)!` with **zero `/ERROR]` lines** and no unsafe-terrain warnings over a
24-minute run. The generated world was then read back off disk: 31 cities measured (13–22
buildings, 28–56 streets, 2–9 parks, 0–28 bridges each), 9 with a landmark, street tiles, cellars,
1810 cracked / 369 mossy blocks per city, 25 scattered sites, and oil rigs standing dry on the
sea surface. Numbers and method: `PHASE3-STATUS.md`.

## ❌ What's NOT done / out of scope

- **Subways, highways, monorails, city spheres** — each is a multi-chunk landscape system of its
  own and is deferred to phase 4. Their data (`rails_*`, `highway_*`, `monorails_*`) is already
  in the resources.
- **Cartographer explorer maps** — `/locate` works; the villager trade offer is phase 4.
- **World styles** — `worldstyles/*.json` is loaded but not consulted; city frequency is a vanilla
  `StructureSet` and the scattered table is now two structures.
- **Building fronts and external stairs** — they need a piece to know its neighbour's storey
  count, which the structure model deliberately avoids.
- **Bridge piers**, raised parks, `street_full` as its own section type, vines and ice in the
  climate pass.
- **Craters outside a building** — damage is applied to the blocks the building itself places;
  the surrounding terrain is not chewed up (a piece may only write inside its own chunk).
- **Commands, GUI, config screens, in-game editor, player data, networking, world profiles.**
- **`belowpart` / `inbiome` conditions** are still treated as satisfied; `STRUCTURE_VOID` is
  passthrough; the deferred POI / lighting / sapling passes are still deferred.

### Known rough edge

A city takes one shared ground level (the lowest lot corner), so a city rolled on a lake shore
can sit partly below the waterline and take on water. The engine drains the volume inside each
building footprint, but not water that flows back in from outside. Fixing it properly means
either lifting the ground level above the waterline or draining the city perimeter — phase 4.

## Left to a human with a client

The dedicated server boots clean and the saved world was inspected block by block, but headless
cannot judge how any of it *looks*. In a real client, check: how an intersection of street tiles
reads with its kerbs and lamps; whether the 2×2 landmark dominates its block the way it should;
whether a pier-less bridge over a wide river looks too bare; how visible the snow/moss climate
pass is; and the flooded-city case above.
