# Frontend API Gaps

Backend endpoints, отсутствующие для полноценного Frontend.

## P1 — Критично

| # | Endpoint | Метод | Response | Назначение | Зависимость |
|---|---|---|---|---|---|
| G1 | `GET /auth/me` | `GET` | `{ login, fullName, email, active }` | Получение текущего пользователя из JWT | Sprint 1 |
| G2 | `GET /admin/users` | `GET` | `PagedDataDTO<User>` | Список пользователей | Sprint 1 |
| G3 | `POST /admin/users` | `POST` | `IdDTO` | Создание пользователя | Sprint 1 |
| G4 | `GET /admin/users/{id}` | `GET` | `User` | Детали пользователя | Sprint 1 |
| G5 | `PUT /admin/users/{id}` | `PUT` | `IdDTO` | Редактирование пользователя | Sprint 1 |

## P2 — Важно

| # | Endpoint | Метод | Response | Назначение | Зависимость |
|---|---|---|---|---|---|
| G6 | `GET /process-instances/{id}` | `GET` | `ProcessInstance` | Детали экземпляра (с processDefinitionKey/version/name) | Sprint 2 |
| G7 | `GET /user-tasks/{id}` | `GET` | `UserTask` | Детали задачи | Sprint 3 |
| G8 | `GET /incidents/{id}` | `GET` | `Incident` | Детали инцидента | Sprint 3 |
| G9 | `GET /user-tasks` с `processDefinitionName` | — | `PagedDataDTO<UserTask>` | Имя процесса в списке задач | Sprint 3 |
| G10 | `GET /incidents` с `processDefinitionName` | — | `PagedDataDTO<Incident>` | Имя процесса в списке инцидентов | Sprint 3 |

## Текущие обходные пути

| Gap | Обход | Ненадёжность |
|---|---|---|
| G6 | Загрузка всех инстансов + фильтрация по ID | O(N) на каждый просмотр |
| G7 | Загрузка всех задач + фильтрация по ID | O(N) на каждый просмотр |
| G8 | Загрузка всех инцидентов + фильтрация по ID | O(N) на каждый просмотр |
| G1-G5 | Mock данные в `userService.ts` | Нет реальной интеграции с Keycloak |
