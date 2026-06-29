# 05 — Incidents, Retries, Compensation & Fault Tolerance (as-is)

## Инциденты
- **Создание:** `raiseIncident` [:607] — помечает активность `ERROR` (`errorActivity`) и создаёт `incidents`
  с сообщением (класс+message исключения). Токен **паркуется** (не теряется), процесс не откатывается (**P4**).
- **Источники:** исключение в хендлере (`execute` catch :666), неизвестный тип элемента (:650), исчерпание
  ретраев service-task, явная ошибка модели (exclusive без default, call activity без target — информативный текст).
- **Разрешение:** `resolveIncident` [:1904] — лок инстанса → применить переменные → `completeIncident` →
  **`cancelActivity`** старой ERROR-активности (чтобы поздняя/дубль completion её job игнорировалась) → `execute` заново.
  Это исправляет исторический баг двойного advance токена.

## Ретраи (service task)
Camunda `failJob`-семантика [`failServiceTask` :1171]: явный `retries` ставит бюджет (`0` → инцидент сразу);
`null` → декремент на 1. Пока бюджет>0 — `enqueueAfterCommit` повторно диспетчеризует тот же job (активность
остаётся CREATED). Исчерпан — `ERROR` + инцидент с сообщением воркера. Бюджет берётся из `zeebe:taskDefinition retries`
(changeset 040 `service_tasks.retries`).

## Компенсация
- **Throw** [`processCompensationThrow` :219]: `activityRef` → таргет одной активности; `null` → все завершённые.
  Кандидаты = `getCompletedActivities(pi)`, отфильтрованы по `activityRef`.
- **`runCompensation`** [:245]: сортирует кандидатов по `createdAt` **убыв.** (реверс порядка завершения),
  для каждого ищет compensation-boundary → его handler → `execute` синхронно на runToken.
- **Cancel end (transaction)** [`processCancelEnd` :274]: компенсирует завершённые активности транзакции в
  обратном порядке, отменяет scope, продолжает с cancel-boundary.
- **Ограничения:** compensate-all и targeted работают на **корне**; компенсация в scope встроенного
  подпроцесса и propagation отмены через **вложенные** транзакции — **не поддержаны** (C1 роадмапа).

## Отказоустойчивость (анализ)
| Код | Свойство | Деталь |
|-----|----------|--------|
| **P2** | Идемпотентность завершения | статус-гарды терпят at-least-once редоставку RabbitMQ, boundary-прерывание, дубль-completion |
| **F2** | Нет transactional outbox | `enqueueAfterCommit` публикует `ServiceTaskEnqueued` в памяти после commit; **краш в окне commit→afterCommit** оставит активность CREATED без события → job не уйдёт воркеру |
| **F3** | Нет recovery-свипа | «застрявшие» CREATED service-task (потерянный enqueue, F2) повторно не диспетчеризуются — нет периодического ресвипа |
| **F1/F4** | Таймеры не кластеро-безопасны | дубли в multi-node, неатомарный fire (см. [04](04-scheduler-events.md)) |

**Вывод по FT:** в пределах одной транзакции/ноды поведение корректно и идемпотентно (сильная сторона).
Слабые места — **доставка job через in-memory afterCommit без outbox** (F2/F3) и **таймеры без координации** (F1).
Оба — на критическом пути HA; кандидаты в ADR (outbox-паттерн; SKIP LOCKED/leader election).
