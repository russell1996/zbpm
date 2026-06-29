# ZorroBPM — Reverse-Engineering Analysis (as-is, по коду)

> **Источник истины — исходники**, не старые доки. Дата: 2026-06-29. Версия: `0.7.17-SNAPSHOT`.
> Каждое утверждение привязано к классу/файлу и проверено чтением кода (V1).
> Это as-is картина движка + матрица проблем со сквозными кодами наблюдений.

## Документы анализа
1. [01-architecture.md](01-architecture.md) — модули, зависимости, bootstrap, потоки данных.
2. [02-execution-engine.md](02-execution-engine.md) — токенная модель, state machine, диспетчер, шлюзы, подпроцессы, multi-instance.
3. [03-persistence-data.md](03-persistence-data.md) — JPA-сущности, схема (41 changeset), переменные/скоупы, блокировки.
4. [04-scheduler-events.md](04-scheduler-events.md) — таймеры/scheduler, message/signal, event subprocess, conditional.
5. [05-incidents-compensation.md](05-incidents-compensation.md) — инциденты, ретраи, компенсация, отказоустойчивость.
6. [06-feel-dmn.md](06-feel-dmn.md) — FEEL, DMN-движок, hit policies.
7. [07-api-integrations.md](07-api-integrations.md) — REST/контракты, RabbitMQ, client SDK, worker, безопасность.
8. [08-cluster-ha-security.md](08-cluster-ha-security.md) — кластерные свойства, HA, масштаб, безопасность (сводно).

## Коды наблюдений
`P*` плюсы · `M*` минусы · `B*` bottlenecks · `T*` тех-долг · `F*` отказоустойчивость · `S*` масштаб · `SEC*` безопасность.
`T1–T9` совпадают с роадмапом [../README.md](../README.md) §3. Каждая будущая задача/PR трассируется к этим кодам.

---

## Матрица проблем (сводно)

### Плюсы (P)
| Код | Наблюдение | Подтверждение |
|-----|-----------|---------------|
| **P1** | Чистая модульность (contract↔engine↔rest/mq) + диспетчер по `EnumMap<BpmnElementType,ElementHandler>` | `ActivityServiceImpl.createHandlers()` :92 |
| **P2** | Идемпотентное завершение: статус-гарды терпимы к at-least-once редоставке RabbitMQ | `completeServiceTask` :1251, `failServiceTask` :1173 |
| **P3** | Пессимистичный per-instance лок сериализует конкурентный обход токена (корректность join'ов) | `lockAndReload` :1242, `lockProcessInstance` |
| **P4** | Ошибки паркуются инцидентом, не глотаются; неизвестный элемент → инцидент, а не тихий drop | `execute` :650-669, `raiseIncident` :607 |
| **P5** | C8-семантика: scoped IO-mappings, `failJob` retry-бюджет, zeebe-расширения, FEEL | `applyIoMappings` :1208, `failServiceTask` :1179 |
| **P6** | Атомарный кэш BPMN-моделей (`computeIfAbsent`), parse-once | `BpmnServiceImpl` :33 |
| **P7** | Публикация job в MQ только `afterCommit` (не диспатчим незакоммиченную задачу) | `ServiceTaskEnqueueServiceImpl` |
| **P8** | Гард глубины исполнения против безконечных циклов/рекурсии call activity | `execute` :637-645 |
| **P9** | Схема через Liquibase (41 changeset), H2 в тестах / PostgreSQL в проде | `db/changelog/` |

### Минусы (M)
| Код | Наблюдение | Подтверждение | Связь |
|-----|-----------|---------------|-------|
| **M1** | DMN: `break` на первом совпадении для ВСЕХ политик → корректны только UNIQUE/FIRST/ANY; COLLECT/PRIORITY/RULE/OUTPUT ORDER нет | `DmnServiceImpl.evaluate` :94-100 | T6 |
| **M2** | Inclusive gateway только канонический single-split→single-join (forward-BFS первый join); неканонические топологии хрупки | `processInclusiveGateway` :813, `findInclusiveJoin` :905 | — |
| **M3** | `assignee`/`formKey` парсятся, но не пишутся в `user_tasks` → фильтры assignee неработоспособны | `DBServiceImpl.createUserTask` | T7 |
| **M4** | Нет optimistic locking / version-колонок на сущностях (только пессимистичный лок) | entities | — |
| **M5** | Кэш BPMN-моделей без вытеснения (растёт по числу/редеплою определений) | `BpmnServiceImpl.models` | T-debt |
| **M6** | Документная рассинхронизация: память Mimo врёт (timeCycle «не поддерживается», race в BpmnService) | см. [../_audit/doc-inventory.md](../_audit/doc-inventory.md) | doc-debt |

### Bottlenecks (B)
| Код | Наблюдение | Подтверждение |
|-----|-----------|---------------|
| **B1** | Весь обход процесса — синхронно и рекурсивно в одном потоке и одной транзакции → большие транзакции и долгое удержание лока на широких/глубоких графах | `execute` рекурсия :633-678; `proceedToOutgoing` :152 |
| **B2** | Пессимистичный лок инстанса сериализует ВСЕ операции по инстансу → потолок параллелизма внутри инстанса | `lockProcessInstance` (6 call sites) |
| **B3** | Таймеры — фиксированный poll 5s + полный `findDueTimerJobs`; нужных hot-индексов нет | `TimerScheduler` :26; T8 |
| **B4** | Многократная перезагрузка полного набора переменных за один шаг обхода (DB roundtrips на элемент) | `getVariables` вызовы в `processFlow`/`isFlowActive`/handlers |

### Тех-долг (T) — синхронизирован с роадмапом
| Код | Наблюдение | Подтверждение |
|-----|-----------|---------------|
| **T2** | `findDueTimerJobs`+`markTimerJobFired` не атомарны; нет SKIP LOCKED/conditional UPDATE | `TimerScheduler`, `DBServiceImpl.markTimerJobFired` |
| **T3** | Версионирование через JVM `synchronized(VERSION_LOCK)` — локально для JVM | `ProcessDefinitionServiceImpl` |
| **T6** | DMN hit policies (см. M1) | `DmnServiceImpl` |
| **T7** | assignee/formKey persistence (см. M3) | `DBServiceImpl.createUserTask` |
| **T8** | Нет Actuator/Micrometer; нет hot-индексов БД | — |

### Отказоустойчивость (F)
| Код | Наблюдение | Подтверждение | Связь |
|-----|-----------|---------------|-------|
| **F1** | Нет leader election/distributed lock → таймеры дублируются в multi-node | `TimerScheduler @Scheduled` | T2 |
| **F2** | Нет transactional outbox: enqueue job — `afterCommit` в памяти; краш в окне commit→publish осиротит CREATED-задачу без редоставки | `ServiceTaskEnqueueServiceImpl` | — |
| **F3** | Нет recovery-свипа «застрявших» CREATED service-task (если enqueue потерян, повторной диспетчеризации нет) | — | — |
| **F4** | `markTimerJobFired` (load→set→save) не атомарен (single-node спасает `fixedDelay`, но гонка возможна при перекрытии) | `DBServiceImpl` | T2 |

### Масштаб (S)
| Код | Наблюдение |
|-----|-----------|
| **S1** | Single-node допущения сквозь весь движок (таймеры, version-лок) |
| **S2** | Per-instance сериализация ограничивает параллелизм внутри инстанса |
| **S3** | Безлимитный кэш моделей + полные перезагрузки переменных |
| **S4** | Нет архивации/cleanup истории (`activities`/`tokens` растут безгранично) |

### Безопасность (SEC)
| Код | Наблюдение | Подтверждение | Связь |
|-----|-----------|---------------|-------|
| **SEC1** | Data API без аутентификации (защищены только `/auth/me`+`/users/**`) | `JwtAuthFilter.isProtected` :30 | T1 |
| **SEC2** | Дефолтный `jwt-secret=change-me-…`, нет fail-fast в prod | `TokenService` :29 | T4 |
| **SEC3** | CORS `allowedOriginPatterns("*")` + `allowCredentials(true)` | `WebConfiguration` :13-16 | T4 |
| **SEC4** | Bootstrap `admin/admin` без форс-смены пароля | `UiUserBootstrap` | T4 |
| **SEC5** | Нет мультитенантности/изоляции данных | — | T5 |
| **SEC6** | Исполнение FEEL/script-выражений из модели — поверхность исполнения произвольного кода при недоверенных моделях | `ScriptServiceImpl`, script/business-rule tasks | — |

---

## Итоговая оценка (подтверждена кодом)
Движок **зрелый для своей версии**: аккуратная идемпотентность, локинг, C8-совместимая семантика, ошибки не глотаются.
Главные ограничения — **архитектурные для multi-node** (синхронный одно-поточный обход + пессимистичный лок + отсутствие координации) и **безопасность по умолчанию** (открытый Data API).
Блокеры прод-запуска: **SEC1/T1, SEC2-4/T4, F1/T2**. Для HA — пересмотр модели исполнения (async continuation/job-based) — отдельный архитектурный трек (ADR).
