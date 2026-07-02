# WO-SEC-5 — Проверка assignee при complete user-task     [P2 / HIGH]

## Цель
Убедиться что завершить задачу может только её assignee (или ADMIN).

## Проблема
```
zorrobpm-rest/.../resource/RuntimeResource.java:44-46
```
Нет проверки кто вызывает. Любой аутентифицированный пользователь (после WO-SEC-1) может
завершить чужую задачу зная её UUID.

## Зависимость
**Требует WO-SEC-1 выполненным** — иначе проверять некого (всё открыто).

## Scope
**Можно трогать:** `RuntimeResource.java`, `ActivityServiceImpl.completeUserTask` (проверка в сервисе),
`UserTaskEntity` / `AssigneeEntity` (получить assignee).  
**Нельзя:** менять contract; изменять структуру assignee (она уже есть в AssigneeEntity).

## Критерии приёмки

| # | Критерий | Команда проверки | Ожидаемый факт |
|---|---|---|---|
| 1 | Assignee завершает свою задачу → 200 | IT: логин assignee → complete | 200 |
| 2 | Другой USER пытается завершить → 403 | IT: логин user2 → complete task user1 | 403 + FORBIDDEN |
| 3 | ADMIN может завершить любую → 200 | IT: логин admin → complete task user1 | 200 |
| 4 | Unassigned task: любой USER → 200 | IT: задача без assignee → complete | 200 |
| 5 | **proof-of-failure (V3)** | IT #2 на текущем коде (после WO-SEC-1) | **КРАСНЫЙ** (200 вместо 403) |
| 6 | Регресс | `mvn clean verify` | BUILD SUCCESS |

## DoD / отчёт
Таблица критерий → команда → факт. Proof-of-failure #5.
