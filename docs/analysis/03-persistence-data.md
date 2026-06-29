# 03 — Persistence & Data Model (as-is)

## Стек
JPA/Hibernate → PostgreSQL 16 (прод), H2 (тесты). Схема — **только Liquibase**, 41 changeset
(`db/changelog/changesets/`, мастер `db.changelog-master.yaml`). Все изменения схемы — через changeset (**P9**).
Единая абстракция доступа — `DBService` (195-строчный интерфейс) / `DBServiceImpl` (~775 строк).

## Сущности (20)
| Группа | Сущности |
|---|---|
| Определения | `ProcessDefinitionEntity`, `BpmnEntity` (XML-файл), `DmnDefinitionEntity` (с version) |
| Рантайм | `ProcessInstanceEntity`, `ActivityEntity`(+`ActivityStatus`), `TokenEntity` (parent + scopeActivityId) |
| Задачи | `UserTaskEntity`, `ServiceTaskEntity` (retries), `AssigneeEntity` |
| Переменные | `ProcessVariableEntity` (scoped) |
| События | `MessageSubscriptionEntity`, `MessageStartSubscriptionEntity`, `SignalSubscriptionEntity`, `SignalStartSubscriptionEntity` |
| Таймеры | `TimerJobEntity`, `TimerStartJobEntity` |
| Шлюзы | `ParallelGatewayEntity` (arrivals + expectedCount для inclusive) |
| Инциденты/Auth | `IncidentEntity`, `UiUserEntity` |

## Переменные и скоупы
- Типы: `ProcessVariableType` = `STRING, UUID, LONG, BOOLEAN, DOUBLE, JSON` (`@Enumerated(STRING)`).
  DOUBLE и JSON присутствуют (C8-1 закрыт) — JSON хранит объекты/списки.
- **Скоупинг:** `setVariables(pi, scopeId, vars)` — `scopeId==null` пишет корень инстанса, иначе локальный scope активности.
  `getVariables(pi, scopeId)` отдаёт merged-view (локальное затеняет корень). `deleteVariables(pi, scopeId)`
  чистит локали (IO-mapping inputs после завершения задачи). Уникальность scope — changeset 038.
- **B4:** хендлеры часто перечитывают полный набор переменных за один шаг обхода (много DB-roundtrips).

## Блокировки и конкурентность
- **P3.** `lockProcessInstance` → `processInstanceRepository.findByIdForUpdate` с `@Lock(PESSIMISTIC_WRITE)`
  (реальный `SELECT … FOR UPDATE`). Сериализует конкурентный обход одного инстанса (защита join'ов и
  double-advance). Берётся в `completeServiceTask`/`failServiceTask`/`completeUserTask`/`resolveIncident` и
  родительских continuation'ах (6 call sites).
- **M4.** **Нет `@Version`** ни на одной сущности → оптимистичной блокировки нет; корректность держится
  исключительно на пессимистичном локе. Для путей, не берущих лок инстанса, конкурентные апдейты не защищены.
- **B2.** Пессимистичный лок — потолок параллелизма внутри инстанса.

## Идемпотентность данных
- Parallel/inclusive join arrivals — отдельные строки (`parallel_gateways`), идемпотентный
  `recordParallelGatewayArrival` (повтор того же flow не двоит), `clearParallelGatewayArrivals` для циклов.
- Подписки (`message_*`/`signal_*`) — `consume*Subscription` помечает потреблённой; start-подписки
  пересоздаются на новую версию (`delete…ByKey` + `create…`).

## Долг/риски данных
- **S4.** Нет архивации/cleanup: `activities`, `tokens`, завершённые инстансы растут безгранично → деградация
  запросов и таблиц во времени (нужны партиционирование/cleanup-джоб — Фаза 3 роадмапа).
- **T8.** Нет hot-индексов: `variables(process_instance_id, scope_id)`, `activities(process_instance_id, status)`,
  `timer_jobs(fired, due_at)`, `message_subscriptions(consumed, message_name)`.
- **M5.** Кэш моделей в памяти (`BpmnServiceImpl.models`) без вытеснения — растёт по числу/редеплою определений.
