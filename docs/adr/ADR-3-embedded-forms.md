# ADR-3 — Встроенные формы (аналог Camunda Forms)

Статус: **ПРИНЯТ** (продукт-владелец, 2026-07-14)

## Решения продукт-владельца
1. **Движок:** `@bpmn-io/form-js` (точный аналог Camunda Forms) — принято.
2. **Объём:** полный — FORM-1..5 (task-формы + старт-формы + **серверная валидация**) + **FORM-6 (встроенный
   редактор form-js в SPA)**. Все фазы в работе.
3. **Авторинг:** встроенный `FormEditor` в консоли ZBPM (FORM-6), не только Camunda Modeler.
4. **Привязка:** linked (`formId` + деплой) как основа; embedded — по возможности, не обязателен для MVP.
5. **Серверная валидация (FORM-5):** нужна (жёсткая, 400) — «мусором не стартанёшь».

Контекст: [integration-guide.md](../../README.md), [ADR-2](ADR-2-centralized-control-plane.md)

## Проблема
Сейчас движок несёт только `formKey` (строку) — форму рендерит и валидирует внешняя система (external forms,
паритет с Camunda external form). Нет **встроенных** форм: чтобы ZBPM сам отдавал схему формы и рисовал её
(как Camunda Forms + Tasklist). Нужно для процессов, где своей внешней системы/формы нет.

## Решение (кратко): взять `@bpmn-io/form-js`, не изобретать
`@bpmn-io/form-js` — движок форм Camunda (MIT, framework-agnostic vanilla-JS). Даёт **бесплатно**:
JSON-схему форм, рендерер (`Form`), редактор (`FormEditor`), и авторинг в **Camunda Modeler**. Мы добавляем
только: хранение схем, form-API, обёртку-рендерер на Vue. Схему НЕ придумываем — она уже стандарт.

## Архитектура — 4 слоя

### 1. Хранилище (backend)
Таблица `form` (миграция): `id`, `form_key`, `version`, `schema_json` (text), `created_at`. Версионирование
как у process-definition (новый деплой ключа → новая версия, latest — активная). Форма — **ресурс деплоя**.

### 2. Деплой + привязка
- **Деплой:** отдельный `POST /forms` `{ key, schema }` (НЕ трогаем `AddProcessDefinitionDTO`/contract — G-C).
  Опционально позже: мультиресурсный деплой (BPMN+формы одним пакетом).
- **Привязка в BPMN** (уже парсится, `BpmnParseServiceImpl:754`):
  - `zeebe:formDefinition formId="orderForm"` → **linked** внутренняя форма (резолв по `form_key`);
  - `formKey="camunda-forms:embedded:..."`/inline JSON → **embedded** (хранить инлайн со схемой процесса);
  - `externalReference="https://..."` → **external** (как сейчас, passthrough — не трогаем).
  Различаем тип по префиксу/полю; external-путь остаётся рабочим (обратная совместимость).

### 3. Form-API (backend)
```
POST /forms                         деплой/версия схемы формы
GET  /forms/{key}                   схема (latest)
GET  /user-tasks/{id}/form          {schema, data}  — схема задачи + текущие variables (prefill)
GET  /process-definitions/{key}/start-form   схема стартовой формы (по formKey старт-события)
```
`GET /user-tasks/{id}/form` резолвит formId/formKey задачи → схема + маппинг variables инстанса в `data`
(prefill). Для external-ref возвращает `{ type: "external", url }` (фронт линкует наружу, как сейчас).

### 4. Рендерер (frontend)
Обёртка `<FormRenderer :schema :data @submit>` вокруг form-js `Form` (mount в `ref`-div, vanilla API).
- **Task:** `TaskDetail.vue` → `GET /user-tasks/{id}/form` → рендер → submit → маппинг data→variables →
  `POST /user-tasks/{id}/complete`.
- **Start:** новая `StartForm.vue` → `GET .../start-form` → рендер → `POST /process-instances` с variables.
- Валидация клиентская — form-js делает из схемы (required/pattern/min-max).
- data (плоский `{key: value}`) ↔ ProcessVariable (`name/value/type`): тип из компонента схемы (number/
  checkbox/datetime/textfield/select).

## Опционально (later): серверная валидация
`POST /process-instances` и `/complete` валидируют submitted variables против схемы формы (required/типы) → 400.
Закрывает «стартовой формой можно послать что угодно» из integration-guide §3. Отдельная фаза, не блокер.

## Фазовый план (WO)
| WO | Слой | Содержание |
|---|---|---|
| **FORM-1** | backend | таблица `form` + миграция (PG-гейт), `POST /forms`, `GET /forms/{key}`, версионирование |
| **FORM-2** | backend | резолв привязки: `GET /user-tasks/{id}/form` (schema+prefill), `GET .../start-form`; formId→form; external→passthrough |
| **FORM-3** | frontend | dep `@bpmn-io/form-js` (+lock, P-12), `<FormRenderer>`, интеграция в `TaskDetail.vue` (task-форма → complete) |
| **FORM-4** | frontend | `StartForm.vue` (стартовая форма → start-instance) + список процессов со стартовой формой |
| **FORM-5** | backend | (опц.) серверная валидация variables против схемы → 400 |
| **FORM-6** | frontend | (опц.) встроенный `FormEditor` (form-js) для авторинга в SPA, либо полагаемся на Camunda Modeler |

Зависимости: FORM-1 → FORM-2 → FORM-3 → FORM-4; FORM-5/6 независимы (после FORM-2/3).

## Почему так
- **form-js = точный аналог Camunda Forms** (та же схема/рендерер/модельер) — минимум своего кода, максимум совместимости.
- **Не ломаем external forms** — новый internal-путь рядом; `formKey`-passthrough остаётся.
- **Формы = данные** (schema_json), процессы = BPMN — один generic-рендерер на N форм (не N экранов).
- **Contract не трогаем** (отдельный `POST /forms`, а не расширение deploy-DTO) — обходим G-C.

## Открытые вопросы (продукт-владельцу)
1. **Авторинг:** редактор в SPA (FORM-6) или достаточно Camunda Modeler / ручной JSON? (влияет на объём)
2. **Embedded vs linked:** нужны embedded-формы (JSON внутри BPMN) или только linked (`formId` + деплой)? Linked проще.
3. **Серверная валидация (FORM-5):** нужна жёсткая (400) сразу или клиентской form-js достаточно на старте?
