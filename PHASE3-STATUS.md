# PHASE3-STATUS — Structure-миграция + топ-10 улучшений

Итоговый статус фазы 3. Закон: `PHASE3-PLAN.md`, `PORT-PLAN-26.2.md` §6–§9.
Исходник для порта — `1.21/` (read-only). Ветка `claude/mod-top-10-improvements-bshn5u`.

Документ сведён волной 3 из трёх источников: отчёта волны 1 (агент E),
`PHASE3-STATUS-F.md` (движок) и `PHASE3-STATUS-G.md` (городская ткань). Оба
пофайловых отчёта удалены — здесь всё, что в них было.

## Toolchain

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
- **Консоль dev-сервера не читает stdin** (loom не пробрасывает его через JavaExec). Смоук-тест
  волны 3 гоняется через **RCON**: `enable-rcon=true`, `rcon.port=25575` в `run/server.properties`,
  клиент — 40 строк на Python. Не воевать с консолью.

## Прогресс — всё закрыто

- [x] Волна 0 — окружение, базовый зелёный билд, статус-файл
- [x] Волна 1 — агент E: миграция `Feature` → `Structure`, сетка кварталов, датапак-конфиг
- [x] Волна 2 — агент F: движок (разрушения, условия лута/мобов, подвалы, роли, кодеки CityStyle)
- [x] Волна 2 — агент G: городская ткань (уличные тайлы, парки, мульти-здания, фонари, мосты,
      scattered, биомные стили)
- [x] Волна 3 — агент E: интеграция, сведение дублей, датаген, смоук-тест сервера, документация

| Пункт плана | Источник | Статус |
|---|---|---|
| Датапак-конфигурируемость | IMPROVEMENTS #10 | **ГОТОВО** — кодек структуры + `citystyles`/`multibuildings` |
| Поиск города в выживании (`/locate`) | IMPROVEMENTS #8 | **ГОТОВО** — три структуры находятся |
| Плотность / силуэт квартала | IMPROVEMENTS #6 | **ГОТОВО** — сетка E + этажность падает к краю (G) |
| Разрушения и обломки | IMPROVEMENTS #1 | **ГОТОВО** |
| Лут и мобы по условиям | IMPROVEMENTS #2 | **ГОТОВО** |
| Подвалы | IMPROVEMENTS #3 | **ГОТОВО** |
| Роли зданий | IMPROVEMENTS #5 | **ГОТОВО** |
| Взрывы/кратеры | PORT #5 | **ГОТОВО** (в пределах ячейки, см. §9.1) |
| Уличные фонари и декор | IMPROVEMENTS #4 | **ГОТОВО** |
| Редкий «центр города» | IMPROVEMENTS #7 | **ГОТОВО** — 9 из 31 замеренного города |
| Биомная стилизация | IMPROVEMENTS #9 | **ГОТОВО** |
| Мульти-здания 2×2 | PORT #1 | **ГОТОВО** |
| Scattered-структуры | PORT #2 | **ГОТОВО** — cabin / radiotower / oilrig |
| Уличные тайлы | PORT #3 | **ГОТОВО** |
| Парки и фонтаны | PORT #4 | **ГОТОВО** |
| Мосты | PORT #6 | **ГОТОВО** |
| CityStyle / WorldStyle | PORT #9 | **ЧАСТИЧНО** — citystyle решает палитру, worldstyle не читается (см. §9) |

Отложено в фазу 4 осознанно: метро (PORT #7), шоссе (PORT #8), городские сферы (PORT #10),
карта исследователя у картографа, Biolith-настройки в датапак.

---

## Верификация (волна 3, реальные прогоны)

| Задача | Результат |
|---|---|
| `gradle build` (базовый, до фазы 3) | **GREEN** |
| `gradle build` (волна 1) | **GREEN** — 12 s |
| `gradle build` (волна 3, после сведения) | **GREEN** — 12–13 s, **89/89 юнит-тестов**, 0 падений |
| `gradle runDatagen` (волна 3) | **GREEN** — 9 файлов, 6 переписано; вывод закоммичен |
| `gradle runServer` (смоук, волна 3) | **GREEN** — `Done (5.662s)!`, **0 строк `/ERROR]`** за весь 23-минутный прогон, **0** `unsafe terrain read/write`, 0 предупреждений мода |
| Загрузка ассетов | 12 variants, 29 palettes, 186 parts, 34 buildings, 3 conditions, 6 styles, **7 citystyles**, 2 worldstyles, **13 multibuildings**, 0 файлов с ошибками |
| `/locate structure lostbuildings:lost_city` | **РАБОТАЕТ** — `[720, ~, -656]` (974 блока) |
| `/locate structure lostbuildings:scattered` | **РАБОТАЕТ** — `[-128, ~, 192]` (230 блоков) |
| `/locate structure lostbuildings:oil_rig` | **РАБОТАЕТ** — `[-864, ~, 992]` (1315 блоков) |

Юнит-тесты (89): `CityLayoutTest` 23, `EngineDamageAreaTest` 14, `EngineConditionTest` 13,
`EngineBuildingRoleTest` 13, `WorldGenBoundsTest` 10, `StreetDecorTest` 9, `StreetTilesTest` 6,
`LostBuildingsSanityTest` 1.

### Что реально стоит в мире

Сид `1337`. Метод: `/locate` из 31 точки, `forceload` найденных чанков, `save-all flush`,
затем чтение сохранённых region-файлов и NBT стартов структур напрямую (свой парсер Anvil/NBT —
консоль не отвечает, но диск не врёт).

| Проверка | Результат |
|---|---|
| Город строится | **ДА** — старт в чанке (45, −41): **77 кусков** = 56 street + 17 building + 4 park |
| 31 город замерен | 13–22 здания, 28–56 улиц, 2–9 парков, 0–28 мостов на город |
| **Уличные тайлы** | **ДА** — уличная ячейка на y=47 это 128 `stone_bricks` + 125 `smooth_stone_slab`, то есть рисунок части `street_*`, а не сплошная мостовая волны 1. Маски соседей в NBT: 12× «прямая NS», 12× «прямая EW», 16× перекрёсток (`street_all`), 16× тупик (`street_end`) |
| **Парк** | **ДА** — `park_plants`, `park_trees`, `park_fountain2`; 14 906 `grass_block` + 6 415 `oak_leaves` на город |
| **Мульти-здание 2×2** | **ДА** — **9 из 31** города (29 % при `downtown_chance = 0.3`); встречены все пять: `center00..11`, `library00..11`, `shopping00..11`, `shopping_open00..11`, `town00..11` |
| **Повреждения / выветривание** | **ДА** — на город: 1 810 `cracked_stone_bricks`, 369 `mossy_stone_bricks`, 122 `mossy_cobblestone` |
| **Подвал** | **ДА** — здание с `GroundY=48`: пол на y=42, комната 43–45 (стекло, решётки), перекрытие 46–47, первый этаж с 48. Ровно один этаж вниз, как `Cellars=1` в NBT |
| Фонари и декор | **ДА** — 167 `lantern` + столбы `iron_bars` вдоль обочин |
| Лут и роли | **ДА** — 29 сундуков, 77 спавнеров, 69 `bookshelf` (роль `LIBRARY`); в NBT города `Kind`: 12 RESIDENTIAL, 3 SHOP, 2 LIBRARY |
| Мосты | **ДА** — куски `lostbuildings:bridge` в 15 из 31 города (до 28 штук там, где город сел на озеро) |
| **Scattered** | **ДА** — 25 находок: `cabin` и `radiotower`, оба реально строятся (кабина: терракота 71–79 поверх грунта на 70) |
| **`oil_rig` на воде, `foundation` выключен** | **ДА** — вода до y=62, палуба с y=64–65, три яруса до y=79, перила `iron_bars`, рельсы, `redstone_torch` на 82. В NBT: `Foundation=0`, `Cellars=0`, 4 квадранта `oilrig00..11`. **Не тонет и не заливается** |
| Пустые срабатывания | **НЕТ** — центральная ячейка безусловна (`CityLayoutTest#everyCityHasAtLeastOneBuilding`) |
| Детерминизм | тесты `CityLayoutTest` / `EngineDamageAreaTest` не менялись и зелёные |

---

## Что свела волна 3

### 1. Мульти-здания: источник истины — `multibuildings/*.json`

F портировал `MultiBuildingRE` и грузил таблицы кварталов в `Assets`; G шёл мимо и собирал имя
квартала по соглашению «префикс + x + z». Оба пути давали одинаковый ответ на шипованных данных,
но датапаком правился только один.

**Решение:** таблица из `Assets` — единственный источник. `LostCityConfig.quadrantOf(name, quadrant)`
читает `multibuildings/<name>.json`; соглашение об именах **удалено**, фолбэка на него нет
(отсутствующая таблица = «ничего не ставим» + одно предупреждение в лог). Конфиг структуры
по-прежнему решает, **какие** мультиздания разрешены, — IMPROVEMENTS #10 цел.
`ScatteredStructure` (нефтевышка) ходит через тот же метод.

Побочный эффект, который и доказывает, что путь живой: дефолт сменился с префикса `town` на имя
файла `townhall`, и в мире квадранты называются `town00..town11` — то есть имена пришли **из
таблицы**, а старое соглашение дало бы несуществующие `townhall00`.

### 2. Городские стили: источник истины — `citystyles/*.json`

G схлопнул «биом → citystyle → style» в «биом → style», из-за чего 5 скопированных
`citystyles/*.json` грузились и никем не читались.

**Решение:** `StyleSelector.cityStyleFor(biome)` отвечает **именем citystyle**, а палитра берётся
из его поля `style` (`StyleSelector.styleOfCityStyle`, через `Assets`). Дописаны два файла на три
строки — `citystyle_snowy.json` и `citystyle_swamp.json` (стилей `snowy`/`swamp` в оригинале не
было). Теперь датапак перекрашивает все пустынные города одной строкой.

Остальное содержимое citystyle (`streetblocks`, `parkblocks`, `selectors`) сознательно **не**
читается: список зданий, материал улиц и парковые части — это поля кодека структуры
(IMPROVEMENTS #10), и citystyle был бы вторым конкурирующим источником той же настройки. Это
записано в javadoc `StyleSelector`, чтобы следующий читатель не искал.

### 3. Scattered: источник истины — конфиг структуры

`lostcities/scattered/` (3 файла) никем не парсился — в `AssetLoader` такой категории нет, а
`ScatteredSettingsRE` это вообще другая схема (таблица из worldstyle). Их содержимое
(`terrainheight`, `heightoffset`) уже выражено в `ScatteredConfig.land()/ocean()` и в
сгенерированных `worldgen/structure/{scattered,oil_rig}.json`.

**Решение:** каталог `lostcities/scattered/` **удалён**. Нечитаемый JSON в датапак-каталоге —
ровно та ловушка, которую волна 3 должна была убрать.

### 4. `worldstyles/` — явно понижены в статусе, не удалены

Грузятся, но не читаются ничем. Всё, что в них есть, либо выражено иначе (частота городов —
ванильный `StructureSet`, таблица scattered — отдельные структуры), либо принадлежит фазе 4
(`multisettings`, `citybiomemultipliers`). Записано в javadoc `AssetLoader` вместе с запретом
«не подключать, не решив сперва, что это лучше датапак-пути».

### 5. Предел подвалов: 2, и теперь он один

Движок зажимал `MAX_CELLARS = 2`, кодек конфига принимал до 4 — значения 3–4 **молча**
превращались в 2. Молчаливое усечение — ловушка, и убрана она в пользу движка: `PHASE3-PLAN` §5.3
говорит «1–2 этажа вниз», а подвал в этом порту — обычный этажный срез под землёй (§9.4), так что
четыре таких подряд ничего бы не дали, кроме экскавации.

Кодек теперь `Codec.intRange(0, PlaceSettings.MAX_CELLARS)` — **граница берётся из константы
движка**, второго числа в коде не осталось. Датапак с `"cellars": 4` теперь получает внятную
ошибку при загрузке вместо тихо другого мира.

### 6. Датаген: ванильные биом-теги пришлось добавлять как optional

`gradle runDatagen` падал:
`Couldn't define tag lostbuildings:has_structure/scattered as it is missing following references:
#minecraft:is_forest,…`. Причина не в данных: `TagsProvider` сверяет ссылки только с тегами
**этого** провайдера плюс родительского, а у мода ванильного родителя нет. `addTag` заменён на
`addOptionalTag` (в JSON `"required": false`). Все перечисленные теги — ванильные и всегда
загружены, поэтому итоговый набор биомов идентичен.

---

## Отклонения от контрактов

### Волна 1 (агент E)

1. **`LostCityStructure` лежит в `world/structure/`, но реестровые типы — в `registry/`.**
   `StructureType` и `StructurePieceType` — это записи в `BuiltInRegistries`, а не датапак-объекты,
   поэтому они в `registry/ModStructureTypes.java` и `registry/ModStructurePieceTypes.java`.
2. **`CityPiece` не хранит `ChunkPos`/`Transform`, а выводит их из `boundingBox`.** Сигнатура
   `postProcess` соблюдена дословно; ячейка куска — ровно один чанк, поэтому
   `cellChunkX()/cellMinX()/…` считаются из бокса и в NBT нет дублирующих полей.
3. **`CityLayout` возвращает `Plan` (запись), а не голый `List`.** `Plan` — это
   `originChunkX/originChunkZ + List<Cell>`; координаты города нужны и `/locate`, и волне 2.
   `Cell` несёт поля контракта плюс `buildingIndex` и `neighbourMask`.
4. **Поворот в `CityLayout` — `int quarterTurns`, а не `Transform`.** `Transform` импортирует
   `net.minecraft…Rotation`, а `CityLayout` обязан быть Minecraft-free (тесты в CI без Minecraft).
5. **Целевые биомы Biolith остались Java-константами.** `BiomePlacement.replaceOverworld`
   вызывается на инициализации мода, когда датапаки ещё не загружены. Вместо этого
   датапак-конфигурируемым сделано то, что реально определяет, где стоят города: **`biomes`
   структуры — это тег `#lostbuildings:has_structure/lost_city`**.
6. **Старый `Feature`-путь удалён в том же коммите, что и миграция.** `GroupBuildingPlacement`
   и переписанный `Streets` физически не компилируются рядом. Откат — `git checkout c1a4cc2`.

### Волна 2 (агент F, движок)

7. **`BuildingRole.RESIDENTIAL`, а не `DWELLING`** — совпало с уже написанным
   `BuildingPiece.engineRole()` у G. Итоговый набор: `RESIDENTIAL, SHOP, LIBRARY, TOWER`.
8. **Финальная форма `PlaceSettings`** — три новых поля дописаны **в конец**, порядок старых шести
   не тронут:
   ```java
   public record PlaceSettings(int minFloors, int maxFloors, boolean lighting, boolean spawners,
                               boolean loot, int waterLevel,
                               int cellars, float damageChance, BuildingRole role)
   ```
   Компактный конструктор нормализует вход; есть старая шестиаргументная форма (целое здание без
   подвалов), фабрика `intact(...)`, `withFloors/withCellars/withDamage/withRole`, `isIntact()`,
   `MAX_CELLARS = 2`. `BuildingEngine.generateBuilding(...)` — список параметров не менялся.
   Новое в публичном API: `cellarDepth`, `floorHeight()`, `pickCellars`, `BuildingRole`,
   `ConditionContext/Matcher/Resolver`, `DamageArea`, `Explosion`, `Weathering`,
   `Assets.get/putCityStyle|WorldStyle|MultiBuilding`.
9. **Условия `ConditionRE` резолвятся честно**, поэтому содержимое сундуков и спавнеров изменилось
   даже при `damageChance = 0`. Это и есть IMPROVEMENTS #2. **Число значений, взятых из
   `RandomSource`, не изменилось**, поэтому ни один блок не сдвинулся (см. «Регрессионная калитка»).
10. **`Building.getRandomPart(...)` получил перегрузку с `ConditionContext`.** Старая
    пятиаргументная форма осталась и ведёт себя как раньше.

### Волна 2 (агент G, ткань)

11. **Сетка кварталов изменена: здание там, где обе координаты относительно центра чётные.**
    Волна 1 делала шахматку `(i + j)` чётное; на ней уличные ячейки касаются только углами, у любой
    улицы ноль дорожных соседей, и все 100 % тайлов стали бы `street_none`. Тайлы физически
    невозможны без этой правки. Инвариант «два здания не делят ребро чанка» сохранён; добавлен тест
    `streetCellsFormOneConnectedLattice`.
12. **`Cell.neighbourMask` сменил семантику для уличных ячеек** — для `STREET` считаются только
    соседи-`STREET` (дорожная связность).
13. **`CityLayout.Cell`/`Settings` расширены** (`variant`, `kind`; `parkChance`, `parkKindCount`,
    `downtownChance`, `multiBuildingCount`; `Plan.downtown`). 6-аргументный конструктор `Settings`
    волны 1 сохранён. `CityLayout` по-прежнему Minecraft-free и не импортирует `BuildingRole`.
14. **`LostCityConfig` разбит на вложенные `StreetSettings` и `ContentSettings`** —
    `RecordCodecBuilder.group` держит максимум 16 полей, плоский список дорос до 18. В JSON это
    объекты `"streets"` и `"content"`; `street_block`/`street_width` волны 1 переехали в
    `streets.block`/`streets.width`.
15. **Отдельного `ScatteredPiece` нет.** Scattered-здание — это здание; `ScatteredStructure`
    собирает обычные `BuildingPiece` (для `oilrig` — четыре, как квадранты 2×2).
16. **`ModStructureSets.SPACING/SEPARATION` → `CITY_SPACING`/`CITY_SEPARATION`, 10/6 → 14/10.**
    Инвариант `separation >= city_size` обязан держаться, а `city_size` вырос 5 → 9.
17. **`StyleSelector` получил второе измерение — `Climate`.** Стиль (из чего дом построен) один на
    город; налёт (что на нём выросло) — поверхностный проход по куску. Биом сэмплится один раз в
    структуре. **Ни один кусок не читает биом во время генерации** — `WorldGenBounds` не нарушен.

### Волна 3 (интеграция)

18. **`StyleSelector.styleFor` больше не возвращает `null`** — теперь это «биом → citystyle →
    style» с фолбэком на `standard`. `select(WorldGenLevel, BlockPos)` сохранён как точка входа
    контракта §3, хотя структуры его не используют (они сэмплят biome source на этапе сборки старта).
19. **`LostCityConfig` перестал быть Minecraft-free-адъяцентным**: он импортирует `Assets` и
    `PlaceSettings`. Это не нарушение — `LostCityConfig` и раньше держал `BlockState`; чисто
    тестируемым обязан быть только `CityLayout`, и он им остался.

---

## Отключённый контент (лестница упрощений §9)

### Волна 1

* **`terrain_adaptation` оставлен `none`.** Ванильный beard/бордюр мог бы заменить экскавацию
  `Foundation`, но изменил бы, как здание садится в склон. Поле есть в JSON структуры.
  *(Волна 2 включила `beard_thin` для scattered — см. `worldgen/structure/scattered.json`.)*
* **Групповой уровень земли — минимум по углам «зданиевых» ячеек.** На пересечённой местности
  город врезается в склон; экскавация ограничена `MAX_EXCAVATION = 64`. Умнее (медиана/террасы) —
  не сейчас.

### Волна 2 — движок (F)

1. **`DamageArea` работает по одной ячейке, а не по кольцу соседних чанков.** Кусок пишет только в
   свой чанк (§7), поэтому межчанковый кратер выразить нечем. Та же математика сферы и та же кривая
   урона `3·(r−d)/r`, но центры взрывов внутри своей ячейки (есть тест). Выброшено: второй
   «мини-взрыв», `EXPLOSIONS_IN_CITIES_ONLY`, `getExplosionChance()` из citystyle (её роль играет
   `PlaceSettings.damageChance`), `isCompletelyDestroyed`/`getLowestExplosionHeight`.
2. **Теги `lostcities:not_breakable`/`easy_breakable` не портированы** — заменены двумя хардкод-
   списками в `Weathering` с тем же смыслом.
3. **«Повреждённый вариант палитры» в данных — это только `iron_bars`.** `cracked_*`/`mossy_*` в
   данных нет вообще; заменено таблицей «блок → состаренный блок» внутри `Weathering` с переносом
   свойств блок-стейта. Данные не тронуты.
4. **Подвал у `building1..8` — это обычный этажный срез под землёй.** `"cellar": true` есть только
   у четырёх квадрантов `town*`. Если у здания нет подходящей части для отрицательного этажа,
   берётся обычный срез (этаж 1, чтобы не унаследовать парадную дверь). Настоящие подвальные части —
   это новые JSON-геометрии, а их волна 2 писать не имела права.
5. **`belowpart` и `inbiome` по-прежнему считаются выполненными.** Первое тянуло бы имя части
   предыдущего этажа через весь стек, второе — чтение биома внутри генерации (запрещено
   `WorldGenBounds`). В шипованных данных ни одного вхождения (проверено `grep`).
6. **Кодеки CityStyle/WorldStyle урезаны** до того, что фаза 3 умеет использовать. Выброшены
   `corridorblocks`, `railblocks`, `sphereblocks`, `generalblocks`, `stuff_tags`, `multisettings`,
   `settings`, `cityspheres`, `parts`, `raildungeons`, а у `ObjectSelector` —
   `minSpawnDistance/maxSpawnDistance/feather`. Оставлено: `style`, `inherit`, `explosionchance`,
   `buildingsettings`, `streetblocks`, `parkblocks`, `selectors`, `outsidestyle`, `citystyles`,
   `citybiomemultipliers`, `scattered`. Неизвестные поля DFU игнорирует — **JSON править не нужно**.
7. **`BiomeMatcher` хранит сырые строки и ничего не резолвит.**
8. **Кратеров снаружи здания нет** — урон применяется к блокам, которые кладёт само здание.
9. **`STRUCTURE_VOID` = passthrough, POI/освещение/отложенные саженцы** — как и раньше.

### Волна 2 — ткань (G)

* **Мосты без опор.** Быки моста живут в многочанковом коде шоссе (фаза 4). `BridgePiece` кладёт
  настил и расчистку над ним. Через широкую реку мост выглядит висящим — принято.
* **`PartPlacer` не ставит спавнеры и не пишет лут-NBT.** Уличная/парковая геометрия шипованных
  частей — блоки и факелы, так что сегодня не теряется ничего; чужой датапак с сундуком в парке
  получит пустой сундук вместо падения генерации.
* **Парк заменяет участок, а не уличную ячейку** (в оригинале парк — вариант *уличного* чанка).
  На этой сетке улицы образуют замкнутую решётку, и вырезание ячейки пробило бы в ней дыру.
* **Парк без бордюра и без `parkelevation`** — приподнятых парков нет.
* **`street_full` не выбирается по связности.** Оригинал выбирает его отдельным типом уличной
  секции, а не по соседям. Часть в данных, имя в `StreetTiles.FULL`, но `forMask` его не вернёт.
* **Фронты зданий (`building_front*`) и лестницы (`stairs*`) не ставятся** — `generateFrontPart`
  требует знания соседнего здания и его этажности из куска-соседа, то есть межкусковой связи.
* **Климатический налёт — снег и мох, без лиан и льда.** Один блок сверху по heightmap-колонке.
* **`worldstyles/` скопированы, но `citybiomemultipliers` и `multisettings` не используются**
  (частота городов — `StructureSet`). Фаза 4.
* **`multi1..multi5` и `huge1/huge2` не в дефолтном списке мультизданий** — их таблицы повторяют
  одно обычное здание 4 или 9 раз, что уже умеет одноклеточный путь.

### Волна 3

* **Полный worldstyle-путь не подключён** (см. «Что свела волна 3», §4). Понижен в статусе явно.
* **Карта исследователя у картографа (IMPROVEMENTS #8, вторая половина) не сделана.** `/locate`
  работает для всех трёх структур; торговый оффер картографа — отдельная работа поверх
  `StructureTags` и таблиц торговли, и она не входила в §4 «свести F и G». Перенесено в фазу 4.
* **Нового юнит-теста на сведение нет.** `quadrantOf` и `styleOfCityStyle` читают статический
  `LostBuildings.ASSETS`, то есть требуют загруженного `ResourceManager`; чистой логики в них нет
  (обе — один поиск в мапе плюс фолбэк). Проверены вместо этого в мире: имена `town00..town11`
  могут появиться **только** из таблицы, а не из старого соглашения (оно дало бы `townhall00`).

---

## Регрессионная калитка: `damageChance = 0`

Требование «`damageChance = 0` воспроизводит целые здания в точности» выполнено тем, **как написан
код**, а не декларацией:

* `DamageArea.forBuilding(..., 0f)` возвращает разделяемую константу `DamageArea.NONE` — не
  аллоцирует и не тянет ни одного числа.
* Раскладка взрывов никогда не берётся из `RandomSource` здания: она считается собственным
  SplitMix64-потоком от сида мира и координаты ячейки. Порядок генерации чанков не влияет на вид руины.
* `damageAt(...)` при `NONE` возвращает 0, `Weathering.damage(...)` при 0 возвращает блок до единого
  `rand.nextFloat()`.
* `pickCellars(...)` не бросает кубик вообще — число уже решено вызывающим, иначе экскавация и
  bounding box куска разошлись бы с реальностью.
* Повышение спавнера `easymobs → hardmobs` и «фирменный» лут роли катаются от хеша (сид + позиция),
  а не от `RandomSource`.
* Сид лут-таблицы тянется **всегда**, когда у палитры есть лут, а не только при найденной таблице —
  иначе честная фильтрация условий «съела» бы выборку и сдвинула все последующие блоки.

Итог: при `cellars = 0, damageChance = 0` последовательность обращений к `RandomSource` идентична
доволновой. Геометрия бит-в-бит та же; отличаются только строки в `LootTable`/`SpawnData`.

---

## Что включено обратно из `PORT-STATUS.md` → «Отключённый контент»

| Было записано как отключённое | Сейчас |
|---|---|
| «Condition resolution: factor-weighted pick only» | **Включено.** `ConditionMatcher` честно проверяет `top/ground/cellar/floor/range/inpart/inbuilding/chunkx/chunkz`; `ConditionResolver` выкидывает неподходящих и перевешивает остальных по роли. Городской сундук доступен с 4-го этажа и в подвале, рельсовый — только в `rail_dungeon*` |
| «`inpart` treated as satisfied» | **Включено** (и для лут/моб-условий, и для `PartRef`) |
| «`issphere`/`isbuilding` treated as satisfied» | **Включено с определённым ответом**: движок всегда строит здание и никогда сферу |
| «no cellars (cellars=0)» | **Включено.** Цикл этажей идёт от `−cellars`; каждый подвальный срез вычищается в воздух (`carveCellar`) перед записью |
| «no damage/rubble» | **Включено.** `DamageArea` + `Explosion` + `Weathering` |
| CityStyle/WorldStyle/MultiBuilding не грузились | **Грузятся**; `inherit` разворачивается на загрузке; multibuilding и citystyle **читаются на генерации** (волна 3) |
| «Only standalone building groups, no city grid» | **Заменено настоящей структурой**: сетка 9×9 ячеек, улицы-тайлы, парки, доминанта 2×2, мосты |

Осталось отключённым: `belowpart`, `inbiome`, `STRUCTURE_VOID` = passthrough, POI/освещение/
отложенные саженцы, фронты зданий, `street_full`.

---

## Стык `Foundation` ↔ подвалы

`BuildingPiece` вызывает `Foundation.build(...)` **до** `generateBuilding(...)`, а `Foundation`
гонит столбы вниз от `baseY − 1` — сквозь будущий подвал. Движок защищается сам: `carveCellar`
вычищает каждый подвальный срез непосредственно перед записью части, так что «засыпанного подвала»
не бывает (подтверждено в мире: комната на y=43–45 под полом первого этажа на 48). Это лишние
записи; дешёвая оптимизация — начинать столбы с `baseY − BuildingEngine.cellarDepth(settings) − 1`.
**Не сделано волной 3 сознательно**: экономия только в скорости, а риск сдвинуть геометрию
ненулевой, и §9 велит не трогать зелёное ради красоты.

---

## Известные шероховатости (не блокеры, честно записаны)

* **Город может сесть в озеро.** Общий уровень земли — минимум по «зданиевым» ячейкам, уличные
  из выборки исключены. Если минимум приходится на берег, соседние ячейки оказываются под водой, и
  внутри здания стоит вода (видно в замере: `water` внутри этажей у города (45, −41)). Движок
  дренирует только объём внутри футпринта до `waterLevel`; воду, которая натекает снаружи после
  генерации, он не держит. Правильное лечение — уровень земли выше уреза воды или настоящий
  дренаж по периметру города; это фаза 4.
* **`/locate` для города — до 19 секунд** при поиске в неразведанной местности (spacing 14 /
  separation 10 и большой bounding box старта). Ванильная цена, но заметная.
* **Сгенерированный `worldgen/structure/lost_city.json` содержит только `buildings`.** Все
  остальные поля — `optionalFieldOf` со значениями, равными дефолтам, поэтому кодек их не пишет.
  Это корректно, но означает, что список доступных ручек виден только в javadoc `LostCityConfig`,
  а не в файле.
* **Коммит `9e29897` («WIP … DOES NOT BOOT») описывает состояние неверно.** Он был сделан извне
  как страховочный снимок незавершённой работы; диагноз «падает загрузка датапаков из-за старой
  схемы `lost_city.json`» **не подтвердился**. Настоящая причина того падения — недоудалённый
  каталог `run/world`, оставшийся от убитого предыдущего процесса (`Unable to read or access the
  world gen settings file`). Ровно тот же код на чистом `run/world` загружается и работает:
  `Done (5.662s)!`, 0 ошибок. Исправление зафиксировано этим коммитом.

---

## Волна 1 — что мигрировало

| Было (`Feature`) | Стало (`Structure`) |
|---|---|
| `LostBuildingFeature` + `ModConfiguredFeatures`/`ModPlacedFeatures` + `RarityFilter(6)` | `LostCityStructure` + `ModStructures`/`ModStructureSets` |
| `CellLattice` (5 офсетов, ~1 ячейка на срабатывание, ~33 % пустых) | `CityLayout` — сетка 9×9 ячеек, 25 участков, ~17 застроенных |
| `GroupBuildingPlacement.place()` | `BuildingPiece.postProcess()` |
| `Streets.connect()` (L-маршруты) | `StreetPiece.postProcess()` → тайл `street_*` + `StreetDecor` |
| окно записи 48×48 (`WorldGenBounds.WRITE_RADIUS`) | `chunkBox` = ровно свой чанк; кусок = ячейка = чанк |
| константы в Java | кодек структуры + `citystyles/` + `multibuildings/` |

## Дефолты (волна 2 + 3)

| Поле | Было | Стало | Почему |
|---|---|---|---|
| `city_size` | 5 | **9** | На новой сетке дом стоит через ячейку: 5 даёт 3×3, 9 даёт 5×5 участков |
| `spacing` / `separation` | 10 / 6 | **14 / 10** | `separation >= city_size` |
| `park_chance` | — | 0.25 | §6.3 |
| `downtown_chance` | — | 0.3 | IMPROVEMENTS #7 (замерено: 9/31 = 29 %) |
| `lamp_spacing` | — | 8 | §6.5 |
| `pothole_chance` | — | 0.04 | §6.5 |
| `cellars` | — | 1 (максимум 2) | §5.3, сведено волной 3 |
| `damage_chance` | — | 0.2 | контракт F↔G |

Город — 144×144 блока вместо 80×80.

## Файлы

**Движок** (`engine/`): `BuildingRole`, `damage/{Explosion,DamageArea,Weathering}`,
`condition/{ConditionContext,ConditionMatcher,ConditionResolver}`,
`codec/{CityStyleRE,WorldStyleRE,MultiBuildingRE,SelectorsRE,ObjectSelector,BuildingSettingsRE,
StreetSettingsRE,ParkSettingsRE,CityStyleSelector,CityBiomeMultiplier,BiomeMatcher,
ScatteredSettingsRE}`; изменены `PlaceSettings`, `BuildingEngine`, `Building`, `Assets`, `AssetLoader`.

**Структура** (`world/structure/`): `LostCityStructure`, `LostCityConfig`, `CityLayout`,
`StreetTiles`, `StreetDecor`, `ScatteredStructure`, `ModStructures`, `ModStructureSets`,
`ModStructureTags`; куски `piece/{CityPiece,BuildingPiece,StreetPiece,ParkPiece,BridgePiece,
PartPlacer}`; `registry/{ModStructureTypes,ModStructurePieceTypes}`;
`world/feature/{Streets,Foundation,StyleSelector,WorldGenBounds}`; `data/*`.

**Данные** (`resources/data/lostbuildings/lostcities/`): parts 186, buildings 34, palettes 29,
variants 12, conditions 3, styles 6 (+`snowy`, `swamp`), citystyles 7 (+`citystyle_snowy`,
`citystyle_swamp`), multibuildings 13, worldstyles 2. Каталог `scattered/` удалён волной 3.

**Тесты** (89): `CityLayoutTest`, `StreetTilesTest`, `StreetDecorTest`, `WorldGenBoundsTest`,
`EngineDamageAreaTest`, `EngineConditionTest`, `EngineBuildingRoleTest`, `LostBuildingsSanityTest`.

## Что осталось человеку

Визуальная приёмка в клиенте. Головой стоит посмотреть: как читается перекрёсток из тайлов и
бордюр с фонарями; смотрится ли доминанта 2×2 в центре квартала; не выглядит ли мост без опор
слишком голым; насколько заметен климатический налёт; и главное — города, севшие в воду
(см. «Известные шероховатости»).
