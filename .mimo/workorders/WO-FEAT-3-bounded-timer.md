# WO-FEAT-3 — Bounded Repeating Timers R<n>/PT     [P3 / GAP-4]

## Цель
Реализовать ограниченное повторение таймеров: `R3/PT1H` должен сработать ровно 3 раза.

## Проблема
```
zorrobpm-engine/.../scheduler/TimerExpressions.java:63-70
```
`rearmRepeatingBoundaryTimer` re-arms только при `isInfiniteCycle` (unbounded `R/PT...`).
Bounded repetition `R<n>/PT...` — только первое срабатывание, re-arm не происходит.

## Scope
**Можно трогать:** `TimerExpressions.java`, `TimerScheduler.java` / `TimerJobExecutor` (re-arm logic),
`TimerJobEntity` (добавить `remainingCount`), Liquibase changeset.  
**Нельзя:** менять infinite repeat logic; трогать другие типы таймеров.

## Критерии приёмки

| # | Критерий | Команда проверки | Ожидаемый факт |
|---|---|---|---|
| 1 | `R3/PT1S` → 3 срабатывания | IT: timer boundary с R3/PT1S, дождаться 3с | ровно 3 signal в БД |
| 2 | После 3-го — не срабатывает | IT: тот же, ждать 4с | ровно 3, не 4 |
| 3 | `R/PT1S` (infinite) по-прежнему работает | existing timer IT | зелёные |
| 4 | **proof-of-failure (V3)** | IT #1 на текущем коде | **КРАСНЫЙ** (1 срабатывание вместо 3) |
| 5 | Регресс | `mvn clean verify` | BUILD SUCCESS |

## DoD / отчёт
Таблица критерий → команда → факт. Proof-of-failure #4.
