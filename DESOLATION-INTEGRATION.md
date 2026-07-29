# Lost Buildings × Desolation — integration design

Written after reading both trees in full. Statements are **[verified]** when they come from a
specific line of source, **[inferred]** when they come from reasoning I did not execute.

---

## 0. Compatibility — there is no caveat

| | Lost Buildings | Desolation (`unknown-wq/desolation`, branch `26.2`) |
|---|---|---|
| mod id | `lostbuildings` | `desolation` |
| loader | Fabric | Fabric |
| Minecraft | `>=26.2 <26.3` | `>=26.2 <26.3` |
| Java | `>=25` | `>=25` |
| Fabric API | required | `>=0.154.0+26.2` |
| **Biolith** | `depends: "*"`, `BiomePlacement.replaceOverworld` | `depends: >=3.6.0-alpha.9`, `BiomePlacement.replaceOverworld` |

Same loader, same Minecraft, same Java, same worldgen library. Everything below is buildable today.

**What Desolation is** [verified: `raltsmc.desolation.registry.*`]: a wildfire-aftermath mod. Three
biomes — `desolation:charred_forest`, `charred_forest_small`, `charred_forest_clearing` — a full
charred-wood block set, ash/ember/charcoal blocks, burnt flora, two mobs
(`desolation:ash_scuttler` ambient, `desolation:blackened` monster), two structures (`charred_hut`,
`ash_tinker_base`), and the **Ashen Lung** meter with the **Air Filter** helmet.

### The one thing worth flagging before anything else

Both mods already replace `minecraft:forest` through Biolith:

```java
// LostBuildings BiolithGeneration.init()          // Desolation DesolationBiolithGeneration.init()
replaceOverworld(PLAINS,  LOST_CITY, 0.15);        replaceOverworld(FOREST,      CHARRED_FOREST_SMALL, …);
replaceOverworld(FOREST,  LOST_CITY, 0.15);        replaceOverworld(BIRCH_FOREST,CHARRED_FOREST_SMALL, …);
replaceOverworld(SAVANNA, LOST_CITY, 0.15);        replaceOverworld(FOREST,      CHARRED_FOREST,       …);
                                                   replaceOverworld(TAIGA,       CHARRED_FOREST,       …);
```

They are **already competing for the same vanilla biome** when both are installed. Nothing crashes —
Biolith just hands `minecraft:forest` to whichever noise field wins a given cell — but it means the
biome layer is a shared surface between these two mods whether we design for it or not.

---

## 1. The question you asked: biome-driven or content-driven?

**Biome-driven.** Not as a preference — as the only one of the two that is *safe on its own*.

Four reasons, in order of weight:

1. **Only the biome gate degrades correctly.** A palette entry naming `desolation:charred_planks`
   is unconditional: `Tools.stringToState` catches the parse failure, logs a warning and returns
   `Blocks.AIR` [verified: BUILDING-GUIDE §7.1]. So a content-driven integration, on its own, ships
   every player without Desolation a city full of holes — silently, with no crash to tell them.
   A biome tag is the opposite: without Desolation the tag is empty, the structure never places, and
   the charred content is never reached. **The gate has to come first; content rides behind it.**
2. **It is this mod's own idiom.** `StyleSelector` already answers "biome → city style → palette
   style → what the city is made of", and `climateFor` already answers "biome → what has grown on it
   since". Adding a charred branch is finishing a table that exists, not inventing a mechanism.
3. **Desolation publishes exactly the tag we need, deliberately.** `DesolationBiomeModifications`
   hangs its whole spawn roster off `#desolation:charred_forests` with the javadoc *"other mods can
   add their own mobs to the Charred Forest by selecting the same tag"* [verified]. That is a mod
   author explicitly offering the biome as the integration surface.
4. **The biome gate pulls the atmosphere across for free.** `AshenLungHandler.isInAshenAir` tests
   `world.getBiome(pos).is(CHARRED_FORESTS)` [verified] — a biome tag, not a namespace check, and
   again documented as datapack-steerable. Put a city inside a charred forest and the smoke meter,
   the Air Filter, the Blackened and the ash-storm rain multiplier all apply to it with **zero
   lines of code**. No content-driven design can buy that at any price.

So: **biome-driven placement is the load-bearing seam; content-driven theming is the cargo.** The
proposals below are ordered by how much of their weight rests on the gate.

---

## 2. Proposals, ranked

### #1 — The Charred City *(built; see §3)*

**What the player experiences.** They walk into a Charred Forest, the ash starts filling their lung
meter, and through the blackened trunks there is a **burnt-out town** — five cells square, no street
lamps, potholed blackstone roads, buildings of soot-blackened masonry and charred timber with their
windows blown out. Vacant lots are drifts of ash, dead stumps and ash brambles. Cellar spawners are
mostly **Blackened**. The Air Filter stops being a curiosity and becomes the thing you put on before
you go looting.

It is the obvious idea, and the obvious one is right here: a mod about *what a place looks like after
everyone left* and a mod about *what a forest looks like after it burned* are the same sentence.

**What it takes.** A second instance of the existing `lostbuildings:lost_city` structure type,
keyed to `#desolation:charred_forests`, with its own building list. Because `LostCityConfig` already
owns the building list, street material, damage, density and parks as codec fields
[verified: `LostCityConfig.MAP_CODEC`], and because a building's `refpalette` shadows the style chain
[verified: BUILDING-GUIDE §5], **the whole thing is datapack files. No Java at all.**

**How it degrades.** Perfectly, and this is the point. Without Desolation
`#desolation:charred_forests` resolves empty (see §4 on the stub tag), the structure has no biome to
generate in, and the four charred buildings, two palettes, park part and mob condition are inert data
that nothing ever reads. **A player without Desolation sees no change whatsoever** — not a hole, not
a warning, not a missing block.

**Risks.** Two, both handled:
* A `desolation:` id in the *structure JSON* would be fatal, not silent — `BlockState.CODEC` is the
  `{"Name": …}` record codec and `SpawnerData` uses `byNameCodec()`; both hard-fail on an unknown id
  and take the datapack down with them. So the structure JSON is **100% vanilla**; every Desolation
  block lives in a lostcities palette, where the failure mode is a warning. [verified]
* A missing *tag* is also a hard codec failure (`Missing tag:`). Fixed by shipping an empty
  merge stub — §4.

---

### #2 — Charred as a first-class *city style* + an `ASHEN` climate

**What the player experiences.** The charred look stops being one bespoke structure and becomes
something the engine understands: **any** city that happens to stand in a charred biome is built
charred, and a fine layer of ash settles over its roofs exactly the way snow does in the north and
moss does in a swamp.

**What it takes.** Java, in the two places that already own these decisions:
`StyleSelector.opinionatedCityStyleFor` gains a charred branch, `Climate` gains `ASHEN`, and
`CityPiece`'s surface pass learns to lay `desolation:ash`. Exact diffs in §5. It also wants a proper
`styles/charred.json` + `citystyles/citystyle_charred.json` pair so the look is a real style rather
than four buildings' local palettes.

**Why it is #2 and not #1.** It is strictly better design and strictly worse risk. `StyleSelector` is
consulted by *every* city, so a charred style referencing Desolation blocks is one bad predicate away
from painting an ordinary city with air. The gate is a runtime `biome.is(tag)` rather than a datapack
tag that is simply empty — same effect, but the failure is a Java bug instead of a no-op. Do it
**after** #1 has been seen in a world.

**How it degrades.** `biome.is(#desolation:charred_forests)` on an unbound tag is `false`, so the
branch is dead and every city keeps its current style. Safe — but only because someone wrote it that
way, which is the difference from #1.

---

### #3 — Cross-seeding the two mods' own structures

**What the player experiences.** Desolation's `charred_hut` turns up on the fringes of a Lost City —
the homestead that was there before the city burned — and the burnt city's `scattered` cabins turn up
in the Charred Forest.

**What it takes.** Tag edits only, and they are cheap: add the three charred biomes to
`#lostbuildings:has_structure/scattered`, and add `lostbuildings:lost_city` to
`#desolation:charred_hut_has_structure`. The first is ours (§5.3); the second is *Desolation's* tag,
which we can contribute to from our own jar the same way we contribute the stub.

**Why it is #3.** It is real content for about six lines, but it is a garnish — it does not make
either mod's core loop different. Worth doing, not worth leading with.

**How it degrades.** Missing-namespace entries in a tag file must be written `{"id": …, "required":
false}`, which is exactly the form Desolation's own generated tags use [verified]. With that, absent
mods are skipped silently.

---

### #4 — Progression hook: the Air Filter as city loot *(recommended against)*

**What it would be.** Add `desolation:air_filter` and `desolation:activated_charcoal` to
`lostbuildings:chests/lostcitychest`, so a city run is how you gear up for the ash.

**Why not.** It is the one idea here that cannot be gated. `conditions/chestloot.json` and the loot
tables are shared by every city in every biome [verified: BUILDING-GUIDE §6], so a Desolation item in
them is either a hard reference (breaks without the mod) or needs a whole conditional-loot mechanism
this engine does not have. The right shape is a *separate* loot table named only by the charred
city's palette — which is proposal #1 territory, and better done there. Listed so it is explicitly
rejected rather than forgotten.

---

## 3. What I built (all new files — nothing existing was touched)

`gradle build --no-daemon` exits 0; **164 tests, 0 failures**, `DatapackGeometryTest` 5/5 green.

| file | what it is |
|---|---|
| `data/desolation/tags/worldgen/biome/charred_forests.json` | **empty merge stub** — the load-bearing safety file, see §4 |
| `data/lostbuildings/worldgen/structure/charred_city.json` | second `lostbuildings:lost_city` instance, biomes `#desolation:charred_forests` |
| `data/lostbuildings/worldgen/structure_set/charred_cities.json` | `random_spread`, spacing 20 / separation 8, own salt |
| `…/lostcities/palettes/charred_city.json` | soot-blackened masonry vocabulary |
| `…/lostcities/palettes/charred_timber.json` | charred-wood vocabulary |
| `…/lostcities/conditions/charredmobs.json` | spawner table, `desolation:blackened` weighted 12 |
| `…/lostcities/buildings/charred_tower.json` | `building1_*` + `top1x1_*` geometry, `refpalette: charred_city` |
| `…/lostcities/buildings/charred_timber_tower.json` | same geometry, `refpalette: charred_timber` |
| `…/lostcities/buildings/charred_block.json` | `building4_*` + `top4_*`, `refpalette: charred_city` |
| `…/lostcities/buildings/charred_annex.json` | same geometry, `refpalette: charred_timber` |
| `…/lostcities/parts/charred_park_ash.json` | 2-slice burnt vacant lot: ash drifts, stumps, brambles |

### Three decisions worth defending

**No new geometry.** The four buildings re-use `building1_*`/`building4_*`/`top*` verbatim and change
only the palette. That is not laziness — it is the repo's own precedent: `building8.json` is
*literally* `building1`'s fourteen part refs with a two-entry local palette [verified]. Proven
geometry, zero slice-count risk, nothing to conflict with the concurrent `street_*`/`bridge_*` work.

**Every `desolation:` id was checked against the registry, mechanically.** All fourteen block ids and
the one entity id were extracted from my files by regex and matched against the `register("…")` calls
in `DesolationBlocks.java` / `DesolationEntities.java`. Zero unmatched. Blockstate properties were
read off the *block classes*, not guessed: `charred_log` is a `RotatedPillarBlock` (`axis`),
`ash` extends `SnowLayerBlock` (`layers`), and `charred_branches` extends `LeavesBlock` — which is
why the park part writes `[persistent=true]`, or the branches would decay on a random tick.

**A fixed hole, for free.** `building1`'s parts use the character `o` twelve times, and `o` is
defined **only** in `palettes/oilrig.json` — so in a standard-style city those twelve blocks are
already silently air today. Both charred palettes define `o`, so the charred towers do not inherit
the defect. *(Not fixed for `building1` itself — that would be an edit to a shared file. Flagging it.)*

### What the charred city actually looks like, in numbers

`city_size: 5` (3×3 building lots) · `density: 0.6` · `damage_chance: 0.55` (vs 0.2 for a normal
city) · `lamp_spacing: 0` (no working street lights) · `pothole_chance: 0.18` · `downtown_chance: 0`
and `multi_buildings: []` — deliberately no landmark, because every shipped multibuilding quadrant
(`center*`, `library*`, `shopping*`, `town*`) has its own look that the charred palettes do not
cover, and a pristine grey shopping mall in the middle of a burn would wreck it.

**One known cosmetic wart:** park lots still get a `minecraft:grass_block` lawn. `ParkPiece` reads the
`G` character from the *style* palette before the part palette is layered on
[verified: `ParkPiece` line ~95], so a park part cannot override its own lawn. The ash part covers
most of it; `park_chance` is held to 0.3 for that reason. Fixing it properly is a `ParkPiece` change
and belongs with proposal #2.

---

## 4. The stub tag — the single most important file here

```json
// data/desolation/tags/worldgen/biome/charred_forests.json   (shipped by US, in THEIR namespace)
{ "replace": false, "values": [] }
```

**Why.** A structure's `biomes` field decodes through `HolderSetCodec`, which resolves a `#tag`
reference via `HolderGetter.get(TagKey)`. An **unbound tag is a `DataResult` error**, not an empty
set — so without this file, a player who installs Lost Buildings and *not* Desolation gets a datapack
error on `charred_city.json`. **[verified: MC codec behaviour; the reason for the file, not a guess
about it]**

`"replace": false` means it merges: empty on its own, and when Desolation is present its three
biomes are added by Desolation's own generated file at the identical path. Contributing to another
mod's namespace is normal datapack practice and is how the same file at
`src/main/generated/data/minecraft/tags/…` already works in both repos.

It also makes proposal #3 and the `StyleSelector` branch in #2 safe by the same mechanism.

---

## 5. Exact changes to existing files — **for you to apply, I did not touch these**

None of the following is required for §3 to work. They are proposal #2 and #3.

### 5.1 `world/feature/StyleSelector.java` — charred style + `ASHEN` climate

```java
// beside CONVENTIONAL_IS_DESERT / _SWAMP / _SNOWY:
/** Desolation's Charred Forest family. Unbound (mod absent) => is() is false => branch is dead. */
private static final TagKey<Biome> DESOLATION_CHARRED_FORESTS =
        TagKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("desolation", "charred_forests"));

/** Burnt-out city for Desolation's Charred Forest — {@code citystyles/citystyle_charred.json}. */
public static final String CHARRED_CITY_STYLE = "citystyle_charred";

// in enum Climate, after SWAMPY:
/** Ash settles on every flat surface. */
ASHEN,

// in opinionatedCityStyleFor(...), FIRST — charred beats desert/swamp/snowy because a burn
// is a stronger statement about a place than its rainfall is:
if (isCharred(biome)) {
    return CHARRED_CITY_STYLE;
}

// in climateFor(...), also first:
if (isCharred(biome)) {
    return Climate.ASHEN;
}

private static boolean isCharred(Holder<Biome> biome) {
    return biome.is(DESOLATION_CHARRED_FORESTS);
}
```

**Careful:** `styleOfCityStyle` already falls back to `DEFAULT_STYLE` with a one-shot warning if
`citystyle_charred` is missing, so shipping the Java without the datapack files degrades to a normal
grey city rather than breaking. That is the correct order to land it in.

### 5.2 `world/structure/piece/CityPiece.java` — the ash surface pass

`CityPiece` line ~154 currently reads `boolean everywhere = this.climate == Climate.SNOWY;` for the
snow/moss cover pass. `ASHEN` should join it with `desolation:ash` as the cover block — but **that
block must be looked up by id at runtime and skipped when absent**, never referenced statically:

```java
// resolve once, statically; null when Desolation is not installed
private static final BlockState ASH_COVER = BuiltInRegistries.BLOCK
        .getOptional(Identifier.fromNamespaceAndPath("desolation", "ash"))
        .map(Block::defaultBlockState)
        .orElse(null);
```

…and the `ASHEN` branch must no-op when `ASH_COVER == null`. I have deliberately **not** written this
one out as a finished diff: `CityPiece`'s cover pass is next door to `BridgePiece`/`Streets`, which
are being edited concurrently, and I would rather hand you the constraint than a diff that conflicts.

### 5.3 `data/ModBiomeTagProvider.java` — charred biomes get scattered structures *(proposal #3)*

```java
builder(ModStructureTags.HAS_SCATTERED)
        …existing addOptionalTag calls…
        .addOptionalTag(TagKey.create(Registries.BIOME,
                Identifier.fromNamespaceAndPath("desolation", "charred_forests")));
```

`addOptionalTag` is required, not optional — see that file's own javadoc on why a plain `addTag`
fails datagen for a tag this provider does not itself generate. Then re-run `runDatagen`, which
rewrites `src/main/generated/data/lostbuildings/tags/worldgen/biome/has_structure/scattered.json`.

### 5.4 `fabric.mod.json` — declare the soft dependency

`biolith` is currently a **hard** `depends`. Desolation must **not** be: add it to a new `suggests`
block so the mod menu shows the relationship without requiring the mod.

```json
  "suggests": {
    "desolation": ">=2.0.1"
  },
```

Nothing in §3 needs this; it is documentation for the player, and it is the honest declaration of
what the charred city is.

### 5.5 `world/gen/BiolithGeneration.java` — the biome collision, if you want it resolved

`replaceOverworld(FOREST, LOST_CITY, 0.15)` and Desolation's two `replaceOverworld(FOREST, …)` calls
overlap. **My recommendation is to leave this alone.** Biolith resolves it without error, the two
biomes simply share `minecraft:forest`, and the current 0.15 is documented in that file at length as
load-bearing — cutting it once stopped the mod generating anything. If you ever do want them
composed rather than competing, the tool is `BiomePlacement.addSubOverworld(CHARRED_FOREST,
LOST_CITY, criterion)` — a Lost City *inside* the burn rather than beside it — which is exactly the
call Desolation uses for its own clearings [verified: `DesolationBiolithGeneration`]. That is a
bigger change than it looks and wants its own session.

---

## 6. Verification performed

* `gradle build --no-daemon` → exit 0. **164 tests, 0 failures, 0 errors**; `DatapackGeometryTest`
  5/5 (baseline 159 — the delta is concurrent work, no class regressed).
* Every weighted `blocks` list in the new palettes checked against the 128-slot rule
  (`sum(before last) < 128 <= total`) — the one palette mistake that throws at load rather than
  failing silently.
* Every character used by all 33 part refs the four charred buildings pull in, resolved against
  `styles/standard` ∩ its `refpalette`. Zero undefined. Rubble char `}` defined in both palettes.
* All 14 `desolation:` block ids and 1 entity id regex-extracted from the new files and matched
  against `register("…")` in Desolation's own registry sources. Zero unmatched.
* Blockstate properties read off Desolation's block *classes*, not assumed.
* `lostbuildings/logs/` created by the build was deleted.
