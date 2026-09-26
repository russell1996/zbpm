# ADR-5 — Формы = единый артефакт «рендер + форматтер валидации» (form-js) для external forms

Статус: **ПРИНЯТ, уточнён** → финальная модель контракта входных variables в [ADR-6 (ElementArtifact + Variable Schema)](ADR-6-element-artifact-variable-schema.md). Этот ADR остаётся в силе в части «две дорожки form-js/внешние формы»; формат и хранение Variable Schema см. ADR-6.
Автор: CTO / Architect. Связь: [ADR-3 (встроенные формы)](ADR-3-embedded-forms.md), [ADR-6](ADR-6-element-artifact-variable-schema.md), [integration-guide](../../README.md).

## Контекст
ZBPM — headless-оркестратор (никто не логинится в ZBPM как end-user; SPA = админка). Внешние системы работают
через свой UI + BFF (см. модель «движок авторизует систему, не человека»). Нужны формы для external-старта и
user-task'ов, желательно с **визуальным конструктором** (no-code) и валидацией. Опциональная фича под external forms.

## Решение
1. **Один артефакт двойного назначения — form-js schema.** Не делаем свой формат и НЕ держим отдельно
   «форму для рендера» и «JSON Schema для валидации» (они разъезжаются). form-js schema — источник истины и для
   рендера, и для валидации: каждое поле несёт компонент (как рисовать) + `validate:{}` (что проверять).
2. **form-js как весь стек, не свой велосипед:** `FormEditor` = конструктор (drag-drop → JSON), viewer = рендерер,
   schema = формат, `validate` = правила. Свой drag-drop билдер НЕ строим.
3. **Валидация = «форматтер» из той же схемы, на двух уровнях:** клиент (form-js viewer) + сервер
   (`FormValidator`, ZBPM). Серверный слой обязан покрывать ПОЛНЫЙ словарь form-js `validate` (см. WO-FORM-8),
   иначе «микс формы и валидатора» неполный.
4. **Привязка — `formKey` на элементе BPMN** (start event / user-task) в Camunda Modeler; per-element,
   версионируется с процессом. У service-task форм нет.
5. **Где хранить (две опции):**
   - **A (по умолчанию):** форма в ZBPM (`form`-таблица, FORM-1..6) → external BFF тянет `GET /forms/{key}` /
     `GET /process-definitions/{key}/start-form` → рендер form-js viewer в своём UI. Даёт серверную валидацию
     (FORM-5) бесплатно.
   - **B:** form-js как npm-либа в внешнем стеке, JSON в BFF; ZBPM остаётся variables-only. Выбирать при строгой
     headless-развязке; валидацию тогда BFF гонит сам (form-js viewer умеет и в Node).
6. **Опциональность:** form-js-формы (no-code) и ручные/программные payload'ы сосуществуют. Не всё обязано идти
   через форму.

## Последствия
- Внешний UI подключает `@bpmn-io/form-js` (viewer; editor — если конструктор нужен на их стороне).
- `FormValidator` должен стать полным форматтером валидации form-js → **WO-FORM-8**.
- CSP внешнего UI, использующего form-js, требует `'unsafe-eval'` (FEEL/feelin `new Function`) — как в ZBPM (AUD-4c).
- ZBPM Modeler отменён (ADR-4) — привязку `formKey` авторят в Camunda Modeler desktop.
