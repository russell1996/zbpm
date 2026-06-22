# ZorroBPM CE

Лёгкий движок бизнес-процессов BPMN 2.0 на Spring Boot с **высокой совместимостью с Camunda 8** (реальные C8-BPMN-модели исполняются без правок файлов). Деплой BPMN-процессов, запуск экземпляров, выполнение внешней работы через брокер сообщений и управление пользовательскими задачами — через REST API и единый **SPA** (Operate/Tasklist/Cockpit в одном приложении, `zorrobpm-frontend`).

> ⚠️ **Статус — Community Edition, ранняя стадия.**
> Рабочий одноузловой движок с хорошим покрытием happy-path тестами. **Ещё не готов к промышленной эксплуатации:** нет встроенной аутентификации/авторизации, рассчитан на запуск в **одном экземпляре** (таймеры и версионирование определений пока небезопасны при нескольких репликах). Запускайте в доверённой сети / за аутентифицирующим шлюзом. См. [Ограничения](#ограничения-и-замечания-по-проду).

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

Движок использует BPMN 2.0 + Zeebe-расширения (`http://camunda.org/schema/zeebe/1.0`), поэтому большинство
конструкций моделируется так же, как в **Camunda 8**. Полный аудит — в
[docs/camunda8-compatibility.md](docs/camunda8-compatibility.md). Сводка:

| Статус | Конструкции |
|---|---|
| ✅ **Совместимо** (модель переносится в C8 без правок) | Start/End/Terminate, Message/Timer/Error/Signal/Escalation/Link события (start/catch/throw/boundary), Exclusive/Parallel/Inclusive/Event-based шлюзы, Service/User (вкл. `zeebe:userTask`)/Receive/**Send** task (`zeebe:taskDefinition`), **Script task** (`zeebe:script`, `zeebe:taskDefinition` job-worker, inline), Business rule task (`zeebe:calledDecision`, DMN versioning), Call activity (`propagateAllChildVariables`), Embedded & Event subprocess (message/signal/error/timer), **Multi-instance** (per-instance `inputElement`/`loopCounter` + `outputCollection`, на user **и** service task), **переменные** `STRING/LONG/DOUBLE/BOOLEAN/UUID/JSON` (объекты/списки, доступ к свойствам в FEEL/DMN), **`timeCycle`** (ISO `R[n]/<duration>` + cron + повтор), `zeebe:ioMapping` (scoped), **Compensation** (compensate-all + targeted), correlation key (`zeebe:subscription`), FEEL-условия |
| ⚠️ **Точечные edge'ы** | Business rule FEEL-режим (`zeebe:script` — проектное расширение), компенсация в scope подпроцесса, ограниченный повтор таймера `R<n>` (бесконечный `R/`/cron — есть), MI на send/script job-worker форме (на user/service — есть) |
| ❌ **Не поддерживается в Camunda 8** (стандарт BPMN, но C8 не исполняет) | **Conditional** события (start/catch/boundary), **Transaction** subprocess, **Cancel** события (end/boundary) — надстройка движка, полезная вне C8 |

Реальные C8-BPMN-модели исполняются **без правок файлов** (главная цель проекта): JSON-payload, десятичные,
collection-driven multi-instance, cron-таймеры, DMN с версионированием. Полный аудит и оставшиеся edge'ы —
в [docs/camunda8-compatibility.md](docs/camunda8-compatibility.md). DMN исполняется собственным движком
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
`window.location.origin`. Подробности — [docs/deployment.md](docs/deployment.md).

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
curl -X POST http://localhost:8080/service-tasks/<SERVICE_TASK_ID>/fail \
  -H 'Content-Type: application/json' \
  -d '{"message":"downstream 503"}'
```

**Что происходит:**
1. На каждый `FAILED` движок уменьшает `retries_remaining` сервис-задачи.
2. Пока `> 0` — задача **переотправляется** воркеру (тот же job-эвент); инцидент НЕ создаётся.
3. При `0` — активность помечается `ERROR` и создаётся **инцидент** с текстом ошибки воркера; токен остаётся припаркованным.
4. Оператор смотрит инцидент через `GET /incidents` (поля: `activityId`, `message`, `createdAt`).
5. **Resolve** — `POST /incidents/{id}/resolve` (можно передать переменные) → задача исполняется заново (свежий бюджет retries) и при успехе процесс идёт дальше.

> Бизнес-ошибки (ожидаемые исходы) — это **не** инцидент: их моделируют через **error boundary / event-subprocess** по `errorCode`, а не через `FAILED`.

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

Схема управляется **Liquibase** (`zorrobpm-engine/src/main/resources/db/changelog`), применяется автоматически при старте. Ключевые таблицы: `process_definitions`, `process_instances`, `activities`, `tokens`, `variables`, `user_tasks`, `service_tasks`, `incidents`, `timer_jobs`, `message_subscriptions`, `signal_subscriptions`, `signal_start_subscriptions`, `parallel_gateways`, `dmn_definitions`.

## Ограничения и замечания по проду

- **Нет аутентификации/авторизации** — все эндпоинты открыты. Размещайте сервис за аутентифицирующим шлюзом и в закрытой сети.
- **Только один экземпляр** — таймеры опрашиваются без leader-election, версионирование определений использует JVM-лок; несколько реплик могут дублировать срабатывание таймеров и конфликтовать на версионировании.
- **CORS полностью открыт** на backend — в проде доступ идёт через nginx (frontend-контейнер / внешний reverse proxy), ограничивайте на этом уровне.
- **Сборка требует JDK 21**, хотя в POM движка указано `java.version=17`.
- При апгрейде на существующем брокере очередь `zorrobpm.complete-service-task` получает dead-letter-аргументы — если она уже есть без них, удалите её один раз (`PRECONDITION_FAILED` при переобъявлении).

## CI/CD

GitLab CI (`.gitlab-ci.yml`), self-hosted runner с Docker (tag `zbpm`). Стадии:

```
test  →  package  →  deploy  →  rollback
```

- **test** — backend `mvn clean verify` (полный reactor: unit + интеграционные) и frontend `npm ci && npm run build` (typecheck + сборка).
- **package** — Docker-образы backend и frontend, версионируются тегом коммита (`$CI_COMMIT_SHORT_SHA`) + `latest`.
- **deploy** — авто на ветке по умолчанию (вручную на прочих): `docker compose up -d` с версионными тегами; предыдущий тег сохраняется в `.previous_tag`.
- **rollback** — ручная стадия: переразвёртывание предыдущего тега.

Прод за внешним nginx reverse proxy: `https://zorro.i-smet.kz` → `frontend` (nginx) → `/api` → `app:8080`.
Полный runbook (деплой, откат, переменные, топология) — [docs/deployment.md](docs/deployment.md).

## Лицензия

Apache License 2.0.
