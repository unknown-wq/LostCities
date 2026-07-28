# PHASE3-STATUS — Structure-миграция + топ-10 улучшений

Живой статус фазы 3. Закон: `PHASE3-PLAN.md`. Исходник для порта — `1.21/` (read-only).

## Toolchain (волна 0 — ГОТОВО, 2026-07-28)

- JDK 25: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64` (`apt-get install openjdk-25-jdk-headless`;
  индекс apt в свежем контейнере устаревший — сначала `apt-get update`, иначе 404 на .deb).
- Gradle **9.6.1**: `/opt/gradle-9.6.1/bin/gradle` — из вендоренного дистрибутива этого репо
  (`./gradle-dist/install.sh`, требует `unrar` + `unzip`). **Никогда `./gradlew`** — wrapper
  тянет дистрибутив с services.gradle.org, что прокси режет (403).
- Сборка (ОДНА за раз в этом чекауте, никогда параллельно):
  ```sh
  cd /home/user/LostCities/lostbuildings && JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 \
    /opt/gradle-9.6.1/bin/gradle build --no-daemon 2>&1 | tee /tmp/errors.txt
  ```
- Декомпилированные исходники 26.2: `/opt/mc-src/` (через `genSources`) — **только grep**,
  не перегенерировать.
- **Базовый билд до правок: GREEN** (`gradle build`, 1m22s, юнит-тесты прошли) — отправная точка.

## Прогресс

- [x] Волна 0 — окружение, базовый зелёный билд, этот файл
- [x] Волна 1 — агент E: миграция `Feature` → `Structure`, сетка кварталов, датапак-конфиг
- [ ] Волна 2 — агент F: движок (разрушения, условия лута/мобов, подвалы, роли, кодеки CityStyle)
- [ ] Волна 2 — агент G: городская ткань (уличные тайлы, парки, мульти-здания, фонари, мосты,
      scattered, биомные стили)
- [ ] Волна 3 — агент E: интеграция, датаген, смоук-тест сервера, документация

## Отклонения от контрактов

_(агенты дописывают: любая сигнатура из `PHASE3-PLAN.md` §3, которую пришлось изменить)_

### Волна 1 (агент E)

1. **`LostCityStructure` лежит в `world/structure/`, но реестровые типы — в `registry/`.**
   §3 говорит только «новые классы структуры живут в `world/structure/**`». `StructureType` и
   `StructurePieceType` — это записи в `BuiltInRegistries`, а не датапак-объекты, поэтому они
   поехали в `registry/ModStructureTypes.java` и `registry/ModStructurePieceTypes.java` рядом с
   тем, что там уже было. `world/structure/**` содержит саму структуру, конфиг, раскладку,
   датаген-бутстрапы и теги. Для F и G это ничего не меняет.
2. **`CityPiece` не хранит `ChunkPos`/`Transform`, а выводит их из `boundingBox`.**
   Контракт §3 задаёт только сигнатуру `postProcess`, она соблюдена дословно. Ячейка куска —
   это ровно один чанк, поэтому `cellChunkX()/cellChunkZ()/cellMinX()/cellMinZ()` считаются из
   бокса, и в NBT нет дублирующих полей. Наследникам (`ParkPiece`, `BridgePiece`,
   `ScatteredPiece` — волна 2) достаточно вызвать `cellBox(...)` в конструкторе.
3. **`CityLayout` возвращает `Plan` (запись), а не голый `List`.**
   §3 говорит «список кусков». `Plan` — это `originChunkX/originChunkZ + List<Cell>`; координаты
   города нужны и `/locate`, и волне 2 (доминанта в центре, центр города). `Cell` несёт ровно
   поля из контракта (тип, координата чанка, поворот, этажность, роль) плюс `buildingIndex`
   и `neighbourMask` — первое потому, что чистая функция не может знать имён зданий (они в
   конфиге), второе потому, что §6.2 требует маску занятых соседей для уличных тайлов.
4. **Поворот в `CityLayout` — `int quarterTurns`, а не `Transform`.**
   `com.lostbuildings.engine.Transform` импортирует `net.minecraft...Rotation`, а `CityLayout`
   обязан быть Minecraft-free (юнит-тесты в CI без Minecraft). Конвертация — одна строка в
   `BuildingPiece`.
5. **Целевые биомы Biolith остались Java-константами.**
   `PHASE3-PLAN.md` §4.5 требует «целевые биомы Biolith → в кодек». `BiomePlacement.replaceOverworld`
   вызывается на этапе инициализации мода, когда датапаки ещё не загружены, поэтому датапак-путь
   туда физически не ведёт. Вместо этого датапак-конфигурируемым сделано то, что реально
   определяет, где стоят города: **`biomes` структуры — это тег
   `#lostbuildings:has_structure/lost_city`** (генерируется датагеном, правится одним файлом).
   `BiolithGeneration.LOST_CITY_CHANCE` и список заменяемых ванильных биомов — по-прежнему
   константы; это перенесено в Фазу 4 вместе с остальной Biolith-настройкой.
6. **Старый `Feature`-путь удалён в том же коммите, что и миграция, а не после отдельного
   зелёного прогона.** §4.4 просит удалять только после зелёного. `GroupBuildingPlacement`
   вызывает `Streets.connect`, а `Streets` пришлось переписать под ячейку (маршрутов между
   центрами групп больше нет), так что две реализации физически не компилируются рядом.
   Откат — `git checkout c1a4cc2 -- lostbuildings/src`, отправная точка зафиксирована зелёной
   в волне 0. Прогон после удаления зелёный (см. «Верификация»).

## Отключённый контент (лестница упрощений §9)

_(агенты дописывают: что не поддалось после двух честных попыток и чем заменено)_

### Волна 1 (агент E)

* **Уличные тайлы не делались** — это §6.2, волна G. `StreetPiece` кладёт ту же ручную мостовую,
  что клал `Streets` до миграции, только по ячейке, а не по L-маршруту. Материал и ширина —
  из конфига (`street_block`, `street_width`); `neighbourMask` уже посчитан и лежит в NBT куска,
  так что G меняет один метод.
* **Улицы связаны по диагонали, а не ортогонально.** На шахматной сетке уличные ячейки касаются
  друг друга только углами. При `street_width = 16` (дефолт) ячейка мостится целиком, и сеть
  получается угол-в-угол непрерывной; при меньшей ширине выходят отдельные крестовины у каждого
  дома. Настоящая связная сетка улиц — это уличные тайлы волны 2, здесь она сознательно не
  строилась (волна 1 = «то же самое, другой механизм»).
* **`terrain_adaptation` оставлен `none`.** Ванильный beard/бордюр мог бы заменить экскавацию
  `Foundation`, но это изменило бы то, как здание садится в склон — а волне 1 запрещено менять
  содержимое. Знание записано: поле есть в JSON структуры, включается датапаком одной строкой.
* **Один стиль на город.** Стиль сэмплится один раз в центре города (`BiomeSource.getNoiseBiome`
  + существующая таблица `StyleSelector.styleFor`). Раньше он сэмплился один раз на группу —
  то же самое, только область больше. Биомная стилизация по кускам — §6.8, волна G.
* **Групповой уровень земли — минимум по углам всех «зданиевых» ячеек.** То же правило, что было
  у `GroupBuildingPlacement`, но теперь по площади 5×5 чанков. На сильно пересечённой местности
  город врезается в склон глубже, чем раньше; экскавация по-прежнему ограничена
  `MAX_EXCAVATION = 64`. Умнее (медиана / террасы) — не в волне 1.

## Верификация

| Задача | Результат |
|---|---|
| `gradle build` (базовый, до правок) | **GREEN** |
| `gradle build` (волна 1) | **GREEN** — 12 s, юнит-тесты прошли (`CityLayoutTest` 14 тестов, `WorldGenBoundsTest`, `LostBuildingsSanityTest`) |
| `gradle runDatagen` (волна 1) | **GREEN** — 4 файла: `worldgen/biome/lost_city`, `worldgen/structure/lost_city`, `worldgen/structure_set/lost_cities`, `tags/worldgen/biome/has_structure/lost_city`; вывод закоммичен |
| `gradle runServer` (смоук, волна 1) | **GREEN** — `Done (6.391s)!`, **0 строк `/ERROR]`**, ни одного `unsafe terrain read/write` |
| `/locate structure lostbuildings:lost_city` | **РАБОТАЕТ** — `The nearest lostbuildings:lost_city is at [48, ~, 832] (833 blocks away)` |
| Город реально строится | **ДА** — `forceload` 81 чанка вокруг находки: 66 чанков ссылаются на структуру, в NBT старта 10 `lostbuildings:building` + 12 `lostbuildings:street`. Чанк (3,52) — центральное здание (сундук, спавнер, витражи, лестницы), его ортогональные соседи (2,52)/(3,51)/(4,52) — ровно мостовая `stone_bricks`. Шахматка соблюдена. |
| Пустые срабатывания | **НЕТ** — центральная ячейка безусловна (`CityLayoutTest#everyCityHasAtLeastOneBuilding`); у `CellLattice` их было ~33 % |

### Волна 1 — что мигрировало

| Было (`Feature`) | Стало (`Structure`) |
|---|---|
| `LostBuildingFeature` + `ModConfiguredFeatures`/`ModPlacedFeatures` + `RarityFilter(6)` | `LostCityStructure` + `ModStructures`/`ModStructureSets` (`spacing=10`, `separation=6`) |
| `CellLattice` (5 офсетов, ~1 ячейка на срабатывание) | `CityLayout` — чистая шахматная сетка 5×5 чанков, 13 «зданиевых» ячеек, ~10 застроенных |
| `GroupBuildingPlacement.place()` | `BuildingPiece.postProcess()` (та же логика: `Foundation` → `BuildingEngine`) |
| `Streets.connect()` (L-маршруты между центрами) | `StreetPiece.postProcess()` → `Streets.paveCell()` |
| окно записи 48×48 (`WorldGenBounds.WRITE_RADIUS`) | `chunkBox` = ровно свой чанк; кусок = ячейка = чанк |
| константы в Java | кодек структуры: `buildings`, `min_floors`, `max_floors`, `foundation`, `city_size`, `density`, `street_block`, `street_width`, `biomes` (тег) |
