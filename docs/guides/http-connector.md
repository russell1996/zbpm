# HTTP/REST outbound-коннектор

Встроенный коннектор, который даёт BPMN-процессу вызвать внешний HTTP-сервис из
service-task с `jobType="zorrobpm:http"`. Появился в WO-ENG-31 (Фаза 2). Это НЕ
универсальный HTTP-клиент: за это отвечает воркер, работающий в отдельном модуле
`zorrobpm-http-connector`, и по умолчанию он **выключен и deny-all** — включать его
надо явно.

## Включение и allowlist

Всё живёт под префиксом `zorrobpm.http-connector.*` (в compose — переменные
`ZORROBPM_HTTP_CONNECTOR_*`):

| Свойство | Переменная окружения | Дефолт | Смысл |
|---|---|---|---|
| `enabled` | `..._ENABLED` | `false` | `false` → каждая задача детерминированно падает с `HTTP_CONNECTOR_DISABLED` |
| `allowed-hosts` | `..._ALLOWED_HOSTS` | пусто | **Пусто = deny-all.** Список через запятую: точное имя или суффикс через точку (`api.example.com` покрывает и `api.example.com`, и `*.api.example.com`) |
| `allow-private-networks` | `..._ALLOW_PRIVATE_NETWORKS` | `false` | `true` в prod-профиле — **ФАТАЛ на старте** |
| `max-response-bytes` | `..._MAX_RESPONSE_BYTES` | `1048576` | Превышение — BPMN-ошибка, не silent-truncate |
| `default-connection-timeout-seconds` | `..._CONNECTION_TIMEOUT` | `20` | Кап сверху — 120 |
| `default-read-timeout-seconds` | `..._READ_TIMEOUT` | `20` | Кап сверху — 300 |
| `max-redirects` | `..._MAX_REDIRECTS` | `0` | `0` = не следовать (зеркало `followRedirects=false` в Camunda) |
| `secrets-json` | `..._SECRETS_JSON` | пусто | Секреты одним JSON-объектом, см. ниже |

**Таймаут — общий на весь обмен** (NEW5-06): `readTimeout` ограничивает и получение
заголовков, и все хопы редиректа, и чтение тела. Медленный сервер не держит поток
воркера дольше этого срока.

**Редиректы** (если `max-redirects > 0`): каждый хоп заново проходит SSRF-гейт, и
**auth-заголовки снимаются при смене origin** (схема/хост/порт) — секрет не уходит
на второй хост, даже если он тоже в allowlist (NEW5-12).

## Входы (переменные BPMN-задачи)

| `http.*` | Обязателен | Пример | Что делает |
|---|---|---|---|
| `http.url` | да | `https://api.example.com/v1/pay` | цель вызова |
| `http.method` | нет | `GET` (по умолчанию) | `GET/POST/PUT/PATCH/DELETE` |
| `http.headers` | нет | `{"X-Tenant":"t1"}` | JSON-объект заголовков. Литеральные секреты здесь запрещены — только `http.authRef` |
| `http.queryParameters` | нет | `{"page":"2"}` | JSON-объект query-параметров |
| `http.body` | нет | `{"a":1}` | тело запроса (`POST/PUT/PATCH`) |
| `http.connectTimeout` / `http.readTimeout` | нет | `5` | перекрывают дефолт модели **в пределах капа** |
| `http.authType` | нет | `none` | `none` / `bearer` / `basic` / `apiKey` |
| `http.authRef` | при `authType != none` | `svc` | ИМЯ секрета, не сам секрет |

## Выходы (успех, задача завершается)

| Переменная | Тип | Смысл |
|---|---|---|
| `http.status` | LONG | код ответа |
| `http.headers` | JSON | заголовки ответа; `set-cookie` **всегда** отфильтрован |
| `http.body` | STRING/JSON | тело ответа (`JSON`, если content-type содержит `json`) |

Возвращённые воркером переменные уходят в SUCCESS-completion как есть
(`JobCompletionListener` → `ServiceTaskCompleteData.variables`), то есть становятся
выходами service-task без дополнительной обёртки: в `zeebe:ioMapping` их берут по
имени — `source=activityOutput(http.status)`, `target=httpStatus`.

## Ошибки

| BPMN-ошибка | Когда | Ретраи |
|---|---|---|
| `HTTP_CONNECTOR_DISABLED` | коннектор выключен | нет — детерминированно |
| `HTTP_CONNECTOR_CONFIG` | невалидный вход, неизвестный/битый `authRef`, превышен `max-response-bytes`, слишком много редиректов, редирект без `Location`, невалидный URL | нет — детерминированно, ретраить бессмысленно |
| `HTTP_<код>` | ответ не-2xx (404, 503, …) | нет — по решению CTO это явный исход, а не молчаливый success |
| инцидент движка | таймаут, обрыв соединения, нерезолвящийся хост | **да** — транзиентно, `FAILED` → ретраи/инцидент по политике движка |

У всех детерминированных ошибок в BPMN-переменных приходят `http.status` (если ответ
уже получен), `http.headers`, `http.body` и `error`.

## Секреты

Секрет — JSON-объект, **по имени** (`http.authRef` ссылается на имя, значение секрета
в модель не попадает):

```json
{"type": "bearer",  "token": "..."}
{"type": "basic",   "username": "...", "password": "..."}
{"type": "apiKey",  "name": "X-Api-Key", "value": "...", "in": "header"}
```

`in` — `header` (по умолчанию) или `query` (тогда секрет уходит в query-строку; для
allowlisted-сервисов, которые так требуют).

Два способа задать (NEW5-07 — раньше в compose-деплое не было ни одного, и
`authType != none` был недостижим без ручной правки compose):

1. **Одна переменная на всё** (рекомендуется для деплоя):
   ```bash
   ZORROBPM_HTTP_CONNECTOR_SECRETS_JSON='{"svc":{"type":"bearer","token":"..."}}'
   ```
2. **Поштучно** — `zorrobpm.http-connector.secrets.<name>` (удобно в обычном
   `application.properties`/`application-<профиль>.yml`; явное значение перекрывает
   значение из `secrets-json`).

Битый JSON в `secrets-json` роняет **старт** приложения (fail-fast), а не первый
HTTP-вызов в проде.

**Осознанная граница:** секрет в переменной окружения виден в `docker inspect` — так
же, как все остальные секреты проекта (JWT, пароль почты). Переход на смонтированный
файл секретов меняет топологию деплоя и оформлен как отдельное решение, а не сделал
молчаливый побочный эффект этого фикса.

## Безопасность

- **SSRF-гейт до первого байта в сокете**: схема, приватность/резерв IP, allowlist
  хоста; каждый хоп редиректа проверяется заново. `allow-private-networks=true` в
  prod — ФАТАЛ на старте.
- **Fail-closed дефолты**: выключен + deny-all.
- Литеральные секреты во входах (`http.auth`, `http.token`, `http.authorization`, …)
  отклоняются — только `authRef`.
- Коннектор включается оператором и работает только с заранее разрешёнными хостами;
  авторизацию на стороне сервиса он не заменяет (токен уходит, куда настроен).

## Ограничения

- Vault/внешний secret-store не подключён: секреты приходят из конфигурации или
  окружения (решение CTO п.6 для Фазы 2, отдельная задача на Vault).
- Ответ читается целиком в память в пределах `max-response-bytes`; стриминг и
  постраничный ответ не поддержаны.
- `PUT`/`PATCH`/`POST` отправляют `http.body` как строку; бинарная отправка (файл,
  form-data) не поддержана.
