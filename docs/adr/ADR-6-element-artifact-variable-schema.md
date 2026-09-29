# ADR-6 — ElementArtifact + Variable Schema (контракт входных variables для внешнего BFF)

Статус: **ПРИНЯТ** (продукт-владелец, 2026-07-15)
Автор: CTO / Architect. Связь: [ADR-1 (multi-tenant)](ADR-1-multi-tenant-authorization.md),
[ADR-3 (встроенные формы)](ADR-3-embedded-forms.md), [ADR-5 (формы как валидатор)](ADR-5-forms-as-validation-formatter.md),
[integration-guide](../../README.md). ADR-5 уточняется настоящим ADR (финальная модель).

## Контекст
ZBPM — headless-оркестратор: end-user'ы живут во внешних системах + их BFF; ZBPM SPA — админка. Нужен механизм
**описания ожидаемых process variables** для **Start Event** и **User Task**, чтобы внешний BFF **валидировал
входной JSON до вызова Runtime**, затем сам формировал variables и вызывал Runtime. Runtime менять не требуется.
Цели: максимальная совместимость с Camunda 8 / Modeler; переиспользование существующей формовой инфраструктуры
(FORM-1..6: `FormEntity`, парсинг `formDefinition`, резолв по ключу); минимум собственных сущностей и BPMN-расширений.

## Compatibility Principle (управляющее правило)
- Есть штатный механизм Camunda 8 (напр. External Form Reference для User Task) → используем именно его.
- Нет (напр. для Start Event) → реализуем внутри ZBPM, **не модифицируя BPMN и без собственных BPMN-extension**.

## Решения

### D1. `ElementArtifact` — инфраструктурная сущность (не «Form»)
Нейтральный версионируемый контейнер (эволюция таблицы `form`), привязанный к элементу процесса. Отвечает
**исключительно** за: хранение · версии · публикацию · получение · резолв · привязку к элементам. **Бизнес-логики
не содержит.** Интерпретация payload — специализированными сервисами по `kind`. (Form и Variable Schema — разные
домены, но один механизм хранения/резолва; объединять по домену нельзя, по механизму — нужно.)

### D2. `kind` — обязательный явный атрибут (БЕЗ auto-detect)
`kind` задаётся пользователем **явно при создании** артефакта. **Никакого определения типа по содержимому payload**
(`components`/`$schema`) — auto-detect удобен на старте, но становится источником неоднозначности при появлении новых
типов. Значения **максимально конкретные и расширяемые без переименований**:
`FORM_JS` · `VARIABLE_SCHEMA` · (в будущем — иные виды артефактов).

### D3. Доменные сервисы по `kind` (раздельные)
- `FormArtifactService` (`FORM_JS`): form-js рендер + серверная form-js-валидация.
- `VariableSchemaService` (`VARIABLE_SCHEMA`): отдаёт JSON Schema. Используется **исключительно внешним BFF**.
Общее у них — только хранилище/резолв через `ElementArtifact`. Полиморфного поведения в самой сущности нет.

### D4. Формат `VARIABLE_SCHEMA` = **JSON Schema Draft 2020-12** (стандарт платформы)
Variable Schema **всегда** хранится как JSON Schema 2020-12. **Собственный DSL не разрабатываем.** Стандарт нативно
покрывает: `type`, `required`, `properties` (вложенность), `items`/`prefixItems` (массивы), `default`, `enum`,
`minimum/maximum/pattern`, `if/then/else`, `oneOf` и т.д. BFF валидирует **готовой библиотекой** (ajv и аналоги).
Все расширения — только через **штатный механизм расширений JSON Schema** (неизвестные ключи игнорируются валидатором).

### D5. UI-метаданные — пространство имён `x-ui` (будущее развитие)
Для будущей автогенерации форм внешними Frontend — собственное пространство внутри JSON Schema: `x-ui`, `x-layout`,
`x-component` и т.п. **Ядро платформы и BFF эти свойства НЕ анализируют** — только для внешних Frontend. Один JSON
одновременно: BFF — для валидации, Frontend — для генерации UI. **Без изменения архитектуры платформы.**

### D6. Привязка — по Compatibility Principle
| Элемент | Camunda-механизм | Как привязывается |
|---|---|---|
| **User Task** | ✅ `External Form Reference` (`zeebe:formDefinition externalReference`) | в BPMN (Camunda Modeler); ZBPM трактует `externalReference` как **universal `artifactKey`** |
| **Start Event** | ❌ нет | **внутри ZBPM по `elementId`** — BPMN стерильный, ноль extension |

- **Per-elementId** (процесс может иметь несколько start event'ов, в т.ч. message start). Скалярный
  `ProcessDefinitionEntity.startFormKey` обобщается в связку `(pd_version, element_id, artifact_key)`.
- **Message Start**: контракт входа = **payload сообщения** (переменные корреляции); резолвится по той же
  внутренней привязке (по `elementId`). Message-correlation при этом идёт по `messageRef`, не по `elementId`.

### D7. Единый резолвер
```
element → artifactKey → ElementArtifact(kind) → доменный сервис
```
Один резолвер и одна сущность; различается **только источник** `artifactKey` (BPMN `externalReference` для user-task /
внутренняя ZBPM-привязка для start event) — это зеркалит саму асимметрию Camunda. Порядок для start: явная
admin-привязка по `elementId` → (опц.) конвенция `<processKey>:<elementId>`.

### D8. Версионирование — пиннинг к версии Process Definition
Артефакт версионируется; **версия артефакта пиннится к версии Process Definition**. Контракт **иммутабелен** для
конкретной версии процесса (инстанс, стартованный на v3, резолвит те версии схем, что были актуальны для v3).
Отказ от принципа latest-wins.

### D9. Изоляция Runtime от Variable Schema (жёстко)
- Runtime (`startProcessInstance` / `completeUserTask`) **не работает с Variable Schema и не знает о `kind`**.
- Единственная серверная валидация в рантайме — form-js — **инкапсулирована в `FormArtifactService`**: Runtime
  делегирует «прогони форм-валидацию, если применима к элементу», сервис сам резолвит артефакт и возвращает **no-op
  для всего, что не `FORM_JS`**. Runtime не ветвится по `kind`, не ссылается на `VARIABLE_SCHEMA`.
- `VariableSchemaService` из Runtime **не вызывается никогда** (только внешним BFF).
- Существующий FORM-5 приводится к этой модели (валидация уезжает за фасад `FormArtifactService`).

### D10. Настройка (где что)
| Что | Где | Как |
|---|---|---|
| Процесс + `externalReference` на User Task | **Camunda Modeler** | штатное External Form Reference |
| Содержимое всех артефактов (JSON Schema / form-js), выбор `kind` | **UI Zorro** | реестр/редактор артефактов (эволюция экрана Forms) |
| Привязка стартового артефакта к Start Event по `elementId` | **UI Zorro** | Admin: дефиниция → её start event'ы → прикрепить артефакт |
| Версии/пиннинг | ZBPM (авто при деплое) + просмотр в UI | |

## Модель данных (ориентир)
```
element_artifact( id, artifact_key, version, kind[FORM_JS|VARIABLE_SCHEMA|…], payload_json:text, created_at )
element_artifact_binding( process_definition_id, process_definition_version, element_id, artifact_key, artifact_version )
```
User-task `externalReference` парсится в тот же `artifact_key` (binding выводится из BPMN); start-event binding — из UI.
`ProcessDefinitionEntity.startFormKey` — мигрируется в `element_artifact_binding` (частный случай).

## API (ориентир)
- Единый резолв, возвращает `{ kind, payload, prefill? }`: `GET /process-definitions/{key}/start-form`
  (обобщить на per-start `elementId`), `GET /user-tasks/{id}/form`. BFF разбирает по `kind`.
- CRUD артефактов: обобщить `POST /forms` → `(key, kind, payload)`.
- Детали сигнатур — на этапе реализации (contract-модуль).

## Риски и границы
1. **Version drift** — снимается D8 (пиннинг).
2. **Доверие к BFF**: ZBPM не форсит валидацию внешних артефактов (by design) — ответственность BFF; метрика
   orphan-инцидентов как страховка.
3. **Один элемент = один артефакт** (single `externalReference`) — осознанная граница MVP.
4. **Неатомарная публикация** (артефакты деплоятся отдельно от BPMN) — улучшается в итерации 3.
5. Message-correlation — по `messageRef`, не путать с `elementId`-привязкой артефакта.

## План реализации (MVP → итерации)
- **MVP (ит. 1):** `element_artifact` + обязательный явный `kind` (`FORM_JS`|`VARIABLE_SCHEMA`); `VariableSchemaService`
  (отдаёт JSON Schema); единый резолв `{kind, payload}`; UI: явный выбор `kind` + редактор JSON Schema +
  привязка стартового артефакта по `elementId`; **form-js валидация за фасадом `FormArtifactService`, Runtime
  kind-agnostic**. → BFF уже может тянуть контракт и валидировать.
- **Ит. 2:** пиннинг версии артефакта к версии PD; per-start-event binding (мульти-старт); проверка валидности
  JSON Schema при публикации.
- **Ит. 3:** атомарная публикация (бандл BPMN + артефакты одной транзакцией).
- **Ит. 4:** `x-ui`-метаданные (внешняя автогенерация форм) — без изменения ядра; ZBPM остаётся источником JSON.

## Что НЕ делаем
- Не auto-detect `kind`. Не собственный DSL (только JSON Schema). Не собственные BPMN-extension. Не тянем
  Variable Schema / `kind` в Runtime. Не делаем `ElementArtifact` носителем бизнес-логики.
