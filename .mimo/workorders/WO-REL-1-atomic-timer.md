# WO-REL-1 — Атомарный timer-fire + защита от дублей     [P1 / HIGH]

## Цель
Сделать выборку и пометку таймера атомарными, чтобы при N-нодах один job срабатывал ровно один раз.

## Проблема (точно по коду)

```
zorrobpm-engine/.../scheduler/TimerScheduler.java:26-27
```
```java
@Scheduled(fixedDelayString = "${zorrobpm.engine.timer-poll-interval-ms:5000}")
public void fireDueTimers() {
```
Нет распределённой координации. На 2 нодах один job fire-ится дважды.

```
zorrobpm-engine/.../service/impl/DBServiceImpl.java:473-477
```
```java
public void markTimerJobFired(UUID timerJobId) {
    TimerJobEntity entity = timerJobRepository.findById(timerJobId).orElseThrow();
    entity.setFired(true);
    timerJobRepository.save(entity);   // read-modify-write, не атомарно
}
```

## Scope — строго
**Можно трогать:** `TimerScheduler.java`, `DBServiceImpl.markTimerJobFired`, `TimerJobRepository`,
аналогично для timer-start jobs.  
**Допустимо:** `UPDATE timer_jobs SET fired=true WHERE id=? AND fired=false` (claim-then-fire) ИЛИ
`SELECT FOR UPDATE SKIP LOCKED` в repository.  
**Нельзя:** leader election / ShedLock (это ADR-2), менять timer-start scheduling logic за рамками атомарности.

## Критерии приёмки

| # | Критерий | Команда проверки | Ожидаемый факт |
|---|---|---|---|
| 1 | Concurrent claim — job fire-ится ровно 1 раз | IT: 2 потока / 2 поллера одновременно по одному due-job | ровно 1 signal / continuation в БД |
| 2 | Существующие timer-IT не сломаны | прогнать все `*Timer*IntegrationTest*` | зелёные |
| 3 | timeCycle repeat сохранён | timeCycle IT | re-arm работает |
| 4 | **proof-of-failure (V3, V6)** | IT #1 на ТЕКУЩЕМ коде, реальные 2 потока | **КРАСНЫЙ** (2 срабатывания) |
| 5 | Индекс на `(fired, due_at)` | Liquibase changeset + `EXPLAIN` | index scan |
| 6 | Регресс | `mvn clean verify` | BUILD SUCCESS |

## Запреты (V4/V6)
**Конкурентность реальная** — два потока, не sequential. `@Disabled` запрещён.
Leader election — только через ADR-2. Scope строго.

## Гейты
G3 + G4 + G5 (если индекс).

## DoD / отчёт
Таблица критерий → команда → факт. IT #4 показан красным до фикса и зелёным после.
Приложить как воспроизведена конкуренция (2 Thread / CountDownLatch / etc).
