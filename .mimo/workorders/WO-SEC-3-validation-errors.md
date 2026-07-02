# WO-SEC-3 — DTO Validation + Global Error Handler     [P0 / HIGH]

## Цель
(a) Добавить Jakarta Bean Validation на все входящие DTO.
(b) Добавить `@RestControllerAdvice` с единым error-контрактом `{code, message}`.
Без этого фронт получает opaque 500 на любую ошибку, и невалидные данные доходят до движка.

## Проблемы (точно по коду)

Нет ни одного `@Valid` на `@RequestBody` ни в одном resource:
```
zorrobpm-rest/.../resource/RuntimeResource.java:44
```
```java
public IdDTO completeUserTask(@PathVariable UUID id, @RequestBody CompleteTaskDTO dto) {
    // dto не валидируется
```

Нет `@RestControllerAdvice` — любой exception → 500 с пустым `message`.
`@SneakyThrows` в ProcessDefinitionResource:46 — IOException утекает как необъявленный RuntimeException.

## Scope — строго
**Можно трогать:**
- Все `*Resource.java` в `zorrobpm-rest` — добавить `@Valid`
- Все DTO в `zorrobpm-contract` — добавить jakarta validation аннотации на поля
- Новый класс `GlobalExceptionHandler.java` в `zorrobpm-rest`
- `ProcessDefinitionResource.java:46` — убрать `@SneakyThrows`, добавить try-catch
- Тесты

**Нельзя:** менять логику движка; менять HTTP-методы или пути в contract.

## Новый error-контракт (реализовать)
```json
{ "code": "ENGINE_ERROR", "message": "Task not found" }
```

| Exception | HTTP | code |
|---|---|---|
| `EngineException` | 422 | `ENGINE_ERROR` |
| `BpmnParseException` | 400 | `PARSE_ERROR` |
| `MethodArgumentNotValidException` | 400 | `VALIDATION_ERROR` + details |
| `NoSuchElementException` | 404 | `NOT_FOUND` |
| Остальные RuntimeException | 500 | `INTERNAL_ERROR` |

## Критерии приёмки

| # | Критерий | Команда проверки | Ожидаемый факт |
|---|---|---|---|
| 1 | POST /user-tasks/{id}/complete с null variables → 400 | curl с `{"variables":null}` | `{"code":"VALIDATION_ERROR","message":"..."}` |
| 2 | POST /process-instances без processDefinitionId → 400 | curl с `{}` | 400 + VALIDATION_ERROR |
| 3 | GET /user-tasks/{id} с несуществующим UUID → 404 | curl с рандомным UUID | `{"code":"NOT_FOUND","message":"..."}` |
| 4 | EngineException из движка → 422 | IT с бизнес-ошибкой | 422 + ENGINE_ERROR |
| 5 | GET /process-definitions/{id}/xml для несущ. файла → 404 | curl с несущ. UUID | 404 (раньше было 500) |
| 6 | Фронт получает message | тот же curl | `message` не пустой |
| 7 | **proof-of-failure (V3)** | IT #3 на текущем коде | **КРАСНЫЙ** (500 вместо 404) |
| 8 | Регресс | `mvn clean verify` | BUILD SUCCESS |

## Запреты
Без `@SneakyThrows` в новом коде; без `e.printStackTrace()` в handler; не менять HTTP-пути.

## Гейты
G3 + G4. Code review CTO.

## DoD / отчёт
Таблица критерий → команда → факт. Proof-of-failure #7.
