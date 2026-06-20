# ZorroBPM CE

Лёгкий движок бизнес-процессов BPMN 2.0 на Spring Boot. Деплой BPMN-процессов, запуск экземпляров, выполнение внешней работы через брокер сообщений и управление пользовательскими задачами — всё через простой REST API.

> ⚠️ **Статус — Community Edition, ранняя стадия.**
> Рабочий одноузловой движок с хорошим покрытием happy-path тестами. **Ещё не готов к промышленной эксплуатации:** нет встроенной аутентификации/авторизации, рассчитан на запуск в **одном экземпляре** (таймеры и версионирование определений пока небезопасны при нескольких репликах). Запускайте в доверённой сети / за аутентифицирующим шлюзом. См. [Ограничения](#ограничения-и-замечания-по-проду).

## Обзор

ZorroBPM исполняет определения BPMN-процессов:

- **Деплой** BPMN XML → парсится, версионируется (по `id`/ключу процесса) и сохраняется.
- **Запуск** экземпляров; движок обходит граф процесса токенами.
- **Сервис-задачи** отправляются внешним **воркерам** через RabbitMQ и завершаются асинхронно.
- **Пользовательские задачи** ждут завершения через API.
- Поддержаны **таймеры, сообщения, шлюзы, подпроцессы, call activity, инциденты** (см. матрицу ниже).

## Поддержка BPMN

| Поддерживается | Пока не поддерживается |
|---|---|
| Start / End / Terminate-end события | Multi-instance (параллельный / последовательный) |
| **Message start**, **Timer start** и **Signal start** (старт по сообщению / расписанию / сигналу) | Conditional события (парсятся, исполнение — позже) |
| Потоки управления (sequence flow) | Компенсации |
| Exclusive gateway (условия на FEEL + поток по умолчанию) | Transaction sub-process |
| Parallel gateway (split / join) | Event sub-process |
| **Inclusive gateway** (split по всем истинным веткам + default; динамический join) | Business rule (DMN) задачи |
| **Event-based gateway** (гонка catch-событий: message / timer / signal) | |
| Service task (внешние воркеры через RabbitMQ) | |
| User task (assignee, кандидаты-пользователи/группы, form key) | |
| **Send / Receive task** (message throw / catch в форме задачи) | |
| **Script task** (inline FEEL-выражение, результат в переменную) | |
| Call activity, встроенный подпроцесс | |
| Промежуточные **catch**: обычное ожидание, **message** (+корреляция), **timer** (дата/длительность) | |
| Промежуточный **throw**, **message throw** (корреляция внутри движка) | |
| **Signal catch / throw** (broadcast всем подписчикам, 1:N) | |
| **Link catch / throw** (внутрипроцессный «goto» по имени link) | |
| **Escalation** throw / end + **escalation boundary** (прерывающий и непрерывающий) | |
| **Error end** + **Error boundary** (с распространением по scope и в родительский процесс) | |
| **Boundary**: timer, message, **signal** и **escalation**, **прерывающие и непрерывающие** | |
| Инциденты (создаются автоматически при ошибке) + ручное разрешение | |

Условия вычисляются движком **Camunda FEEL**.

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

Поднимутся PostgreSQL, RabbitMQ и приложение. Затем:

- База API: `http://localhost:8080`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`
- Management UI RabbitMQ: `http://localhost:9300` (по умолчанию `zorrodev`/`zorrodev`)

Остановить: `docker compose down` (добавьте `-v`, чтобы удалить тома с данными).

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

`ProcessVariable` = `{ "name": ..., "type": "STRING|LONG|BOOLEAN", "value": "..." }`.

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

Схема управляется **Liquibase** (`zorrobpm-engine/src/main/resources/db/changelog`), применяется автоматически при старте. Ключевые таблицы: `process_definitions`, `process_instances`, `activities`, `tokens`, `variables`, `user_tasks`, `service_tasks`, `incidents`, `timer_jobs`, `message_subscriptions`, `signal_subscriptions`, `signal_start_subscriptions`, `parallel_gateways`.

## Ограничения и замечания по проду

- **Нет аутентификации/авторизации** — все эндпоинты открыты. Размещайте сервис за аутентифицирующим шлюзом и в закрытой сети.
- **Только один экземпляр** — таймеры опрашиваются без leader-election, версионирование определений использует JVM-лок; несколько реплик могут дублировать срабатывание таймеров и конфликтовать на версионировании.
- **CORS полностью открыт**, размер тела запроса не ограничен — ужесточите перед публичным доступом.
- **Сборка требует JDK 21**, хотя в POM движка указано `java.version=17`.
- При апгрейде на существующем брокере очередь `zorrobpm.complete-service-task` получает dead-letter-аргументы — если она уже есть без них, удалите её один раз (`PRECONDITION_FAILED` при переобъявлении).

## CI/CD

GitLab CI (`.gitlab-ci.yml`) деплоит через Docker Compose на ветке по умолчанию. Тесты в пайплайне пока **не** запускаются — прогоняйте `mvn verify` перед мержем.

## Лицензия

Apache License 2.0.
