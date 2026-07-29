# Editing Lost Cities building data — a working guide

Everything here was derived by reading the engine source in this repo and by shipping
`building3`. Statements are marked **[verified]** when they come from a specific line of Java in
this tree, and **[inferred]** when they come from reading the shipped data or from reasoning that
was not checked against code.

There are **two Java trees** in this repo and only one of them runs:

| tree | status |
|---|---|
| `1.21/src/main/java/mcjty/lostcities/**` | the upstream Forge/1.21 Lost Cities port. **Reference only** — it is not what generates your buildings. |
| `lostbuildings/src/main/java/com/lostbuildings/**` | the live Fabric / MC 26.2 engine. **This is what reads your JSON.** |

They share the data format but **not** the behaviour. If you find a feature in `1.21/` (doors,
corridors, city spheres, highways) check it exists in `lostbuildings/` before you design around it.
See §9 — this cost me real time on `building3`.

---

## 1. Where things live

Datapack root: `lostbuildings/src/main/resources/data/lostbuildings/lostcities/`

```
variants/        block-mix presets referenced by palettes via "variant": "<name>"
palettes/        char -> block tables, SHARED between buildings
parts/           the actual geometry: 16x16 slabs of characters, one file per part
buildings/       assembles parts into a building; may carry its own local palette
conditions/      weighted tables for loot table ids and mob ids ("chestloot", "easymobs", ...)
styles/          groups of random palettes; a style is one "material look" for a whole city
citystyles/      which buildings/styles a city uses; inheritance via "inherit"
multibuildings/  2x2 (dimx/dimz) landmark layouts naming a building per quadrant
worldstyles/     LOADED BUT NOT CONSULTED by this engine  [verified: AssetLoader javadoc]
```

Loading order is `variants -> palettes -> parts -> buildings -> conditions -> styles ->
multibuildings -> citystyles -> worldstyles`, then `resolveReferences()`
[verified: `engine/AssetLoader.java:load`]. Assets are keyed by **bare filename**, so
`parts/building3_top.json` is referenced as `"building3_top"`.

A broken file is skipped with a log line and a failure counter — it does **not** crash the game,
it just makes your building silently wrong. Do not rely on a crash to tell you something is broken.

---

## 2. Part format

```json
{
  "xsize": 16,
  "zsize": 16,
  "slices": [
    [ "################", ... 16 rows total ... ],   <- slice 0
    [ ... ],                                          <- slice 1
    ...
  ]
}
```

### The rules that will bite you

* **Every row must be exactly `xsize` characters, and every slice exactly `zsize` rows.**
  The codec concatenates the rows of a slice into one flat string and indexes into it
  [verified: `codec/BuildingPartRE.java` constructor]. A 15- or 17-char row does not error — it
  shifts every subsequent cell by one and you get a diagonally smeared building.
* For city buildings that is **16x16**.
* **A slice is one Y layer, listed bottom to top.** Slice `i` lands at `origin.y + height + i`
  [verified: `BuildingEngine.generatePart`, `origin.offset(rx, oy + y, rz)`].
* **Every *stacked storey* part must have exactly 6 slices**, because the engine advances by a
  fixed `FLOORHEIGHT = 6` per storey and ignores the part's own height
  [verified: `BuildingEngine.java:56`, `int height = f * FLOORHEIGHT;`]. If you write 5 you get a
  1-block gap; if you write 7 the 7th layer is overwritten by the next storey.
* **This applies only to parts in the storey stack — not to every part.** Nothing sits above a
  `"top": true` part, so a top may be shorter and the shipped ones are: `top4_1` has 4 slices,
  `top4_2` has 3, `top4_3` has 1. Standalone parts placed by their own piece rather than by the
  storey loop are shorter still — `park_plants` is 1 slice, `park_pool` and `park_trees` are 2,
  the `street_*` tiles are 5. Repo-wide, **61 of the 224 parts have fewer than 6 slices**,
  and all of them are tops, streets, parks, fountains, bridges, rails or shop interiors. So do not
  pad a street or a roof out to 6 slices to satisfy this rule — check how the part is placed first.

* **Where the origin and the ceiling come from, per family.** A standalone part has no storey
  pitch to obey, but it does have a budget: its piece writes nothing outside its own bounding box,
  which is `cellBox(chunkX, chunkZ, groundY, belowGround, aboveGround)` — Y from
  `groundY - belowGround` to `groundY + aboveGround` inclusive. Slice `i` of the part lands at
  `originY + i`, so the number of slices you may write is `groundY + aboveGround - originY + 1`.
  Slices past that are computed and silently thrown away by the `chunkBox.isInside` test.
  **[verified: `CityPiece.cellBox`, `PartPlacer.place`, and each piece's own constants]**

  | family | placed by | `originY` | box top | slices that fit |
  |---|---|---|---|---|
  | storey / cellar | `BuildingPiece` → `BuildingEngine` | ground-floor corner `+ 6 * floor` | `groundY + (floors + 2) * 6 + margin` | exactly 6 (the pitch, not the box, is the limit) |
  | `street_*` | `StreetPiece.layTile` | `groundY - 1` | `groundY + 6` | 8 — the shipped tiles use 5 |
  | `park_*`, `fountain*` | `ParkPiece` | `groundY` | `groundY + 8` | 9 — the shipped parts use 1–2 |
  | `bridge_*` | `BridgePiece` | `groundY - 1` | `groundY + 8` | 10 — `clearDeckVolume` empties `groundY .. groundY + 4` first, so a taller bridge part is free to fill it back in |

  Note the street origin: the tile's slice 0 **replaces** the base course at `groundY - 1` rather
  than sitting on it, which is why slice 0 is the carriageway and slice 1 is the raised pavement.
* **`parts2` overlays are the third exception.** A part referenced from a building's `parts2`
  list is meant to be drawn *over* a storey, not stacked as one, so it is a thin furniture layer:
  `shopping11_in_3` is 1 slice, `shopping11_in_1` and `_in_2` are 3. (In the live engine `parts2`
  is never placed at all — see §9.) **The rule in one line: exactly the parts referenced from
  `parts` without `"top": true` must be 6 slices.**

### Orientation — **[verified]**

```java
// engine/BuildingPart.java
public Character getC(int x, int y, int z) { return slices[y].charAt(z * xSize + x); }
// engine/BuildingEngine.java  (Transform.ROTATE_NONE)
int rx = ox + x;  int rz = oz + z;
BlockPos pos = origin.offset(rx, oy + y, rz);
```

* `slices[y]` — index into the **outer** array is Y (bottom to top).
* **row index inside a slice = z**, first row is `z = 0`.
* **character index inside a row = x**, first character is `x = 0`.
* `x` maps to world **+X = east**, `z` maps to world **+Z = south**. So with no rotation
  **row 0 is the north edge and column 0 is the west edge.**

```
        x = 0 .............. 15   (west -> east)
 z = 0  ##   ######   ##          <- north edge
 z = 1  ##aaa#    #aaa##
 ...
 z = 15 ##   ######   ##          <- south edge
```

**But** a building is placed with a random quarter turn:
`Transform.values()[Math.floorMod(quarterTurns, 4)]`
[verified: `world/structure/piece/BuildingPiece.java:164`], i.e. one of
`ROTATE_NONE / 90 / 180 / 270`. Block states are rotated with the same transform
(`b.rotate(transform.getMcRotation())`), so a `facing=north` stair stays consistent with the
geometry. Consequence: **"north" in your part data is building-local, not world-north.** Design
for internal consistency, never for a real compass direction.

### Air and `structure_void` — **[verified]**

```java
if (b == BlockStates.AIR || b == BlockStates.STRUCTURE_VOID) { continue; }
// "Air means leave the world untouched here."
```

A space character does **not carve**; it leaves whatever is already there. Above ground the
building volume has already been cleared, so it reads as air. **In cellars it would not** — which
is why the engine explicitly calls `carveCellar()` before writing each cellar slice
[verified: `BuildingEngine.carveCellar`]. Also, a column whose characters are *all* spaces is
stored as `null` and skipped entirely (an optimisation, not a behaviour change).

---

## 3. Building format

Real file, `buildings/building6.json`, trimmed:

```json
{
  "filler": "#",
  "rubble": "}",
  "mincellars": 1,
  "maxcellars": 1,
  "palette": { "palette": [ /* entries, see §4 */ ] },
  "parts": [
    { "part": "building6_cellar", "cellar": true },
    { "part": "building6_ground", "ground": true, "top": false },
    { "part": "building6_1", "top": false, "ground": false, "cellar": false },
    { "part": "building6_3", "top": false, "ground": false, "cellar": false, "range": "2,16" },
    { "part": "building6_top", "top": true }
  ]
}
```

| field | meaning |
|---|---|
| `filler` (required) | char used for structural fill / foundations. Conventionally `#`. |
| `rubble` | char strewn over the ground floor of a damaged building [verified: `scatterRubble`]. **Only used when damage is on.** |
| `mincellars` / `maxcellars` | clamp the caller's cellar count. Engine hard cap is **2** [verified: `PlaceSettings.MAX_CELLARS = 2`]. |
| `minfloors` / `maxfloors` | clamp the storey count; omit to inherit the world config range [verified: `pickFloors`]. |
| `overrideFloors` | makes this building's min/max win over the citystyle/profile. |
| `preferslonely` | 0..1 chance the building wants no neighbours. |
| `allowDoors` | parsed, **never read by the live engine**. See §9. |
| `refpalette` | name of a shared palette file to attach. Mutually exclusive with `palette`. |
| `palette` | **inline local palette** — see §5. |
| `parts` | list of part refs, see below. |
| `parts2` | a second, usually shorter part meant to be overlaid on the storey at the same height. Declared by 10 shipped buildings — the `shopping*` and `town*` quadrants only, **not** `center*` or `library*` — but **the live engine never places it** (see §9), so today it is inert data. |

### Part refs and their conditions

For every storey `f` in `-cellars .. floors` the engine collects **all** part refs whose condition
matches and picks one uniformly at random [verified: `Building.pick`, `BuildingEngine`
floor loop]. So "give a floor three possible looks" = three refs with the same condition.

Storey numbering [verified: `condition/ConditionContext.java`]:

* `floor == 0` is the **ground floor**; positive is upward; **negative is a cellar**.
* `topFloor == floors`, and the loop runs `f = -cellars .. floors` **inclusive**, so the top part
  is an extra storey placed *above* the last normal floor.

| condition | matches when |
|---|---|
| `"top": true/false` | `floor == topFloor` |
| `"ground": true/false` | `floor == 0` |
| `"cellar": true/false` | `floor < 0` |
| `"floor": n` | `floor == n` (negative addresses cellars) |
| `"range": "a,b"` | `a <= floor <= b`, inclusive both ends |
| `"inpart"` / `"inbuilding"` | name match (string or list) |
| `"chunkx"` / `"chunkz"` | cell chunk coordinate — used by `multibuildings` quadrants |
| `"isbuilding"` | always true in this engine |
| `"issphere"` | always false in this engine |
| `"belowpart"`, `"inbiome"` | **ignored — treated as satisfied** [verified: `ConditionMatcher` javadoc] |

Omitting a field means "don't care". A ref with no conditions at all matches every storey
including cellars and the top — which is how the old `building3` accidentally used its office
floors as cellars.

**Gotcha:** if no ref matches a cellar storey the engine does *not* skip it — it re-rolls with the
context of storey 1 and buries an ordinary office floor [verified: `BuildingEngine` floor loop
comment]. A building with `mincellars > 0` and no `cellar: true` part therefore looks fine and is
subtly wrong. Always author a real cellar part.

---

## 4. Palette entry syntax

A palette file is `{"palette": [ ...entries... ]}`. A local palette on a building is
`"palette": {"palette": [ ...entries... ]}` (note the double nesting — it is a `PaletteRE` object,
not a bare list).

An entry must supply exactly one of `block` / `blocks` / `variant` / `frompalette`, else the
loader throws `Illegal palette` [verified: `Palette.parsePaletteArray`].

```json
{ "char": "q", "block": "minecraft:iron_block", "damaged": "minecraft:iron_bars" }

{ "char": "m",
  "blocks": [
    { "random": 30,   "block": "minecraft:weathered_cut_copper" },
    { "random": 25,   "block": "minecraft:exposed_cut_copper" },
    { "random": 20,   "block": "minecraft:oxidized_cut_copper" },
    { "random": 15,   "block": "minecraft:cut_copper" },
    { "random": 1000, "block": "minecraft:weathered_copper" }
  ]
}

{ "char": "#", "variant": "deepslate", "damaged": "minecraft:iron_bars" }
{ "char": "@", "frompalette": "a" }
{ "char": "&", "block": "minecraft:barrel[facing=up]", "loot": "chestloot" }
{ "char": "1", "block": "minecraft:spawner", "mob": "easymobs" }
{ "char": "T", "block": "minecraft:wall_torch[facing=north]", "torch": true }
{ "char": ";", "block": "minecraft:furnace",
  "tag": { "Items": [ { "Slot": 0, "id": "minecraft:coal", "Count": 10 } ] } }
```

### How `random` weights actually resolve — **[verified], and not what it looks like**

`CompiledPalette` expands a weighted entry into a **fixed 128-slot table**
(`RANDOM_TABLE_SIZE = 128`). Each `blocks` entry is written into `random` **literal consecutive
slots**, in list order, until the table is full; lookup is `table[rand.nextInt(128)]`.

```java
// engine/CompiledPalette.java
private static final int RANDOM_TABLE_SIZE = 128;
for (Pair<Integer, BlockState> pair : r) { idx = addEntries(randomBlocks, idx, pair.getRight(), pair.getLeft()); ... }
if (idx < randomBlocks.length) {
    throw new RuntimeException("Invalid palette entry for '" + key + "'! Not enough blocks in the random list (factor should go up to 128)");
}
```

Consequences you must internalise:

1. `random` is a **slot count out of 128**, not a relative weight. `{"random": 30}` means
   `30/128 = 23.4%`.
2. The trailing **`"random": 1000` entry is not a rare fallback — it is "fill the rest of the
   table".** In the `m` example above the first four take `30+25+20+15 = 90` slots and the
   `weathered_copper` fallback takes the remaining **38**, making it the single most likely block.
   That idiom is everywhere in the shipped data; copy it, but understand it.
3. **The weights before the fallback must sum to < 128, and the last entry must be big enough to
   reach 128, or the game throws at asset-load time.** This is the one palette mistake that is
   loud rather than silent.
4. `variant` entries go through the same table (a variant file is just a `blocks` list).

### The other fields

| field | effect |
|---|---|
| `damaged` | registers `thisState -> damagedState` in a global map used by the weathering/damage pass. Harmless when damage is off. |
| `loot` | a **condition name** (`conditions/*.json`), not a loot table id. The condition resolves to a loot table weighted by storey/part. Applies to any block with an inventory block entity. |
| `mob` | a condition name resolving to an entity id; written as `SpawnData` on a `minecraft:spawner`. |
| `torch` | marks the block as a light source that is skipped when the placement settings disable lighting. |
| `tag` | raw block-entity NBT compound merged in after placement. |
| `frompalette` | alias: "this char means whatever char X means", resolved iteratively after all direct entries [verified: `CompiledPalette.addPalettes` fixpoint loop]. Used by `glass_side_variant_*`. |

Existing condition names in this repo: `chestloot`, `easymobs`, `hardmobs`.

---

## 5. The local-palette technique (use this)

`CompiledPalette` is built in layers, later layers **overriding** earlier ones for the same char:

```java
// engine/BuildingEngine.generateBuilding
Palette buildingPalette = b.getLocalPalette(assets);
if (buildingPalette != null) { palette = derive(pal, buildingPalette); }
// engine/BuildingEngine.generatePart — a part's own palette layers on top of that
Palette partPalette = part.getLocalPalette(assets);
if (partPalette != null) { compiledPalette = derive(basePalette, partPalette); }
```

Order: **style palettes -> building local palette -> part local palette**.

So: put **all** new characters in a `"palette"` block on `buildings/<yours>.json`. You then get
every new block you need **without editing a single shared file**, which means:

* No merge conflicts with agents working on other buildings in parallel.
* No risk of stealing a character another building already relies on — your definitions are scoped
  to your building only.

**Corollary — choose your chars carefully.** Anything you define locally *shadows* the style
version for your building. If you define `#` locally you lose the per-city material variety
(stonebrick / cyan / gray / silver / desert brick) that the style would otherwise roll for you.
For `building3` I deliberately left `#`, `}` and `a` undefined locally so they keep coming from the
style chain, and only declared genuinely new characters.

### If you are not writing a building — **[verified]**

Everything above is about the building path, where there are three layers to put a palette on. A
`street_*`, `park_*`, `bridge_*` or `fountain*` part is **not** placed by `BuildingEngine`: it is
stamped by `world/structure/piece/PartPlacer.java` on behalf of `StreetPiece`, `ParkPiece` or
`BridgePiece`. There is no building above it, so there are exactly **two** layers:

```
style palettes  ->  the part's own "palette"
```

`PartPlacer.paletteFor` composes them with `new CompiledPalette(stylePalette, partPalette)` — the
same call and the same precedence as `BuildingEngine.generatePart`, so a character defined in both
resolves to the part's version and everything else still comes from the style. Practical
consequences:

* **A local palette on one of these parts is the only place you can put new characters**, because
  there is no `buildings/<name>.json` to hang one on and `palettes/*` is shared (§6). Use it: the
  shipped `street_all` and `street_t` define `,` locally for their crossing paint, and
  `park_trees` defines `P` for its saplings.
* **The whole set of style characters is still available**, so a street part can go on using `S`,
  `Q`, `b`, `_`, `:`, `v`, `x`, `B`, `w`, `9`, `G` exactly as the shipped tiles do.
* **This was broken until recently.** `PartPlacer` used to ignore the part's palette entirely, so
  every character only it defined resolved to `null`, which the placer reads as "leave the world
  alone" — the blocks did not error, they simply were not there. If you are reading an old part
  whose local palette appears to do nothing, that is why. `StreetTilesTest` pins the fix.
* **`"refpalette"` works here too, and is the same slot.** `Assets` runs
  `BuildingPart.resolveLocalPalette` once at load and turns a `refpalette` name into the part's
  local palette, so a part has *either* an inline `"palette"` *or* a `"refpalette"`, never both —
  and whichever it is, that is the layer `PartPlacer` puts over the style. A `refpalette` naming a
  palette no style rolls (`rails`, `oilrig`) is a legitimate way to give one part a private
  vocabulary without touching a shared file.

### Which characters are already taken

A style is a list of *groups*; one palette is chosen per group and all of them merged
[verified: `BuildingEngine.buildPalette`]. So a char is only **guaranteed** if every palette in its
group defines it. Computed over all five styles that can host a city building
(`standard`, `desert`, `snowy`, `swamp`, `standard_border`), the guaranteed set is:

```
(space) ! # $ % ( ) * + - . / 1 4 9 : = @ A B C D F G K L Q R S T W X Y Z \ _ ` a b c d g h l p t u v w x y z { ~
```

Notable: `}` (rubble) is **not** guaranteed — `bricks_desert*.json` omits it, so never use `}`
inside slices even though you must still declare it as the building's `rubble` char.
`;` is likewise only in `default.json`, not `default_desert.json`.

Everything else is free for a local palette. The set I used for `building3` was:

```
0 2 3 5 6 7 8   e j m n o q r s   H I J M N O P U V   & ' , < > ? [ ] ^ |
```

The verification script in §8 recomputes the guaranteed set for you — do not trust this list to
stay current.

---

## 6. Shared vs. safe-to-own files

| do not touch without coordinating | why |
|---|---|
| `palettes/*.json` | every building in every style reads these |
| `styles/*.json` | ditto |
| `citystyles/*.json` | building selection weights; other agents edit these |
| `conditions/*.json` | loot/mob tables shared by everything |
| `variants/*.json` | referenced by palettes across the set |
| `multibuildings/*.json` | e.g. `multi3.json` is a 2x2 of `building3`; renaming a building breaks it |
| shared parts: `top1x1_*`, `top4_*`, `stairs*`, `street_*`, `rails_*`, `highway_*`, `park_*` | referenced by many buildings — **never rename or repurpose**; add your own part instead |

| safe to own | |
|---|---|
| `buildings/<yours>.json` | one building, one owner |
| `parts/<yours>_*.json` | name-space your parts with the building name, always |

Practical rule: if your change would be visible to a building you do not own, it belongs in a
local palette or a new part file instead.

---

## 7. Traps I actually hit

1. **A bad block id or blockstate silently becomes AIR.**
   ```java
   // engine/util/Tools.stringToState
   catch (CommandSyntaxException e) { LOGGER.warn("Cannot parse block state '{}' — falling back to AIR", s); return Blocks.AIR...; }
   ```
   A typo in a property name (`half=upper` on a trapdoor, `type=upper` on a slab) does not fail the
   build, does not fail asset loading, and produces a hole in your wall. There is no compile-time
   check — proofread every blockstate string. Correct forms used in `building3`:
   `slab[type=bottom|top|double]`, `stairs[facing=north|south|east|west,half=bottom|top,shape=straight]`,
   `trapdoor[facing=...,half=top|bottom,open=true|false,powered=false,waterlogged=false]`,
   `bed[facing=...,part=head|foot,occupied=false]`, `lantern[hanging=true]`,
   `chest[facing=...]`, `barrel[facing=up]`, `dispenser[facing=...,triggered=false]`,
   `observer[facing=...,powered=false]`, `hopper[facing=down,enabled=true]`,
   `lightning_rod[facing=up,powered=false]`, `lever[face=floor,facing=north,powered=false]`,
   `water_cauldron[level=3]`.

2. **An undefined character also silently becomes air**, with a one-shot warning:
   ```java
   warnOnce("palette:" + part.getName() + ":" + c, "Could not find entry '" + c + "' ... using air");
   ```
   The single most common way to ship a building full of holes. Run the §8 script.

3. **The weighted-list 128 rule** (§4). Sum-below-128 plus a fat fallback, or it throws on load.

4. **Beds** need both halves. `facing=north` puts the **head at the smaller z** and the foot at
   `z+1`. I got this backwards once and produced two floating half-beds.

5. **Ladders** need the support block on the opposite side of `facing`. `ladder[facing=north]`
   in this data set is used with a solid block at `z+1` (south) — that is the convention every
   shipped building follows, and it works. **[inferred from shipped data, not from code.]**

6. **Wall torches**: `wall_torch[facing=north]` needs its support at `z+1`. Same convention.

7. **Panes / bars / fences / walls / stairs are auto-corrected.** The engine recomputes
   `north/south/east/west` connection flags and the stair `shape` after the whole building exists
   [verified: `BlockStates.correct` + the second correction pass in `generateBuilding`]. Write
   `shape=straight` and let it sort itself out; do not hand-author connection flags.

8. **Hanging things need something above them.** `lantern[hanging=true]` and `chain` look wrong
   floating. On a 6-slice storey the ceiling is the topmost solid slice, so hang from the slice
   directly below it. Watch out for **recessed facade niches** — those columns are air all the way
   from the foundation to the roof, so a chain placed in one hangs from nothing.

9. **Spawners and loot are wired through `conditions/`, not directly.** `"loot": "chestloot"`,
   `"mob": "easymobs"`. Passing a loot **table id** there does not work.

9a. **On the `PartPlacer` path, a `"mob"` entry places nothing at all.** Not "an empty spawner" —
   nothing:
   ```java
   // world/structure/piece/PartPlacer.place
   Palette.Info info = resolved.getInfo(c);
   if (info != null && info.mobId() != null && !info.mobId().isEmpty()) {
       continue;   // no spawners in street furniture (see class javadoc)
   }
   ```
   This is deliberate and documented in that class's javadoc: streets, parks and bridges are
   scenery, and a spawner in the middle of a road is not scenery. The check is on the character's
   `Info`, not on the block, so it also swallows `1` and `4` from `common.json` — if you want a
   block there, define the character locally without a `"mob"` field. **[verified]**

9b. **On the `PartPlacer` path, `"loot"` and `"tag"` are dropped.** `PartPlacer` calls
   `level.setBlock` and stops; it never builds the block-entity compound that
   `BuildingEngine.generatePart` builds (`blockEntityTag(...)` → `attachBlockEntity(...)` for
   `inf.tag() != null || loot != null`). So a `C` chest in a park part generates as a **real but
   empty chest**, and the furnace char `;`, which carries a `tag` with ten coal, generates empty
   too. That is a deliberate simplification, not an oversight — an empty chest is a better failure
   than a worldgen crash — but do not design a street or park part around loot. **[verified:
   `PartPlacer.place` has no block-entity path; `BuildingEngine.java:436-441` has one]**

10. **`" "` does not delete.** See §2. Relevant if you ever write a part that is meant to hollow
    something out.

11. **Slice-count discipline.** All 6, always, for city buildings. The engine will not warn you.

12. **The floor sandwich.** With 6 slices the shipped convention is
    `0 = floor slab, 1..3 = room, 4..5 = solid ceiling`, which gives a 3-block-tall room and a
    3-block-thick sandwich between storeys. For `building3` I moved to
    `0 = floor slab, 1..4 = room, 5 = ceiling` — 4-tall rooms, 2-block sandwich, which is what makes
    ceiling ductwork and hanging lamps readable. Both work; **just be consistent across every part
    of one building**, because parts are mixed randomly per storey.

---

## 8. Verification recipe

`src/test/java/com/lostbuildings/data/DatapackGeometryTest.java` now walks the whole shipped
datapack on every `gradle build` — slice geometry, storey slice counts, part-reference resolution
and the 128-slot weight rule. Run it (`gradle test --tests '*DatapackGeometryTest'`) and keep it
green; do not edit it to accommodate your asset.

This script is still worth having while you iterate: it is scoped to the one thing you are working
on, it names free characters, and it is faster than a Gradle round trip. Save it outside the repo
(a scratch dir) — it is a tool, not an artefact.

**It works with or without a building.** Pass a building name (`verify.py building3`) and it checks
the building and its parts as before. Pass anything else (`verify.py street`, `verify.py park`,
`verify.py bridge`) and it switches to *part family mode*: it globs `parts/<name>*.json`, drops the
storey six-slice rule and the part-reference check — neither applies to a family with no building —
and resolves each part's characters against the style-guaranteed set plus **that part's own**
`"palette"` or `"refpalette"`, which is the real layering on the `PartPlacer` path (§5).

```python
#!/usr/bin/env python3
"""Verify a lostcities asset: JSON parse, 16x16 geometry, undefined chars.

Usage: python3 verify.py building3      # a building and its parts
       python3 verify.py street         # a part family with no building at all
"""
import json, glob, os, sys, collections

BASE = "/home/user/LostCities/lostbuildings/src/main/resources/data/lostbuildings/lostcities"
NAME = sys.argv[1] if len(sys.argv) > 1 else "building3"
BLD = os.path.join(BASE, "buildings", NAME + ".json")
HAS_BUILDING = os.path.exists(BLD)
ok = True

# --- 1. every file parses -------------------------------------------------
files = sorted(glob.glob(os.path.join(BASE, "parts", NAME + "*.json")))
if HAS_BUILDING:
    files.append(BLD)
elif not files:
    sys.exit("no buildings/%s.json and no parts/%s*.json -- nothing to check" % (NAME, NAME))
docs = {}
for f in files:
    try:
        docs[f] = json.load(open(f))
    except Exception as e:
        ok = False; print("PARSE FAIL", f, e)
print("1. JSON parse: %d/%d OK%s" % (len(docs), len(files),
      "" if HAS_BUILDING else "  (no building: part family mode)"))
bld = docs[BLD] if HAS_BUILDING else None

# --- 2. geometry: xsize x zsize, storey-stack slice counts ----------------
# Only the storey stack must be 6 slices (see section 2): parts referenced from
# "parts" without "top": true. Tops sit above everything and parts2 entries are
# overlays, so both may legitimately be shorter -- reported, never failed.
# With no building there is no storey stack, so every part is "other": a street,
# park or bridge part is bounded by its piece's box, not by the storey pitch.
storey = {p["part"] for p in bld["parts"] if p.get("top") is not True} if bld else set()
counts, other = {}, {}
for f, d in docs.items():
    if "slices" not in d:
        continue
    base, xs, zs = os.path.basename(f), d["xsize"], d["zsize"]
    (counts if base[:-5] in storey else other)[base] = len(d["slices"])
    for si, s in enumerate(d["slices"]):
        if len(s) != zs:
            ok = False; print("  BAD row count", base, "slice", si, len(s))
        for zi, row in enumerate(s):
            if len(row) != xs:
                ok = False; print("  BAD row length", base, si, zi, len(row), repr(row))
print("2. geometry: storey parts =", sorted(set(counts.values())),
      " tops/overlays/standalone =", sorted(set(other.values())))
if counts and set(counts.values()) != {6}:
    ok = False; print("  STOREY PARTS MUST ALL BE 6 SLICES:", counts)

# --- 3. every char is defined --------------------------------------------
def pal_chars(p):
    return {e["char"] for e in json.load(open(p))["palette"]}

def local_chars(d):
    """The chars an inline "palette" or a "refpalette" gives one file."""
    chars = {e["char"] for e in d.get("palette", {}).get("palette", [])}
    if "refpalette" in d:
        chars |= pal_chars(os.path.join(BASE, "palettes", d["refpalette"] + ".json"))
    return chars

# a char is guaranteed only if EVERY palette in its random group defines it
guaranteed = None
for st in ["standard", "desert", "snowy", "swamp", "standard_border"]:
    g = set()
    for grp in json.load(open(os.path.join(BASE, "styles", st + ".json")))["randompalettes"]:
        inter = None
        for p in grp:
            c = pal_chars(os.path.join(BASE, "palettes", p["palette"] + ".json"))
            inter = c if inter is None else inter & c
        g |= inter
    guaranteed = g if guaranteed is None else guaranteed & g

bld_local = local_chars(bld) if bld else set()
used, where = collections.Counter(), collections.defaultdict(set)
undefined = []
for f, d in docs.items():
    known_here = guaranteed | bld_local | local_chars(d)
    for s in d.get("slices", []):
        for row in s:
            for ch in row:
                used[ch] += 1; where[ch].add(os.path.basename(f))
                if ch not in known_here:
                    undefined.append((ch, os.path.basename(f)))
part_local = set()
for f, d in docs.items():
    if f != BLD:
        part_local |= local_chars(d)
print("3. palette: building-local=%s  part-local=%s" % (
      "".join(sorted(bld_local)), "".join(sorted(part_local))))
print("   free chars still available: %s" %
      "".join(sorted(chr(c) for c in range(33, 127)
                     if chr(c) not in guaranteed | bld_local)))
for ch, f in sorted(set(undefined)):
    ok = False; print("   UNDEFINED %r used in %s" % (ch, f))
for c in sorted(c for c in bld_local if c not in used):
    print("   note: local char %r defined but never used" % c)

# --- 4. referenced parts exist -------------------------------------------
if bld:
    for r in [p["part"] for p in bld["parts"]]:
        if not os.path.exists(os.path.join(BASE, "parts", r + ".json")):
            ok = False; print("   MISSING PART", r)
else:
    print("4. part refs: no building, nothing to resolve")

print("\nRESULT:", "ALL CHECKS PASSED" if ok else "FAILURES ABOVE")
sys.exit(0 if ok else 1)
```

Worth adding per building, cheaply:

* **vertical-circulation continuity** — assert your ladder/stair char occupies the same `(x, z)` in
  *every* slice of *every* non-top part, or storeys will not connect.
* **structure mask** — build a set of "these cells are exterior wall / glass" and assert nothing
  overwrote them accidentally. This caught six cells on `building3` where a cobweb-mix character
  (40% cobweb / 60% air) had landed on a window and would have punched holes in the facade.

### Generating parts

Hand-typing ~900 sixteen-character rows is a mistake. Write a throwaway Python generator that
builds each slice from a shell template plus `put(x, z, char)` calls and asserts the geometry as it
emits JSON. Then the row-length rule is enforced by construction and you iterate on layout instead
of on off-by-one errors.

---

## 9. Engine features that exist in `1.21/` but **not** in `lostbuildings/`

Check before you design around any of these. **[verified by absence — grepped the whole
`com.lostbuildings` tree]**

* **Door generation.** `1.21/.../gen/Doors.java` punches doorways at `x=0/15, z=6..9` and
  `z=0/15, x=6..9` on every storey. **There is no `Doors` class in the live engine**, and
  `allowDoors` is parsed and then never read. Your part data must contain its own entrance —
  nothing will cut one for you. (I designed `building3`'s entrance lanes around the 1.21 door
  positions. Harmless — it just means the lanes are clear — but it was wasted analysis.)
* **Corridor / subway connections** (`Corridors.generateCorridorConnections`).
* **City spheres** — `issphere` is hard-wired to false.
* **`belowpart` and `inbiome` conditions** — accepted by the codec, ignored by the matcher.
* **`worldstyles/`** — loaded, never consulted.
* **`parts2` overlays.** `1.21/.../LostCityTerrainFeature` collects them into a `part2Map` and
  draws them over each storey after the main pass. In the live engine `Building.getRandomPart2`
  exists but **nothing calls it** — the only occurrences of `getRandomPart2` in
  `lostbuildings/**` are its own two declarations at `engine/Building.java:153` and `:166`
  [verified]. So the shipped overlay layers are currently never placed. That is **10 buildings
  and 58 overlay refs** of dead data: `shopping01/10/11`, `shopping_open01/10/11` (6 each),
  `town00` (3), `town01` (7), `town10`, `town11` (6 each). Do not put content you care about in
  `parts2`.

Present in the live engine and worth using: damage/weathering (`damaged`), rubble scatter
(`rubble`), honest per-storey loot and mob condition resolution, building `role` biasing of loot.

---

## 10. Build and test

```bash
cd /home/user/LostCities/lostbuildings
JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 PATH=/opt/gradle-9.6.1/bin:$PATH \
  gradle build --no-daemon
```

Verified working (exit 0) as of this writing. Notes:

* `gradle build` runs the JUnit suite, which covers the engine (conditions, damage, city layout,
  street tiles) but **not** your JSON geometry. A green build tells you nothing about whether your
  part rows are 16 characters long. Run the §8 script too.
* `--no-daemon` matters in this environment.
* Add `-q` to suppress the proxy `JAVA_TOOL_OPTIONS` banner.
* `gradle test --no-daemon` alone is faster if you only touched data and want the sanity suite.

---

## 11. Seeing it in game — the `/lostcity` command

A green build tells you the JSON parses. It does not tell you the building looks right, and until
this command the only way to find out was to fly around until worldgen happened to roll a city.
`/lostcity` builds one where you are standing, at whatever size you ask for.

```
/lostcity here [<cells> [<style>]]
/lostcity build <structure> [<cells> [<style>]]
```

| argument | meaning |
|---|---|
| `<cells>` | city size in **cells**, the same unit as `city_size` in the structure JSON. One cell = one chunk = 16×16. Omit it and you get the size the structure ships with (`9` for `lost_city`, `5` for `charred_city`). |
| `<style>` | force a palette style — `standard`, `desert`, `snowy`, `swamp`, `standard_border`, … — instead of resolving one from the biome. Tab-completes from the styles the loaded datapack actually has, and an unknown name is an error rather than a silent fallback. |
| `<structure>` | any `Registries.STRUCTURE` entry whose type is `lostbuildings:lost_city` — so `lostbuildings:lost_city` or `lostbuildings:charred_city`. Anything else (a vanilla village, `lostbuildings:oil_rig`) is refused with a message. |

Permission level 2 — op / cheats, the same bar `/place` sits behind. The city is centred on the
**chunk the caller is standing in**, and the reply names that chunk, the block extent, and what came
out (`… 9x9 cells requested, 65 emitted (22 lots) - 18 buildings, 40 streets, 3 parks, 3 airfield
cells`). Every chunk the city spans is generated before anything is written, so you do not have to
fly the area in first.

**Size cap: 25 cells.** Past that the command refuses rather than clamping. 25×25 is 625 chunks, all
of which have to reach `FULL` synchronously on the server thread before a block may be placed — about
what one player at render distance 12 already holds. Expect the server to visibly hang for as long
as those chunks take; a 9-cell city on already-explored ground is instant.

### What it is, and the three ways it is not worldgen

It is not a re-implementation. It calls `Structure.generate` and `StructureStart.placeInChunk`
exactly as vanilla's `/place structure` does, so the pieces are assembled by `LostCityStructure`
itself — same layout, same seeded ground level, same style and bridge decisions. If the command and
worldgen ever disagree, the bug is in the shared path.

Three differences are unavoidable and worth knowing before you file a bug against what you see:

1. **The terrain-relief veto is off.** `max_height_diff` decides whether a site is *offered* a city;
   it changes nothing about the city that is then built. A command meaning "here" cannot honour a
   rule whose answer is "not here", so it is forced to `0` and a hillside gets a city.
2. **The chunks are already finished.** During worldgen a city is written into chunks whose feature
   step has not run; here it has. Every piece reads `Heightmap.Types.WORLD_SURFACE_WG`, and on a
   finished chunk that includes the trees — so foundations excavate a little more and snow can settle
   on a canopy. Vanilla's `/place` has the same discrepancy.
3. **No structure reference is recorded.** The result is blocks, not a start in the chunk's structure
   list, so `/locate structure lostbuildings:lost_city` will not find what you just built and the
   structure's mob-spawn overrides do not apply. Use `/locate` for real worldgen cities; that is what
   it is for, and it is why the command deliberately has no `locate` subcommand of its own.

Also note: **even `<cells>` values put the airfield one cell outside the square.** `Airport.forPlan`
places the runway at `origin ± outerRing`, and an even-sized grid is not symmetric about its origin
(`cells = 16` runs `-7..8`). Every shipped size is odd, so this only bites when you type an even
number by hand. Harmless — the airfield is still built — but it is why
`LostCityCommandTest.theCityIsTheSizeItWasAskedFor` measures the span with airfield cells excluded.

Source: `world/structure/LostCityStructure.derive(...)` builds a throwaway copy of the registered
structure carrying the derived `LostCityConfig` — a registry entry is shared by every chunk the
server will ever generate and is never mutated.

---

## 12. Suggested workflow for "upgrade building N"

1. Read `buildings/buildingN.json` and every `parts/buildingN_*.json`. Note the **slice count** and
   the existing shell geometry (wall/glass/recess pattern) — reuse it so the building keeps its
   silhouette, and so neighbouring buildings in a `multibuildings` layout still line up.
2. Pick a vertical-circulation cell and keep it identical in every part.
3. Decide the storey sandwich (§7.12) and stick to it.
4. Write a generator script (§8) with a shell template + `put()`.
5. Author: `_cellar`, `_ground`, several numbered floors, `_top`. Give the numbered floors
   `"top": false, "ground": false, "cellar": false` so they never leak into the special storeys.
6. Put every new char in the building's local `palette`.
7. Run the verification script; run gradle.
8. Never rename or edit a shared part; add your own.
