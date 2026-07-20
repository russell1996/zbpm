# Справочник доменных событий (routing-key каталог)

> Источник истины по типам — enum
> [`DomainEventType`](../zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/event/DomainEventType.java).
> Этот файл — человекочитаемая расшифровка: когда каждое событие срабатывает и что несёт.
> Общая механика (exchange/queue/binding, фильтр по assignee) — в [event-notifications-guide.md](event-notifications-guide.md).

## Как читать
- **routing-key** ставит **движок** на каждое письмо. Формат (EVT-7, в проде):
  `process.<processDefinitionKey>.<type>[.<elementId>]`, напр. `process.vacation.user-task.created.Approve`.
  Если pdKey не удалось разрезолвить — fallback на `<type>` (столбец 1 таблицы). pdKey/elementId санитизируются
  (символы вне `[A-Za-z0-9_-]` → `_`).
- **binding-key** пишешь **ты** при привязке своей очереди (паттерн; `*` = один сегмент, `#` = хвост).
- Exchange один: `zorrobpm.events` (topic, durable).

## Каталог событий

| routing-key (= type) | Когда срабатывает (движок, DBServiceImpl) | elementId | `data` (payload) |
|---|---|---|---|
| `process-instance.started` | создан инстанс процесса | — | `{}` |
| `process-instance.completed` | инстанс завершился нормально | — | `{}` |
| `process-instance.cancelled` | инстанс отменён | — | `{}` |
| `activity.completed` | завершилась активность (шаг процесса) | ✅ `bpmnElementId` | `{}` |
| `user-task.created` | создана пользовательская задача | ✅ | `{ activityId }` |
| `user-task.completed` | пользовательская задача завершена | ✅ | `{ activityId }` |
| `service-task.created` | создан джоб сервис-таска | ✅ | `{ activityId }` |
| `incident.raised` | поднят инцидент (ошибка на шаге) | ✅ | `{ incidentId, message }` |
| `incident.resolved` | инцидент разрешён | ✅ | `{ incidentId }` |

> ⚠️ Планируется (EVT-8): в `user-task.created/completed` добавятся `assignee`, `candidateGroups`
> (`candidateUsers` — если есть в модели). Сейчас в `data` только `activityId` — чтобы узнать исполнителя,
> потребитель доспрашивает `GET /user-tasks/{activityId}`.

## Поля конверта (одинаковы у всех событий)

| Поле | Тип | Есть сейчас? |
|---|---|---|
| `eventId` | uuid — для дедупликации | ✅ |
| `type` | напр. `user-task.created` | ✅ |
| `occurredAt` | ISO-8601 | ✅ |
| `processInstanceId` | uuid запуска | ✅ |
| `processDefinitionId` | uuid определения | ✅ |
| `elementId` | id элемента BPMN (где применимо) | ✅ |
| `data` | тип-специфичная нагрузка (таблица выше) | ✅ |
| `processDefinitionKey` | человекочитаемый ключ процесса | ✅ (EVT-7) |
| `sequence` | сквозной курсор (докачка, Контракт B) | в таблице `events` есть; в AMQP-конверт пока НЕ кладётся (бери через Контракт B) |

## Примеры binding-key (что писать при подписке)

| Хочешь получать | binding-key |
|---|---|
| всё по процессу `vacation` | `process.vacation.#` |
| все задачи (created+completed) в vacation | `process.vacation.user-task.#` |
| только созданные задачи в vacation | `process.vacation.user-task.created.#` |
| конкретный элемент | `process.vacation.user-task.created.Approve` |
| все инциденты по всем процессам | `process.*.incident.#` |
| завершения инстансов | `process.*.process-instance.completed.#` |
| вообще всё | `process.#` |

## Правила потребления (кратко)
- Доставка **at-least-once** → дедуп по `eventId` обязателен.
- Порядок: по `sequence` (глобально); внутри одного `processInstanceId` сохранён.
- Пропущенное до создания очереди / за время простоя дольше retention → добор `GET /events?since=<sequence>`.
- Список сущностей (задачи и т.п.) берётся ЗАПРОСОМ (`GET /user-tasks?...`), событие — только пинг «перечитай».
