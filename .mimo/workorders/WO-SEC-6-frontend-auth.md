# WO-SEC-6 — JWT httpOnly cookie + role guard на роутах     [P2 / HIGH]

## Цель
(a) Перенести JWT из `localStorage` в `httpOnly; Secure; SameSite=Strict` cookie (устраняет XSS-кражу токена).
(b) Добавить role-guard на admin-роуты фронтенда.

## Проблемы (проверено по диску)
- `zorrobpm-frontend/src/stores/auth.ts:6,10,18-20` — токен в `localStorage` (XSS-доступен).
- `zorrobpm-frontend/src/services/api.ts:9-12` — interceptor ставит `Authorization: Bearer`.
- `zorrobpm-frontend/src/app/router.ts` — route `admin/users` (стр. ~124) имеет только `requiresAuth`,
  guard `beforeEach` роль НЕ проверяет → любой авторизованный открывает admin.
- `JwtAuthFilter.java:80` — читает токен только из `Authorization` header, cookie не читает.

---

## 🧭 РЕШЕНИЯ CTO (проверено по диску)

**Мина SameSite снята:** фронт ходит на `/api` через прокси — vite dev (`vite.config.ts:17`
`/api`→`localhost:8080`) и nginx prod (`/api/`→backend). Значит фронт↔бэкенд **same-origin**
в обоих окружениях → `httpOnly; SameSite=Strict` cookie работает и в dev, и в prod.
**Важно:** фронт должен слать запросы на относительный `/api`, не на `:8080` напрямую.

**CSRF:** перенос в cookie вводит CSRF-риск, но `SameSite=Strict` + same-origin его закрывает
(cross-site запрос не приложит cookie). Это причина Strict, а не Lax.

**Нет frontend-тестов (проверено):** `package.json` scripts = только dev/build/preview,
нет vitest/playwright. CI гоняет `npm run build` (tsc typecheck + bundle).
→ **pre-approved:** добавить `vitest` в devDependencies + `"test": "vitest run"` script
(минимальная инфра, аналог validation-стартера в WO-SEC-3). Guard-логику вынести в чистую
функцию для unit-теста.

---

## Scope
**Backend:** `AuthResource.java` (login дополнительно ставит `Set-Cookie: zbpm_token=...;
HttpOnly; Secure; SameSite=Strict; Path=/` — JSON body с токеном МОЖНО оставить для Java-клиента);
`JwtAuthFilter.java` (читать токен из cookie `zbpm_token` ИЛИ из `Authorization: Bearer` — Bearer первым).
**Frontend:** `auth.ts` (убрать localStorage), `api.ts` (убрать Bearer interceptor,
добавить `withCredentials: true`), `router.ts` (route `admin/users` → `meta.requiresAdmin: true`;
в `beforeEach` — если `to.meta.requiresAdmin && !auth.isAdmin` → redirect на dashboard/403;
guard-логику вынести в тестируемую функцию `resolveGuard(to, auth)`).
**Тесты:** backend IT (MockMvc), frontend vitest (guard).

**Нельзя:** менять `zorrobpm-contract`; ломать Java-клиентов (Bearer остаётся как альтернатива);
слать запросы напрямую на `:8080` в обход прокси.

## Критерии приёмки — переформулированы под доступную проверку

| # | Критерий | Тест | Ожидаемый факт |
|---|---|---|---|
| 1 | Логин ставит httpOnly cookie | backend IT (MockMvc) | `Set-Cookie: zbpm_token=...; HttpOnly` |
| 2 | Запрос с cookie (без Authorization) → авториз. | backend IT (`.cookie(...)`) | 200 |
| 3 | Bearer header всё ещё работает (Java-клиент) | backend IT | 200 |
| 4 | `logout`/init не трогают localStorage (токен там не хранится) | grep + `tsc` | нет `localStorage.*zbpm_token` в auth.ts |
| 5 | Guard: USER на requiresAdmin-роут → redirect | **vitest** unit `resolveGuard` | возвращает redirect |
| 6 | Guard: ADMIN на requiresAdmin-роут → пропуск | **vitest** unit `resolveGuard` | возвращает undefined (пропуск) |
| 7 | **proof-of-failure (V3)** | vitest #5 без `requiresAdmin`/isAdmin | **КРАСНЫЙ** (USER проходит) |
| 8 | Регресс | `mvn clean verify` + `npm run build` + `npm test` | всё зелёное |

> Реальный browser-e2e (cookie в DevTools, редирект в UI) — **ручная проверка со скриншотом**
> в отчёте (playwright не ставим). Автоматизированное покрытие — backend IT + vitest guard.

## Запреты
Не ломать Java-клиентов (Bearer). Не убирать `Secure` в prod-cookie. Запросы только через `/api`.

## Гейты
G6 (security) + HARD GATES G-A…G-G. CTO review + CI green (backend + frontend build + vitest).

## DoD / отчёт
Таблица #1–#8 → команда → факт. Proof-of-failure #7 (vitest RED→GREEN). Скриншот browser-проверки. Отчёт в `mimo-to-cto.md`.
