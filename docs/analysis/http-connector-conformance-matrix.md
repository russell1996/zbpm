# Матрица HTTP-коннектора — 8.9 docs | Camunda Connectors 8.5.9 live | ours | вердикт

WO-OPS-30 **Ф2б**. Поведение измерено на **живом Camunda Connectors 8.5.9**
(`camunda/connectors-bundle:8.5.9`, стенд `zbpm-cmp-connectors`, воркер `io.camunda:http-json:1`
на живом Zeebe 8.5.0 `zbpm-cmp-zeebe`, gRPC `127.0.0.1:36500`) и на **нашем коннекторе**
(`CamundaHttpJsonWorker` → `HttpConnectorWorker` → `HttpSsrfGate`, боевые классы, настоящий
`java.net.http.HttpClient`). Дизайн/спецификация — **docs.camunda.io 8.9**
(`components/connectors/protocol/rest`) + шаблон `io.camunda.connectors.HttpJson.v2`.

**Один источник правды на обе стороны:** список кейсов
`zorrobpm-http-connector/src/test/resources/http-connector-cases.json` (33 кейса: тело задания в
диалекте Camunda + taskHeaders + ожидания обеих сторон + колонка «8.9 docs»). Его читают и
оракул (`docs/analysis/c8-oracle/http-connector.js`), и наш JUnit
(`HttpConnectorConformanceIntegrationTests`). BPMN для живой стороны **генерируется** из этого
же списка (`docs/analysis/c8-oracle/scenarios/f2b-*/process.bpmn` — копии в дереве), поэтому
«оба движка меряют разные модели» исключено конструктивно.

**Одна мок-цель на обе стороны.** В кампанийном прогоне обе стороны бьют в ОДИН контейнер
`zbpm-cmp-mock` (в сети `zbpm-cmp_default`): коннектор Camunda достаёт его по имени, наш
maven-контейнер — тоже по имени. Значит URL совпадает, и журнал мока показывает, что реально
ушло в сеть. В CI (внешних контейнеров нет) наша сторона поднимает встроенный Java-мок с ТОЙ ЖЕ
таблицей маршрутов (таблица читается из того же файла кейсов — «мок значит разное для сторон»
исключено).

**Итог: 29 кейсов — поведение совпадает с ожиданием из документации 8.9; 4 кейса —
`docs≠8.5` (живой 8.5.9 ведёт себя иначе, чем описано в 8.9).** Со стороны «ours» расхождений
**11** (из них 4 — сознательные «by design», 7 — кандидаты в Ф4).

Особенности измерения (честно):

* **живая сторона и наша сторона меряются РАЗНЫМИ слоями.** У Camunda коннектор — воркер
  Zeebe (его семантика: завершить/уронить job). У нас коннектор — воркер RabbitMQ, а ошибка
  уходит в движок через `ActivityService.throwServiceTaskError` (код BPMN-ошибки). Полный путь
  «ошибка → инцидент движка» — тема Ф2а (протокол job), здесь не измеряется.
* **`failed` на живой стороне = «задание упало и будет повторено»**, а не «ошибка у пользователя».
  Наблюдается по отсутствию следующего задания (dump-job не появился) при факте HTTP-вызова
  (это видно в журнале мока).
* **текст ошибки живого коннектора в логе контейнера не читается** (лог `zbpm-cmp-connectors`
  ротируется и перебит шумом `ProcessDefinitionImporter`), поэтому причина падения
  восстановлена по исходникам 8.5.9 (`HttpInteractionService`, `HttpService`, `Authentication`)
  и по наблюдению «был HTTP-вызов / не было».
* **живой коннектор держит cookie-jar**: `set-cookie` из ответа `/f2b/headers` уходит в
  СЛЕДУЮЩИЙ запрос живой стороны (заголовок `cookie`). У нас (JDK `HttpClient` без
  cookie-jar) — нет, и `set-cookie` вырезается из заголовков ответа. Это различие клиента,
  а не семантики вызова; в таблице отдельной строкой.
* **OAuth-ветка на 8.5.9 не доходит до HTTP-вызова**: коннектор падает на биндинге/валидации
  `authentication.*` (в `HttpCommonRequest` 8.5.9 нет `followRedirects`, а `OAuthAuthentication`
  требует `clientAuthentication`). Наш коннектор отвергает тип заранее — детерминированно.
* **тело запроса у живой стороны сериализуется по правилу «валидный JSON — как есть, иначе
  JSON-строка»** (измерено в трёх точках: `{"temp":25}` → как есть; `zorro-f2b-body` при
  `text/plain` → `"zorro-f2b-body"`; `zorro-f2b-body` при `application/json` → то же с кавычками).
  У нас тело всегда уходит как есть.

| # | кейс | 8.9 docs | Connectors 8.5.9 live | ours | вердикт / находка |
|---|------|----------|----------------------|------|------------------|
| 1 | `f2b-get-200-json` — GET 200 JSON | коннектор завершает задание; в контексте ответа `status`/`body`/`headers` | **completed**, HTTP-вызов ушёл, ответ прочитан | **success**: `http.status=200`, `http.body` (JSON) | `match` |
| 2 | `f2b-post-body` — POST, тело-строка, `text/plain` | Request body — FEEL-выражение | **completed**, но тело ушло как JSON-**строка** `"zorro-f2b-body"` | **success**, тело ушло **как есть**: `zorro-f2b-body` | `ours differs` — **R1**: тело не-JSON у Camunda JSON-экранируется, у нас нет |
| 3 | `f2b-post-json-body` — POST, тело-JSON | тело сериализуется в JSON | **completed**, тело ушло как есть `{"temp":25}` | **success**, тело как есть | `match` (подтверждает правило из №2) |
| 4 | `f2b-post-json-ct-text-body` — `application/json` + текстовое тело | то же | **completed**, тело ушло как JSON-строка | **success**, тело как есть | `ours differs` — **R1** (тот же корень, другой Content-Type) |
| 5 | `f2b-method-put` — PUT с телом | метод из списка GET/POST/DELETE/PATCH/PUT | **completed**, тело — JSON-строка | **success**, тело как есть | `ours differs` — **R1** |
| 6 | `f2b-method-delete` — DELETE без тела | DELETE в списке методов | **completed**, тела нет | **success**, тела нет | `match` |
| 7 | `f2b-headers-custom` — произвольные заголовки | headers — FEEL-карта в запросе | **completed**, `origin` и `x-f2b-case` дошли | **success**, те же заголовки | `match` |
| 8 | `f2b-query-parameters` — query-параметры | queryParameters — FEEL-карта в URL | **completed**, `q=Berlin&units=metric` | **success**, те же пары | `match` |
| 9 | `f2b-auth-basic-inline` — Basic в модели | Username/Password в модели (в проде — `{{secrets.X}}`), шлёт `Authorization: Basic` | **completed**, `Authorization: Basic em9ycm8tdXNlcjp6b3Jyby1wYXNz` | **bpmnError `HTTP_CONNECTOR_CONFIG`** ДО сокета (инлайн-секрет в модели — стоп) | `ours differs` — **R2** (by design: другой механизм секрета, `http.authRef`) |
| 10 | `f2b-auth-bearer-inline` — Bearer в модели | Bearer в модели, `Authorization: Bearer` | **completed**, `Authorization: Bearer zorro-token` | **bpmnError `HTTP_CONNECTOR_CONFIG`** ДО сокета | `ours differs` — **R2** |
| 11 | `f2b-auth-apikey-inline` — API key в модели | имя/значение ключа в модели, location — headers или query | **completed**, `X-Api-Key: zorro-key` | **bpmnError `HTTP_CONNECTOR_CONFIG`** ДО сокета | `ours differs` — **R2** |
| 12 | `f2b-auth-oauth` — OAuth 2.0 client-credentials | отдельный flow: token endpoint, client id/secret, scopes | **падает ДО HTTP-вызова** (на 8.5.9 нет `followRedirects`, а `OAuthAuthentication` требует `clientAuthentication`; HTTP-запроса не было) | **bpmnError `HTTP_CONNECTOR_CONFIG`** (OAuth не поддержан) | `docs≠8.5` + `ours differs` — **R3**: обе стороны падают, но по-разному и без HTTP-вызова |
| 13 | `f2b-ours-authref-basic` — наша диалекта (`http.authRef`) | аналога нет: у Camunda секрет резолвится из secret store по `{{secrets.X}}` внутри `authentication.*` | **completed** — живая сторона нашу диалекту просто игнорирует (читает только `url`/`method`/`authentication`/…) и делает обычный GET | **success**, `Authorization: Basic em9ycm8tdXNlcjp6b3Jyby1wYXNz` ушёл | `ours differs` — **R2** (наша диалекта; живой аналогии нет — сравнения не существует) |
| 14 | `f2b-result-variable` — taskHeader `resultVariable` | полный ответ выгружается в переменную процесса | **completed**, переменная `response` = `{status, headers, body}` | **bpmnError `HTTP_CONNECTOR_CONFIG`** ДО сокета (мы отвечаем тройкой `http.*`) | `ours differs` — **R4** (by design, но ломает ожидание автора шаблона) |
| 15 | `f2b-result-expression` — taskHeader `resultExpression` | FEEL-контекстное выражение над `response` | **completed**, выражение исполнено: переменная `probe` = `zorro-f2b` | **success**, ответ в `http.body`; выражение **НЕ исполняется** (WARN в лог) | `ours differs` — **R5** (by design: второй движок FEEL не заводим) |
| 16 | `f2b-error-expression` — taskHeader `errorExpression` | выражение получает `error.code`/`error.variables` и может вернуть `bpmnError(...)` | **bpmnError** — ошибка 404 превращена в BPMN-ошибку по выражению автора | **bpmnError `HTTP_CONNECTOR_CONFIG`** (сам заголовок отвергнут) | `ours differs` — **R6**: выражения ошибок не исполняем; **коды разные** (важно для границы ошибок автора) |
| 17 | `f2b-status-404` — 404 без `errorExpression` | при ошибке коннектор бросает исключение, задание **ретраится** | **failed** (job упал, будет повтор) | **bpmnError `HTTP_404`** — детерминированно, **ретраев нет** | `ours differs` — **R7** (важнейшее: не-2xx у нас не ретраится) |
| 18 | `f2b-status-500` — 500 без `errorExpression` | то же | **failed** (ретраится) | **bpmnError `HTTP_500`** — без ретраев | `ours differs` — **R7** |
| 19 | `f2b-redirect-default` — 302, `followRedirects` не задан | при выключенном followRedirects коннектор **возвращает исходный 3xx** вместе с `Location` | **failed** — на 8.5.9 `followRedirects` **нет вовсе**, а 3xx → исключение → job упал | **bpmnError `HTTP_CONNECTOR_CONFIG`** («too many redirects (max 0)») | `docs≠8.5` + `ours differs` — **R8**: у нас ЛЮБОЙ 3xx — ошибка конфигурации, у Camunda 8.9 — нормальный ответ, у 8.5.9 — падение job'а |
| 20 | `f2b-follow-redirects-true` — `followRedirects=true` | при включённом — коннектор сам следует за 3xx и возвращает финальный ответ | **failed** (на 8.5.9 входа `followRedirects` не существует → 3xx → падение) | **bpmnError `HTTP_CONNECTOR_CONFIG`** (бюджет редиректов — админская ручка, из BPMN не расширить) | `docs≠8.5` + `ours differs` — **R8** |
| 21 | `f2b-empty-body` — 200 с пустым телом | body — тело ответа (в 8.9 ещё нет режима «As JSON/As text») | **completed**, 200 | **success**, `http.status=200` | `match` |
| 22 | `f2b-text-plain` — 200 `text/plain` | body — тело ответа | **completed**, 200 | **success**, `http.body` типа **STRING** = `zorro-f2b-text` | `match` (тип выводится из Content-Type: json → JSON, иначе STRING) |
| 23 | `f2b-medium-body` — 100 КиБ тела | ограничений на размер в 8.9 нет | **completed** (ответ прочитан, тело не отдавалось в переменные) | **success**, 100 КиБ < капа 1 МиБ | `match` |
| 24 | `f2b-large-body` — 2 МиБ тела | то же | **completed** (тело прочитано, в переменные не клалось) | **bpmnError `HTTP_CONNECTOR_CONFIG`** — кап 1 МиБ | `ours differs` — **R9** (by design: кап вместо молчаливого усечения; в отличие от живого — ДЕТЕРМИНИРОВАННО) |
| 25 | `f2b-medium-body-result-variable` — 100 КиБ + `resultVariable` | ответ целиком в переменную | **completed**, переменная `response` несёт 100 КиБ | **bpmnError `HTTP_CONNECTOR_CONFIG`** (resultVariable отвергнут) | `ours differs` — **R4** |
| 26 | `f2b-512kib-body-result-variable` — 512 КиБ + `resultVariable` | то же | **completed** | **bpmnError `HTTP_CONNECTOR_CONFIG`** | `ours differs` — **R4** |
| 27 | `f2b-1mib-body-result-variable` — 1 МиБ + `resultVariable` | то же | **completed** (1 МиБ влезает) | **bpmnError `HTTP_CONNECTOR_CONFIG`** | `ours differs` — **R4** |
| 28 | `f2b-large-body-result-variable` — 2 МиБ + `resultVariable` | то же | **failed**: HTTP-вызов был, но **завершение задания с 2 МиБ не влезает в брокер** → job падает и ретраится | **bpmnError `HTTP_CONNECTOR_CONFIG`** | `docs≠8.5` + `ours differs` — **R10**: у Camunda отказ САМОГО ЗАВЕРШЕНИЯ (ретраится до бесконечности), у нас — детерминированная ошибка. Граница между 1 МиБ и 2 МиБ (замерено 100 КиБ / 512 КиБ / 1 МиБ / 2 МиБ) |
| 29 | `f2b-read-timeout` — ответ дольше `readTimeout` | read timeout — сколько ждать данных; 0 = бесконечно | **failed** (job упал → ретраится) | **transient** (исключение → FAILED → ретраи движка) | `match` (обе стороны: транзиентный отказ с ретраями) |
| 30 | `f2b-timeout-zero` — `readTimeoutInSeconds=0` | 0 = неограниченное ожидание | **completed** (0 = бесконечно) | **bpmnError `HTTP_CONNECTOR_CONFIG`** (таймаут обязан быть в 1..max) | `ours differs` — **R11** (by design: свой кап, чтобы воркер не висел вечно) |
| 31 | `f2b-network-error` — сервер рвёт соединение | при сетевой ошибке коннектор бросает исключение, задание ретраится | **failed** | **transient** (исключение → ретраи) | `match` |
| 32 | `f2b-response-headers` — заголовки ответа (+`set-cookie`) | в контексте ответа доступны `headers` | **completed**, `x-f2b-probe` в ответе; `set-cookie` **тоже** виден отправителю | **success**, `x-f2b-probe` есть; `set-cookie` **вырезан** | `ours differs` — **R12** (by design, безопасность) + **R13**: живой клиент носит `cookie` в следующих запросах, наш — нет |
| 33 | `f2b-ssrf-private-host` — приватный хост, `allow-private-networks=false` | у Camunda SSRF-защиты нет (кроме прокси в Self-Managed) | **completed**, запрос ушёл | **bpmnError `HTTP_CONNECTOR_CONFIG`** — SSRF-гейт отверг ДО сокета | `ours differs` — **R14** (by design: наша защита, у Camunda её нет) |

## Расхождения по корням (вход в Ф4 — баг-WO, НЕ чинится в кампании)

| корень | что | где | последствие для пользователя |
|--------|-----|-----|---------------------------|
| **R1** | тело запроса: у Camunda не-JSON строка JSON-экранируется (`"text"`), у нас уходит как есть | `HttpConnectorWorker.buildRequest` (наш) vs `HttpRequestMapper` 8.5.9 | API получает другое тело, чем ожидает автор; у нас — «как описал автор» |
| **R2** | аутентификация: у Camunda `authentication.*` в модели (секрет — `{{secrets.X}}`), у нас — `http.authRef` на серверный секрет; любой `authentication.*` кроме `.type` мы отвергаем | `CamundaHttpJsonWorker.rejectUnsupportedAuthInputs` | элемент, смоделированный штатным шаблоном Camunda, у нас не запустится (детерминированная ошибка вместо запроса) |
| **R3** | OAuth: на 8.5.9 коннектор падает на биндинге до HTTP-вызова | живая сторона | наш отказ детерминирован и без сети — в этом мы лучше; но поддержки OAuth нет ни там, ни у нас |
| **R4** | `resultVariable` не поддержан (ответ отдаётся тройкой `http.*`) | `CamundaHttpJsonWorker.rejectUnsupportedHeaders` | автор, заполнивший «Result variable» в шаблоне Camunda, получит отказ вместо результата |
| **R5** | `resultExpression` не исполняется (WARN + игнор) | `CamundaHttpJsonWorker.warnResultExpression` | автор, распаковывающий ответ FEEL-выражением, не получит свои переменные |
| **R6** | `errorExpression` не исполняется; коды BPMN-ошибок не совпадают с Camunda | `CamundaHttpJsonWorker.rejectUnsupportedHeaders` | граница ошибок автора не сработает как задумано |
| **R7** | **не-2xx**: у Camunda — падение job'а с ретраями; у нас — детерминированная BPMN-ошибка `HTTP_<status>` без ретраев | `HttpConnectorWorker.execute` (throwServiceTaskError) | при нестабильном вышестоящем API мы НЕ повторим запрос, а Camunda повторит |
| **R8** | **3xx**: у нас любой редирект при `max-redirects=0` — ошибка конфигурации; у Camunda 8.9 — нормальный ответ, у 8.5.9 — падение job'а | `HttpConnectorWorker.sendWithRedirects` | автор, ожидающий код ответа 302 в переменной, у нас получит ошибку |
| **R9** | кап тела ответа 1 МиБ (у Camunda — практический предел между 1 и 2 МиБ, и он бьёт по ЗАВЕРШЕНИЮ job'а) | `HttpConnectorProperties.maxResponseBytes` | большой ответ — детерминированная ошибка (у нас) против бесконечных ретраев (у Camunda) |
| **R10** | у Camunda отказ самого завершения задания при большом payload | живая сторона | при 2 МиБ в переменной job ретраится до бесконечности — у нас такой петли нет |
| **R11** | `timeout=0` («бесконечно») у нас — ошибка конфигурации | `HttpConnectorWorker.timeoutSeconds` | автор не может задать бесконечное ожидание |
| **R12** | `set-cookie` вырезается из заголовков ответа | `HttpConnectorWorker.filterResponseHeaders` | (by design, безопасность) |
| **R13** | живой клиент носит `cookie` в следующие запросы, наш — нет | клиенты (Google HTTP client vs JDK HttpClient) | различие поведения клиента, не семантики коннектора |
| **R14** | SSRF-гейт: приватные адреса по умолчанию запрещены, allowlist обязателен | `HttpSsrfGate` | (by design, наша защита; у Camunda её нет) |

## Что совпало (по кейсам)

Метод/URL/заголовки/query-параметры, тело-JSON, DELETE/PUT без оговорок, чтение ответа,
200 с пустым телом, `text/plain` → STRING, 100 КиБ, таймаут чтения и сетевая ошибка
(обе стороны — транзиентный отказ с ретраями), `resultExpression` на 200 (у нас — ответ в
`http.body`, живая — распакованные переменные), `errorExpression` на 404 (у нас — отказ по
конфигурации, живая — BPMN-ошибка по выражению автора).
