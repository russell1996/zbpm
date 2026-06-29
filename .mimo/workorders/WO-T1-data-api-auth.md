# WO-T1 — Защита Data API     [Приоритет: P0 / Critical]

## Цель
Закрыть **все** Data API-эндпоинты аутентификацией. Сейчас `JwtAuthFilter.isProtected` защищает только
`/auth/me` и `/users/**`; `/process-*`, `/incidents`, `/variables`, `/dmn`, `/timer-jobs`,
`/message-subscriptions` — открыты без токена.

## Трассировка
Код наблюдения: **SEC1** ([analysis/08](../../docs/analysis/08-cluster-ha-security.md)). Роадмап: **T1** ([docs/README.md](../../docs/README.md) §3, Фаза 1).

## Объём (scope) — строго
Можно трогать: `zorrobpm-rest/src/main/java/.../security/JwtAuthFilter.java` (+ конфиг фильтра), при
необходимости новый `TenantContextFilter`; тесты в `zorrobpm-rest`/`zorrobpm-test`.
НЕЛЬЗЯ: менять `zorrobpm-engine`/`zorrobpm-contract` без ADR. **Флаг включения** обязателен, чтобы не сломать Java-клиент.
> Если выбран путь API-ключа на клиента (связка с T5) — это **структурное** изменение → сначала ADR (V10), затем код.

## Критерии приёмки (объективно, V2)
| # | Критерий | Команда проверки | Ожидаемый факт |
|---|----------|------------------|----------------|
| 1 | Защищённый Data API без токена → 401 | IT: `GET /process-instances` без `Authorization` | HTTP 401 |
| 2 | С валидным токеном → 200 | IT: тот же запрос с Bearer | HTTP 200 |
| 3 | Покрытие списка эндпоинтов | IT на `/process-*`,`/incidents`,`/variables`,`/dmn`,`/timer-jobs`,`/message-subscriptions` | все 401 без токена |
| 4 | Флаг включения | IT с выключенным флагом | поведение совместимо с Java-клиентом |
| 5 | **proof-of-failure (V3)** | до фикса прогнать IT №1 | **КРАСНЫЙ** (сейчас 200), после фикса зелёный |
| 6 | Регресс | `mvn clean verify` | BUILD SUCCESS 10/10 |

## Запреты (V4/V8)
Без @Disabled/skip; без ослабления ассертов; не «закрыл в конфиге» без IT, доказывающего 401; не трогать engine/contract без ADR; только этот scope.

## Гейты
G6 (security, владелец) + G3 + G4. Перед PR — [checklists](../checklists/checklists.md) §Безопасность.

## DoD / отчёт
Таблица критерий→команда→факт; список эндпоинтов с фактическими кодами; невыполненное — «НЕ СДЕЛАНО:<причина>».
