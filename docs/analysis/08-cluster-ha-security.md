# 08 — Cluster, HA, Scale & Security (сводно)

Сквозной разрез по нефункциональным свойствам. Детали — в профильных доках; здесь — системная картина и приоритеты.

## Кластерные свойства / HA
Движок спроектирован под **single-node**. В multi-node ломаются:
| Код | Проблема | Корень | Решение (направление) |
|-----|----------|--------|----------------------|
| **F1/T2** | Таймеры дублируются | `TimerScheduler @Scheduled` без координации; `findDueTimerJobs`+`markTimerJobFired` неатомарны | `SELECT … FOR UPDATE SKIP LOCKED` / conditional UPDATE; ShedLock/leader election |
| **T3** | Версионирование некорректно | `ProcessDefinitionServiceImpl` JVM `synchronized(VERSION_LOCK)` | DB advisory lock / `FOR UPDATE` по ключу + unique `(code,version)` с ретраем |
| **F2/F3** | Потеря/непереотправка job | `afterCommit` in-memory enqueue, нет outbox/ресвипа | transactional outbox + recovery-свип CREATED |

**P3** (пессимистичный per-instance лок) даёт **корректность** конкуренции в пределах общей БД — два инстанса
приложения, бьющие в один Postgres, не словят double-advance по одному инстансу. Но это не делает систему HA:
таймеры и версионирование требуют явной координации.

> **Архитектурная развилка (кандидат в ADR).** Синхронная одно-поточная рекурсивная модель обхода в одной
> транзакции (B1) + пессимистичный лок (B2) — потолок и для масштаба, и для HA. Зрелый путь — **job-based async
> continuation** (каждый шаг — единица работы в очереди/таблице, подбираемая воркерами с SKIP LOCKED). Это
> крупный трек; не делать молча — оформить ADR с trade-offs.

## Масштаб
- **S1** single-node допущения. **S2** per-instance сериализация ограничивает внутри-инстансный параллелизм.
- **S3** безлимитный кэш моделей + полные перезагрузки переменных (B4).
- **S4** нет архивации/cleanup истории — таблицы `activities`/`tokens`/инстансы растут безгранично.
- **T8** нет hot-индексов и Actuator/Micrometer → нет наблюдаемости узких мест под нагрузкой (замеры, не оценки — V6).

## Безопасность (сводная матрица)
| Код | Проблема | Файл | Severity |
|-----|----------|------|----------|
| **SEC1/T1** | Data API без аутентификации (только `/auth/me`+`/users/**`) | `JwtAuthFilter` :30 | **Critical** |
| **SEC2/T4** | Дефолтный `jwt-secret=change-me-…`, нет fail-fast в prod | `TokenService` :29 | **Critical** |
| **SEC3/T4** | CORS `allowedOriginPatterns("*")` + `allowCredentials(true)` | `WebConfiguration` :13-16 | High |
| **SEC4/T4** | Bootstrap `admin/admin` без форс-смены | `UiUserBootstrap` | High |
| **SEC5/T5** | Нет мультитенантности/изоляции данных | — | High (enterprise) |
| **SEC6** | Исполнение FEEL/script из модели — поверхность при недоверенных моделях | `ScriptServiceImpl` | Medium (зависит от модели доверия деплоя) |

## Приоритеты (трассировка к роадмапу [../README.md](../README.md))
1. **Фаза 1 (блокеры прода):** SEC1/T1, SEC2-4/T4, F1/T2 — закрыть до любого недоверенного окружения.
2. **HA-трек (ADR):** модель исполнения (B1/B2) + outbox (F2/F3) + leader election (F1) + версионирование (T3).
3. **Production polish:** T8 (observability/индексы), S4 (cleanup), T6 (DMN), T7 (assignee), M2 (inclusive топологии).

**Итог:** функционально зрелый single-node движок с аккуратной семантикой и идемпотентностью; нефункционально —
требует трека HA/безопасности. Сильные стороны (P2/P3/P4) — фундамент, на котором HA достраивается эволюционно,
а не переписыванием.
