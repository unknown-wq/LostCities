# План: дома Lost Cities → отдельный Fabric 26.2 мод, генерация в биоме (3 параллельных агента)

## Контекст

Взять **дома** из **Lost Cities** (Forge 1.20) и перенести в **новый самостоятельный Fabric 26.2 мод**
так, чтобы они генерировались **только в одном биоме** (через Biolith, как в desolation), а не по всему миру.
Всё, кроме зданий (города, улицы, шоссе, ж/д, сферы, разрушения, команды, GUI, конфиг) — выкидываем.
Движок Lost Cities **портируем** (адаптируем существующий код под 26.2 / Mojang mappings), а не пишем начисто.
`desolation` (`/home/user/desolation`, Fabric 26.2, Java 25, Biolith) — **образец структуры**.
`LostCities` (`/home/user/LostCities`) — источник кода движка и данных.

## Утверждённые решения

| # | Пункт | Решение |
|---|---|---|
| 1 | Целевой мод | Новый отдельный **Fabric 26.2** мод; движок **портируем** (не rewrite с нуля) |
| 2 | Биом | **Свой биом** с домами, вставляем в ванильный оверворлд **через Biolith** (как desolation) |
| 3 | Плотность | **Небольшие группы** домов (2–5 рядом) + **фундамент** под рельеф |
| 4 | Дома | **Простые односегментные**: `building1`–`building8` |
| 5 | Состояние | Целые/чистые (движок разрушений не портируем) |

Дефолты (поправимы): modid `lostbuildings`, пакет `com.lostbuildings`; биом визуально нейтральный
(равнинная основа), Biolith `replaceOverworld` для plains/forest-подобных + опц. sub-биом.

---

## Разбор Lost Cities (что переносим)

**Дом = данные (JSON)**, `data/lostcities/lostcities/`:
`buildings/`(рецепт: части по этажам, filler/rubble, min/max этажей) → `parts/`(геометрия: `xsize/zsize`
+ `slices`, символ в `(z,x)`=блок) → `palettes/`(символ→блок; спец: `blocks`/`variant`/`frompalette`/
`loot`/`mob`/`torch`/`tag`) → `variants/`(взвеш. группы блоков). `styles/standard.json` — какие палитры мёржить.
Вариативность (крыши, материалы, высота) уже в данных — переносится вместе с JSON.

**Движок (порт, ~2.3–3k строк):** `ChunkDriver`→заменяем на запись через `WorldGenLevel.setBlock`;
`BuildingPart`/`Palette`/`CompiledPalette`/`Building`/`Transform`(без railway-таблицы); RE-кодеки на
**Mojang Codec** (переносятся почти без правок); извлечённый `generatePart`+`generateBuilding`+
`handleLoot/Spawner/BlockEntity/Todo`. Forge-точки на замену: `ForgeRegistries.*`→`BuiltInRegistries.*`;
variant через `ServerLifecycleHooks`→резолв из level; загрузка ассетов Forge-datapack-registry→**свой
`AssetLoader`** (ResourceManager+Codec); off-thread todo→**inline** синхронно.

**Обвязка (образец desolation):** Feature→`BuiltInRegistries.FEATURE`; ConfiguredFeature→`FeatureUtils.register`;
PlacedFeature→`PlacementUtils.register`(rarity+count+InSquare+HEIGHTMAP+BiomeFilter); фича в биom→
`generationSettings.addFeature(step,key)`; биом в мир→Biolith `BiomePlacement.replaceOverworld/addSubOverworld`;
датаген→`RegistrySetBuilder`.

---

## Зафиксированные контракты (чтобы 3 агента шли параллельно без блокировок)

Каждый агент правит **свой набор файлов** и кодит против этих сигнатур (стабы для чужих типов —
временно, финальная компиляция на этапе интеграции). Пакет-корень `com.lostbuildings`.

- **Данные:** `src/main/resources/data/lostbuildings/lostcities/{buildings,parts,palettes,variants,conditions,styles}/`
- **Движок (владелец — Агент B):**
  - `engine/AssetLoader.java` → `static Assets load(ResourceManager rm)` (Assets = карты name→Building/Part/Palette/Variant/Condition).
  - `engine/BuildingEngine.java` →
    `CompiledPalette buildPalette(Assets a, RandomSource rand, Style style)` и
    `void generateBuilding(WorldGenLevel level, BlockPos origin, RandomSource rand, Transform t, Building b, CompiledPalette pal, PlaceSettings s)`.
  - `PlaceSettings` (record): `int minFloors,maxFloors; boolean lighting,spawners,loot; int waterLevel`.
- **Feature/Config (владелец регистрации — Агент A; логика place — Агент C):**
  - `world/feature/LostBuildingConfig.java` (record + `Codec`): `List<String> buildings; int minFloors,maxFloors; boolean foundation; int groupMin,groupMax,spacing`.
  - `world/feature/LostBuildingFeature.java` — `Feature<LostBuildingConfig>`, в `place()` **делегирует** в интерфейс
    `world/feature/BuildingPlacement` → `boolean place(FeaturePlaceContext<LostBuildingConfig> ctx, Assets assets, BuildingEngine engine)`.
  - Агент A даёт **стаб** `BuildingPlacement` (ставит 1 тест-блок); Агент C заменяет реальной реализацией
    `GroupBuildingPlacement` при интеграции. Разные файлы → без конфликтов.
- **Точка доступа к ассетам:** `LostBuildings.ASSETS` (загружается на старте/`ServerLifecycleEvents`),
  инжектится в фичу.

---

## Агент A — Каркас, worldgen-обвязка, биом + Biolith, датаген

**Файлы (свои):** `build.gradle`, `gradle.properties`, `settings.gradle`, gradle-wrapper,
`src/main/resources/fabric.mod.json`, `lostbuildings.accesswidener`, `LostBuildings.java` (main),
`registry/ModFeatures.java`, `world/feature/{LostBuildingConfig, LostBuildingFeature, BuildingPlacement(интерфейс+стаб), ModConfiguredFeatures, ModPlacedFeatures}.java`,
`world/biome/{ModBiomes, LostCityBiomeCreator}.java`, `world/gen/BiolithGeneration.java`,
`data/{ModDatagen, ModDynamicRegistryProvider}.java`.

**Задачи:**
1. Каркас сборки — скопировать схему `desolation/build.gradle`+`gradle.properties`, **убрать** geckolib/cloth/modmenu,
   **оставить** fabric-api + `biolith`; Java 25, Loom 1.17, mc 26.2. Зелёный `./gradlew build`.
2. `fabric.mod.json`: entrypoints `main`, `fabric-datagen`; depends fabric-api/biolith/mc≥26.2/java≥25.
3. Регистрация Feature-типа в `BuiltInRegistries.FEATURE` (образец `DesolationFeatures.java`).
4. `LostBuildingConfig` (record+Codec) и `LostBuildingFeature` (делегирует в `BuildingPlacement`); стаб-плейсмент.
5. Configured/Placed features (образцы `Desolation{Configured,Placed}Features.java`): плейсмент под
   «небольшие группы» — `RarityFilter.onAverageOnceEvery(N)` + `InSquarePlacement.spread()` +
   `PlacementUtils.HEIGHTMAP` + `BiomeFilter.biome()` (кластеризацию внутри пятна делает Агент C в place()).
6. Свой биом `lost_city` (образец `BiomeCreator.java`/`DesolationBiomes.java`), нейтральная основа,
   `generationSettings.addFeature(GenerationStep.Decoration.SURFACE_STRUCTURES, PLACED_KEY)`.
7. Biolith-вставка в оверворлд (образец `DesolationBiolithGeneration.java`): `BiomePlacement.replaceOverworld(...)`
   для нескольких ванильных биомов + опц. `addSubOverworld`; init из `onInitialize`.
8. Датаген динам. реестров (`RegistrySetBuilder`: CONFIGURED_FEATURE/PLACED_FEATURE/BIOME) — образцы
   `DesolationDynamicRegistryProvider.java`/`DesolationDatagen.java`. `./gradlew runDatagen` зелёный.

**Результат:** компилящийся мод-скелет; стаб-фича привязана к своему биому, биом вставлен через Biolith;
датаген генерит JSON без ошибок.

## Агент B — Порт движка Lost Cities под 26.2

**Файлы (свои):** `engine/codec/{BuildingRE, PaletteRE, BuildingPartRE, PaletteEntry, PartRef, BlockEntry, PartMeta, DataTools, ConditionTest}.java`,
`engine/{BuildingPart, IBuildingPart, Palette, CompiledPalette, Building, Transform}.java`,
`engine/{AssetLoader, BuildingEngine, Assets, Style, PlaceSettings, BlockStates}.java`, `engine/util/Tools.java`.

**Задачи:**
1. Перенести RE/`data`-классы из `worldgen/lost/regassets/**` — Mojang `Codec`, почти без правок.
2. Перенести `BuildingPart/Palette/CompiledPalette/Building/Transform` из `worldgen/lost/cityassets/**`
   (+`Transform` из `worldgen/lost/`), из `Transform` убрать ~135 строк `transform(RailShape)`.
3. Заменить Forge-точки: `ForgeRegistries.BLOCKS`→`BuiltInRegistries.BLOCK` (в `Tools.stringToState`;
   проверить `BlockStateParser` на 26.2), `ForgeRegistries.BLOCK_ENTITY_TYPES`→`BuiltInRegistries.BLOCK_ENTITY_TYPE`,
   variant-ветка `Palette` без `ServerLifecycleHooks` (резолв из переданного level/Assets).
4. `AssetLoader.load(ResourceManager)` — прочитать все JSON из `data/lostbuildings/lostcities/…` через
   `Codec.parse`, вернуть `Assets` (карты по имени). Тег/условия (`conditions/*`) — в карты для loot/mob.
5. `BuildingEngine`:
   - `buildPalette(...)` — мёрж палитр по `styles/standard.json` (по одной из каждой группы) в `CompiledPalette`.
   - `generateBuilding(...)` — извлечь `generateBuilding`(2129–2192)+`generatePart`(1723–1814) из
     `LostCityTerrainFeature`, писать через `WorldGenLevel.setBlock(pos,state,flag)`; убрать
     damage/debris/corridors/город; cellars опционально; **inline** `handleLoot`(setLootTable)/
     `handleSpawner`(spawner-NBT)/`handleBlockEntity`(`setBlockEntityNbt`)/torch(lighting) через vanilla API.
   - Мини-`correct` для соединяемых блоков (лестницы/заборы/стены/`STRUCTURE_VOID`) по одному блоку
     (адаптировать `ChunkDriver.correct`).

**Результат:** самодостаточный пакет `engine/**`, компилится и вызывается без A/C; API по контракту.
Проверка: юнит-тест или временный main, грузящий Assets и печатающий кол-во buildings/parts/palettes.

## Агент C — Данные + размещение групп/фундамент + Feature.place

**Файлы (свои):** ресурсы `data/lostbuildings/lostcities/**` (копия JSON),
`world/feature/GroupBuildingPlacement.java` (реализация `BuildingPlacement`), `world/feature/Foundation.java`.

**Задачи:**
1. Скопировать **1:1** минимальный набор данных (из инвентаря): `buildings/building1..8`;
   `parts/`: `building1_1..9`, `building2_1..4`, `building3_1..4`, `building4_1..4`, `building5_1..4`,
   `top1x1_1..5`, `top4_1..3` (+ части для 6/7/8); `palettes/`: `common`,`default`,все `bricks_*`,`glass_*`,
   `glass_side_variant_*`; вся папка `variants/`(12); `conditions/`: `chestloot`,`easymobs`,`hardmobs`;
   `styles/standard.json`. (Проще — скопировать все папки целиком, лишнее не грузить.)
2. `GroupBuildingPlacement implements BuildingPlacement`:
   - выбрать размер группы `groupMin..groupMax`, разложить 2–5 домов вокруг origin с шагом `spacing`
     (сетка/кольцо, лёгкий джиттер по rand);
   - для каждого дома: высота земли `level.getHeightmapPos(WORLD_SURFACE_WG, pos)`, случайный поворот
     `Transform.randomRotation()`, выбрать building из `config.buildings`, собрать палитру
     `engine.buildPalette(...)`, вызвать `engine.generateBuilding(...)`;
   - **фундамент** (`Foundation`): столбы вниз до земли (аналог `fillToGround`) + расчистка объёма над домом;
   - анти-наложение домов внутри группы (bbox-проверки).
3. Согласовать спец-символы (loot/mob/torch/tag) — убедиться, что `PlaceSettings` включает нужные флаги.

**Результат:** реальный плейсмент групп с фундаментом + данные в ресурсах; заменяет стаб Агента A.

---

## Интеграция (после 3 агентов; делаю я)

1. Вставить `GroupBuildingPlacement`(C) вместо стаба(A); прокинуть `ASSETS`(B) в фичу.
2. `./gradlew build` — устранить нестыковки сигнатур по контрактам.
3. `./gradlew runDatagen` — сгенерить реестры.

## Что выкидываем

Города/`City`/`CityStyle`/`WorldStyle`, улицы, `Highway`, `Railway`, монорельсы, сферы, scattered,
multibuildings, predefined, `DamageArea`/взрывы/debris, `Corridors`, `BuildingInfo`(→тонкий шим),
`GlobalTodo`/off-thread, `EditModeData`, команды, GUI, playerdata, сеть, `LostCityProfile`.

## Риски 26.2

- Запись блоков — решено `setBlock` (не section-internals `ChunkDriver`).
- Границы чанков: дом ≤16×16 в пятне, группы с запасом; возможен cascading-warning — минимизируем,
  фича на этапе декорации.
- Сигнатуры block-entity/loot/spawner (`setBlockEntityNbt`, `RandomizableContainerBlockEntity.setLootTable`,
  `SpawnData.CODEC`) и `BlockStateParser` — сверить по desolation/mcsrc.dev на 26.2 (не по памяти).
- Точные сигнатуры `BootstrapContext`/`FeatureUtils`/`PlacementUtils`/Biolith — брать из рабочего кода desolation.

## Verification

- `./gradlew build` зелёный (у каждого агента — свой пакет компилится; финал — общий).
- `./gradlew runDatagen` без ошибок.
- `./gradlew runClient`: найти биом `lost_city` (`/locate biome lostbuildings:lost_city`), проверить:
  дома группами, на земле с фундаментом (не висят/не утоплены); вариативность (крыши/материалы/высота);
  работают лут-сундуки/спавнеры/факелы/block-entity; в других биомах домов нет; нет спама cascading.
- Сверка «1:1»: силуэт/материалы building1 совпадают с оригиналом Lost Cities.

## Открытые детали (не блокеры, дефолты проставлены)

- modid/пакет (`lostbuildings`/`com.lostbuildings`); визуал биома (нейтральная основа) и какие ванильные
  биомы заменять Biolith'ом; частота (rarity) и размеры групп; лицензия нового мода (оба исходника — MIT).
