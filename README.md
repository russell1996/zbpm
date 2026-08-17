# ZorroBPM CE

Лёгкий движок бизнес-процессов BPMN 2.0 на Spring Boot с **высокой совместимостью с Camunda 8** (реальные C8-BPMN-модели исполняются без правок файлов). Деплой BPMN-процессов, запуск экземпляров, выполнение внешней работы через брокер сообщений и управление пользовательскими задачами — через REST API и единый **SPA** (Operate/Tasklist/Cockpit в одном приложении, `zorrobpm-frontend`).

> ⚠️ **Статус — Community Edition, ранняя стадия.**
> Рабочий одноузловой движок с хорошим покрытием happy-path тестами. **Ещё не готов к промышленной эксплуатации:** рассчитан на запуск в **одном экземпляре** (таймеры и версионирование определений пока небезопасны при нескольких репликах). JWT-аутентификация для UI и Data-API встроена; обязательно смените JWT-секрет и пароль admin перед выходом в прод. См. [Ограничения](#ограничения-и-замечания-по-проду).

## Текущее состояние

<!-- ОБНОВЛЯЕТСЯ ПРИ КАЖДОМ МЕРЖЕ В master. Правило и порядок — governance/agents/README.md. -->

| | |
|---|---|
| **Обновлено** | 2026-08-17 |
| **Версия** | 0.7.17-SNAPSHOT |
| **Стадия** | стабилизация: закрываем находки внешнего аудита от 2026-08-07 |
| **Активных задач** | 11 (5 в очереди у исполнителя: ACL-5, ACL-6 + три хвоста; ещё 3 эпика HUB ждут решения) (см. [`governance/workorders/_index.md`](governance/workorders/_index.md)) |

**Последнее закрытое:** WO-ACL-4 (владелец обновляет модель изнутри процесса; ключ в XML обязан совпадать с целевым), WO-ACL-3 (новый процесс — заявкой с одобрением; одобрение делает отправителя владельцем одной транзакцией), WO-ACL-2 (роли перечислением, владелец управляет своим процессом; последнего OWNER больше не снять в два запроса), WO-ACL-1 (обычный пользователь наконец видит определения; runtime — по членству), WO-OPS-3-DISK (деплой не стартует на переполненном диске — в инциденте 28.07 падал и откат), WO-TEST-4 (`Rel4ConcurrencyTest` снова умеет падать — гонял копию гварда вместо прод-кода), WO-SEC-57 (ротируемые ключи подписи JWT — секрет можно сменить, не разлогинив всех), WO-OPS-5 (образы больше не копятся на деплой-хосте; защищены текущий, предыдущий и собираемый теги), WO-CLEAN-1 (убраны перегрузки `createTimerJob`, одна из которых разоружала регресс-тест REL-17), WO-PERF-3 (индекс под перевзвод boundary-таймера; осиротевшие job'ы больше не
копятся вечно — уборка батчами в ретеншене), WO-OPS-4a (vitest стал merge-гейтом, test-gate сопоставляет тесты модулям,
`ci/test-gate-selftest.sh` ловит ослабление самого гейта), WO-PERF-4 (jacoco за профилем `coverage`,
локальный прогон движка −18 %),
WO-REL-17 (конечный цикл `R<n>` boundary-таймера теперь исчерпывается), WO-REL-16 (очереди джоб
объявляются при деплое и старте, а не лениво), WO-SEC-50 (контейнеры под non-root, healthcheck,
лимиты), WO-SEC-48 (CSP сведён в один источник, убран `unsafe-eval`), WO-PERF-2 (N+1 в
page-мапперах, индексы на горячих колонках), WO-ENG-10.

**В работе / ждёт решения:**

| Задача | Состояние |
|---|---|
| WO-TEST-5 — `TokenServiceAlgVerificationTest` не может упасть | 🔧 в пуле, 1-я — защита от alg-confusion стоит на слепом тесте |
| WO-PERF-5 — покрытие строится и выбрасывается | 🔧 в пуле, 2-я — **решено: публиковать** |
| WO-SEC-51 — блокирующий CVE-гейт | 🔧 в пуле, 3-я — **решено: валим на CVSS ≥ 9.0, HIGH предупреждением** |
| WO-SEC-49 — fail-fast на дефолтных кредах | 🟡 код готов на ветке; ждёт строки `ZORROBPM_ENFORCE_DB_CREDS=false` в host `.env` |
| WO-OPS-4b — гейты прод-доставки | ⬜ деплой с любой ветки, без health-check. Ведёт CTO: нужны пуш и реальные выкатки |
| WO-SEC-53 — housekeeping мелких находок | ⬜ есть черновик на ветке, без DoD |
| WO-SEC-52 — глобальный rate-limit | ⬜ нужно архитектурное решение |
| **Эпик ACL** — разграничение доступа ([ADR-8](docs/adr/ADR-8-self-service-ownership.md)) | 🔧 ACL-1…4 смержены, в очереди ACL-5 и ACL-6 |
| **Эпик HUB** — Agent Hub для Telegram ([ADR-9](docs/adr/ADR-9-agent-hub-telegram.md)) | 📝 3 задачи написаны; перед HUB-3 нужно решение по показу аргументов инструментов |

**Закрыты без реализации 2026-08-15:** WO-AUD-8c и WO-AUD-8f (чистый рефакторинг без функционального
выигрыша; 8c брошен самим исполнителем как тупик по verify-цепочке), WO-OPS-3-cve (обе проблемы
решены: падение `cve:scan` исправлено, артефакты замерены — 207 МБ из 213 против 813 из 822 в задаче).

**Известные пробелы инфраструктуры:** деплой не проверяет `healthy`; rollback возвращает образ, но не
схему БД. **Ретеншен на стенде выключен** (`RETENTION_TTL_DAYS=0`), поэтому исторические `timer_jobs`
с `process_instance_id IS NULL` там не вычищаются — деградацию снимает индекс (WO-PERF-3), включение
ретеншена остаётся решением CTO. Среда развёрнутого стенда — тестовая, работает на дефолтных кредах
осознанно.

## Обзор

ZorroBPM исполняет определения BPMN-процессов:

- **Деплой** BPMN XML → парсится, версионируется (по `id`/ключу процесса) и сохраняется.
- **Запуск** экземпляров; движок обходит граф процесса токенами.
- **Сервис-задачи** отправляются внешним **воркерам** через RabbitMQ и завершаются асинхронно.
- **Пользовательские задачи** ждут завершения через API.
- Поддержаны **таймеры (date/duration/cron), сообщения, шлюзы, multi-instance, подпроцессы, call activity, компенсация, DMN, инциденты** (см. матрицу ниже).
- **Переменные** — скаляры и **JSON-объекты/списки**; FEEL/DMN читают вложенные свойства и итерируют коллекции.

## Поддержка BPMN

| Поддерживается | Точечные edge'ы (отдельный заход) |
|---|---|
| Start / End / Terminate-end события | Ограниченный повтор таймера `R<n>` (бесконечный `R/`/cron — есть) |
| **Message start**, **Timer start** (date/duration/**cycle**) и **Signal start** | MI на send/script job-worker форме (на user/service task — есть) |
| Потоки управления (sequence flow) | Компенсация в scope встроенного подпроцесса |
| Exclusive gateway (условия на FEEL + поток по умолчанию) | Event sub-process внутри встроенного подпроцесса (не top-level) |
| Parallel gateway (split / join) | Вложенные транзакции |
| **Inclusive gateway** (split по всем истинным веткам + default; динамический join) | |
| **Event-based gateway** (гонка catch-событий: message / timer / signal) | |
| Service task (внешние воркеры через RabbitMQ) | |
| User task (assignee, кандидаты-пользователи/группы, form key; `zeebe:userTask` маркер) | |
| **Multi-instance** (на **user и service task**, параллельный/последовательный; count по `loopCardinality`/`inputCollection`; **per-instance `inputElement`+`loopCounter`**, агрегация **`outputCollection`**; `completionCondition`) | |
| **Send / Receive task** (send: `zeebe:taskDefinition` job-worker или message throw; receive: message catch) | |
| **Script task** (`zeebe:script` FEEL, **`zeebe:taskDefinition` job-worker**, inline) | |
| **Переменные** `STRING/LONG/DOUBLE/BOOLEAN/UUID/JSON` (JSON-объекты/списки, доступ к свойствам и итерация в FEEL/DMN; структурный FEEL-результат → JSON) | |
| **IO mappings** (`zeebe:ioMapping` input/output; **scoped**: input-локали не протекают) | |
| **Business rule task** (DMN через `zeebe:calledDecision`, **версионирование**; или inline FEEL) | |
| Call activity (`propagateAllChildVariables`), встроенный подпроцесс | |
| **Event sub-process** (триггеры message / signal / error / timer; message — прерывающий и непрерывающий) | |
| Промежуточные **catch**: ожидание, **message** (+корреляция по имени и по **ключу**), **timer** (дата/длительность/**cycle**) | |
| Промежуточный **throw**, **message throw** (корреляция внутри движка) | |
| **Signal catch / throw** (broadcast всем подписчикам, 1:N) | |
| **Link catch / throw** (внутрипроцессный «goto» по имени link) | |
| **Conditional** catch / boundary (FEEL-условие на данных; реоценка при изменении переменных) | |
| **Compensation** boundary + throw (compensate-all и targeted по `activityRef`; обратный порядок) | |
| **Transaction** sub-process + **cancel** end / cancel boundary (компенсация + отмена scope) | |
| **Escalation** throw / end + **escalation boundary** (прерывающий и непрерывающий) | |
| **Error end** + **Error boundary** (с распространением по scope и в родительский процесс) | |
| **Boundary**: timer (вкл. повторяющийся **cycle**), message, **signal** и **escalation**, **прерывающие и непрерывающие** | |
| Инциденты (создаются автоматически при ошибке) + ручное разрешение | |

Условия и выражения вычисляются движком **Camunda FEEL**; десятичные и JSON-объекты/списки доступны как числа и Map/List.

## Camunda 8 Compatibility

**Совместимость с исполняемым BPMN-набором Camunda 8: ≈ 95 %.** Из конструкций, которые исполняет Zeebe (движок
Camunda 8), ZorroBPM поддерживает практически все — каждая подтверждена интеграционным тестом на реальной
C8-модели (см. `zorrobpm-engine/src/test/.../integration/*IntegrationTests.java`). Дополнительно движок исполняет
несколько BPMN-стандартных элементов, которые сам C8 **не** исполняет (Conditional-события, Transaction-subprocess,
Cancel-события). Методика оценки: доля исполняемых элементов Zeebe, для которых есть работающий обработчик + зелёный
интеграционный тест. Не покрыты только точечные edge-режимы (правая колонка таблиц) — не целые конструкции.

Движок использует BPMN 2.0 + Zeebe-расширения (`http://camunda.org/schema/zeebe/1.0`), поэтому большинство
конструкций моделируется так же, как в **Camunda 8**. Сводка:

| Статус | Конструкции |
|---|---|
| ✅ **Совместимо** | Start/End/Terminate, Message/Timer/Error/Signal/Escalation/Link события, Exclusive/Parallel/Inclusive/Event-based шлюзы, Service/User/Receive/Send task, Script task, Business rule task (DMN), Call activity, Embedded & Event subprocess, Multi-instance (per-instance vars + outputCollection), переменные STRING/LONG/DOUBLE/BOOLEAN/UUID/JSON, timeCycle (ISO + cron), zeebe:ioMapping (scoped), Compensation (compensate-all + targeted), correlation key, FEEL-условия |
| ⚠️ **Точечные edge'ы** | Business rule FEEL-режим, компенсация в scope подпроцесса, ограниченный повтор таймера `R<n>`, MI на send/script job-worker форме |
| ❌ **Не в Camunda 8** (BPMN-стандарт, но C8 не исполняет) | Conditional события, Transaction subprocess, Cancel события — надстройка движка, полезная вне C8 |

Реальные C8-BPMN-модели исполняются **без правок файлов** (главная цель проекта): JSON-payload, десятичные,
collection-driven multi-instance, cron-таймеры, DMN с версионированием. DMN исполняется собственным движком
решений поверх того же `feel-engine`, что и Camunda 8 (DMN 1.3 + FEEL).

## Архитектура

Многомодульный Maven-проект:

| Модуль | Ответственность |
|---|---|
| `zorrobpm-contract` | Контракты REST API (HTTP-interface) + DTO/модели |
| `zorrobpm-engine` | Ядро: парсинг BPMN, исполнение, persistence (JPA), таймеры, инциденты |
| `zorrobpm-rest` | REST-контроллеры, реализующие контракты |
| `zorrobpm-rabbitmq` | Интеграция с RabbitMQ для отправки/завершения сервис-задач (+ DLQ) |
| `zorrobpm-event` / `zorrobpm-exchange` | Полезная нагрузка доменных событий и сообщений брокера |
| `zorrobpm-client` | Готовые Java-клиенты к API |
| `zorrobpm-job-handler-spring-boot-starter` | SDK для написания внешних воркеров |
| `zorrobpm-ce` | Запускаемое Spring Boot приложение (собирает всё вместе) |
| `zorrobpm-test` | Общие тестовые помощники |
| `zorrobpm-frontend` | SPA (Vue 3 + Vite + TS): Operate/Tasklist/Cockpit в одном приложении; отдаётся nginx, ходит в API по относительному `/api` |

**Поток исполнения:**

```
REST  ──▶ RuntimeService ──▶ ActivityService (обход графа токенами)
                                  │
                                  ├─▶ DBService (PostgreSQL через JPA)
                                  ├─▶ ScriptService (условия FEEL)
                                  └─▶ сервис-задачи ──▶ RabbitMQ ──▶ внешний воркер
                                                                          │
RabbitMQ (zorrobpm.complete-service-task) ◀── завершение ◀───────────────┘
TimerScheduler (@Scheduled) ──▶ срабатывание таймеров ──▶ ActivityService
```

## Технологический стек

Java 21 · Spring Boot 4.0.5 · PostgreSQL 16 · RabbitMQ 3.13 · Liquibase · Camunda FEEL · springdoc-openapi.

## Требования

- **JDK 21** (Docker-сборка использует Temurin 21). *Примечание: в POM движка `java.version=17`, но собирать/запускать нужно на 21.*
- Maven 3.9+
- Docker + Docker Compose (для быстрого старта)

## Быстрый старт (Docker Compose)

```bash
git clone https://<internal-git-host>/ismet/microservices/zorro-bpm/zbpm.git
cd zbpm
cp .env.example .env        # при необходимости поправьте креды/порты
docker compose up -d --build
```

Поднимутся PostgreSQL, RabbitMQ, backend (`app`) и frontend (`frontend`, nginx). Затем:

- UI (SPA): `http://localhost:8081` — отдаётся nginx, проксирует `/api` → `app:8080`
- База API: `http://localhost:8080`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`
- Management UI RabbitMQ: `http://localhost:9300` (по умолчанию `zorrodev`/`zorrodev`)

Остановить: `docker compose down` (добавьте `-v`, чтобы удалить тома с данными).

**Frontend** собирается отдельным образом (`zorrobpm-frontend/Dockerfile`, multi-stage `node:22` → `nginx`).
В `src/` нет hardcoded `localhost`/IP: API-база — относительный `/api`, OIDC-redirect берётся из
`window.location.origin`.

## Локальная разработка

```bash
# Собрать всё и прогнать тесты (unit + интеграционные на H2)
JAVA_HOME=/путь/к/jdk-21 mvn clean verify

# Запустить только модуль приложения (нужны доступные Postgres + RabbitMQ)
mvn -pl zorrobpm-ce -am spring-boot:run
```

Docker-сборка образа пропускает тесты (`-DskipTests`); запускайте `mvn verify` локально/в CI как контроль качества.

## Конфигурация

Переменные окружения (со значениями по умолчанию):

| Переменная | По умолчанию | Назначение |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/zorrobpm-db` | JDBC URL |
| `DB_USERNAME` / `DB_PASSWORD` | `zorrodev` / `zorrodev` | Креды БД |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` | `localhost` / `5672` | Адрес брокера |
| `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` | `zorrodev` / `zorrodev` | Креды брокера |
| `APP_FILES_DIR` | `~/.zorrobpm/files` | Каталог хранения BPMN-файлов |
| `APP_PORT` | `8080` | HTTP-порт |

Свойства движка / обмена сообщениями (`application.properties`):

| Свойство | По умолчанию | Назначение |
|---|---|---|
| `zorrobpm.engine.max-execution-depth` | `1000` | Защита от бесконечных циклов / рекурсивных call activity |
| `zorrobpm.engine.timer-poll-interval-ms` | `5000` | Интервал опроса таймеров |
| `spring.rabbitmq.listener.simple.retry.max-attempts` | `5` | Число попыток завершения до отправки в DLQ |

## REST API

| Метод и путь | Описание |
|---|---|
| `POST /process-definitions` | Деплой BPMN (`{ "bpmn": "<xml>" }`) |
| `GET /process-definitions` | Список (постранично; фильтры: `name` — частичное совпадение без учёта регистра, `processDefinitionKey`, `processDefinitionVersion`, `latestVersionOnly`) |
| `GET /process-definitions/{id}` | Метаданные определения |
| `GET /process-definitions/{id}/xml` | Исходный BPMN XML |
| `GET /process-definitions/{id}/structure` | Разобранная структура BPMN в виде вложенного JSON (узлы/рёбра, подпроцессы и boundary-события вложены) — для инспекции и UI |
| `POST /process-instances` | Запуск экземпляра |
| `POST /service-tasks/{id}/complete` | Завершить сервис-задачу |
| `POST /user-tasks/{id}/complete` | Завершить пользовательскую задачу |
| `POST /incidents/{id}/resolve` | Разрешить инцидент (повторно исполняет элемент) |
| `GET /process-instances` · `/user-tasks` · `/service-tasks` · `/variables` · `/incidents` | Постраничные запросы |

**Пример — деплой, запуск, просмотр:**

```bash
# Деплой
curl -X POST http://localhost:8080/process-definitions \
  -H 'Content-Type: application/json' \
  -d "{\"bpmn\": $(jq -Rs . < my-process.bpmn)}"

# Запуск (по ключу, последняя версия)
curl -X POST http://localhost:8080/process-instances \
  -H 'Content-Type: application/json' \
  -d '{"processDefinitionKey":"my-process","variables":[{"name":"amount","type":"LONG","value":"100"}]}'

# Запрос экземпляра
curl "http://localhost:8080/process-instances?id=<INSTANCE_ID>&pageIndex=0&pageSize=10"
```

`ProcessVariable` = `{ "name": ..., "type": "STRING|LONG|DOUBLE|BOOLEAN|UUID|JSON", "value": "..." }`. `DOUBLE` несёт десятичные как число в FEEL/DMN (`19.99`); `JSON` — объект/список (`value` — JSON-строка), доступный в FEEL/DMN по свойствам (`order.total`) и итерируемый.

## Сервис-задачи: написание воркера

Сервис-задача в BPMN объявляет **тип job** (в стиле Zeebe `taskDefinition` type). Создайте приложение-воркер:

1. Добавьте зависимость:

```xml
<dependency>
  <groupId>com.zorrodev.bpm</groupId>
  <artifactId>zorrobpm-job-handler-spring-boot-starter</artifactId>
  <version>0.7.17-SNAPSHOT</version>
</dependency>
```

2. Реализуйте обработчик на каждый тип job:

```java
@Component
public class ChargeHandler implements JobHandler {
    @Override public String getJob() { return "charge-card"; }   // совпадает с типом job у задачи

    @Override public List<ProcessVariable> handleJob(JobDetailModel job) {
        // ... работа с job.getVariables() ...
        ProcessVariable result = new ProcessVariable();
        result.setName("chargeStatus"); result.setType("STRING"); result.setValue("OK");
        return List.of(result);
    }
}
```

Стартер автоматически подписывается на `zorrobpm.jobs.<job>` и публикует завершение в `zorrobpm.complete-service-task`. Воркер должен смотреть на тот же брокер RabbitMQ, что и движок.

### Ошибки воркера и инциденты

Движок и воркер развязаны через RabbitMQ — об ошибке воркер сообщает **обратным событием** (тем же каналом `zorrobpm.complete-service-task`), просто с `status="FAILED"` и текстом ошибки. Движок сам ведёт счётчик попыток и решает: повторить или создать инцидент. Модель — как в Camunda («fail job» с retries → инцидент).

**Откуда движок знает, что за ошибка:** только воркер видел исключение — поэтому он кладёт его в сообщение. Текст ошибки (`errorMessage`) пишется воркером из пойманного исключения; движок добавляет контекст («где»: service-task, инстанс) и сохраняет всё в инцидент.

**Кол-во повторов** задаётся на задаче (`zeebe:taskDefinition retries="3"`, по умолчанию **3**):

```xml
<bpmn:serviceTask id="charge">
  <bpmn:extensionElements>
    <zeebe:taskDefinition type="charge-card" retries="3" />
  </bpmn:extensionElements>
</bpmn:serviceTask>
```

**Через SDK** (рекомендуется) — просто бросьте исключение из `handleJob`; стартер сам отправит `FAILED` с текстом исключения:

```java
@Override public List<ProcessVariable> handleJob(JobDetailModel job) {
    throw new IllegalStateException("downstream 503");   // → движок применит retries → при 0 создаст инцидент
}
```

**Через REST** (для не-RabbitMQ интеграций) — сообщить о сбое напрямую:

```bash
# по умолчанию: уменьшить бюджет попыток на 1 (инцидент — когда дойдёт до 0)
curl -X POST http://localhost:8080/service-tasks/<SERVICE_TASK_ID>/fail \
  -H 'Content-Type: application/json' \
  -d '{"message":"downstream 503"}'

# retries=0 (как Camunda failJob) — инцидент сразу, независимо от оставшегося бюджета
curl -X POST http://localhost:8080/service-tasks/<SERVICE_TASK_ID>/fail \
  -H 'Content-Type: application/json' \
  -d '{"message":"fatal: bad config","retries":0}'
```

Поле `retries` необязательное: если указать — бюджет попыток **выставляется** в это значение (`0` → инцидент немедленно; `>0` → задача переотправляется с этим бюджетом); если не указывать — бюджет **уменьшается на 1**.

**Что происходит:**
1. На каждый `FAILED` движок уменьшает `retries_remaining` сервис-задачи.
2. Пока `> 0` — задача **переотправляется** воркеру (тот же job-эвент); инцидент НЕ создаётся.
3. При `0` — активность помечается `ERROR` и создаётся **инцидент** с текстом ошибки воркера; токен остаётся припаркованным.
4. Оператор смотрит инцидент через `GET /incidents` (поля: `activityId`, `message`, `createdAt`).
5. **Resolve** — `POST /incidents/{id}/resolve` (можно передать переменные) → задача исполняется заново (свежий бюджет retries) и при успехе процесс идёт дальше.

> Бизнес-ошибки (ожидаемые исходы) — это **не** инцидент: их моделируют через **error boundary / event-subprocess** по `errorCode`, а не через `FAILED`.

## Руководство по интеграции — пошагово (для новичка)

> От «что это вообще» до рабочей интеграции внешней системы. Справочник эндпоинтов — в разделе [REST API](#rest-api)
> выше; воркеры — в [Сервис-задачи](#сервис-задачи-написание-воркера). URL в примерах: прод — `https://<host>/api/…`
> (nginx срезает `/api`); локально — `http://localhost:8080/…` без `/api`.

### Что это простыми словами
ZorroBPM — «дирижёр» процессов. Вы рисуете схему (`.bpmn`: прямоугольники — шаги, ромбы — развилки, стрелки —
порядок), движок её **исполняет**: ведёт каждую заявку по шагам, ждёт людей, зовёт внешние системы, помнит
состояние. Сам бизнес-логику **не выполняет** — только оркеструет. Четыре роли: **моделлер** (рисует схему),
**движок** (исполняет), **воркер** (ваш код на service task), **UI/фронт** (человек на user task). Новая внешняя
система = ещё один API-ключ, per-system кода в движке нет.

Восемь слов: **Definition** (задеплоенная схема, `key`+`version`) · **Instance** (одна заявка, `id`) · **Token**
(где сейчас заявка) · **Variables** (данные заявки) · **User Task** (ждёт человека) · **Service Task** (ждёт
машину/воркер) · **Job** (единица работы service task по типу `zeebe:taskDefinition type`) · **Incident**
(застряло — нужен оператор).

### Доступ для внешней системы (API-ключ)
Человек-владелец создаёт ключ (UI «Мой профиль → API-ключи» или `POST /me/api-key`); ключ вида `zbpm_sk_…`
показывается один раз. Дальше каждый запрос машины шлёт его в заголовке — он приоритетнее cookie:

```
Authorization: Bearer zbpm_sk_XXXXXXXX
```

Для машин — только API-ключ (не логин/пароль). Ниже подразумевается `-H "$AUTH"`, где `AUTH="Authorization: Bearer zbpm_sk_…"`.

### Шаг 1 — нарисовать схему (Camunda Modeler)
Скачайте **Camunda Modeler**, создайте диаграмму типа **BPMN (Camunda 8 / Cloud)** — важно именно C8, движок читает
Zeebe-расширения (`zeebe:*`) как Camunda 8. Нарисуйте `Start → User Task → End`. Кликните по процессу → **Process ID**
= `vacation` (будущий `key`). На шагах панель справа проставляет `zeebe:*` за вас: service task —
`<zeebe:taskDefinition type="send-email" retries="3"/>`; user task — `<zeebe:userTask/>` +
`<zeebe:assignmentDefinition assignee="ivan" candidateGroups="hr"/>`; форма — `<zeebe:formDefinition
externalReference="…"/>`. Поэтому реальные C8-модели идут **без правок файла**.

### Шаг 2 — задеплоить и запустить
Деплой (`POST /process-definitions`, тело JSON `{bpmn}`, требует SUPER_ADMIN) и запуск (`POST /process-instances`) —
см. примеры в разделе [REST API](#rest-api). Повторный деплой того же `Process ID` = новая версия; старые экземпляры
доедут по своей.

### Шаг 3 — User Tasks (человек в процессе): полный цикл
Ваш внешний Tasklist/портал:
1. **Инбокс** — поллинг (webhook «новая задача» пока нет):
   `GET /user-tasks?candidateGroup=hr&state=CREATED&page=0&size=50` (или `?assignee=ivan`). Ответ — страница
   `UserTask`: `id, processInstanceId, bpmnElementId, name, formKey, assignee, candidateGroups, createdAt`.
2. **Данные формы** — `GET /variables?processInstanceId=<id>`; какую форму рисовать — по `formKey` (ваш фронт мапит
   ключ на компонент; валидация ввода — через Variable Schema, ниже).
3. **Завершить** — вернуть результат:
   ```bash
   curl -X POST https://<host>/user-tasks/<taskId>/complete -H "$AUTH" -H 'Content-Type: application/json' \
     -d '{"variables":[{"name":"approved","type":"BOOLEAN","value":"true"}]}'
   ```
   Движок сам продвинет токен (например, шлюз по `approved` выберет ветку).

### Шаг 4 — Service Tasks (машина в процессе)
Два способа получить работу — подробно в разделе [Сервис-задачи: написание воркера](#сервис-задачи-написание-воркера):
**A) RabbitMQ push** (Java, стартер `JobHandler`, очередь `zorrobpm.jobs.<type>`) — рекомендуется; **B) REST-поллинг**
(любой язык): `GET /service-tasks?job=<type>&state=CREATED` → `POST /service-tasks/{id}/complete` (успех, с
переменными) или `/fail` (`{message, retries?}`). Семантика `fail`/retries/инцидентов — там же.

### Свой внешний фронтенд + Variable Schema
UI ходит в API через ваш BFF (ключ на бэкенде, не в браузере). Движок форм **не рендерит**, но хранит **JSON Schema
2020-12** артефакты (`ElementArtifact`, `kind=VARIABLE_SCHEMA`) и привязывает к элементам
(`GET/POST /process-definitions/{key}/element-bindings`, версия пиннится к версии определения). BFF: получить схему
входных переменных элемента → **провалидировать** ввод → `POST …/complete`. Расширения `x-ui`/`x-builder` в схеме
несут метаданные для рендера полей. Дизайн — [ADR-6](docs/adr/ADR-6-element-artifact-variable-schema.md).

### End-to-end: «Отпуск»
`Start → User Task «Согласовать» → Exclusive Gateway (по approved) → [да] Service Task send-email → End ; [нет] End`.
```bash
AUTH="Authorization: Bearer zbpm_sk_…"
# деплой и старт — см. REST API; затем:
TASK=$(curl -s "https://<host>/user-tasks?candidateGroup=hr&state=CREATED" -H "$AUTH" | jq -r '.data[0].id')
curl -X POST https://<host>/user-tasks/$TASK/complete -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"variables":[{"name":"approved","type":"BOOLEAN","value":"true"}]}'
# движок дошёл до service task send-email → ваш воркер выполнил → End.
```

### Частые ошибки
- **401/403 везде** — нет/просрочен токен; для машин `Authorization: Bearer zbpm_sk_…`. Читать может любой
  аутентифицированный; энфорс прав записи по владельцу ещё раскатывается (см. [Ограничения](#ограничения-и-замечания-по-проду)).
- **Застряло на service task** — воркер не берёт джобы этого типа или исчерпал retries → `GET /incidents`. `getJob()`
  воркера должен == `zeebe:taskDefinition type`.
- **User task не в инбоксе** — проверьте фильтр (`assignee` vs `candidateGroup`) и маркер `zeebe:userTask`.
- **Шлюз → инцидент** — ни одно условие не истинно и нет **default flow**. Задайте default.
- **Переменная не читается в FEEL** — неверный `type` (число как `STRING`). Число → `LONG`/`DOUBLE`, объект → `JSON`.

## События (event notifications) — как узнать, что что-то изменилось

Движок эмитит **доменные события** на каждом изменении состояния (старт/завершение/отмена инстанса, создание/
завершение user-task, создание service-task, инцидент, завершение активности). Событие пишется в **транзакционный
outbox в той же транзакции**, что и изменение (at-least-once, не теряется), затем публикуется. Три способа получить
(ADR-7, `docs/adr/ADR-7-event-notification-architecture.md`):

| Контракт | Транспорт | Кому |
|---|---|---|
| **A** exchange `zorrobpm.events` | RabbitMQ topic | внешние **системы** с AMQP |
| **B** `GET /events?since=cursor` | HTTP pull | любая система/UI без AMQP (firewall-friendly) |
| **C** `GET /events/stream` | HTTP SSE | браузерные UI (push) |

**Каталог типов событий (routing key):** `process-instance.started` · `process-instance.completed` ·
`process-instance.cancelled` · `activity.completed` · `user-task.created` · `user-task.completed` ·
`service-task.created` · `incident.raised` · `incident.resolved`.

**Envelope (JSON):** `{ sequence, id, type, version, occurredAt, processDefinitionKey, processDefinitionId,
processInstanceId, elementId, ownerScope, data }`.

### Ловля через RabbitMQ (Контракт A) — пошагово

Движок сам объявляет durable topic-exchange `zorrobpm.events` при старте и публикует туда каждое событие с
routing-key = тип. **Очередь создаёт потребитель:** topic-exchange без привязанной очереди роняет сообщения (стандарт
AMQP) — поэтому подпишитесь, создав СВОЮ очередь и привязав её.

**Шаг 1. Проверьте, что exchange есть** (на хосте с RabbitMQ):
```bash
rabbitmqctl list_exchanges | grep zorrobpm.events      # → zorrobpm.events   topic
```

**Шаг 2. Создайте свою durable-очередь и привяжите к exchange** (паттерн routing-key под ваши нужды):
```bash
# все события:
rabbitmqadmin declare queue name=my-app.events durable=true
rabbitmqadmin declare binding source=zorrobpm.events destination=my-app.events routing_key="#"
# ИЛИ только инциденты:      routing_key="incident.*"
# ИЛИ конкретный тип:        routing_key="user-task.created"
```
> Один потребитель = одна durable-очередь. Несколько потребителей — каждый свою очередь (каждый получит копию по
> своему паттерну). Не биндите к общей очереди, если хотите независимую доставку.

**Шаг 3. Читайте из своей очереди** (пример — Spring Boot воркер):
```java
@Component
public class EventConsumer {
    @RabbitListener(queues = "my-app.events")
    public void onEvent(String envelopeJson) {
        // envelopeJson — JSON envelope (type, sequence, processInstanceId, data, …)
        // РАЗБЕРИТЕ и реагируйте. Обработка ДОЛЖНА быть идемпотентной:
        //   доставка at-least-once → возможен повтор; дедуп по полю "id" или "sequence".
    }
}
```
Пример на любом языке — консюмер AMQP 0-9-1 к очереди `my-app.events` (Go/Python/Node — любой клиент RabbitMQ).

**Шаг 4. Гарантии и правила:**
- **At-least-once, не exactly-once** — возможен дубликат при ретрае. Дедуп по `id`/`sequence`, обработка идемпотентна.
- **Порядок** — монотонный `sequence` (глобальный); в рамках одного `processInstanceId` порядок сохранён.
- **Догон после простоя** — если консюмер лежал, события копились в его durable-очереди (не потеряются). Либо
  добрать пропущенное через Контракт B: `GET /events?since=<последний_обработанный_sequence>`.
- **Не подтверждайте (ack) до успешной обработки** — при падении сообщение вернётся (requeue) или уйдёт в DLQ по
  вашей настройке.

**Быстрая проверка «вживую»:** привяжите очередь с `routing_key="#"`, запустите любой процесс (см. [Руководство по
интеграции](#руководство-по-интеграции--пошагово-для-новичка)) — в очереди появятся `process-instance.started`,
`user-task.created` и т.д.

### Контракт B (HTTP pull) — без RabbitMQ
```bash
curl "https://<host>/events?since=<sequence>&type=incident.raised&limit=100" \
  -H "Authorization: Bearer zbpm_sk_..."
# → события после курсора (только те process-definition, на которые у ключа есть grant); в ответе — следующий курсор.
```
Курсор `since` — последний обработанный `sequence`; реплеится, firewall-friendly. **AuthZ:** видны только свои
process-definition (кросс-тенант события не отдаются).

### Контракт C (SSE push) — для браузерных UI
`GET /events/stream` (`text/event-stream`, JWT-auth, `Last-Event-ID`=sequence для докачки) — живой поток в браузер/
BFF. Тот же authz-фильтр. Встроенный SPA использует его для realtime без поллинга.

## Авторизация веб-консоли (UI)

Простой self-contained вход в UI по логину/паролю (без Keycloak):

- Аккаунты — в таблице `ui_users` (логин, PBKDF2-хэш пароля, роль `SUPER_ADMIN`/`ADMIN`/`USER`).
- `POST /auth/login` `{username,password}` → выставляет **httpOnly-cookie** `zbpm_token` (access, подпись HMAC-SHA256) + `refresh_token`; SPA ходит по cookie-сессии, программные клиенты — `Authorization: Bearer <token>`.
- **Refresh + ревокация** (`POST /auth/refresh`, `/auth/logout`) — короткий access + ротация refresh с детекцией повторного использования.
- **Rate-limit** на `POST /auth/login` (защита от brute-force).
- **API-ключи для интеграций** — service accounts аутентифицируются статическим ключом `Authorization: Bearer zbpm_sk_…` (без refresh; хранится как SHA-256, ревокация — kill-switch). Для машин (воркеры, 1С, SAP, боты).
- `GET /auth/me` — текущий пользователь (нужен валидный токен/ключ).
- `GET/POST/PUT /users` — управление пользователями (требует роль `ADMIN`/`SUPER_ADMIN`).
- При первом старте создаётся админ **`admin` / `admin`** (если таблица пуста) — **обязательно смените пароль** (в проде задайте `zorrobpm.security.default-admin-password`).

Конфигурация (env / `application.properties`):

| Ключ | По умолчанию | Назначение |
|---|---|---|
| `zorrobpm.security.jwt-secret` | dev-секрет | секрет подписи токена — **обязательно переопределить в проде** |
| `zorrobpm.security.jwt-ttl-minutes` | `720` | срок жизни токена |
| `zorrobpm.security.require-api-auth` | `true` | защищать ли Data-API токеном (отключить только при наличии внешнего шлюза) |
| `zorrobpm.security.default-admin-username` | `admin` | имя сид-админа |
| `zorrobpm.security.default-admin-password` | `admin` | пароль сид-админа при первом старте |

> По умолчанию **все** эндпоинты (`/process-*`, `/user-tasks`, `/incidents`, …) требуют валидный `Authorization: Bearer <token>`. Отключить можно через `zorrobpm.security.require-api-auth=false` — только при наличии аутентифицирующего шлюза перед сервисом.

## Java-клиент

```xml
<dependency>
  <groupId>com.zorrodev.bpm</groupId>
  <artifactId>zorrobpm-client</artifactId>
  <version>0.7.17-SNAPSHOT</version>
</dependency>
```

Задайте `M11S_ZORRODEV_BPM_URL` (по умолчанию `http://localhost:8080`) и внедрите `RuntimeClient`, `ProcessDefinitionClient`, `QueryClient`.

## База данных и миграции

Схема управляется **Liquibase** (`zorrobpm-engine/src/main/resources/db/changelog`), применяется автоматически при старте. Ключевые таблицы: `process_definitions`, `process_instances`, `activities`, `tokens`, `variables`, `user_tasks`, `service_tasks`, `incidents`, `timer_jobs`, `message_subscriptions`, `signal_subscriptions`, `signal_start_subscriptions`, `parallel_gateways`, `dmn_definitions`, `outbox` (transactional outbox), `ui_users`, `refresh_tokens`, а также multi-tenant: `process` (реестр), `process_member` (владельцы/дизайнеры), `service_account` + `service_account_permission` (API-ключи интеграций).

## Ограничения и замечания по проду

- **JWT-авторизация включена по умолчанию** для всех эндпоинтов (`require-api-auth=true`). В прод-профиле приложение **не стартует** с дефолтным `jwt-secret` — задайте `ZORROBPM_JWT_SECRET`. Смените пароль `admin`.
- **Модель авторизации в процессе внедрения.** Разворачивается multi-tenant RBAC (владение процессом: `OWNER`/`DESIGNER`, service accounts с правами, `SUPER_ADMIN`) — см. `docs/adr/ADR-1-multi-tenant-authorization.md`. Чтение открыто любому аутентифицированному; **энфорсмент записи по владельцу процесса ещё раскатывается** (WO-MT-3) — до его завершения любой аутентифицированный пользователь может выполнять операции над любым процессом. Планируйте доступ соответственно.
- **Известные пункты в работе** (независимый аудит): усиление rate-limit за доверенным прокси и серверный энфорс смены дефолтного пароля — см. `governance/workorders/` (WO-SEC-13/14). До их закрытия усиливайте контроль на уровне nginx/сети.
- **Масштабирование (scale-out).** Таймеры (`timer_jobs`/`timer_start_jobs`) и очистка ретенции теперь безопасны при нескольких репликах: атомарный захват задач через `SELECT … FOR UPDATE SKIP LOCKED` — только один экземпляр обрабатывает каждую задачу (WO-REL-6/7, доказано PG-IT на реальном PostgreSQL). **Остаётся** на одном экземпляре: версионирование определений использует JVM-лок (несколько реплик могут конфликтовать при одновременном деплое одного и того же ключа процесса). Разрешение инцидентов идемпотентно (WO-REL-5).
- **Ретенция.** Очистка терминальных (завершённых/отменённых) инстансов — fail-safe каскад, **выключена по умолчанию** (`RETENTION_TTL_DAYS=0`). Задайте число дней, чтобы включить автоудаление старых инстансов.
- **CORS** ограничивается allowlist'ом в прод-профиле (`ZORROBPM_CORS_ORIGINS`, по умолчанию домен прода); в dev — открыт. Доступ в проде идёт через nginx reverse proxy.
- **Сборка требует JDK 21**, хотя в POM движка указано `java.version=17`.
- При апгрейде на существующем брокере очередь `zorrobpm.complete-service-task` получает dead-letter-аргументы — если она уже есть без них, удалите её один раз (`PRECONDITION_FAILED` при переобъявлении).

## CI/CD

GitLab CI (`.gitlab-ci.yml`), self-hosted runner с Docker (tag `zbpm`). Стадии:

```
test  →  package  →  deploy  →  rollback
```

- **test** — backend `mvn clean verify` (полный reactor: unit + интеграционные) и frontend `npm ci && npm run build` (typecheck + сборка).
- **package** — Docker-образы backend и frontend, версионируются тегом коммита (`$CI_COMMIT_SHORT_SHA`) + `latest`.
- **deploy** — **ручной запуск** (`when: manual`, в т.ч. на ветке по умолчанию — WO-AUD-6, чтобы неудачная сборка не уезжала в прод автоматически): `docker compose up -d` с версионными тегами; предыдущий тег сохраняется в `.previous_tag`. Отдельный job `test:pg` гоняет `@Tag("pg")`-тесты против реального `postgres:16`.
- **rollback** — ручная стадия: переразвёртывание предыдущего тега.

Прод за внешним nginx reverse proxy: `https://zorro.i-smet.kz` → `frontend` (nginx) → `/api` → `app:8080`.

## Лицензия

Apache License 2.0.
