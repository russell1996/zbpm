# Feature Gaps — ZorroBPM CE

> Функциональные пробелы, подтверждённые по исходному коду.

---

## G-1
**90% REST API без аутентификации**

Дубль CRITICAL-2 из [01-security](01-security.md#critical-2). Выделен как отдельный gap потому что это не только уязвимость — это архитектурный gap: auth система есть, но intentionally не применена к data API (коммент в JwtAuthFilter: "locking the data API down is a separate concern (the API-key plan)").

Нужно принять архитектурное решение: JWT Bearer для всех (расширить фильтр) vs. отдельный API-key механизм для Java-клиентов.

---

## G-2
**DMN: только UNIQUE/FIRST/ANY hit policies**

```
zorrobpm-engine/.../service/impl/DmnServiceImpl.java:94-106
```

```java
// UNIQUE/FIRST/ANY all return a single result — the first matching rule is sufficient here
break;
```

Все три hit policy реализованы одинаково: возвращается первое совпавшее правило. Реально отличаются, но движок не различает:

| Hit Policy | Ожидаемое поведение | Реальное |
|---|---|---|
| UNIQUE | ровно 1 совпадение, иначе ошибка | первое совпадение, нет валидации |
| FIRST | первое совпадение | первое совпадение ✓ |
| ANY | все совпадения дают одинаковый результат | первое совпадение |
| **COLLECT** | все совпадения + агрегация (SUM/MIN/MAX/COUNT) | **НЕ РЕАЛИЗОВАН** |
| **RULE ORDER** | все совпадения в порядке правил | **НЕ РЕАЛИЗОВАН** |
| **OUTPUT ORDER** | все совпадения в порядке output values | **НЕ РЕАЛИЗОВАН** |
| **PRIORITY** | первое совпадение по приоритету output | **НЕ РЕАЛИЗОВАН** |

---

## G-3
**Вложенные event sub-processes не поддержаны**

```
zorrobpm-engine/.../service/impl/ActivityServiceImpl.java:1821
```

```java
// Non-message triggers and event sub-processes nested inside an embedded subprocess
// are not yet supported.
```

Event sub-process внутри embedded subprocess (не на уровне процесса) — не поддерживается.
При попытке BPMN с такой конфигурацией — неопределённое поведение (silently ignored или NPE).

---

## G-4
**Bounded repeating timer `R<n>/PT` — только первое срабатывание**

```
zorrobpm-engine/.../scheduler/TimerExpressions.java:63-70
```

`R3/PT1H` — должен сработать 3 раза с интервалом 1 час. Фактически: срабатывает 1 раз, `rearmRepeatingBoundaryTimer` re-arms только для infinite (`R/PT...` без счётчика). Bounded repeat (`R<n>`) — отмечен как "separate enhancement".

---

## G-5
**Targeted compensation + compensation в subprocess scope**

```
zorrobpm-engine/.../service/impl/ActivityServiceImpl.java:213-214
```

```java
// Targeted (activityRef) compensation, compensation end events and
// compensation within a subprocess scope are not yet supported.
```

Реализована только: "compensate all в scope процесса" через intermediate throw event.
Не реализованы:
- Targeted compensation (`<compensateEventDefinition activityRef="..."/>`)
- Compensation end event
- Compensation внутри subprocess scope

---

## G-6
**Нет cancel / terminate API для process instance**

Сканирование `RuntimeContract.java` — нет ни одного из:
- `DELETE /process-instances/{id}`
- `POST /process-instances/{id}/cancel`
- `POST /process-instances/{id}/terminate`

Оператор не может остановить зависший инстанс через API. Только через incident management (непрямой путь) или прямую манипуляцию БД.

---

## G-7
**Нет transactional outbox для service-task enqueue**

Дубль R-1 из [02-reliability](02-reliability.md#r-1). Выделен как gap потому что это архитектурное решение: система работает только если RabbitMQ доступен в момент транзакции. Outbox pattern — стандартное решение для guaranteed delivery.

---

## G-8
**Нет global `@RestControllerAdvice` — все ошибки = opaque 500**

Нет ни одного `@RestControllerAdvice` / `@ControllerAdvice` в кодовой базе.

Текущий error-контракт:
- `404` — только в `DmnResource` и `ProcessDefinitionResource` (вручную, через `ResponseStatusException`)
- `401` — только `JwtAuthFilter` (для защищённых путей)
- `500` — всё остальное: `EngineException`, `NoSuchElementException` из `orElseThrow()`, `BpmnParseException`, NPE и т.д.

Тело 500-ответа: `{timestamp, status, error, "message":"", path}` — поле `message` пустое по умолчанию в Spring Boot 3+. Фронт не может различить "задача не найдена" от "движок упал".

**Фикс (WO-ERR):**
```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(EngineException.class)
    ResponseEntity<ErrorDTO> handleEngine(EngineException e) { /* 422 */ }
    
    @ExceptionHandler(BpmnParseException.class)
    ResponseEntity<ErrorDTO> handleParse(BpmnParseException e) { /* 400 */ }
    
    // NotFoundException → 404, ValidationException → 400, etc.
}
```

---

## G-9
**DMN `listDecisions()` без пагинации**

```
zorrobpm-engine/.../service/impl/DmnServiceImpl.java:126
zorrobpm-contract/.../DmnContract.java
```

`GET /dmn/decisions` возвращает все решения без `pageIndex`/`pageSize`. При большом каталоге DMN — unbounded response. Контракт не объявляет `PagedDataDTO`, возвращает `List<DmnDecision>`.

---

## G-10 (наблюдение)
**Тесты на H2 вместо PostgreSQL**

```
zorrobpm-engine/src/test/resources/application.properties
```

```
spring.datasource.url=jdbc:h2:mem:test
```

Все интеграционные тесты работают на H2 in-memory. Различия с реальным PostgreSQL:
- `SELECT FOR UPDATE SKIP LOCKED` — поведение H2 vs PG может отличаться
- `JSONB` тип — H2 не поддерживает нативно
- UUID handling — H2 хранит как VARCHAR, PG как UUID
- Уникальные констрейнты при конкурентных INSERT — H2 может вести себя иначе

Это создаёт риск "тесты зелёные, прод красный".
