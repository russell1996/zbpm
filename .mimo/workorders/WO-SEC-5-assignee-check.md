# WO-SEC-5 — Проверка assignee при complete user-task     [P2 / HIGH]

## Цель
Завершить user-task может только её assignee (или ADMIN). Иначе любой аутентифицированный
пользователь, зная UUID задачи, завершает чужую.

## Проблема (проверено по диску)
`RuntimeResource.java:46` — `completeUserTask(id, dto)` без проверки, кто вызывает.
После WO-SEC-1 доступ требует токен, но НЕ проверяет, что вызывающий = assignee.

## Зависимость
Требует WO-SEC-1 (в master ✅) — есть `authClaims` в request.

---

## 🧭 АРХИТЕКТУРНЫЕ РЕШЕНИЯ CTO (приняты заранее — проверено по диску, не отступать)

**1. Проверку делать в `RuntimeResource` (rest-слой), НЕ в engine-сервисе.**
Причина: ~14 engine-тестов вызывают `RuntimeService.completeUserTask` напрямую без claims.
Проверка в `ActivityServiceImpl`/`RuntimeService` сломает их все. Rest-слой изолирован.

**2. Claims брать из request — как `AuthResource`:**
```java
TokenService.Claims claims = (TokenService.Claims) request.getAttribute("authClaims");
```
Добавь `private final HttpServletRequest request;` в `RuntimeResource` (@RequiredArgsConstructor).
**Сигнатуру `RuntimeContract.completeUserTask` НЕ менять** (contract freeze).

**3. Assignee задачи брать НЕ из `UserTask` DTO** — в нём нет поля assignee (проверено),
а добавлять его в contract-модель ЗАПРЕЩЕНО (G-C). Источник — `UserTaskEntity.assignee` (String):
- вариант A: inject `UserTaskRepository` в `RuntimeResource`, `repo.findById(id).map(e -> e.getAssignee())`;
- вариант B: новый engine-метод `QueryService.getAssignee(UUID): String`.
Любой — на твой выбор, но БЕЗ изменения `UserTask` DTO / contract.

**4. 403 через `ResponseStatusException(HttpStatus.FORBIDDEN, ...)`** → его ловит
`GlobalExceptionHandler` (WO-SEC-3). НО сейчас его switch не мапит 403 →
даёт `code=INTERNAL_ERROR`. **Добавь в switch**: `case 403 -> "FORBIDDEN";`
(файл `GlobalExceptionHandler.java` — в scope этого WO).

---

## Логика проверки (в RuntimeResource.completeUserTask, до вызова сервиса)
```
assignee = задача.assignee
if claims.role() == "ADMIN":            → пропустить (может любую)
elif assignee == null или пусто:        → пропустить (критерий #4)
elif assignee.equals(claims.username()):→ пропустить (свой)
else:                                    → throw ResponseStatusException(403, "Not your task")
```
(`Claims` = `record(UUID userId, String username, String role, long exp)` — проверено.)

## Scope
**Можно:** `RuntimeResource.java`, `GlobalExceptionHandler.java` (case 403), источник assignee
(`UserTaskRepository` inject ИЛИ новый `QueryService.getAssignee`), новый тестовый BPMN, тесты.
**Нельзя:** менять `RuntimeContract`/`UserTask` DTO/`ProcessVariable`; менять сигнатуру
`RuntimeService.completeUserTask`; трогать engine-логику завершения.

## Тестовые данные (нужно создать — готового нет)
Готового BPMN с `zeebe:assignee` в тестах НЕТ. Создай `zorrobpm-rest/src/test/files/assignee-task.bpmn`:
user task с `zeebe:assignmentDefinition assignee="user1"`, start→userTask→end. Плюс в тесте
создай двух UI-юзеров (user1, user2) как в WO-SEC-1/2 (`UiUserRepository.save` + `PasswordHasher`).

Также **адаптируй `RuntimeResourceTest`** (unit, @InjectMocks): теперь resource зависит от
`HttpServletRequest` (+ источник assignee) — добавь моки, чтобы существующий
`completeUserTask_delegatesToService` компилировался и проходил (mock claims = ADMIN → пропуск).

## Критерии приёмки — каждый с тестом (G-A/G-B)

| # | Критерий | Тест | Ожидаемый факт |
|---|---|---|---|
| 1 | Assignee завершает свою задачу | IT (login user1 → complete) | 200 |
| 2 | Другой USER завершает чужую | IT (login user2 → complete task user1) | 403 + `{"code":"FORBIDDEN"}` |
| 3 | ADMIN завершает любую | IT (login admin → complete task user1) | 200 |
| 4 | Unassigned task: любой USER | IT (задача без assignee → complete) | 200 |
| 5 | **proof-of-failure (V3)** | IT #2 на текущем коде | **КРАСНЫЙ** (200 вместо 403) |
| 6 | Регресс (в т.ч. 14 engine complete-тестов) | `mvn clean verify` | BUILD SUCCESS |

## Запреты
Без изменения contract/DTO. Без проверки в engine-слое (сломает engine-тесты). Без `@Disabled`.

## Гейты
G6 (security) + HARD GATES G-A…G-G. CTO review + CI green.

## DoD / отчёт
Полная таблица #1–#6 → команда → факт. Proof-of-failure #5. Отчёт в `mimo-to-cto.md`.
