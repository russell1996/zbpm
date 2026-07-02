# WO-SEC-4 — Rate-limit на /auth/login     [P2 / HIGH]

## Цель
Предотвратить brute-force атаки на эндпоинт логина.

## Проблема
```
zorrobpm-rest/.../resource/AuthResource.java:24
```
Нет throttle, lockout, CAPTCHA. Неограниченные попытки логина.

## Scope
**Можно трогать:** `AuthResource.java`, новый `RateLimitFilter.java` или Bucket4j interceptor,
`zorrobpm-rest/pom.xml` (добавить Bucket4j если нужно).  
**Нельзя:** менять `LoginDTO`, менять ответ при успешном логине, трогать engine.

## Решение
Bucket4j (in-memory, достаточно для MVP): 5 запросов / 1 минута с IP → 429 Too Many Requests.
При превышении: `{"code":"RATE_LIMITED","message":"Too many attempts, retry after 60s"}`.

## Критерии приёмки

| # | Критерий | Команда проверки | Ожидаемый факт |
|---|---|---|---|
| 1 | 6-я попытка → 429 | IT: 6 логинов подряд с одного IP | HTTP 429 + Retry-After header |
| 2 | После паузы → снова работает | IT: подождать, снова логин | HTTP 200 |
| 3 | Успешный логин (1-я попытка) не блокируется | IT: правильные данные | HTTP 200 |
| 4 | **proof-of-failure (V3)** | IT #1 на текущем коде | **КРАСНЫЙ** (200 вместо 429) |
| 5 | Регресс | `mvn clean verify` | BUILD SUCCESS |

## Запреты
Не блокировать `/auth/login` по Bearer (он не нужен там). Не ломать тесты которые тоже логинятся.

## DoD / отчёт
Таблица критерий → команда → факт. Proof-of-failure #4.
