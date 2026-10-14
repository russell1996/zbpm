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
