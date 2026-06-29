# WO-T2 — Атомарный timer-fire (защита от дублей)     [Приоритет: P0 / Critical]

## Цель
Сделать выборку и пометку «отработан» таймер-джоба **атомарными**, чтобы конкурентные поллеры (в т.ч.
будущий multi-node) не отрабатывали один и тот же timer-job дважды. Сейчас `TimerScheduler.fireDueTimers`
(`@Scheduled`) делает `findDueTimerJobs` (SELECT) + `markTimerJobFired` (load→set→save) **не атомарно**, без координации.

## Трассировка
Коды: **F1/F4/T2** ([analysis/04](../../docs/analysis/04-scheduler-events.md)). Роадмап: **T2** (Фаза 1).

## Объём (scope) — строго
Можно трогать: `zorrobpm-engine/.../scheduler/TimerScheduler.java`, `TimerJobExecutor`,
`DBServiceImpl.markTimerJobFired`/`findDueTimerJobs`, `TimerJobRepository` (аналогично timer-start).
Допустимо: `SELECT … FOR UPDATE SKIP LOCKED` ИЛИ conditional `UPDATE … WHERE fired=false` (claim-then-fire).
> Полноценный leader election/ShedLock — **структурно** → отдельный ADR (V10). Здесь — атомарный claim на уровне БД.

## Критерии приёмки (объективно, V2/V6)
| # | Критерий | Команда проверки | Ожидаемый факт |
|---|----------|------------------|----------------|
| 1 | Конкурентный claim не двоит | IT: 2 потока/«поллера» одновременно по одному due-job | job отрабатывается **ровно один раз** (1 signal/continuation) |
| 2 | Due-таймеры всё ещё срабатывают | существующие timer-IT | зелёные (нет регресса) |
| 3 | timeCycle repeat сохранён | timeCycle-IT | повтор/reschedule работает |
| 4 | **proof-of-failure (V3/V6)** | прогнать IT №1 на старом коде | **КРАСНЫЙ** (2 срабатывания), после фикса 1 |
| 5 | (Опц.) индекс `timer_jobs(fired,due_at)` | changeset + EXPLAIN | скан заменён на index scan (B3/T8) |
| 6 | Регресс | `mvn clean verify` | BUILD SUCCESS 10/10 |

## Запреты (V4/V6/V8)
**Конкурентность реальная** — два потока/транзакции, не последовательная имитация (иначе тест ничего не доказывает).
Без @Disabled; без «починил в single-node» под видом защиты от дублей; leader election — только через ADR; scope строго.

## Гейты
G3 + G4 + G5 (если индекс). Структурный механизм координации — G1 (ADR).

## DoD / отчёт
Таблица критерий→команда→факт; показать IT №1 красным до фикса и зелёным после; приложить, как воспроизведена конкуренция.
