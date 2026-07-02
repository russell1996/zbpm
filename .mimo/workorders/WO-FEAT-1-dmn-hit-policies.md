# WO-FEAT-1 — DMN Hit Policies: COLLECT / RULE ORDER / PRIORITY     [P3 / GAP-2]

## Цель
Реализовать недостающие hit policies в DMN-движке.

## Проблема
```
zorrobpm-engine/.../service/impl/DmnServiceImpl.java:94-106
```
```java
// UNIQUE/FIRST/ANY all return a single result — the first matching rule is sufficient here
break;   // ← все политики делают break на первом совпадении
```

| Hit Policy | Статус |
|---|---|
| UNIQUE | частично (нет валидации уникальности) |
| FIRST | ✓ |
| ANY | частично (нет валидации одинаковости) |
| COLLECT | ❌ не реализован |
| RULE ORDER | ❌ не реализован |
| OUTPUT ORDER | ❌ не реализован |
| PRIORITY | ❌ не реализован |

COLLECT также поддерживает агрегации: SUM, MIN, MAX, COUNT.

## Scope
**Можно трогать:** `DmnServiceImpl.java`, `DmnDecision` / `DmnRule` модели если нужны.  
**Нельзя:** менять API (`DmnContract`); менять формат хранения DMN в БД.

## Критерии приёмки

| # | Критерий | Команда проверки | Ожидаемый факт |
|---|---|---|---|
| 1 | COLLECT возвращает все совпадения | IT: DMN с COLLECT, 3 matching rules | 3 результата в output |
| 2 | COLLECT SUM агрегирует | IT: COLLECT+SUM, matching rules 10+20 | output = 30 |
| 3 | RULE ORDER — порядок совпадает с порядком в таблице | IT: RULE ORDER | результаты упорядочены как строки |
| 4 | UNIQUE: >1 совпадения → ошибка | IT: 2 matching rules в UNIQUE | 422 EVALUATION_ERROR |
| 5 | FIRST по-прежнему работает | existing DMN IT | зелёные |
| 6 | **proof-of-failure (V3)** | IT #4 на текущем коде | **КРАСНЫЙ** (возвращает 1 результат вместо ошибки) |
| 7 | Регресс | `mvn clean verify` | BUILD SUCCESS |

## DoD / отчёт
Таблица критерий → команда → факт. Proof-of-failure #6.
