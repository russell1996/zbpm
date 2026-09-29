# ZBPM — документация

> Роадмап и приоритеты задач ведутся в [`governance/workorders/_index.md`](../governance/workorders/_index.md)
> — этот файл больше не дублирует их. Здесь — карта продуктовой документации: что где искать.

## Структура

| Папка | Что внутри |
|---|---|
| [`adr/`](adr/) | Architecture Decision Records — принятые архитектурные решения (ADR-1…ADR-8), каждое с датой и статусом |
| [`audit/`](audit/) | Независимый технический аудит кодовой базы (снэпшот 2026-08-23) — 10 разделов, см. [`audit/README.md`](audit/README.md) |
| [`guides/`](guides/) | Практические руководства для разработчиков: события движка, интеграция внешнего фронта |
| [`examples/`](examples/) | Рабочие примеры (BPMN-процесс + форма) |
| [`archive/`](archive/) | Устаревшие документы, оставлены для истории — не источник истины |

## Руководства (`guides/`)

- [**external-frontend-integration.md**](guides/external-frontend-integration.md) — с чего начать: как
  внешний UI (браузер, старт-формы) должен работать с движком целиком — старт процесса, аутентификация,
  realtime, корреляция результата. Читать первым.
- [event-catalog.md](guides/event-catalog.md) — справочник: полный каталог типов событий, routing-key,
  состав `data` для каждого типа. Смотреть, когда нужен точный формат конкретного события.
- [event-notifications-guide.md](guides/event-notifications-guide.md) — глубокое погружение в AMQP-контракт
  (Контракт A): подписка своей очередью, фильтрация по сотруднику/группе, топология routing-key. Нужен,
  если пишешь backend-интеграцию со своим RabbitMQ-потребителем (не браузерный фронт).

## Архитектура — ключевые ADR

- [ADR-7](adr/ADR-7-event-notification-architecture.md) — вся событийная архитектура (outbox, три контракта
  потребления: AMQP/HTTP-pull/SSE). База для обоих гайдов выше.
- [ADR-1](adr/ADR-1-multi-tenant-authorization.md) / [ADR-2](adr/ADR-2-centralized-control-plane.md) /
  [ADR-8](adr/ADR-8-self-service-ownership.md) — модель прав (кто что видит/меняет), в такой хронологии
  ADR-8 замещает часть ADR-2.
- [ADR-3](adr/ADR-3-embedded-forms.md) — встроенные формы (аналог Camunda Forms), релевантно для
  старт-форм из гайда external-frontend-integration.
