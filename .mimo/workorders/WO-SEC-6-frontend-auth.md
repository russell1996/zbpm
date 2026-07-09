# WO-SEC-6 — JWT httpOnly cookie + role guard на роутах     [P2 / HIGH]

## Цель
(a) Перенести JWT из `localStorage` в `httpOnly; Secure; SameSite=Strict` cookie — устраняет XSS-кражу токена.
(b) Добавить role-guard на admin-роуты во фронтенде.

## Проблемы
```
zorrobpm-frontend/src/stores/auth.ts:10,19-20
```
```typescript
const TOKEN_KEY = 'zbpm_token'
localStorage.getItem(TOKEN_KEY)  // XSS-уязвимость
```

```
zorrobpm-frontend/src/app/router.ts:127,140-153
```
Route `/admin/users` проверяет только `isAuthenticated`, не `isAdmin`.

## Scope
**Backend:** `AuthResource.java` — выдавать `Set-Cookie: zbpm_token=...; HttpOnly; Secure; SameSite=Strict`
вместо (или плюс) JSON body. `JwtAuthFilter.java` — читать токен из cookie ИЛИ из Bearer header
(backward compat с Java-клиентом).  
**Frontend:** `auth.ts` — убрать localStorage, убрать ручной `Authorization: Bearer` из `api.ts` (cookie автоматом),
`router.ts` — добавить `meta.requiresAdmin` + guard.

**Нельзя:** менять `zorrobpm-contract`; ломать Java-клиентов (они используют Bearer — оставить как альтернативу).

## Критерии приёмки

| # | Критерий | Команда проверки | Ожидаемый факт |
|---|---|---|---|
| 1 | Логин устанавливает httpOnly cookie | curl -c cookies.txt POST /auth/login | `Set-Cookie: ...; HttpOnly` |
| 2 | Запрос с cookie (без Authorization header) → авториз. | curl --cookie cookies.txt GET /user-tasks | 200 |
| 3 | Bearer header всё ещё работает | curl -H "Authorization: Bearer ..." | 200 |
| 4 | localStorage не содержит токен | e2e: проверить `localStorage.getItem('zbpm_token')` | `null` |
| 5 | `/admin/users` для USER-роли → redirect /403 | e2e: логин как user → navigate /admin/users | redirect |
| 6 | `/admin/users` для ADMIN → доступен | e2e: логин как admin | страница открывается |
| 7 | **proof-of-failure (V3)** | IT #5 на текущем коде | **КРАСНЫЙ** (USER видит /admin/users) |
| 8 | Регресс | `mvn clean verify` + `npm run build` | оба BUILD SUCCESS |

## Запреты
Не ломать Java-клиентов (Bearer должен остаться). Не убирать HTTPS проверку в prod.

## DoD / отчёт
Таблица критерий → команда → факт. Proof-of-failure #7.
