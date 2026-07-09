# WO-SEC-1 — Аутентификация всех Data API эндпоинтов     [P0 / CRITICAL]

## Цель
Закрыть **все** runtime и query эндпоинты JWT-аутентификацией.
Сейчас `JwtAuthFilter.isProtected` защищает только `/auth/me` и `/users/**`.

## Проблема (точно по коду)
```
zorrobpm-rest/.../security/JwtAuthFilter.java:29-31
```
```java
private static boolean isProtected(String path) {
    return path.equals("/auth/me") || isUsersPath(path);
}
```
Всё остальное — `/process-instances`, `/user-tasks`, `/variables`, `/incidents`, `/dmn`, `/timer-jobs`,
`/message-subscriptions`, `/process-definitions`, `/service-tasks` — открыто без токена.

## Scope — строго
**Можно трогать:** `JwtAuthFilter.java` и её тесты в `zorrobpm-rest`.  
**Нельзя:** менять `zorrobpm-engine`, `zorrobpm-contract`; менять логику auth-flow (только фильтрацию).  
**Флаг:** `zorrobpm.security.require-api-auth=true` — по умолчанию `true` в prod, `false` в dev/test для обратной совместимости Java-клиентов.

## Критерии приёмки

| # | Критерий | Команда проверки | Ожидаемый факт |
|---|---|---|---|
| 1 | GET /process-instances без токена → 401 | `curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/process-instances` | `401` |
| 2 | GET /user-tasks без токена → 401 | аналогично | `401` |
| 3 | POST /process-instances без токена → 401 | аналогично | `401` |
| 4 | POST /user-tasks/{id}/complete без токена → 401 | аналогично | `401` |
| 5 | С валидным Bearer → прежнее поведение | IT с реальным логином → запросом | `200` |
| 6 | `/auth/login` без токена → 200 | curl POST /auth/login | `200` (не заблокирован) |
| 7 | Флаг выключен → пропускает без токена | IT с `require-api-auth=false` | `200` |
| 8 | **proof-of-failure (V3)** | прогнать IT #1 на ТЕКУЩЕМ коде | **КРАСНЫЙ** (200 вместо 401) |
| 9 | Регресс | `mvn clean verify -pl zorrobpm-rest` | BUILD SUCCESS |

## Запреты (V1–V10)
Запрещено: `@Disabled` на тестах; "закрыл в конфиге без IT"; трогать engine/contract.  
Конкурентный тест не нужен — это не гонка, это конфигурация фильтра.

## Гейты
Security review CTO. IT #1–7 зелёные. BUILD SUCCESS.

## DoD / отчёт
Таблица критерий → команда → фактический вывод. Явно показать proof-of-failure (#8).
