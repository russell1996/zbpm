# 04 — Scheduler, Timers & Events (as-is)

## Таймеры
**`TimerScheduler`** [scheduler/TimerScheduler.java] — `@Scheduled(fixedDelay = ${...timer-poll-interval-ms:5000})`.
Каждые 5s: `findDueTimerJobs(now)` → `executor.fire(job)`; `findDueTimerStartJobs(now)` → `startExecutor.fire(...)`.
Ошибки одного job логируются и не валят цикл.

- **Виды:** boundary timer (interrupting/non-interrupting), intermediate timer catch, timer start, timer-triggered
  event subprocess. `createTimerJob(activityId, dueAt[, boundaryElementId])`, `createEventSubprocessTimerJob`, `createTimerStartJob`.
- **`TimerExpressions`** [scheduler/TimerExpressions.java] — первая выдача `timeCycle`: ISO `R[n]/<duration>`
  и Spring 6-field cron. `repeats()`: безграничный `R/<dur>` или cron повторяются; `R<n>` ограничен (счётчика нет — **C3** роадмапа).
  ✅ **timeCycle реализован** (старая память Mimo «не поддерживается» — устарела).
- **Повтор:** timer-start перепланирует следующий запуск; non-interrupting boundary перевзводится каждый цикл, пока хост активен.

### Проблемы таймеров
- **F1/T2.** `@Scheduled` без координации → в multi-node каждая нода выберет тот же due-job → **дубли**.
- **F4/T2.** `findDueTimerJobs` (SELECT) и `markTimerJobFired` (load→set→save) **не атомарны**: нет
  `SELECT … FOR UPDATE SKIP LOCKED` / conditional `UPDATE … WHERE fired=false`. Single-node спасает `fixedDelay`
  (нет перекрытия), но это допущение, не гарантия.
- **B3.** Полный скан due-job каждые 5s без `timer_jobs(fired, due_at)` индекса.

## Сообщения (message)
- **Catch/receive** [`enterMessageCatch` :461]: создаёт подписку (`message_subscriptions`), опц. с
  correlation-key (`evaluateCorrelationKey` :477 — FEEL по переменным) и boundary-привязкой; токен паркуется.
- **Throw/send** [`processMessageThrow` :382 / `processSendTask` :366]: `zeebe:taskDefinition` → job worker;
  иначе message-throw (`correlateMessage`). Корреляция: `correlateMessage(name[, key], pi, vars)` — будит подписки
  по имени (+ опц. key для таргетированной доставки среди инстансов с одним именем).
- **Message start** — `message_start_subscriptions` (по версии определения; новая версия вытесняет старую).

## Сигналы (signal)
- **Catch** [`enterSignalCatch` :424] — подписка `signal_subscriptions`, токен паркуется.
- **Throw** [`processSignalThrow` :406] — broadcast 1:N: будит **все** активные подписки (`findSignalSubscriptions`).
- **Signal start** — `signal_start_subscriptions`; broadcast стартует инстанс каждого подписчика.

## Event subprocesses
`subscribeEventSubprocesses` [:1969] на старте инстанса регистрирует триггеры: message → instance-scoped подписка;
signal → подписка; timer → `createEventSubprocessTimerJob`. Error-триггер — без подписки (через throwError-propagation).
Interrupting и non-interrupting (message — обе формы).

## Conditional events
`enterConditionalCatch` [:498]: если FEEL-условие уже истинно — pass-through; иначе паркуется.
`triggerConditionalEvents(pi)` [:530] вызывается после изменения переменных (например, после complete) и
переоценивает активные conditional-catch/boundary, будя те, чьё условие стало истинным.
> conditional-события — **проектное расширение** (в Camunda 8 отсутствуют by design), не гэп.

## Сводно
Семантика событий богатая и аккуратная (start/catch/throw/boundary/event-subprocess по всем 4 типам триггеров).
Главный системный риск — **планировщик таймеров не кластеро-безопасен** (F1/F4/T2): это первый кандидат
HA-трека вместе с моделью исполнения.
