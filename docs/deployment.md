# ZorroBPM — Deployment Runbook

Сборка, упаковка, развёртывание и откат ZorroBPM (backend + frontend) через GitLab CI/CD и Docker Compose.

## Топология (production)

```
                 https://zorro.i-smet.kz
                          │
                 ┌────────▼────────┐
                 │ external nginx  │  (reverse proxy)
                 │  reverse proxy  │
                 └────────┬────────┘
                          │  :FRONTEND_PORT → :80
                 ┌────────▼────────────────────────┐
                 │ frontend (nginx, SPA)            │
                 │  /        → static dist/         │
                 │  /api/    → http://app:8080/     │   (Docker DNS, не IP)
                 └────────┬─────────────────────────┘
                          │
                 ┌────────▼────────┐   ┌────────────┐   ┌────────────┐
                 │ app (backend)   │──▶│ postgres   │   │ rabbitmq   │
                 │  :8080          │   │  :5432     │   │  :5672     │
                 └─────────────────┘   └────────────┘   └────────────┘
```

- Frontend ходит в API **только по относительному `/api`**; nginx снимает префикс `/api` и проксирует на
  backend (`app:8080`), эндпоинты которого живут в корневом контексте.
- Внутри сети сервисы адресуются **по именам** (`app`, `postgres`, `rabbitmq`) — без hardcoded IP.
- OIDC redirect resolves to `window.location.origin` (= `https://zorro.i-smet.kz`).

## Артефакты и образы

| Артефакт | Где собирается | Тег |
|---|---|---|
| backend jar | `Dockerfile` (multi-stage maven → JRE) | — (внутри образа) |
| `zorrobpm-app` image | `Dockerfile` (context `.`) | `:$CI_COMMIT_SHORT_SHA`, `:latest` |
| frontend `dist/` | `zorrobpm-frontend/Dockerfile` (node 22) | — (внутри образа) |
| `zorrobpm-frontend` image | `zorrobpm-frontend/Dockerfile` (nginx) | `:$CI_COMMIT_SHORT_SHA`, `:latest` |

## Pipeline (`.gitlab-ci.yml`)

```
test (backend mvn verify, frontend npm build)  →  package (docker build, SHA+latest)
   →  deploy (compose up, запись .current_tag)  →  rollback (manual, .previous_tag)
```

- `deploy` авто на ветке по умолчанию, вручную — на остальных.
- `rollback` — ручная стадия; разворачивает образы из `.previous_tag`.
- Образы хранятся на деплой-хосте; `docker image prune -f` удаляет только dangling, поэтому
  SHA-тегированные образы (включая цель отката) сохраняются.

## Переменные окружения

Хостовый `$DEPLOY_DIR/.env` (НЕ коммитится) хранит секреты; дефолты — в `.env.example`:

| Переменная | Назначение |
|---|---|
| `DB_NAME` / `DB_USERNAME` / `DB_PASSWORD` / `DB_PORT` | PostgreSQL |
| `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` / `RABBITMQ_PORT` / `RABBITMQ_MGMT_PORT` | RabbitMQ |
| `APP_PORT` / `APP_TAG` | backend порт / тег образа |
| `FRONTEND_PORT` / `FRONTEND_TAG` | frontend порт / тег образа |

`APP_TAG`/`FRONTEND_TAG` в деплое выставляются пайплайном в `$CI_COMMIT_SHORT_SHA` (поверх `.env`).

## Ручной деплой (без CI)

```bash
cd /opt/zorro-bpm
cp .env.example .env            # один раз, затем отредактировать секреты
export APP_TAG=$(git rev-parse --short HEAD) FRONTEND_TAG=$(git rev-parse --short HEAD)
docker compose build
docker compose up -d --remove-orphans
docker compose ps
echo "$APP_TAG" > .current_tag
```

## Откат

Через CI: запустить ручную стадию **rollback** последнего успешного пайплайна.

Вручную на хосте:

```bash
cd /opt/zorro-bpm
export APP_TAG=$(cat .previous_tag) FRONTEND_TAG=$(cat .previous_tag)
docker compose up -d --remove-orphans
cp .previous_tag .current_tag
```

## Health checks

- `postgres` — `pg_isready`; `rabbitmq` — `rabbitmq-diagnostics ping`.
- `frontend` — `GET /healthz` → `200 ok` (nginx).
- `app` — `GET /actuator/health` если включён actuator, иначе порт `:8080`.

## Проверки готовности к поставке

1. `mvn clean verify` — зелёный (backend, полный reactor).
2. `cd zorrobpm-frontend && npm ci && npm run build` — зелёный (typecheck + сборка).
3. `docker compose build` — оба образа собираются.
4. `docker compose up -d && docker compose ps` — все сервисы healthy.
5. UI доступен, API отвечает по относительному `/api` через nginx.
