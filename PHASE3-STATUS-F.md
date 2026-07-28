# PHASE3-STATUS-F — агент F, волна 2: движок и внутренности зданий

Отдельный файл (агент G пишет свой; волна 3 сводит оба в `PHASE3-STATUS.md`).
Закон: `PHASE3-PLAN.md` §5, `PORT-PLAN-26.2.md` §6–§9.
Владение: **только `lostbuildings/src/main/java/com/lostbuildings/engine/**`** + тесты `Engine*`.

## Итог

Все шесть задач §5 сделаны. Gradle не запускался (в чекауте параллельно работает G),
но код проверен строже, чем «на глаз» — см. «Верификация».

| §5 | Задача | Статус |
|---|---|---|
| 1 | Разрушения и обломки (IMPROVEMENTS #1 + PORT #5) | **ГОТОВО** |
| 2 | Лут и мобы по условиям (IMPROVEMENTS #2) | **ГОТОВО** |
| 3 | Подвалы (IMPROVEMENTS #3) | **ГОТОВО** (с одним §9-упрощением) |
| 4 | Роли зданий, лут-часть (IMPROVEMENTS #5) | **ГОТОВО** |
| 5 | Кодеки CityStyle / WorldStyle / MultiBuilding (PORT #9) | **ГОТОВО** (в урезанном виде) |
| 6 | Этажность приходит от вызывающего (IMPROVEMENTS #6) | **ПОДТВЕРЖДЕНО**, правок не потребовалось |

## Замороженный контракт §3 — финальная форма

```java
public record PlaceSettings(int minFloors, int maxFloors, boolean lighting, boolean spawners,
                            boolean loot, int waterLevel,
                            int cellars, float damageChance, BuildingRole role)
```

Три новых поля **дописаны в конец**, порядок старых шести не тронут. Компактный конструктор
нормализует вход (`minFloors ≥ 0`, `maxFloors ≥ minFloors`, `cellars` → `[0, MAX_CELLARS]`,
`damageChance` → `[0, 1]`, `role == null` → `RESIDENTIAL`). Дополнительно:

* `PlaceSettings(int,int,boolean,boolean,boolean,int)` — **старая шестиаргументная форма**;
  даёт целое здание без подвалов, то есть ровно то, что мод генерировал до волны 2.
* `PlaceSettings.intact(min, max, waterLevel)` — фабрика.
* `withFloors / withCellars / withDamage / withRole`, `isIntact()`, `MAX_CELLARS = 2`.

`BuildingEngine.generateBuilding(WorldGenLevel, BlockPos, RandomSource, Transform, Building,
CompiledPalette, PlaceSettings)` — список параметров не менялся.

Новое в публичном API движка (для вызывающей стороны, то есть для G):

* `BuildingEngine.cellarDepth(PlaceSettings)` — на сколько блоков здание уходит ниже `origin`.
* `BuildingEngine.floorHeight()` — 6, чтобы placement-проход не хардкодил константу.
* `BuildingEngine.pickCellars(Building, PlaceSettings)` — та же цифра, что возьмёт движок
  (учитывает `mincellars`/`maxcellars` самого здания); **ничего не тянет из `RandomSource`**.
* `BuildingRole` (enum, `engine/`), `ConditionContext`, `ConditionMatcher`, `ConditionResolver`,
  `DamageArea`, `Explosion`, `Weathering`.
* `Assets.getCityStyle/getWorldStyle/getMultiBuilding` + соответствующие `put*` и `Map`-геттеры.

## Отклонения от контрактов

1. **`BuildingRole.RESIDENTIAL`, а не `DWELLING`.** Я начал писать `DWELLING`; при сборке
   выяснилось, что G уже написал `BuildingPiece.engineRole()` с
   `case RESIDENTIAL -> BuildingRole.RESIDENTIAL`. Плана на имена констант нет, менять чужой файл
   нельзя — переименовал у себя. **Волне 3 сводить нечего**: `PlaceSettings` у G и у меня совпали
   до порядка полей, включая `engineRole()`. Итоговый набор: `RESIDENTIAL, SHOP, LIBRARY, TOWER`
   (ровно `CityLayout.BuildingKind`).
2. **`PlaceSettings.MAX_CELLARS = 2`, а кодек конфига у G допускает `cellars` до 4.**
   §5.3 говорит «1–2 этажа вниз», поэтому движок жёстко зажимает 2. Значения 3–4 из датапака не
   упадут, а просто превратятся в 2. Если волна 3 захочет глубже — поднять одну константу.
3. **Условия `ConditionRE` резолвятся честно, поэтому содержимое сундуков и спавнеров изменилось
   даже при `damageChance = 0`.** Это ровно IMPROVEMENTS #2 и это не откатывается флагом. Что
   при этом гарантировано: **число значений, взятых из `RandomSource`, не изменилось**, поэтому
   ни один блок не сдвинулся (см. «Регрессионная калитка»).
4. **`Building.getRandomPart(...)` получил перегрузку с `ConditionContext`.** Старая
   пятиаргументная форма (`isTop/isGround/isCellar/floor`) осталась и ведёт себя как раньше —
   она просто сворачивает булевы в контекст. Вне движка её никто не вызывает.
5. **Кодеки CityStyle/WorldStyle урезаны** (детали ниже, в «Лестнице упрощений»). Форма файлов
   не менялась: DFU игнорирует неизвестные поля, так что все 20 JSON из `1.21/` и все 20 копий,
   которые положил G, парсятся без единой ошибки — проверено, см. «Верификация».

## Регрессионная калитка: `damageChance = 0`

Требование «`damageChance = 0` воспроизводит сегодняшние целые здания в точности» выполнено
следующим образом (это не декларация, это то, как написан код):

* `DamageArea.forBuilding(..., 0f)` возвращает разделяемую константу `DamageArea.NONE` —
  **не аллоцирует и не тянет ни одного числа**.
* Раскладка взрывов вообще никогда не берётся из `RandomSource` здания: она считается
  собственным SplitMix64-потоком от сида мира и координаты ячейки. Это нужно и само по себе
  (порядок генерации чанков не должен влиять на вид руины), и ради этой калитки.
* `damageAt(...)` при `NONE` возвращает 0, `Weathering.damage(...)` при 0 возвращает блок
  до единого `rand.nextFloat()`.
* `pickCellars(...)` не бросает кубик вообще (число уже решено вызывающим — иначе экскавация
  и bounding box куска разошлись бы с реальностью).
* Повышение спавнера `easymobs → hardmobs` и «фирменный» лут роли катаются **не** от
  `RandomSource`, а от хеша (сид мира + позиция). Ноль лишних выборок.
* Сид лут-таблицы (`rand.nextLong()`) теперь тянется **всегда**, когда у палитры есть лут, а не
  только при успешно найденной таблице — иначе честная фильтрация условий могла бы «съесть»
  одну выборку и сдвинуть все последующие блоки здания.

Итог: при `cellars = 0, damageChance = 0` последовательность обращений к `RandomSource`
идентична доволновой. Геометрия здания бит-в-бит та же; отличаются только строки, попавшие в
`LootTable`/`SpawnData` (это и есть IMPROVEMENTS #2).

## Что включено обратно из `PORT-STATUS.md` → «Отключённый контент»

| Было записано как отключённое | Сейчас |
|---|---|
| «Condition (loot/mob) resolution: factor-weighted pick only; position/part filters ignored» | **Включено.** `ConditionMatcher` честно проверяет `top/ground/cellar/floor/range/inpart/inbuilding/chunkx/chunkz`, `ConditionResolver` выкидывает неподходящих кандидатов из мешка и перевешивает оставшихся по роли. Шипованный `chestloot.json` наконец работает как задумано: городской сундук доступен с 4-го этажа и в подвале (`range "4,100"` / `"-100,-3"`), рельсовый — только внутри `rail_dungeon*` (`inpart`). |
| «`inpart` … treated as satisfied» (для выбора частей) | **Включено** (для лут/моб-условий и для `PartRef`). |
| «`issphere`/`isbuilding` treated as satisfied» | **Включено с определённым ответом**: движок всегда строит здание и никогда — сферу, поэтому `isbuilding` истинно, `issphere` ложно. В шипованных данных этих полей нет, так что поведение не изменилось, но датапак теперь получает честный ответ. |
| «no cellars (cellars=0)» | **Включено.** Цикл этажей идёт от `-cellars`; каждый подвальный срез перед записью вычищается в воздух (`carveCellar`), потому что `Foundation` уже успел засыпать этот объём столбами. |
| «no damage/rubble» | **Включено.** `DamageArea` + `Explosion` + `Weathering`: сферы взрыва, «съедание» блоков, замена на `cracked_*`/`mossy_*`, вероятность растущая с этажом, обломки на первом этаже из собственного `rubble`-символа здания. |
| CityStyle / WorldStyle / MultiBuilding вообще не грузились | **Загружаются** из `citystyles/`, `worldstyles/`, `multibuildings/`; цепочки `inherit` разворачиваются на этапе загрузки. |

Осталось отключённым (и это по-прежнему честно записано): `belowpart` и `inbiome`
(см. ниже), `STRUCTURE_VOID` = passthrough, POI/освещение/отложенные саженцы.

## Лестница упрощений (§9) — что урезано и чем заменено

1. **`DamageArea` работает по одной ячейке, а не по кольцу соседних чанков.**
   Оригинал сканировал `±offset` чанков и клал кратеры поперёк города. В волне 2 город — это
   независимые `StructurePiece`, каждый пишет только в свой чанк (§7), поэтому межчанковый
   кратер выразить нечем. Заменено: та же математика сферы и та же кривая урона
   (`3·(r−d)/r`), но центры взрывов гарантированно внутри своей ячейки (есть тест).
   Выброшено вместе с этим: второй «мини-взрыв», фильтр `EXPLOSIONS_IN_CITIES_ONLY`,
   `getExplosionChance()` из citystyle (её роль играет `PlaceSettings.damageChance`),
   `isCompletelyDestroyed`/`getLowestExplosionHeight` (быстрые пути для посубчанковой записи,
   которой у нас нет).
2. **Теги `lostcities:not_breakable` / `easy_breakable` не портированы.**
   Блок-теги — территория G, и в данных их нет. Заменено: два хардкод-списка в `Weathering`
   с тем же смыслом (бедрок/барьер/обсидиан/structure-блоки не едятся; стекло и решётки
   ломаются в 2.5 раза охотнее).
3. **«Повреждённый вариант палитры» в данных — это только `iron_bars`.**
   Все 14 палитр с полем `damaged` указывают на `minecraft:iron_bars`; `cracked_*`/`mossy_*`,
   которых просит план, в данных нет вообще. Заменено: таблица «блок → состаренный блок»
   внутри `Weathering` (stone_bricks → cracked, ступени/плиты/стены → mossy, cobblestone →
   mossy, nether/deepslate/blackstone → cracked), с переносом всех свойств блок-стейта
   (ступень остаётся ступенью, смотрящей туда же). Данные не тронуты — они у G.
4. **Подвал у зданий `building1..8` — это обычный этажный срез под землёй.**
   В шипованных данных `"cellar": true` есть **только** у четырёх квадрантов `town*`
   (`town00_c` и т. д.). Пропускать подвал у всех остальных значило бы, что IMPROVEMENTS #3
   не видно в игре. Заменено: если у здания нет подходящей части для отрицательного этажа,
   берётся обычный срез (этаж 1, чтобы не унаследовать парадную дверь; для одноэтажек — 0).
   Стены и лестничная клетка при этом свои, темнота — от того, что это под землёй, лут — от
   честного `cellar`/`range`-условия. Настоящие подвальные части — это новые JSON, то есть G.
5. **`belowpart` и `inbiome` по-прежнему считаются выполненными.**
   Первое потребовало бы тащить через весь стек имя части предыдущего этажа, второе — чтение
   биома внутри генерации, ровно то, что запрещает `WorldGenBounds`. В шипованных данных
   ни одного вхождения нет (проверено `grep`).
6. **Кодеки CityStyle/WorldStyle урезаны до того, что фаза 3 умеет использовать.**
   Выброшены `corridorblocks`, `railblocks`, `sphereblocks` (метро/железка/сферы — фаза 4 по §1),
   `generalblocks`, `stuff_tags`, `multisettings`, `settings`, `cityspheres`, `parts`,
   `raildungeons`, а у `ObjectSelector` — `minSpawnDistance/maxSpawnDistance/feather` (их даже
   оригинальный кодек хардкодил константами). Оставлено всё, из чего строится город: `style`,
   `inherit`, `explosionchance`, `buildingsettings`, `streetblocks`, `parkblocks`, `selectors`
   (buildings / multibuildings / parks / fountains / bridges / stairs / fronts), а у world style —
   `outsidestyle`, `citystyles`, `citybiomemultipliers`, `scattered`.
   Неизвестные поля DFU игнорирует, поэтому **JSON править не нужно**.
7. **`BiomeMatcher` хранит сырые строки и ничего не резолвит.** Резолюция биома — это чтение
   мира и выбор стиля, то есть §6.8 и агент G. Движок только доставляет данные.
8. **Кратеров снаружи здания нет.** Урон применяется к блокам, которые кладёт само здание;
   ландшафт вокруг не выгрызается. Причина та же, что в п. 1 — кусок пишет только в свою ячейку,
   а улица рядом принадлежит другому куску.

## Стык с `Foundation` (это важно для G и для волны 3)

`BuildingPiece` вызывает `Foundation.build(...)` **до** `generateBuilding(...)`, а `Foundation`
гонит столбы вниз от `baseY − 1` — то есть ровно сквозь будущий подвал. Движок защищается сам:
`carveCellar` вычищает каждый подвальный срез в воздух непосредственно перед записью части, так
что «засыпанного подвала» не бывает. Но это лишние записи. Дешёвая оптимизация для волны 3
(в файле G, поэтому я её не делал): начинать столбы не с `baseY − 1`, а с
`baseY − BuildingEngine.cellarDepth(settings) − 1`. Пол самого нижнего подвала при этом остаётся
заполнителем фундамента — это правильно и специально: срез вычищается только на
`sliceCount` блоков вверх от своей отметки, блок под ним не трогается.

## Файлы

**Новые** (`lostbuildings/src/main/java/com/lostbuildings/engine/`):

* `BuildingRole.java` — enum ролей + таблицы лута/мобов + `tier()` (Minecraft-free).
* `damage/Explosion.java` — сфера урона на `int`-ах (Minecraft-free).
* `damage/DamageArea.java` — раскладка сфер + поэтажное выветривание (Minecraft-free).
* `damage/Weathering.java` — «урон → замена блока», таблица `cracked_*`/`mossy_*`.
* `condition/ConditionContext.java` — где сейчас находится движок (Minecraft-free).
* `condition/ConditionMatcher.java` — честная проверка `ConditionTest` (Minecraft-free).
* `condition/ConditionResolver.java` — взвешенный выбор среди подошедших + вес роли.
* `codec/CityStyleRE.java`, `codec/WorldStyleRE.java`, `codec/MultiBuildingRE.java`
* `codec/SelectorsRE.java`, `codec/ObjectSelector.java`, `codec/BuildingSettingsRE.java`,
  `codec/StreetSettingsRE.java`, `codec/ParkSettingsRE.java`, `codec/CityStyleSelector.java`,
  `codec/CityBiomeMultiplier.java`, `codec/BiomeMatcher.java`, `codec/ScatteredSettingsRE.java`

**Изменённые:**

* `engine/PlaceSettings.java` — контракт §3 (см. выше).
* `engine/BuildingEngine.java` — подвалы, урон, честные условия, роли, обломки, `cellarDepth`.
* `engine/Building.java` — выбор части через `ConditionMatcher` + перегрузка с контекстом.
* `engine/Assets.java` — три новых реестра + разворачивание `inherit` у citystyle.
* `engine/AssetLoader.java` — три новых каталога.

**Тесты** (`lostbuildings/src/test/java/com/lostbuildings/engine/`):

* `damage/EngineDamageAreaTest.java` — 12 тестов: калитка `damageChance = 0`, детерминизм,
  взрывы не выходят за ячейку, поэтажный рост, геометрия сферы.
* `condition/EngineConditionTest.java` — 12 тестов: все поля условий, диапазоны (включая
  отрицательные), «ничего не подошло → null», влияние роли.
* `EngineBuildingRoleTest.java` — 16 тестов: таблицы ролей, `tier()`, повышение спавнера,
  и форма `PlaceSettings` (в т. ч. что шестиаргументная форма даёт целое здание).

## Верификация

Gradle **не запускался** (в чекауте параллельно работает агент G — §7 и брифинг это запрещают).
Вместо этого — прямой `javac`/`java` в скрэтч-каталог, ничего в дереве проекта не трогая:

| Проверка | Результат |
|---|---|
| `javac` всего `src/main/java` против настоящих джарок 26.2 + DFU + Fabric из gradle-кэша | **GREEN**, 0 ошибок (единственная ошибка в процессе — `BuildingRole.RESIDENTIAL` из файла G — устранена переименованием, см. «Отклонения» №1) |
| `javac` всего `src/test/java` | **GREEN** |
| JUnit 5, пакет `com.lostbuildings.engine` | **40 / 40 GREEN** |
| JUnit 5, весь `com.lostbuildings` | 63 / 65; обе падающие — `CityLayoutTest` (файл агента G, он его сейчас переписывает), ни одна не моя |
| Тесты не требуют Minecraft | подтверждено: прогон шёл **без** `Bootstrap.bootStrap()` и ни один тест не тронул реестр блоков |
| Разбор всех 5 `citystyles` + 2 `worldstyles` + 13 `multibuildings` урезанными кодеками | **20/20 OK** — и для копий, которые положил G, и для оригиналов из `1.21/` |
| Разворачивание `inherit` (`standard → common → config`) | проверено вручную: `style=standard`, `width=8`, 8 зданий, 12 мульти-зданий, 8 парков; у `citystyle_border` — `maxfloors=1`, `maxcellars=1`, `buildingchance=0.2`, пустой (не унаследованный!) список мульти-зданий |
| `grep` на `mcjty.*` / `ResourceLocation` / `Identifier.of(` / Forge / `javax.annotation` | чисто (два попадания `mcjty` — упоминания в javadoc «откуда портировано») |

## Что должна свести волна 3

1. Ничего по `PlaceSettings` — форма совпала с той, против которой уже написан `BuildingPiece`.
2. Решить, поднимать ли `MAX_CELLARS` с 2 до 4 (кодек конфига у G допускает 4).
3. Опционально: сдвинуть старт столбов `Foundation` на `cellarDepth` вниз (экономия записей).
4. `CityLayoutTest` красный — это волна 2 агента G, не моё.
5. `citystyles`/`worldstyles` теперь грузятся, но **выбор** стиля их пока не читает (это §6.8, G).
   Если G не успел — движок просто продолжит работать по `StyleSelector`, ничего не сломается.
