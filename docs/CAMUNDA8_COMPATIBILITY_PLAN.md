# Camunda 8 Compatibility Plan

Анализ `docs/REPORT_FOR_CLAUDE.md` под **единственный критерий приоритизации — влияние на совместимость
с BPMN-моделями Camunda 8 (Zeebe)**. Каждый пункт отчёта сверен с текущим кодом (`master`).
Исправления не выполнялись — только анализ и backlog.

---

## 0. Снимок состояния проекта (2026-06-21)

| Область | Состояние |
|---|---|
| **Backend** | Полный reactor `mvn clean verify` — зелёный (unit + интеграционные: 61 engine ITs + 17 REST + unit). Все 10 модулей `BUILD SUCCESS`. |
| **Frontend** | Появился новый модуль `zorrobpm-frontend` (Vue 3 + Vite + TS, MVP). `npm ci && npm run build` — зелёный (typecheck + сборка). В `src/` нет hardcoded `localhost`/IP; API-база относительная `/api`. |
| **Docker** | Оба образа (`zorrobpm-app`, `zorrobpm-frontend`) собираются; `docker compose config` валиден. Добавлен сервис `frontend` (nginx: SPA + `/api`→`app:8080`). |
| **CI/CD** | `.gitlab-ci.yml` переписан: `test → package → deploy → rollback`, версионирование образов по `$CI_COMMIT_SHORT_SHA`, стратегия отката. |
| **Прод** | За внешним nginx: `https://zorro.i-smet.kz` → `frontend` → `/api` → `app`. Runbook: [deployment.md](deployment.md). |
| **C8 BPMN backlog** | Без изменений в этой сессии (фокус — публикация/инфраструктура). Новые BPMN-конструкции **не** реализовывались. См. §2 ниже. |

> **Фокус сессии 2026-06-21:** публикация и инфраструктура (frontend-контейнеризация, CI/CD, Docker,
> деплой/откат, skills) — **выполнено и влито в `master`** (merge `0802fe4`). Реализация P0/P1 из
> BPMN-backlog ниже — следующий трек (в этой сессии BPMN-конструкции не реализуются).
>
> Frontend↔backend API-разрывы (отдельный трек, не C8-совместимость) вынесены в
> [frontend-api-gaps.md](frontend-api-gaps.md).

> **Шкала приоритета (по влиянию на C8-совместимость):**
> - **P0** — модели Camunda 8 **не смогут корректно выполняться**; обязательно до релиза.
> - **P1** — модели выполняются с ограничениями; возможны ошибки исполнения или отличия поведения.
> - **P2** — частичная несовместимость; редкие сценарии или отдельные BPMN-элементы.
> - **P3** — технический долг, надёжность/безопасность/производительность, рефакторинг (не меняет семантику исполнения C8-моделей).
>
> **Оценка сложности:** S ≈ 1–2 дня · M ≈ 3–5 дней · L ≈ 1–2 недели.

---

## 1. Сверка пунктов отчёта с текущим кодом

Отчёт составлен по более раннему снимку. Часть пунктов **уже закрыта** (работы по C8-совместимости,
влиты в `master`); часть — **актуальна**; часть — **ложные срабатывания**.

### ✅ Уже закрыто (проверено по коду) — в backlog НЕ включается

| Пункт отчёта | Текущий статус |
|---|---|
| Send task = только `messageRef` (⚠️) | ✅ `zeebe:taskDefinition` → job worker; `messageRef` → message throw (`processSendTask`) |
| Compensation: нет targeted (`activityRef`) | ✅ `processCompensationThrow` фильтрует по `activityRef` |
| `zeebe:userTask` не парсится (P3 #19) | ✅ парсится и исполняется (маркер игнорируется JAXB, assignment/form читаются) |
| Script Task: `zeebe:script` | ✅ `zeebe:script` + inline `<bpmn:script>` |
| Multi-instance: count из `zeebe:loopCharacteristics` | ✅ `inputCollection` → размер коллекции, либо `loopCardinality` |
| Event subprocess: только message-триггер | ✅ message / signal / error / timer (interrupting; message — также non-interrupting) |
| IO-mappings «протекают» (плоские переменные) | ✅ scoped: input-локали не протекают, output → родитель, очистка по завершении |

### ⚠️ Ложные срабатывания / уже корректно

| Пункт отчёта | Вывод |
|---|---|
| `ProcessVariable type "LONG" вместо значения` (`ServiceTaskEnqueueServiceImpl:53`) | **Не баг**: `setType(type.toString())` пишет поле *type*; *value* устанавливается отдельно (`setValue(pv.getValue())`) |
| `Variable scope leak` (`:1103-1114`) | **Корректно**: это и есть новое scoped-поведение (input-локали удаляются `deleteVariables` по завершении) |
| Concurrency-комментарии «корректно» (timer re-fire guard, child→parent lock order, conditional re-trigger) | Подтверждены как корректные в отчёте — задач нет |

### 🔴 Актуальные гэпы (проверено по коду) — основа backlog

| Пункт отчёта | Подтверждение в коде |
|---|---|
| Call activity NPE (BUG-1/2/3/8) | `processCallActivity`: голый `bpmnElement.getExtensions().getCallActivityExtension().getProcessId()` без null-проверок |
| `propagateAllChildVariables` игнорируется | флаг **нигде не используется** (`grep` пуст); `finishBranch` всегда копирует child→parent |
| `timeCycle` не парсится | в `bpmn`-моделях нет `timeCycle`/`CYCLE`; парсятся только `timeDate`/`timeDuration` |
| Нет DOUBLE/DATE/JSON типов | `ProcessVariableType = {STRING, UUID, LONG, BOOLEAN}` |
| MI per-instance `inputElement`/`loopCounter`/`outputCollection` | в коде комментарий «not yet supported»; биндинга нет |
| BpmnService race (BUG-9) | `containsKey(id)` + `get(id)` (не атомарно), без guard на `null` от `getFileBytes` |
| NPE-цепочки (proceedToOutgoing/execute/fireBoundary/finishBranch) | без null-проверок `flow`/`target`/`element`/`boundary` |
| DMN versioning | перезапись по `decisionId` при повторном деплое |

---

## 2. Camunda 8 Compatibility Backlog

> Приоритет — **строго по влиянию на исполнение C8-моделей**, а не по общему техдолгу.

### P0 — модели C8 не выполняются корректно (обязательно до релиза)

#### C8-1 · Модель переменных: JSON-объекты, списки и десятичные (DOUBLE)
- **Описание:** текущая модель переменных скалярная (`STRING/UUID/LONG/BOOLEAN`). Camunda 8 — JSON-центрична: переменные часто являются объектами (`{...}`), списками (`[...]`) и десятичными числами; FEEL обращается к вложенным свойствам (`order.total`) и итерирует коллекции.
- **BPMN-элемент:** process variables / data objects, FEEL-выражения (условия потоков, IO-mapping, DMN, script/business-rule), multi-instance `inputCollection`.
- **Влияние на совместимость:** C8-модели с JSON-payload, коллекциями-в-переменных и десятичными суммами **не выполняются корректно** — переменная теряет структуру/тип, FEEL по свойствам и сравнения десятичных ломаются (инцидент или неверный результат). Затрагивает большинство реальных C8-процессов.
- **Риск миграции:** **высокий** — типичный C8-процесс получает JSON-вход.
- **Приоритет:** **P0**
- **Сложность:** **L** (расширить `ProcessVariableType` + хранение JSON, маппинг в/из FEEL-значений `feel-engine`, согласовать с `ScriptService`/DMN/IO-mapping).

#### C8-2 · Multi-instance: per-instance `inputElement` / `loopCounter` / `outputCollection`
- **Описание:** при MI порождается N инстансов (count готов), но текущий элемент коллекции (`inputElement`), индекс (`loopCounter`) и агрегирование результатов (`outputCollection`/`outputElement`) не привязываются к инстансу.
- **BPMN-элемент:** `multiInstanceLoopCharacteristics` + `zeebe:loopCharacteristics`.
- **Влияние на совместимость:** **доминирующий C8-паттерн MI** — «для каждого элемента коллекции». Без `inputElement` тело инстанса не видит свой элемент → задача/воркер получает неполные данные → неверное/падающее исполнение. Агрегация в `outputCollection` отсутствует.
- **Риск миграции:** **высокий** — collection-driven MI повсеместен.
- **Приоритет:** **P0**
- **Сложность:** **M** (scoped-переменные уже есть из Этапа 2; писать `inputElement`=element[i] в scope инстанса; `outputCollection` зависит от C8-1 — списки).

### P1 — выполняются с ограничениями / отличия поведения

#### C8-3 · `timeCycle` (повторяющиеся / cron-таймеры)
- **Описание:** парсятся только `timeDate`/`timeDuration`. `<timeCycle>` (например, `R/PT1H`, cron) не читается → у таймера нет выражения → `computeDueAt` бросает исключение → инцидент.
- **BPMN-элемент:** `timerEventDefinition timeCycle` (timer start / boundary / intermediate catch).
- **Влияние на совместимость:** модели с cycle/cron-таймерами (планировщики, периодические напоминания) — **таймер не срабатывает**, элемент нефункционален.
- **Риск миграции:** **средний** — частый паттерн для запланированных процессов.
- **Приоритет:** **P1**
- **Сложность:** **M** (парсинг `timeCycle`, `TimerEventType.CYCLE`, механизм перепланирования следующего срабатывания).

#### C8-4 · `propagateAllChildVariables = false`
- **Описание:** флаг парсится, но `finishBranch` всегда копирует все переменные child→parent.
- **BPMN-элемент:** `callActivity` / `zeebe:calledElement propagateAllChildVariables`.
- **Влияние на совместимость:** модели, явно отключающие пропагацию (изоляция переменных дочернего процесса), получают **лишние переменные** в родителе → отличие поведения, возможна порча данных/логики маршрутизации.
- **Риск миграции:** **средний** (специфичная, но осознанная конфигурация в C8).
- **Приоритет:** **P1**
- **Сложность:** **S** (прочитать флаг родительского call-activity в `finishBranch`, пропустить копирование при `false`).

### P2 — частичная несовместимость / редкие сценарии / надёжность исполнения

#### C8-5 · Call activity: null-safety и понятные ошибки
- **Описание:** `processCallActivity` обращается к `extensions().getCallActivityExtension().getProcessId()` без null-проверок; несуществующий целевой процесс → `NoSuchElementException`.
- **BPMN-элемент:** `callActivity`.
- **Влияние на совместимость:** корректный C8 call-activity при задеплоенном таргете **работает**; но при инкрементальном деплое (родитель раньше ребёнка — частый сценарий миграции) — невнятный NPE/NoSuchElement-инцидент вместо «process X не задеплоен».
- **Риск миграции:** **средний** (migration DX; не блокирует корректные модели).
- **Приоритет:** **P2**
- **Сложность:** **S** (Optional-цепочка + информативные `EngineException`; см. Task 1/8 отчёта).

#### C8-6 · Script task в форме job worker (`zeebe:taskDefinition`)
- **Описание:** обрабатывается `zeebe:script` (FEEL); C8 8.2+ допускает script task через job worker (`zeebe:taskDefinition`), который сейчас не диспатчится.
- **BPMN-элемент:** `scriptTask`.
- **Влияние на совместимость:** C8 script task на воркере не исполняется (инцидент «нет скрипта»).
- **Риск миграции:** **низкий** (редкий вариант — обычно FEEL).
- **Приоритет:** **P2**
- **Сложность:** **S** (по аналогии с `processSendTask`: ветка job-worker для script task).

#### C8-7 · Надёжность графа: null-safe `proceedToOutgoing`/`execute`/`fireBoundary`/`finishBranch` + `BpmnService` race
- **Описание:** NPE-цепочки при отсутствии `flow`/`target`/`element`/`boundary`; `getProcessDefinitionModelById` использует `containsKey`+`get` (гонка, NPE при `parse(null)`).
- **BPMN-элемент:** общий рантайм исполнения (любые элементы).
- **Влияние на совместимость:** на корректных C8-моделях в single-instance — обычно ОК; под нагрузкой/при граничных XML — невнятные инциденты/NPE, нестабильное исполнение.
- **Риск миграции:** **средний** (надёжность реального прогона).
- **Приоритет:** **P2**
- **Сложность:** **S–M** (null-guards + `computeIfAbsent`; см. Tasks 2/4/5 отчёта).

### P3 — техдолг / надёжность / безопасность (не меняет семантику исполнения C8-моделей)

| ID | Описание | BPMN-элемент | Влияние на C8 | Риск | Приоритет | Сложность |
|---|---|---|---|---|---|---|
| C8-8 | DMN versioning (перезапись при повторном деплое) | Business rule task / DMN | Не блокирует исполнение задеплоенной модели; влияет на историю/откат | низкий | P3 | S |
| C8-9 | `@Transactional` на entry points `ActivityService` | — | Сейчас транзакцию даёт REST-слой; вне него — риск частичных коммитов | низкий | P3 | S |
| C8-10 | Кластеризация таймеров (leader-election / `SKIP LOCKED`) | timer events | В single-instance ОК; в кластере — двойное срабатывание таймеров | средний (кластер) | P3 | M |
| C8-11 | Optimistic locking (`@Version`) / timeout на `SELECT FOR UPDATE` | — | Надёжность под конкуренцией; не семантика модели | низкий | P3 | M |
| C8-12 | Аутентификация/авторизация, CORS | REST | Безопасность (отдельный release-трек), не C8-исполнение | — | P3 | M |
| C8-13 | Прочее: `SneakyThrows`, reflection в `collectionSize`, N+1 в `processFlow`, JDK `java.version` mismatch | — | Качество/производительность | низкий | P3 | S |

---

## 3. Ответы

### 1. Какие задачи обязательны для достижения совместимости с Camunda 8?
**Обязательны (P0):**
- **C8-1 — модель переменных (JSON-объекты/списки + DOUBLE).** Фундамент: без неё JSON-payload, коллекции и десятичные C8-модели не исполняются корректно.
- **C8-2 — per-instance `inputElement`/`loopCounter`/`outputCollection` для multi-instance.** Доминирующий C8-паттерн «для каждого элемента коллекции» (опирается на C8-1 для коллекций-в-переменных).

Сильно желательны до релиза (P1): **C8-3 (`timeCycle`)** для запланированных процессов и **C8-4 (`propagateAllChildVariables`)** для корректной изоляции переменных call activity.

### 2. Какие задачи можно отложить?
- **Весь P3** (DMN versioning, транзакционный контур, кластеризация таймеров, optimistic locking, auth/CORS, мелкий техдолг) — не меняет семантику исполнения C8-моделей; ведётся отдельным треком надёжности/безопасности.
- **P2** можно стадировать после P0/P1: call-activity сообщения об ошибках (C8-5), script-task job-worker (C8-6), общая null-safety/`BpmnService` race (C8-7) — улучшают DX и надёжность, но корректные модели в single-instance работают и без них.

### 3. Текущий уровень совместимости с Camunda 8 (в процентах)
- **По покрытию конструкций/нотации BPMN+Zeebe: ~88%.** Поддержаны почти все исполняемые элементы и C8-нотация (события, шлюзы, задачи вкл. `zeebe:taskDefinition`/`zeebe:userTask`/`zeebe:calledDecision`, IO-mappings scoped, event subprocess со всеми триггерами, compensation вкл. targeted, корреляция по ключу).
- **По доле реальных C8-моделей, выполняемых без правок: ~70%.** Ниже покрытия, потому что гэп модели переменных (C8-1) и MI-элемента (C8-2) затрагивает многие реальные процессы (JSON-вход, итерация коллекций, десятичные суммы).

*(Оценки экспертные, взвешенные по частоте паттернов; точные цифры зависят от выборки моделей.)*

### 4. Самое большое препятствие для запуска реальных C8-моделей
**Скалярная модель переменных (C8-1).** Camunda 8 — JSON-центрична: процессы переносят объекты и списки, FEEL обращается к вложенным свойствам и итерирует коллекции, суммы — десятичные. Текущие `STRING/UUID/LONG/BOOLEAN` не представляют эти формы, что **каскадом** ломает: коллекции для multi-instance, FEEL-доступ к свойствам объектов, сравнения десятичных в шлюзах/условиях. Пункты отчёта «нет DOUBLE/DATE» и «MI без per-instance vars» — симптомы одного корня. Это первый кандидат на устранение для реальной C8-совместимости.

---

*Документ — только анализ и план. Исправления/код не вносились. Для каждого пункта указаны затрагиваемые
классы по тексту отчёта и подтверждены текущим кодом `master`.*
