# Авторизация и безопасность

> Вход в UI, API-ключи, роли, настройки безопасности. Вход в документацию — [README](../../README.md), карта — [docs/README.md](../README.md).

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

## Политика DMN evaluate — «чтение определения»

`POST /dmn/{decisionId}/evaluate` — **чистая функция над переданными переменными**. Эндпоинт читает опубликованное ОПРЕДЕЛЕНИЕ решения (строку `dmn_definitions`) и считает таблицу решений в FEEL. Данные инстансов он **не читает и не пишет**: во входе нет ссылки на инстанс, а `DmnService.evaluate(decisionId, variables)` ходит только в `dmn_definitions`. Оценка чужого решения над своими переменными не раскрывает чужих данных.

Кто может вызвать (решение maintainer 2026-10-21, аудит F-21):

| Кто | Что видит | Ответ |
|---|---|---|
| `UserPrincipal` — обычный аутентифицированный пользователь, в том числе **не участник** процесса | все **определения** (ADR-8 §1) | `200` + результат |
| `ServicePrincipal` — API-ключ | определения в пределах грантов: та же резолюция, что `readableRuntimePdIds` | `200` + результат; без гранта на процесс-владелец решения — `404` (существование решения скрыто) |
| `SUPER_ADMIN` | всё | `200` + результат |
| без аутентификации | — | `401` |

Почему определения видны всем, а runtime-данные — нет: ADR-8 §1 («видит все процессы» = **только определения** — BPMN, схема, версии; экземпляры, переменные, задачи и инциденты чужих процессов пользователю не видны). `GET /dmn` и `GET /dmn/{id}` следуют той же политике.

Что ограничивает саму оценку: rate-limit (`/dmn` — DATA-бакет) и лимит размера запроса (5 МБ, `DeployDmnDTO.MAX_DMN_LENGTH`). Политика закреплена тестом `DmnEvaluatePolicyIntegrationTests` (модуль `zorrobpm-rest`) и описана в Javadoc `DmnResource.evaluateDecision`.
