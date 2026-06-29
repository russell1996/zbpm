# 01 — Архитектура (as-is)

## Модули (Maven reactor, 10 + frontend)

```
zorrobpm-contract   API-интерфейсы (@GetExchange/@PostExchange), DTO, model, exception   ← фундамент
        ▲
zorrobpm-engine     ядро: парсинг (JAXB), токенное исполнение, JPA-persistence,
                    таймеры, DMN, FEEL, security (JWT/bootstrap)
        ▲                      ▲
zorrobpm-rest        zorrobpm-rabbitmq   (MQ-мост: engine events ↔ broker)
        ▲                      ▲
        └──────────┬───────────┘
            zorrobpm-ce      @SpringBootApplication, scanBasePackages
```
Вспомогательные: `zorrobpm-event` (доменные ApplicationEvent), `zorrobpm-exchange` (модели MQ-сообщений),
`zorrobpm-client` (Java SDK поверх контрактов), `zorrobpm-job-handler-spring-boot-starter` (SDK воркера),
`zorrobpm-test` (хелперы). Frontend: `zorrobpm-frontend` (Vue 3 SPA).

**P1.** Зависимости направлены к `contract`; `engine` не зависит от `rest`/`rabbitmq` — те зависят от `engine`.
Интеграция engine→MQ — через Spring `ApplicationEvent` (`ServiceTaskEnqueued`), а не прямой вызов брокера:
`engine` публикует событие, `rabbitmq`-модуль слушает (`@EventListener`) и кладёт в очередь. Это держит
ядро свободным от транспортных деталей (V7-граница соблюдена).

## Слои исполнения (entry → ядро)
- **REST** `zorrobpm-rest/resource/*` — тонкие `@Transactional` контроллеры, делегируют в `RuntimeService`/`QueryService`.
- **RuntimeServiceImpl** — публичный фасад: резолвит определение (key+version → id) и делегирует в `ActivityService`.
- **ActivityServiceImpl** (~2415 строк) — вся логика обхода графа (диспетчер хендлеров).
- **DBService/DBServiceImpl** — единственная абстракция персистентности (токены, активности, переменные, подписки, таймеры, инциденты, локи).
- **BpmnService** — кэш распарсенных моделей (`computeIfAbsent`).
- **ScriptService/DmnService** — FEEL / DMN.

## Bootstrap
`zorrobpm-ce/APP.java` (`@SpringBootApplication`, `scanBasePackages` по `com.zorrodev.bpm`).
Spring Boot 4.0.5 / Java 21. Liquibase прогоняет changelog при старте. Профиль `test` исключает MQ-публикацию
(`@Profile("!test")` на `ServiceTaskEnqueueServiceImpl`) — тесты гоняются на H2 без брокера.

## Потоки данных
- **Старт процесса:** REST → RuntimeService → `ActivityService.startProcessInstance` → создаётся `process_instance` + корневой `token` → `execute(start event)` → синхронный рекурсивный обход до первой wait-state/окончания, всё в одной транзакции контроллера.
- **Service task:** активность создаётся (CREATED) → `enqueueAfterCommit` регистрирует afterCommit-публикацию → после COMMIT транзакции `ServiceTaskEnqueued` → `rabbitmq`-листенер → очередь `zorrobpm.jobs.<type>` → воркер → `complete-service-task` → листенер → `ActivityService.completeServiceTask` (новая транзакция) → обход продолжается.
- **User task:** активность + строка `user_tasks`, токен паркуется; завершение через REST `completeUserTask`.
- **Таймер/сообщение/сигнал:** паркуется wait-state + подписка/timer_job; внешний триггер (`TimerScheduler` poll / `correlateMessage`) возобновляет токен через `signal`.

## Риски архитектуры
- **B1/B2.** Граница транзакции = весь синхронный участок обхода + пессимистичный лок инстанса → масштаб внутри инстанса ограничен; широкий/глубокий граф = большая транзакция.
- **S1.** Координация между нодами отсутствует (см. [08](08-cluster-ha-security.md)).
- **F2.** Мост engine→MQ через afterCommit без outbox — окно потери job при крахе (см. [05](05-incidents-compensation.md)).
