# WO-SEC-3 — DTO Validation + Global Error Handler     [P0 / HIGH]

## Цель
(a) Добавить Jakarta Bean Validation на входящие DTO.
(b) Добавить `@RestControllerAdvice` с единым error-контрактом `{code, message}`.
Без этого фронт получает opaque 500 на любую ошибку, а невалидные данные доходят до движка.

## Проблемы (проверено по диску — актуально)

- Нет ни одного `@Valid` на `@RequestBody` (проверено: `git grep @Valid` → пусто).
  `RuntimeResource.java`: `completeUserTask` (стр. 45), `completeServiceTask` (33),
  `failServiceTask` (39), `resolveIncident` (51), `startProcessInstance` (27) — все без `@Valid`.
- Нет `@RestControllerAdvice` (проверено: `git grep RestControllerAdvice` → пусто) → любой exception → 500 с пустым `message`.
- `ProcessDefinitionResource.java:46` — `@SneakyThrows` → IOException утекает как необъявленный RuntimeException.
- `EngineException` и `BpmnParseException` уже существуют в `zorrobpm-contract/exception/` — их только импортировать (чтение contract, не изменение).

---

## ⚠️ САНКЦИОНИРОВАННЫЕ ИСКЛЮЧЕНИЯ (pre-approved CTO — можно без доп. эскалации)

Обычно G-C замораживает `zorrobpm-contract` и pom. Для этого WO CTO **заранее** разрешает строго следующее:

1. **pom:** добавить `spring-boot-starter-validation` в `zorrobpm-rest/pom.xml`
   (иначе `@Valid` молча не сработает — стартера сейчас нет). Только эта зависимость.
2. **contract DTO:** добавить jakarta-validation **аннотации** (`@NotNull`, `@Valid`, class-level constraint)
   на поля **существующих** DTO. **Additive только.**

**ВСЁ ЕЩЁ ЗАПРЕЩЕНО (стоп → эскалация):** переименование/смена типов полей DTO, изменение сигнатур,
HTTP-методов, путей; любые другие зависимости; изменение логики движка.

---

## Scope — строго
**Можно трогать:**
- `*Resource.java` в `zorrobpm-rest` — добавить `@Valid` на `@RequestBody`
- DTO в `zorrobpm-contract` — **только** validation-аннотации (см. исключение 2)
- Новый `GlobalExceptionHandler.java` в `zorrobpm-rest`
- `zorrobpm-rest/pom.xml` — **только** validation-стартер (см. исключение 1)
- `ProcessDefinitionResource.java:46` — убрать `@SneakyThrows`, обернуть в try-catch
- Тесты

**Нельзя:** менять логику движка; HTTP-методы/пути; сигнатуры; имена/типы полей DTO.

---

## Новый error-контракт (реализовать)
```json
{ "code": "ENGINE_ERROR", "message": "Task not found" }
```

| Exception | HTTP | code |
|---|---|---|
| `MethodArgumentNotValidException` | 400 | `VALIDATION_ERROR` (+ details по полям) |
| `BpmnParseException` | 400 | `PARSE_ERROR` |
| `EngineException` | 422 | `ENGINE_ERROR` |
| `NoSuchElementException` | 404 | `NOT_FOUND` |
| Остальные `RuntimeException` | 500 | `INTERNAL_ERROR` |

---

## Валидация DTO — корректные правила (ВНИМАНИЕ на #2)

- `StartProcessInstanceDTO` — запуск возможен по `processDefinitionId` **ИЛИ** `processDefinitionKey`.
  **НЕЛЬЗЯ** ставить `@NotNull` только на `processDefinitionId` — это сломает запуск по ключу.
  Нужен **class-level constraint**: «задан хотя бы один из id/key».
- `CompleteTaskDTO.variables` — пустой список легитимен (задача без переменных).
  `@NotNull` допустимо (не `null`), но `@NotEmpty` — **нет**.

---

## Критерии приёмки — КАЖДЫЙ обязан иметь тест (G-A, G-B)

| # | Критерий | Тест (обязателен) | Ожидаемый факт |
|---|---|---|---|
| 1 | POST /process-instances без id И без key → 400 | IT | `{"code":"VALIDATION_ERROR",...}` |
| 2 | **POST /process-instances по key (без id) → работает (НЕ сломано)** | IT (регресс!) | 200 + инстанс создан |
| 3 | GET /user-tasks/{id} с несуществующим UUID → 404 | IT | `{"code":"NOT_FOUND",...}` |
| 4 | `EngineException` из движка → 422 | IT с бизнес-ошибкой | 422 + ENGINE_ERROR |
| 5 | GET /process-definitions/{id}/xml для несущ. файла → 404 (было 500) | IT | 404 + NOT_FOUND |
| 6 | `message` в ответе не пустой | тот же IT #3 | `message` заполнен |
| 7 | **proof-of-failure (V3)** | IT #3 или #5 на текущем коде | **КРАСНЫЙ** (500 вместо 404) |
| 8 | Регресс | `mvn clean verify` | BUILD SUCCESS |

> Критерий #2 — **страховка от регресса**: доказывает, что валидация не сломала запуск по ключу.
> Проходить таблицу построчно (G-A): каждый критерий → ✅ или «НЕ СДЕЛАНО: причина». Ни одного молча.

## Запреты
Без `@SneakyThrows` в новом коде; без `e.printStackTrace()`/stacktrace в теле ответа; не логировать секреты/токены.

## Гейты
G3 + G4 + HARD GATES G-A…G-G. Code review CTO.

## DoD / отчёт
Полная таблица критериев #1–#8 → команда → факт. Proof-of-failure #7 (RED→GREEN). Отчёт в `mimo-to-cto.md`.
