# Поддержка BPMN — что исполняет движок

> Полная матрица исполняемых BPMN-конструкций. Вход в документацию — [README](../../README.md), карта — [docs/README.md](../README.md).

## Поддержка BPMN

| Поддерживается | Точечные edge'ы (отдельный заход) |
|---|---|
| Start / End / Terminate-end события | Ограниченный повтор таймера `R<n>` (бесконечный `R/`/cron — есть) |
| **Message start**, **Timer start** (date/duration/**cycle**) и **Signal start** | MI на send/script job-worker форме (на user/service task — есть) |
| Потоки управления (sequence flow) | Компенсация в scope встроенного подпроцесса |
| Exclusive gateway (условия на FEEL + поток по умолчанию) | Event sub-process внутри встроенного подпроцесса (не top-level) |
| Parallel gateway (split / join) | Вложенные транзакции |
| **Inclusive gateway** (split по всем истинным веткам + default; динамический join) | |
| **Event-based gateway** (гонка catch-событий: message / timer / signal) | |
| Service task (внешние воркеры через RabbitMQ) | |
| User task (assignee, кандидаты-пользователи/группы, form key; `zeebe:userTask` маркер) | |
| **Multi-instance** (на **user и service task и на встроенном подпроцессе**, параллельный/последовательный; count по `loopCardinality`/`inputCollection`; **per-instance `inputElement`+`loopCounter`**, агрегация **`outputCollection`**; `completionCondition` — дочерним элементом, FEEL; ранний выход отменяет остальные инстансы, частичный результат передаётся дальше. Отказ при деплое: MI на call activity / transaction / ad-hoc) | |
| **Send / Receive task** (send: `zeebe:taskDefinition` job-worker или message throw; receive: message catch) | |
| **Script task** (`zeebe:script` FEEL, **`zeebe:taskDefinition` job-worker**, inline) | |
| **Переменные** `STRING/LONG/DOUBLE/BOOLEAN/UUID/JSON` (JSON-объекты/списки, доступ к свойствам и итерация в FEEL/DMN; структурный FEEL-результат → JSON) | |
| **IO mappings** (`zeebe:ioMapping` input/output; **scoped**: input-локали не протекают) | |
| **Business rule task** (DMN через `zeebe:calledDecision`, **версионирование**; или inline FEEL) | |
| Call activity (`propagateAllChildVariables`), встроенный подпроцесс | |
| **Event sub-process** (триггеры message / signal / error / timer; message — прерывающий и непрерывающий) | |
| Промежуточные **catch**: ожидание, **message** (+корреляция по имени и по **ключу**), **timer** (дата/длительность/**cycle**) | |
| Промежуточный **throw**, **message throw** (корреляция внутри движка) | |
| **Signal catch / throw** (broadcast всем подписчикам, 1:N) | |
| **Link catch / throw** (внутрипроцессный «goto» по имени link) | |
| **Conditional** catch / boundary (FEEL-условие на данных; реоценка при изменении переменных) | |
| **Compensation** boundary + throw (compensate-all и targeted по `activityRef`; обратный порядок) | |
| **Transaction** sub-process + **cancel** end / cancel boundary (компенсация + отмена scope) | |
| **Escalation** throw / end + **escalation boundary** (прерывающий и непрерывающий) | |
| **Error end** + **Error boundary** (с распространением по scope и в родительский процесс) | |
| **Boundary**: timer (вкл. повторяющийся **cycle**), message, **signal** и **escalation**, **прерывающие и непрерывающие** | |
| Инциденты (создаются автоматически при ошибке) + ручное разрешение | |

Условия и выражения вычисляются движком **Camunda FEEL**; десятичные и JSON-объекты/списки доступны как числа и Map/List.
