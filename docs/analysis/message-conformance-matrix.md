# Матрица сообщений (+ script/DMN) — 8.9 docs | Zeebe 8.5 live | ours | вердикт

WO-OPS-30 **Ф2в**. Поведение измерено на **живом Zeebe 8.5.0** (`camunda/zeebe:8.5.0`, стенд
`zbpm-cmp-zeebe`, gRPC `127.0.0.1:36500`) и на **нашем движке** (боевые пути
`RuntimeService`/`ActivityService` → handler'ы → outbox-тело задания → таблицы). Дизайн/
спецификация — **docs.camunda.io 8.9** (`components/concepts/messages`, `components/modeler/bpmn/
message-events`).

**Один источник правды на обе стороны:** список кейсов
`zorrobpm-engine/src/test/resources/message-cases.json` (11 кейсов: модель BPMN + шаги +
ожидания обеих сторон + колонка «8.9 docs»). Его читают и живой оракул
(`docs/analysis/c8-oracle/messages.js`), и наш JUnit
(`MessageConformanceIntegrationTests`). BPMN лежит в
`zorrobpm-engine/src/test/resources/message/*.bpmn`, копии — в
`docs/analysis/c8-oracle/scenarios/f2v-*/process.bpmn` (генерирует `node messages.js --sync`),
при каждом прогоне оракул сверяет их **побайтово** — иначе «оба движка» мерили бы разные модели.

**Ожидания перенесены из живого 8.5** (G-Q: источник ожиданий — оракул, не наша реализация).
В трёх известных расхождениях ожидание фиксирует **наше** измеренное поведение
(характеризация расхождения), а измеренное значение живой стороны лежит рядом в
`expect.observations[].liveReference` (список расхождений — в `expect.knownDifference`).

**Итог (11 кейсов).**
* **8 кейсов — `match`**: старт по сообщению, публикация «в пустоту», публикация до деплоя
  (message start без ключа и С ключом), бросок и приём сообщения, корреляция по ключу,
  прерывающая граница, script task.
* **3 кейса — `ours differs`**: буферизация (R4), непрерывающая граница (R1 — тело задания),
  DMN-вызов (R2).

| № | кейс | 8.9 docs | Zeebe 8.5 live | ours | вердикт |
|---|---|---|---|---|---|
| 1 | `f2v-msg-start` | 8.9: сообщение публикуется через PublishMessage; подписка message start event создаёт инстанс; переменные сообщения попадают в скоуп инстанса | `deploy`: deployOk<br>`publish`: published<br>`observeJob`(`afterStart`): type=f2vAfterStart vars={"caseTag":"f2v-msg-start","payload":"hello"} jobObserved<br>`completeJob`(`afterStart`): type=f2vAfterStart completed<br>состояние инстанса: не читается на 8.5 (Gateway API) | `deploy`: deployOk<br>`publish`: correlated=0 started=1 published<br>`observeJob`(`afterStart`): type=f2vAfterStart vars={"caseTag":"f2v-msg-start","payload":"hello"} jobObserved<br>`completeJob`(`afterStart`): type=f2vAfterStart completed<br>состояние инстанса: у нас читается | **match** |
| 2 | `f2v-msg-unknown-name` | 8.9: PublishMessage с timeToLive — сообщение с TTL>0 буферизуется на брокере до истечения TTL; при TTL=0 (по умолчанию) сообщение отбрасывается, если подписки нет | `deploy`: deployOk<br>`publish`: published<br>`observeJob`(`afterStart`): type=f2vAfterStart noJob<br>состояние инстанса: не читается на 8.5 (Gateway API) | `deploy`: deployOk<br>`publish`: correlated=0 started=0 published<br>`observeJob`(`afterStart`): type=f2vAfterStart noJob<br>состояние инстанса: у нас читается | **match** |
| 3 | `f2v-msg-publish-before-deploy` | 8.9: буферизация сообщений (message buffering) — сообщение с TTL>0, опубликованное до появления подписки, живёт на брокере до TTL и доставляется, когда подписка откроется; при TTL=0 сообщение отбрасывается | `publish`: published<br>`deploy`: deployOk<br>`observeJob`(`afterEarly`): type=f2vAfterEarly noJob<br>состояние инстанса: не читается на 8.5 (Gateway API) | `publish`: correlated=0 started=0 published<br>`deploy`: deployOk<br>`observeJob`(`afterEarly`): type=f2vAfterEarly noJob<br>состояние инстанса: у нас читается | **match** |
| 4 | `f2v-msg-buffered-catch` | 8.9: сообщение с TTL>0 буферизуется на брокере и доставляется, когда откроется подписка с тем же ключом корреляции; при TTL=0 буферизации нет | `deploy`: deployOk<br>`publish`: published<br>`observeJob`(`afterCatch`): type=f2vAfterBuffered vars={"caseTag":"f2v-msg-buffered-catch","orderId":"ORD-BUF","payload":"early"} jobObserved<br>состояние инстанса: не читается на 8.5 (Gateway API) | `deploy`: deployOk<br>`publish`: correlated=0 started=0 published<br>`observeJob`(`afterCatch`): type=f2vAfterBuffered noJob<br>состояние инстанса: у нас читается | **ours differs** |
| 5 | `f2v-msg-buffered-start-key` | 8.9: сообщение с TTL>0 буферизуется на брокере; для message start новая инстанция создаётся, если нет активной инстанса с тем же ключом | `publish`: published<br>`deploy`: deployOk<br>`observeJob`(`afterStart`): type=f2vAfterBufferedStartKey noJob<br>состояние инстанса: не читается на 8.5 (Gateway API) | `publish`: correlated=0 started=0 published<br>`deploy`: deployOk<br>`observeJob`(`afterStart`): type=f2vAfterBufferedStartKey noJob<br>состояние инстанса: у нас читается | **match** |
| 6 | `f2v-msg-throw-catch` | 8.9: бросок сообщения из процесса (intermediate message throw event) публикует сообщение; в 8.9 бросок — элемент с zeebe:publishMessage | `deploy`: deployOk<br>`observeJob`(`throw1`): type=f2vThrow vars={"orderId":"ORD-1"} jobObserved<br>`completeJob`(`throw1`): type=f2vThrow completed<br>`publish`: published<br>`observeJob`(`afterCatch`): type=f2vAfterCatch vars={"caseTag":"f2v-msg-throw-catch","orderId":"ORD-1","payload":"late"} jobObserved<br>состояние инстанса: не читается на 8.5 (Gateway API) | `deploy`: deployOk<br>`observeJob`(`throw1`): type=f2vThrow vars={"orderId":"ORD-1"} jobObserved<br>`completeJob`(`throw1`): type=f2vThrow completed<br>`publish`: correlated=1 started=0 published<br>`observeJob`(`afterCatch`): type=f2vAfterCatch vars={"caseTag":"f2v-msg-throw-catch","orderId":"ORD-1","payload":"late"} jobObserved<br>состояние инстанса: у нас читается | **match** |
| 7 | `f2v-msg-catch-key` | 8.9: подписка с correlationKey будится только сообщением с тем же ключом; подписка без ключа — любым сообщением с тем же именем | `deploy`: deployOk<br>`publish`: published<br>`observeJob`(`afterCatch`): type=f2vAfterCatchKey noJob<br>`publish`: published<br>`observeJob`(`afterCatch`): type=f2vAfterCatchKey vars={"caseTag":"f2v-msg-catch-key","orderId":"ORD-1","payload":"right"} jobObserved<br>состояние инстанса: не читается на 8.5 (Gateway API) | `deploy`: deployOk<br>`publish`: correlated=0 started=0 published<br>`observeJob`(`afterCatch`): type=f2vAfterCatchKey noJob<br>`publish`: correlated=1 started=0 published<br>`observeJob`(`afterCatch`): type=f2vAfterCatchKey vars={"caseTag":"f2v-msg-catch-key","orderId":"ORD-1","payload":"right"} jobObserved<br>состояние инстанса: у нас читается | **match** |
| 8 | `f2v-msg-boundary` | 8.9: boundary message event (cancelActivity=true) прерывает хост и уводит поток по ветке границы | `deploy`: deployOk<br>`observeJob`(`host`): type=f2vHost vars={"orderId":"ORD-2"} jobObserved<br>`publish`: published<br>`observeJob`(`afterBoundary`): type=f2vAfterBoundary vars={"caseTag":"f2v-msg-boundary","orderId":"ORD-2","payload":"cancel"} jobObserved<br>`observeJob`(`host`): type=f2vHost noJob<br>состояние инстанса: не читается на 8.5 (Gateway API) | `deploy`: deployOk<br>`observeJob`(`host`): type=f2vHost vars={"orderId":"ORD-2"} jobObserved<br>`publish`: correlated=1 started=0 published<br>`observeJob`(`afterBoundary`): type=f2vAfterBoundary vars={"caseTag":"f2v-msg-boundary","orderId":"ORD-2","payload":"cancel"} jobObserved<br>`observeJob`(`host`): type=f2vHost noJob<br>состояние инстанса: у нас читается | **match** |
| 9 | `f2v-msg-boundary-noninterrupting` | 8.9: boundary message event (cancelActivity=false) создаёт параллельную ветку, хост продолжает работу | `deploy`: deployOk<br>`observeJob`(`host`): type=f2vHostNI vars={"orderId":"ORD-3"} jobObserved<br>`publish`: published<br>`observeJob`(`afterBoundary`): type=f2vAfterBoundaryNI vars={"caseTag":"f2v-msg-boundary-noninterrupting","orderId":"ORD-3","payload":"spawn"} jobObserved<br>`observeJob`(`host`): type=f2vHostNI vars={"caseTag":"f2v-msg-boundary-noninterrupting","orderId":"ORD-3","payload":"spawn"} jobObserved<br>состояние инстанса: не читается на 8.5 (Gateway API) | `deploy`: deployOk<br>`observeJob`(`host`): type=f2vHostNI vars={"orderId":"ORD-3"} jobObserved<br>`publish`: correlated=1 started=0 published<br>`observeJob`(`afterBoundary`): type=f2vAfterBoundaryNI vars={"caseTag":"f2v-msg-boundary-noninterrupting","orderId":"ORD-3","payload":"spawn"} jobObserved<br>`observeJob`(`host`): type=f2vHostNI vars={"orderId":"ORD-3"} jobObserved<br>состояние инстанса: у нас читается | **ours differs** |
| 10 | `f2v-script-feel` | 8.9: script task с zeebe:script (FEEL) — исполняется воркером скриптов; результат кладётся в resultVariable | `deploy`: deployOk<br>`observeJob`(`afterScript`): type=f2vAfterScript vars={"amount":21,"doubled":42} jobObserved<br>состояние инстанса: не читается на 8.5 (Gateway API) | `deploy`: deployOk<br>`observeJob`(`afterScript`): type=f2vAfterScript vars={"amount":21,"doubled":42} jobObserved<br>состояние инстанса: у нас читается | **match** |
| 11 | `f2v-dmn-called` | 8.9: business rule task с zeebe:calledDecision — вызов DMN-решения, результат в resultVariable | `deploy`: deployOk<br>`observeJob`(`afterDecision`): type=f2vAfterDecision noJob<br>состояние инстанса: не читается на 8.5 (Gateway API) | `deploy`: deployOk<br>`observeJob`(`afterDecision`): type=f2vAfterDecision vars={"amount":21,"decision":20} jobObserved<br>состояние инстанса: у нас читается | **ours differs** |


## Корни расхождений (для Ф4)

### R4 — буферизация сообщений: в живом 8.5 буфер ЕСТЬ (для catch-подписок), у нас его нет
`f2v-msg-buffered-catch`: публикация с ключом корреляции `ORD-BUF` и `timeToLive=60000`
**ДО старта инстанса** (подписки на catch ещё нет).
* **Camunda 8.5.0 (живая)**: сообщение **буферизуется** и доставляется, когда откроется
  подписка → задание появляется с переменными сообщения
  (`{"caseTag":…,"orderId":"ORD-BUF","payload":"early"}`). То же в документации 8.5/8.9:
  «The message is correlated during the lifetime of the message if a matching subscription is
  active».
* **У нас**: публикация до появления подписки **теряется** — в API публикации **нет параметра
  `timeToLive`** (`PublishMessageDTO`: `messageName`/`correlationKey`/`processInstanceId`/
  `variables`), буфера нет; в наблюдении видно прямо: `timeToLiveRequested=60000` против
  `timeToLiveSupported=false`, `correlated=0`, `subscriptions=[]` — подписки на момент публикации
  не было, и сообщение исчезло. Инстанс остаётся ждать на catch (`catch1` — CREATED).
* **Границы буфера в 8.5 (измерены, а не выведены из документации):** буфер работает для
  **catch-подписки**; для **message start** — нет, и БЕЗ ключа (`f2v-msg-publish-before-deploy`),
  и С ключом (`f2v-msg-buffered-start-key` — контрольный кейс, обе стороны `noJob` → match).
  Различие «буферизуется / не буферизуется» — **по типу события**, а не по наличию ключа; для
  catch ключ в 8.5 обязателен (деплой модели с catch-сообщением без непустого `correlationKey`
  отвергается), так что «работает с ключом» — следствие обязательности ключа, а не условие буфера.
* **Наше «теряем так же» на пути message start — по ДРУГОЙ причине:** в 8.5 сообщение не
  буферизуется (буфера у message start вовсе нет), а у нас его нет вообще — как TTL, так и
  буфера; совпадение наблюдаемого результата не означает совпадения причины.
* Вывод: **ours differs** (R4) — у нас нет ни TTL, ни буфера; если продуктовая семантика
  «сообщение, опубликованное до открытия catch-подписки, не теряется» нужна — отдельная задача
  (Ф4), решение за владельцем.

### R1 — тело задания: снимок на момент диспетчеризации против переменных скоупа на момент активации
`f2v-msg-boundary-noninterrupting`: у Camunda задание читает переменные скоупа **в момент
активации** (воркер получает `{"caseTag":…,"orderId":…,"payload":"spawn"}` — с переменными
сообщения), у нас тело задания — **снимок на момент диспетчеризации** (`{"orderId":"ORD-3"}`).
Следствие для потребителя: переменная, изменившаяся после постановки задания в очередь, до
воркера не доезжает. Расходится ровно один шаг (второе наблюдение `host`); остальные девять
наблюдений кейса совпадают.

### R2 — DMN: у нас вызов решения есть, в живом 8.5 модель принимается, но решение НЕ вычисляется
`f2v-dmn-called`: модель с `zeebe:calledDecision` **принимается** деплоем 8.5 (XSD это
элемент знает), но инстанс **останавливается** на business rule task: задания не появляется,
`decision` не считается. У нас DMN вычисляется (`decision=20` при `amount=21`) и поток идёт
дальше. Расхождение — «ours differs» (у нас фича есть, в живом 8.5 — нет). В документации 8.9
DMN-вызов есть, так что это **наша сверх-возможность**, а не дефект; решение за владельцем.

## Замечания по методу (честно)

* **Состояние инстанса на живой стороне не читается**: Gateway API 8.5 не отдаёт его (Operate вне
  стенда), поэтому шаг `observeState` сравнивается только на нашей стороне и в вердикт не входит.
  Итог по состоянию судится по наличию следующего задания.
* **Наблюдение «есть ли задание» на живой стороне = активация задания**; активация блокирует
  задание на время таймаута, поэтому для повторного наблюдения того же элемента таймаут
  активации короткий (1.5 с) — иначе «нет задания» было бы ЛОЖНЫМ (второе наблюдение увидело бы
  заблокированное задание, а не отсутствующее). На нашей стороне такой блокировки нет: тело
  задания лежит в outbox.
* **Инстанс, созданный публикацией сообщения, на живой стороне заранее неизвестен** — задание
  фильтруется по метке кейса (`caseTag` в переменных сообщения), иначе можно было бы взять
  задание чужого инстанса, оставшееся на стенде от прошлого прогона. Перед кейсом остатки
  заданий этого типа сливаются.
* **Один прогон из шести показал иное** наблюдение `host` (тело задания с переменными
  сообщения) — причина найдена: выбор строки активности шёл через `findFirst()` по
  `findAll()` (порядок JPA не задан), а строк активности элемента может быть больше одной.
  Выбор сделан детерминированным (самая старая живая строка) — после этого 5/5 прогонов
  стабильны.
* **Наши ожидания — из живого 8.5**, поэтому тест краснеет, если расхождение появится
  (проверено мутациями прода, §PRE-DONE отчёта).
* **Порядок шагов в `expect.observations` сгруппирован по (шаг, элемент)**, а не повторяет
  порядок живых наблюдений: связывание идёт по ключу (шаг, элемент, номер повтора), поэтому
  порядок в списке ожиданий на результат не влияет (проверено reviewer раунд 2).
* **Живые измерения лежат в gitignored-файлах** (`scenarios/**/result-8.5.json` и
  `zorrobpm-engine/target/message-observed.json`) — в ветку не попадают; при смене стенда их
  придётся переснять (команда в отчёте).

## Контракты воркеров-потребителей (п.4 инвентаря)

Воркеры консьюмера (Go) — вне репозитория, поэтому контракт проверяется с нашей стороны
(`ConsumerWorkerContractIntegrationTests`, 3 теста):
1. **вход по маппингу** — `source → target` с сохранением типа (`JSON` остаётся `JSON`,
   `STRING` остаётся `STRING`); проверяется ТЕЛО ЗАДАНИЯ (то, что реально получает воркер);
2. **слияние результата** — переменные completion попадают в скоуп и видны следующей задаче
   (в т.ч. переменная типа `JSON`);
3. **ошибка → инцидент** — отказ воркера при исчерпанном бюджете поднимает инцидент на
   элементе с текстом отказа.
