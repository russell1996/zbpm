# Reliability & Performance Analysis — ZorroBPM CE

---

## R-1
**Потеря service-task при сбое RabbitMQ (afterCommit publish)**

```
zorrobpm-engine/.../service/impl/ServiceTaskEnqueueServiceImpl.java:34-68
```

```java
TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
    @Override
    public void afterCommit() {
        publisher.publishEvent(new ServiceTaskEnqueued(detail));
    }
});
```

Сценарий потери данных:
1. Транзакция коммитится → service-task в БД со статусом `CREATED`
2. `afterCommit()` вызывается — RabbitMQ недоступен / приложение падает
3. Publish не состоялся → воркер никогда не получает задачу
4. Инстанс подвисает навсегда в `CREATED`, инцидент не создаётся

Нет retry, нет outbox-таблицы, нет polling для `CREATED` задач старше N минут.

**Severity: HIGH** — молчаливая потеря данных без алерта.

**Фикс (WO-REL-2):** transactional outbox: вместо `afterCommit` писать в таблицу `outbox`, отдельный поллер публикует и помечает `published=true`. Или: recovery-job переотправляет `CREATED` старше 5 минут.

---

## R-2
**Timer-scheduler без distributed lock — N-кратный fire на N-нодах**

```
zorrobpm-engine/.../scheduler/TimerScheduler.java:26-27
```

```java
@Scheduled(fixedDelayString = "${zorrobpm.engine.timer-poll-interval-ms:5000}")
public void fireDueTimers() {
```

При horizontal scaling (2+ подов): каждый под опрашивает БД каждые 5 секунд и пытается fire один и тот же timer-job. `markTimerJobFired` не атомарно с фактическим выполнением (см. R-3), поэтому один job может получить обработку N раз.

Пессимистичный lock на `processInstance` частично помогает — движок не продвинет токен дважды. Но: N лишних DB-транзакций, N лишних RabbitMQ-операций, deadlock-давление на lock.

**Severity: MEDIUM** — блокирует горизонтальное масштабирование.

**Фикс (WO-REL-1):** атомарный `UPDATE timer_jobs SET fired=true WHERE id=? AND fired=false` — claim один раз по БД; или ShedLock leader-election.

---

## R-3
**`markTimerJobFired` не атомарно с fire-action**

```
zorrobpm-engine/.../service/impl/DBServiceImpl.java:473-477
```

```java
public void markTimerJobFired(UUID timerJobId) {
    TimerJobEntity entity = timerJobRepository.findById(timerJobId).orElseThrow();
    entity.setFired(true);
    timerJobRepository.save(entity);
}
```

Последовательность в `TimerJobExecutor`:
1. `markTimerJobFired(id)` → `fired=true` в одной транзакции
2. `activityService.signal(...)` → другая транзакция

Если приложение падает после (1) но до (2) — job помечен как fired, но signal не выполнен. Timer потерян навсегда (нет recovery).

**Severity: LOW** — редкий сценарий при crash в узком окне.

**Фикс:** вынести `markTimerJobFired` + `signal` в одну транзакцию; или: пометить `IN_PROGRESS`, после signal → `DONE`.

---

## R-4
**`BpmnServiceImpl` in-memory cache без eviction — риск OOM**

```
zorrobpm-engine/.../service/impl/BpmnServiceImpl.java:20
```

```java
private final Map<UUID, BpmnProcessDefinitionModel> models = new ConcurrentHashMap<>();
```

`computeIfAbsent` атомарен (race condition исправлен). Но кеш растёт без ограничений:
каждый `processDefinitionId` остаётся в памяти навсегда. При многих версиях процессов и длительной работе → OOM.

**Severity: MEDIUM** — проблема при prod с большим количеством версий.

**Фикс:** заменить на Caffeine с `maximumSize` + `expireAfterAccess`. Или `@Cacheable` + Spring CacheManager.

---

## R-5
**`DBServiceImpl.setVariables` TOCTOU под concurrent writers**

```
zorrobpm-engine/.../service/impl/DBServiceImpl.java:300-319
```

```java
ProcessVariableEntity entity = (scopeId == null
    ? variableRepository.findByNameAndProcessInstanceIdAndScopeIdIsNull(...)
    : variableRepository.findByNameAndProcessInstanceIdAndScopeId(...))
    .orElseGet(() -> { /* создать новую */ });
```

Два concurrent вызова `setVariables` для одной и той же переменной: оба проходят `find` → `null`, оба создают новую сущность → UniqueConstraintViolation → 500.

Защита от этого — пессимистичный lock через `lockProcessInstance`. Он берётся в `lockAndReload` перед вызовами `setVariables` в основном пути. Но если `setVariables` вызывается без предварительного lock — race возможен.

**Severity: MEDIUM** — нужна аудит всех call-site `setVariables` + fallback на `INSERT ... ON CONFLICT UPDATE`.

---

## R-6
**`DmnServiceImpl.listDecisions()` загружает все версии всех DMN в память**

```
zorrobpm-engine/.../service/impl/DmnServiceImpl.java:126-138
```

```java
for (DmnDefinitionEntity e : dmnDefinitionRepository.findAll()) {
    ...
}
```

`findAll()` без пагинации и без фильтра по версии. При 100+ DMN-файлах и 10 версиях каждого → 1000+ записей в памяти для отображения списка.

**Severity: MEDIUM** — деградация производительности при prod с большим каталогом.

**Фикс:** `GROUP BY decision_id HAVING MAX(version)` на уровне репозитория / native query.

---

## R-7
**Parallel gateway join с root-token → потенциальный NPE**

```
zorrobpm-engine/.../service/impl/ActivityServiceImpl.java:784-790
```

```java
Token token = dbService.getToken(tokenId);
UUID oldTokenId = token.getParentId();    // может быть null для root-токена
UUID activityId = dbService.createActivity(processInstanceId, oldTokenId, bpmnElement);
```

Root-токен (созданный без parent, например для interrupting event subprocess на строке 1843) имеет `parentId = null`. Если параллельный gateway join достигается root-токеном — `oldTokenId` будет null, что может нарушить FK constraint или вызвать NPE в downstream логике.

**Severity: LOW** — специфичная архитектурная ситуация, редкая в реальных процессах.

---

## R-8
**N+1: `getProcessInstance()` на каждый flow-переход**

```
zorrobpm-engine/.../service/impl/ActivityServiceImpl.java:1993-2003
```

```java
private UUID processFlow(UUID processInstanceId, UUID tokenId, String flowId, ...) {
    ProcessInstance processInstance = dbService.getProcessInstance(processInstanceId);
    UUID processDefinitionId = processInstance.getProcessDefinitionId();
    BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(processDefinitionId);
```

На каждый sequence flow-переход (их может быть десятки для сложного процесса) выполняется DB-запрос `SELECT * FROM process_instances WHERE id=?`. `processDefinitionId` не изменяется в течение жизни инстанса — можно передать как параметр.

**Severity: MEDIUM** — производительность деградирует при длинных линейных процессах.

**Фикс:** передать `processDefinitionId` как параметр в `processFlow`, избрав лишние запросы.

---

## R-9
**Переменные пишутся дважды при старте инстанса + риск NPE при null variables**

```
zorrobpm-engine/.../service/impl/ActivityServiceImpl.java:1943-1945
```

```java
UUID processInstanceId = dbService.createProcessInstance(parentActivityId, processDefinitionId, variables);
// ... далее:
dbService.setVariables(processInstanceId, variables);   // variables может быть null
```

`createProcessInstance` уже вставляет переменные (с null-guard через `Optional.ofNullable(variables).orElse(List.of())`). Потом `setVariables` вызывается снова с оригинальным `variables` ref — если он `null`, то `DBServiceImpl.setVariables` делает `for(var v : variables)` → NPE.

Двойная запись неэффективна, NPE-риск на старте с пустыми переменными.

**Severity: LOW** — исправить null-guard в `setVariables` call-site.

---

## Покрытие тестами (reliability-аспект)

| Сценарий | Тест | Вердикт |
|---|---|---|
| Timer double-fire (2 потока) | нет | **КРАСНЫЙ** |
| RabbitMQ недоступен при afterCommit | нет | **КРАСНЫЙ** |
| Concurrent setVariables | нет | нет теста |
| processFlow N+1 | нет | нет теста (perf) |
| BpmnServiceImpl cache eviction | нет | нет теста |
| Параллельный gateway TOCTOU | нет | нет теста |
