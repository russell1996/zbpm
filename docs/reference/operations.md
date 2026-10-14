# Эксплуатация: конфигурация, мониторинг, БД, CI/CD, ограничения

> Всё, что нужно тем, кто разворачивает и сопровождает ZBPM. Вход в документацию — [README](../../README.md), карта — [docs/README.md](../README.md).

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
- Management UI RabbitMQ: `http://localhost:9300` (по умолчанию `zorrodev`/`zorrodev`).
  Брокер запущен с `management.path_prefix = /rabbitmq` (WO-QW-12), поэтому корень
  отдаёт 301 на `/rabbitmq/`, а management-API живёт по путям `/rabbitmq/api/…`
  (без префикса — 404, редиректа нет)

Остановить: `docker compose down` (добавьте `-v`, чтобы удалить тома с данными).

## Мониторинг из коробки (WO-OBS-2)

```bash
# 1) создайте сервисный API-ключ как SUPER_ADMIN (UI → Admin → API keys или curl /api/auth) и положите в файл:
echo -n "zbpm_sk_..." > ci/observability/scrape-token && chmod 600 ci/observability/scrape-token
# 2) задайте пароль Grafana в .env:
echo "GRAFANA_ADMIN_PASSWORD=$(openssl rand -base64 24)" >> .env
# 3) поднимите overlay поверх основного стека:
docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d
# 4) откройте Grafana:
open http://localhost:8081/grafana/   # логин admin / ваш пароль
```

Prometheus скрейпит `app:8080/actuator/prometheus` (bearer-токен из `scrape-token`), `rabbitmq:15692/metrics`, `postgres-exporter:9187`, `node-exporter:9100` и себя. Ретенция — `30d` по умолчанию (`PROMETHEUS_RETENTION` в `.env`). Дашборды — см. `WO-OBS-3`, алерты — `WO-OBS-5` (9 правил: quarantine/DLQ/stuck-service-task/диск/app-down/health/Hikari/rabbit-unroutable/incident-spike; OFF по умолчанию, `GRAFANA_ALERT_WEBHOOK_URL` пусто → Firing без notification, задайте webhook — Telegram `https://api.telegram.org/bot<token>/sendMessage?chat_id=<id>` или Slack `https://hooks.slack.com/...` — чтобы слать). Одно-хостовая ретенция честно: TSDB локальный, не HA-хранилище; для долгосрочного хранения — скрейпите внешним Prometheus.

Остановить мониторинг: `docker compose -f docker-compose.yml -f docker-compose.observability.yml down` (оставит основной стек).

**Frontend** собирается отдельным образом (`zorrobpm-frontend/Dockerfile`, multi-stage `node:22` → `nginx`).
В `src/` нет hardcoded `localhost`/IP: API-база — относительный `/api`, OIDC-redirect берётся из
`window.location.origin`.

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

## База данных и миграции

Схема управляется **Liquibase** (`zorrobpm-engine/src/main/resources/db/changelog`), применяется автоматически при старте. Ключевые таблицы: `process_definitions`, `process_instances`, `activities`, `tokens`, `variables`, `user_tasks`, `service_tasks`, `incidents`, `timer_jobs`, `message_subscriptions`, `signal_subscriptions`, `signal_start_subscriptions`, `parallel_gateways`, `dmn_definitions`, `outbox` (transactional outbox), `ui_users`, `refresh_tokens`, а также multi-tenant: `process` (реестр), `process_member` (владельцы/дизайнеры), `service_account` + `service_account_permission` (API-ключи интеграций).

## Ограничения и замечания по проду

- **JWT-авторизация включена по умолчанию** для всех эндпоинтов (`require-api-auth=true`). В прод-профиле приложение **не стартует** с дефолтным `jwt-secret` — задайте `ZORROBPM_JWT_SECRET`. Смените пароль `admin`.
- **Модель авторизации внедрена.** Multi-tenant RBAC (владение процессом: `OWNER`/`DESIGNER`, service accounts с правами, `SUPER_ADMIN`) — см. `docs/adr/ADR-1-multi-tenant-authorization.md`. Чтение открыто любому аутентифицированному; запись энфорсится по владельцу процесса (WO-MT-3/3b/7 — чужое правится только своим или админом, доказано `WriteEnforcementIntegrationTest`).
- **Видимость user task — на уровне процесса (WO-ACL-23, решение владельца 2026-10-07, вариант A).** Участник процесса видит ВСЕ его user task, в том числе чужие: фильтр `GET /user-tasks?assignee=<username>` значение не проверяет (осознанное решение, не баг). `403` на `?relatesTo=` чужого пользователя — UX-защита от случайного запроса, а НЕ граница безопасности (те же строки доступны через `?assignee=`). Условие пересмотра: процессы с чувствительными данными (зарплаты, HR, ПДн) — тогда person-level видимость отдельным WO (вариант B).
- **Масштабирование (scale-out).** Таймеры (`timer_jobs`/`timer_start_jobs`) безопасны при нескольких репликах: атомарный захват задач через `SELECT … FOR UPDATE SKIP LOCKED` — только один экземпляр обрабатывает каждую задачу (WO-REL-6/7, доказано PG-IT на реальном PostgreSQL); очистка ретенции безопасна как идемпотентные батч-удаления в своих транзакциях (WO-REL-7). Версионирование BPMN-процессов безопасно: `pg_advisory_xact_lock` на стороне PostgreSQL, виден всем репликам, не JVM-лок (WO-A-03, доказано `ProcessDefinitionPgIT`). Деплой DMN и форм безопасен тем же приёмом через общий `AdvisoryDeployLock` (ключи `dmn:`/`form:`, WO-SCALE-1, доказано `DmnDeployConcurrencyPgIT`/`FormDeployConcurrencyPgIT` на реальном PostgreSQL). Batch-деплой (`POST /deployments`) сериализуется тем же `AdvisoryDeployLock` перед проверкой дедупа по контенту — конкурентный деплой одинакового содержимого возвращает существующую версию, не 500 (WO-SCALE-4, `BatchDeployConcurrencyPgIT` 8 потоков + ре-ран на живом 3-репличном стенде). Rate-лимиты (`RateLimitFilter`, сброс пароля, тестовые письма) — общий Postgres-backed счётчик, лимит держится глобально независимо от числа реплик (WO-SCALE-2, `PgRateLimiter`, `RateLimitClusterPgIT`). SSE-события долетают до клиентов на всех репликах через RabbitMQ fan-out (ADR-7). Всё вышеперечисленное подтверждено 4-часовым нагрузочным прогоном на 3 репликах ([`docs/internal`](../../docs/internal)) — 5 критериев из 5. Разрешение инцидентов идемпотентно (WO-REL-5).
- **Ретенция.** Очистка терминальных (завершённых/отменённых) инстансов — fail-safe каскад, **выключена по умолчанию** (`RETENTION_TTL_DAYS=0`). Задайте число дней, чтобы включить автоудаление старых инстансов. Чистка `events`/`outbox` — тоже выключена по умолчанию, включается отдельно — см. [`docs/guides/retention.md`](../guides/retention.md).
- **CORS** ограничивается allowlist'ом в прод-профиле (`ZORROBPM_CORS_ORIGINS`, по умолчанию домен прода); в dev — открыт. Доступ в проде идёт через nginx reverse proxy.
- **Сборка требует JDK 21** (объявлено `java.version=21` во всех POM — WO-SCALE-0).
- При апгрейде на существующем брокере очередь `zorrobpm.complete-service-task` получает dead-letter-аргументы — если она уже есть без них, удалите её один раз (`PRECONDITION_FAILED` при переобъявлении).

## CI/CD

GitLab CI (`.gitlab-ci.yml`), self-hosted runner с Docker (tag `zbpm`). Стадии:

```
test  →  security  →  build  →  deploy  →  rollback  →  mirror
```

- **test** — `test:gate` (механические гейты отчётов задач), `test:backend` (полный reactor `mvn clean verify`: unit + интеграционные на H2), `test:pg` (PG-набор на настоящем PostgreSQL 16), `test:rabbit` (интеграция с RabbitMQ), `test:frontend` (typecheck + unit + браузерные тесты в Chromium); ручные: `test:chaos`, `test:e2e-login`.
- **security** — `cve:scan` / `cve:report` / `cve:npm` (сканирование зависимостей).
- **build** — Docker-образы backend и frontend с тегом коммита (`$CI_COMMIT_SHORT_SHA`) + `latest`.
- **deploy** — **ручной запуск** (`when: manual`, в т.ч. на ветке по умолчанию — WO-AUD-6, чтобы неудачная сборка не уезжала в прод автоматически): `docker compose up -d` с версионными тегами; предыдущий тег сохраняется для отката.
- **rollback** — ручная стадия: переразвёртывание предыдущего тега.
- **mirror** — `mirror:github` синхронизирует публичное зеркало кода (без `docs/internal` и внутренних скриптов).
- Также `backup:pg` — резервное копирование PostgreSQL.

Прод за внешним nginx reverse proxy: `https://test-zbpm.telecom.kz` → `frontend` (nginx) → `/api` → `app:8080`.
