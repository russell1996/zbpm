# WO-C8-41 — сверка MI subProcess с настоящим Zeebe 8.5.0: эмпирические факты

Стенд: контейнер `zbpm-cmp-zeebe` (образ `camunda/zeebe:8.5.0`, gRPC `localhost:36500`),
один брокер, топология проверена (`CONNECTED brokers=1 gw=8.5.0`).
Клиент: **сырой gRPC** (`@grpc/grpc-js` + `zeebe.proto` из `@camunda8/sdk@8.5.9`) —
SDK-клиент (`ZeebeGrpcClient.activateJobs`) **падает** на `job.variables` с массивами,
содержащими `null` (`An unsafe number value was received for "results" ... Cannot read
properties of null`), поэтому весь oracle идёт мимо SDK-парсера, сырые строки `variables`
цитируются дословно. BPMN-фикстуры: `docs/analysis/c8-oracle/bpmn/s*.bpmn`,
раннер: `docs/analysis/c8-oracle/run-oracle.js`, полный лог:
`docs/analysis/c8-oracle/logs/oracle-s1-s9-2026-10-13.log`.

Правило документа: только то, что реально увидено в прогоне. Выводов «из документации»
здесь нет. Все `piKey` ниже — из живого прогона 2026-10-13 (UTC ~20:55–21:10).

## S0 (контроль, НЕ из WO: plain w → w2 без MI)

`w` завершён с `{"tu":"tu-plain"}`.
- `s0w2` видит: `{"tu":"tu-plain"}`.
- `s0dump` видит: `{"tu":"tu-plain"}`.

Факт S0: **без MI completion-переменные видны downstream** (w2 и dump).

## S1 (parallel MI sub по ["a","b","c"], w → w2, w завершается с tu БЕЗ output mapping; порядок c,b,a)

Активация `s1w` (piKey=2251799813685341), дословно:
```
key=...369 vars={"tu":null,"item":"a","loopCounter":1,"results":[null,null,null]}
key=...370 vars={"tu":null,"item":"b","loopCounter":2,"results":[null,null,null]}
key=...371 vars={"tu":null,"item":"c","loopCounter":3,"results":[null,null,null]}
```
Факт S1-1: **при входе** каждый инстанс видит `item` (свой), `loopCounter` (1/2/3),
предсозданные `results=[null,null,null]` и предобъявленный `tu=null` (имя outputElement).

Завершения в обратном порядке (c,b,a) с `{"tu":"tu-c"}` и т.д. Активация `s1w2`, дословно:
```
key=...376 vars={"tu":"tu-c","item":"c","loopCounter":3,"results":[null,null,null]}
key=...380 vars={"tu":"tu-b","item":"b","loopCounter":2,"results":[null,null,null]}
key=...384 vars={"tu":"tu-a","item":"a","loopCounter":1,"results":[null,null,null]}
```
Факт S1-2: **гонки НЕТ**: каждая ветка `w2` видит СВОЙ `tu` (`tu-c` при `item=c`),
несмотря на обратный порядок завершений. Completion-переменная элемент-локальна
(соседние ветки её не видят — иначе все три увидели бы `tu-a`, записанный последним).

Завершения `w2` с `{}`. Финальный `s1dump`, дословно:
```
key=...398 vars={"results":["tu-a","tu-b","tu-c"]}
```
Факт S1-3: `outputCollection` упорядочена **по индексу входа** (a,b,c),
несмотря на обратный порядок завершений. Факт S1-4: в `dump` НЕТ ни `tu`, ни
`item`/`loopCounter`/`results` кроме самого `results` — **локалы инстансов дропнуты**,
наружу вышел только агрегированный `results`.

## S2a (то же + output mapping w: tu → tu)

`s2aw` при входе — как S1 (`tu:null,item,loopCounter,results:[null,null,null]`).
Завершения c,b,a с `{"tu":"tu-c"}` и т.д. `s2aw2`, дословно (выборочно):
```
key=...441 vars={"tu":"tu-c","item":"c","loopCounter":3,"results":[null,null,null]}
```
Факт S2a-1: `w2` видит `tu` СВОЕЙ ветки — **output mapping поднял `tu` в скоуп инстанса**
(без маппинга в S1 `w2` видел бы `tu` только своей ветки тоже — см. S1-2; разница S2a
в том, ОТКУДА `tu` берётся: здесь он продукт маппинга, а не сырой completion-переменной).
`s2adump`: `{"results":["tu-a","tu-b","tu-c"]}` — как S1.

## S2b (output mapping w: tu → tuLocal, outputElement == tuLocal)

`s2bw` при входе, дословно:
```
vars={"item":"a","tuLocal":null,"loopCounter":1,"results":[null,null,null]}
```
Факт S2b-1: **таргет output mapping предобъявлен как null** в скоупе инстанса
(`tuLocal:null`); сырого `tu` в скоупе НЕТ (в отличие от S1/S2a, где `tu:null` —
предобъявлено имя outputElement).
Завершения с `{"tu":"tu-c"}` и т.д. `s2bw2`, дословно:
```
vars={"item":"c","tuLocal":"tu-c","loopCounter":3,"results":[null,null,null]}
```
Факт S2b-2: маппинг `tu → tuLocal` вычислился из completion-переменной и лёг в скоуп.
`s2bdump`: `{"results":["tu-a","tu-b","tu-c"]}`.

## S3 (input mapping w: =item → x; input w2: =loopCounter → lc)

`s3w`, дословно:
```
vars={"x":"a","item":"a","loopCounter":1,"results":[null,null]}
vars={"x":"b","item":"b","loopCounter":2,"results":[null,null]}
```
Факт S3-1: `inputElement` (`item`) **виден внутренним элементам**; input mapping
`=item → x` отработал (`x=a` при `item=a`).
`s3w2` после завершения `w` с `{}`, дословно:
```
vars={"lc":1,"x":null,"item":"a","loopCounter":1,"results":[null,null]}
vars={"lc":2,"x":null,"item":"b","loopCounter":2,"results":[null,null]}
```
Факт S3-2: `x` (результат input mapping на `w`) **НЕ виден** следующему элементу
той же ветки (`x:null`) — input mapping элемент-локален; `loopCounter` виден
и маппится (`lc:1/2`).
`s3dump`: `{"results":[null,null]}`.
Факт S3-3: `outputElement == x` собрал `[null,null]` — `x` не пережил границу `w → w2`;
утечки `x`/`lc` в родительский скоуп НЕТ.

## S4 (пустая inputCollection =[])

`s4w`: 0 job-ов (после 2 с ожидания). `s4dump`, дословно:
```
vars={"results":[]}
```
Факт S4: тело НЕ выполняется ни разу, поток идёт дальше, `outputCollection == []`
(переменная СОЗДАНА, а не отсутствует).

## S5 (outputElement == neverSet, никто не обновляет)

`s5w` при входе, дословно:
```
vars={"item":"a","neverSet":null,"loopCounter":1,"results":[null,null]}
```
Факт S5-1: имя несуществующего outputElement **предобъявлено как null**.
`s5dump` после завершений с `{}`: `{"results":[null,null]}`.
Факт S5-2: необновлённый outputElement даёт `null` в слоте (не пропуск, не ошибка).

## S6 (sequential)

Первая активация: ровно 1 job (`item:a,loopCounter:1,results:[null,null,null]`);
после его завершения — ровно 1 (`item:b`, `results:["a",null,null]`);
затем ровно 1 (`item:c`, `results:["a","b",null]`).
Факт S6-1: sequential — **второй job активируется только после завершения первого**.
Факт S6-2: частично агрегированный `results` **виден следующим инстансам**
(`["a",null,null]`, `["a","b",null]`).
`s6dump`: `{"results":["a","b","c"]}`.

## S7 (interrupting timer boundary PT3S на MI-подпроцессе, job-ы НЕ завершались)

До срабатывания: 2 job-а `s7w` (`item a/b`). После 6 с ожидания: `s7w` — 0 job-ов.
`s7dump` (ветка `dumpCancel`, `path=cancel`), дословно:
```
key=...729 vars={"path":"cancel"}
```
Факт S7-1: взведённый таймер прервал MI-тело, **job-ы инстансов отменены** (невидимы).
Факт S7-2: на cancel-ветке **НЕТ `results`** — предсозданный `[null,null]`
и частичная агрегация в родителя НЕ передались (переменная исчезла вместе со скоупом).

## S8a (MI-подпроцесс внутри MI-подпроцесса; inner: ["1","2"]/iitem/innerResults/=iitem; outer outputElement == innerResults)

`s8aw`: 4 job-а, дословно:
```
vars={"iitem":"1","loopCounter":1,"innerResults":[null,null],"oitem":"x","outerResults":[null,null]}
vars={"iitem":"2","loopCounter":2,"innerResults":[null,null],"oitem":"x","outerResults":[null,null]}
vars={"iitem":"1","loopCounter":1,"innerResults":[null,null],"oitem":"y","outerResults":[null,null]}
vars={"iitem":"2","loopCounter":2,"innerResults":[null,null],"oitem":"y","outerResults":[null,null]}
```
Факт S8a-1: внешний `oitem` **виден внутренним элементам** (`oitem:x/y`).
Факт S8a-2: внутренний `loopCounter` **затеняет внешний** (виден только 1/2,
внешнего счётчика рядом нет).
Факт S8a-3: оба предсозданных `innerResults`/`outerResults` видны внутри.
`s8adump`, дословно:
```
vars={"outerResults":[["1","2"],["1","2"]]}
```
Факт S8a-4: `outerResults` собран по внешнему индексу; **`innerResults` в dump НЕТ** —
внутренняя агрегация не утекает в корень.

## S8b (MI service task ["p","q"]/pitem/tresults/=pitem внутри MI-подпроцесса ["a","b"]/item/results/=tresults)

`s8bw`: 4 job-а (2 внешних × 2 внутренних), дословно (выборочно):
```
vars={"pitem":"p","loopCounter":1,"tresults":[null,null],"item":"a","results":[null,null]}
```
Факт S8b-1: внешний `item` виден MI-задаче внутри (`item:a` рядом с `pitem:p`).
`s8bdump`: `{"results":[["p","q"],["p","q"]]}`.
Факт S8b-2: `tresults` в dump НЕТ (не утекает); внешний `results` собран по внешнему индексу.

## S9 (completionCondition — ИСПРАВЛЕННЫЙ прогон; первый прогон был НЕВАЛИДЕН)

**Ловушка синтаксиса (зафиксирована, чтобы не повторить):** `completionCondition` —
это **ДОЧЕРНИЙ ЭЛЕМЕНТ** `bpmn:multiInstanceLoopCharacteristics`
(`<bpmn:completionCondition>=...</bpmn:completionCondition>`), а НЕ атрибут
`zeebe:loopCharacteristics`. Первый вариант фикстуры (`completionCondition="=..."` как
атрибут) Zeebe **молча игнорирует**: все 6 проб имён
(`numberOfInstances`, `numberOfActiveInstances`, `numberOfCompletedInstances`,
`numberOfTerminatedInstances`, `completedInstances`, `numberOfCompleteInstances`)
и даже `=true` были инертны (после 1 завершения оставалось 2 живых сиблинга;
пробы чистились завершениями вручную). Контроль на MI service task с `=true`
(атрибут) — тоже инертно; `completionCondition="true"` без `=` — тоже инертно
(3 job-а при входе, 2 живых после 1 завершения). Эти пробы — НЕ факты о Zeebe,
а факты о невалидном синтаксисе; в раннер они не входят.

Правильный синтаксис (дочерний элемент, как в документации Camunda):
`...636342 vars={"item":"a","loopCounter":1,"results":[null,null,null]}` при входе —
3 job-а; завершён 1; ожидание 35 с (пережить 30-секундные локи сиблингов);
повторная активация: **0 живых сиблингов**; `s9dump`: `{"results":["a",null,null]}`.

Факт S9-1: условие `=numberOfCompletedInstances >= 1` **терминирует оставшиеся инстансы**.
Факт S9-2: частичный `results` (`["a",null,null]`, слоты терминированных — null)
**передаётся в родителя** (MI-тело завершается досрочно, но НЕ подавляет выход).

## Сводная таблица наблюдений (для матрицы паритета WO-C8-41 §2)

| # | Наблюдение Zeebe | Ключ паритета |
|---|---|---|
| S0 | без MI completion-переменные видны downstream | граница применимости элемент-локальности |
| S1-1 | при входе видны item/loopCounter, предсозданы results=[null×N], tu=null | предсоздание |
| S1-2 | w2 видит СВОЙ tu (гонки нет) | скоуп completion-переменных |
| S1-3 | results по индексу входа при обратном завершении | порядок агрегации |
| S1-4 | в dump только results (локалы дропнуты) | дроп локалов |
| S2a-1 | output mapping tu→tu виден в w2 | D1 (скоуп выходов) |
| S2b-1 | таргет маппинга предобъявлен null; tu в скоупе нет | предобъявление |
| S3-1/2 | item виден; x элемент-локален; lc маппится | input mapping |
| S3-3 | results=[null,null], утечек нет | агрегация + изоляция |
| S4 | 0 инстансов, results=[] | пустая коллекция |
| S5-1/2 | neverSet=null; results=[null,null] | необновлённый outputElement |
| S6-1/2 | sequential по одному; частичный results виден | sequential |
| S7-1/2 | job-ы отменены; results НЕ передан | interrupting boundary |
| S8a-1/2 | oitem виден; loopCounter затенён | вложенность скоупов |
| S8a-4/S8b-2 | innerResults/tresults не утекают | изоляция вложенной агрегации |
| S9-1/2 | сиблинги терминированы; частичный results передан | completionCondition |
