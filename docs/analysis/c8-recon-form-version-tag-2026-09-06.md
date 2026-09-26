# Разведка WO-C8-27: где живёт тег версии у `.form`-ресурса (2026-09-06)

Оба вопроса WO закрыты фактами, гадать не пришлось. Ничего не реализовано (граница WO).

## Факты

### Вопрос 1. Место хранения тега — top-level `versionTag` в JSON формы

**F1.** `camunda/camunda-modeler#4463` «Add support for setting a version tag for a Form»
(открыт 2024-08-14, закрыт; часть эпика `camunda/camunda#19812` «[EPIC] Support
`version tag` binding for linked resources»):
`https://github.com/camunda/camunda-modeler/issues/4463`

Дословно (Proposed solution):
> - A new (optional) input field "Version tag" is added to the "General" section of the properties panel for a Form.
> - The version tag is stored in a new `versionTag` JSON property:
> ```
> {
>   "id": "Form_1",
>   "type": "default",
>   "versionTag": "v1",
>   "components": [],
> }
> ```

**F2.** Реализовано не в коде Modeler'а, а в form-js: issue закрыт через
`camunda/camunda-modeler#4489` («Bump modeling deps», merged 2024-08-29) коммитом
`deps: update to bpmn-io/form-js@1.10.0` с пометкой `Closes #4463`.
`https://github.com/camunda/camunda-modeler/pull/4489`

**F3.** Сырой исходник form-js v1.10.0 (raw, сверено побайтово):
`packages/form-js-editor/src/features/properties-panel/entries/VersionTagEntry.js`
`https://raw.githubusercontent.com/bpmn-io/form-js/v1.10.0/packages/form-js-editor/src/features/properties-panel/entries/VersionTagEntry.js`

```js
const path = ['versionTag'];
// ...
const tooltip = <div>Version tag by which this form can be referenced.</div>;
return TextFieldEntry({ ..., label: 'Version tag', ... });
```

Поле правится на корневом объекте формы (`editField(field, ['versionTag'], …)`),
видимо только когда `field.type === 'default'` (т.е. это сама форма, не компонент).
Зарегистрировано в секции General:
`packages/form-js-editor/src/features/properties-panel/groups/GeneralGroup.js`
(raw, тот же тег): `...VersionTagEntry({ field, editField })`, `label: 'General'`.

**F4.** Реальная `.form`-фикстура самого Zeebe (новый файл в PR ниже):
`zeebe/engine/src/test/resources/form/test-form-1-with-version-tag.form`
(патч `https://github.com/camunda/camunda/pull/21494.patch`):

```json
{
  "components": [ … ],
  "type": "default",
  "id": "Form_0w7r08e",
  "executionPlatform": "Camunda Cloud",
  "executionPlatformVersion": "8.1.0",
  "exporter": { "name": "Camunda Modeler", "version": "5.9.0" },
  "schemaVersion": 7,
  "versionTag": "v1.0"
}
```

**Вывод по вопросу 1:** тег — обычное top-level JSON-поле `versionTag` рядом с
`id`/`type`/`schemaVersion`. Никакого XML/`zeebe:versionTag` там нет и быть не может
(форма — JSON). Опционально: формы без тега валидны (поле отсутствует, см. F5).

### Вопрос 2. Как Zeebe резолвит `bindingType="versionTag"` — ключ `(tenantId, formId, versionTag)`

**F5.** `camunda/camunda#21037` «Store deployed forms by id and version tag»
(закрыт через PR #21494, лейблы `version:8.6.0`, `version:8.6.0-alpha5`):
`https://github.com/camunda/camunda/issues/21037`

Дословно (Acceptance Criteria):
> - When a Form is deployed, the (optional) version tag is read from the JSON and stored in a new property `versionTag` in the created `FormRecord` and `PersistedForm`.
> - A new column family is added to store a reference to the respective `formKey` by `formId` and `versionTag`.
> - A new method is added to the `FormState` that allows to find a deployed form by `formId` and `versionTag`.

**F6.** Сырой код из патча PR #21494 (те же факты построчно):
- Чтение при деплое —
  `zeebe/engine/.../processing/deployment/transform/FormResourceTransformer.java`:
  `Optional.ofNullable(form.versionTag).ifPresent(formRecord::setVersionTag);`
  при `private record Form(String id, String versionTag) {}` (только `id` + тег —
  больше из JSON ничего не нужно).
- Хранение — `FormRecord`/`FormMetadataRecord`/`PersistedForm`:
  `new StringProperty("versionTag", "")` (дефолт — пустая строка).
- Индекс — `ZbColumnFamilies.FORM_KEY_BY_FORM_ID_AND_VERSION_TAG`, ключ составной
  `(tenantId, formId, versionTag)` (`DbCompositeKey<>(dbFormId, dbVersionTag)`,
  `PlacementType.PREFIX`); пишется только непустой тег (`if (!versionTag.isBlank())`),
  в коде пометка: «Will only be filled with forms deployed from 8.6 onwards that
  have a version tag».
- Поиск — `DbFormState.findFormByIdAndVersionTag(DirectBuffer formId, String versionTag,
  String tenantId)` → `Optional<PersistedForm>`; есть парное удаление
  `deleteFormInFormKeyByFormIdAndVersionTagColumnFamily` (учтено в
  `ResourceDeletionDeleteProcessor`).

**F7.** `camunda/camunda#21041` «Resolve linked forms to correct version for user tasks
with binding type `versionTag`» (закрыт через PR #21503, те же релиз-лейблы 8.6.0):
`https://github.com/camunda/camunda/issues/21041`

Дословно:
> User tasks that use the new binding type `versionTag` must use exactly the version of the linked form referenced by the specified version tag.
> *Note*: The same version tag can be assigned to multiple deployed versions of a form. In this case, the latest of these versions must be used.
> - During model transformation, the `versionTag` from the `ZeebeFormDefinition` extension element of a user task is added to the `UserTaskProperties`.
> - When the form to be used is resolved in `BpmnUserTaskBehavior` and the binding type is `versionTag`: Use the target `formId` and `versionTag` to retrieve the deployed form from the state (using the method introduced in #21037). In case a form with the given `formId` and `versionTag` cannot be found, return a `Failure` with a meaningful error message.

**F8.** Перекрёстная проверка докой (8.9, choosing-the-resource-binding-type):
`https://docs.camunda.io/docs/components/best-practices/modeling/choosing-the-resource-binding-type/`
> «You can set the version tag for a BPMN process, DMN decision, or Form in the Modeler's properties panel.»
> «If the target resource ID and version tag pair are not deployed, the process instance will have an incident.»
> «Be aware that you can deploy a new version of a resource with an already existing version tag. In this case, the version tag reference will be updated and point to the latest deployed version.»

Последняя цитата — тот же «latest wins», что Note в F7 (там — выбор max версии,
здесь — перезапись указателя; наблюдаемое поведение совпадает).

**Вывод по вопросу 2:** ключ резолва — пара `(formId, versionTag)` (у нас плюс
неявный tenant — сингл). Один тег на нескольких версиях → побеждает latest.
Пары нет → incident/404-аналог (у нас: 404 той же формы, что C8-23).

## Что осталось неясным

Ничего существенного. Мелочь, не влияющая на дизайн: в TS-типах form-js поле
`versionTag`, похоже, не объявлено (в дереве v1.10.0 строка `versionTag` встречается
только в `VersionTagEntry.js` и одной нерелевантной фикстуре `form-json-schema`) — form-js
проносит тег прозрачно, не валидируя. Для нас это значит: при чтении тега из
`schemaJson` парсить lenient-JSON (поле может отсутствовать; формы, сохранённые
старыми версиями редактора, тега не имеют — как и у Zeebe дефолт `""`).

## Оценка стоимости (наши классы)

Повторяет C8-23 (`deployment`) один в один, плюс один JSON-парс:

1. `FormEntity`: новая колонка `version_tag VARCHAR NULL` (миграция; бэкфилл не нужен —
   NULL = «тега нет», как `""` у Zeebe).
2. `DeploymentArtifactRegistrar`: при регистрации `.form` прочитать top-level
   `versionTag` из `schemaJson` (Jackson `readTree`, lenient — отсутствующее поле →
   NULL) и записать в колонку. Сейчас регистрар JSON не парсит вообще — это единственная
   новая обязанность.
3. `FormRepository`: `findTopByFormIdAndVersionTagOrderByVersionDesc(formId, versionTag)`
   (latest-wins — прямо семантика F7/F8; рядом с существующими
   `findTopByFormIdOrderByVersionDesc`, `findFirstByFormIdAndDeploymentIdOrderByVersionDesc`).
4. `FormResolver`: `resolveTaskFormByFormIdAndVersionTag(formId, versionTag, prefill)`
   по образцу `…AndDeployment` (404 той же формы при отсутствии пары).
5. Потребители: ветка `bindingType="versionTag"` в `TaskFormOperationsImpl.getStartForm`
   (значение тега уже разбирается в C8-26: `startFormVersionTag` в PD-модели) и в
   user-task-пути (`formId`/`bindingType` там есть с C8-22/23; тег из `formDefinition`
   JAXB-модель уже несёт — `FormDefinitionModel.versionTag` существует).
6. Тесты зеркалят C8-23: парсинг (plain-start + user task), приоритет, latest-wins
   (две версии с одним тегом → старшая), 404 без пары, legacy-регресс. PG: миграция
   есть → `test:pg` обязателен.

Объём — уровень C8-23 (малый). Отдельный WO на реализацию, как велит граница.

## Рекомендация

**Брать.** Оба факта подтверждены сырым кодом обеих сторон (form-js v1.10.0 +
Zeebe 8.6), ключ резолва и семантика latest-wins однозначны, стоимость — повторение
пройденного C8-23. «Не выяснено» не понадобилось.
