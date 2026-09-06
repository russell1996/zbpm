# Руководство по интеграции ZorroBPM — пошагово

> Вынесено из корневого `README.md` 2026-09-06: там оно занимало 40 % файла и мешало
> читать README как обзор продукта. Содержание не менялось.

> От «что это вообще» до рабочей интеграции внешней системы. Справочник эндпоинтов — в разделе [REST API](#rest-api)
> выше; воркеры — в [Сервис-задачи](#сервис-задачи-написание-воркера). URL в примерах: прод — `https://<host>/api/…`
> (nginx срезает `/api`); локально — `http://localhost:8080/…` без `/api`.

### Что это простыми словами
ZorroBPM — «дирижёр» процессов. Вы рисуете схему (`.bpmn`: прямоугольники — шаги, ромбы — развилки, стрелки —
порядок), движок её **исполняет**: ведёт каждую заявку по шагам, ждёт людей, зовёт внешние системы, помнит
состояние. Сам бизнес-логику **не выполняет** — только оркеструет. Четыре роли: **моделлер** (рисует схему),
**движок** (исполняет), **воркер** (ваш код на service task), **UI/фронт** (человек на user task). Новая внешняя
система = ещё один API-ключ, per-system кода в движке нет.

Восемь слов: **Definition** (задеплоенная схема, `key`+`version`) · **Instance** (одна заявка, `id`) · **Token**
(где сейчас заявка) · **Variables** (данные заявки) · **User Task** (ждёт человека) · **Service Task** (ждёт
машину/воркер) · **Job** (единица работы service task по типу `zeebe:taskDefinition type`) · **Incident**
(застряло — нужен оператор).

### Доступ для внешней системы (API-ключ)
Человек-владелец создаёт ключ (UI «Мой профиль → API-ключи» или `POST /me/api-key`); ключ вида `zbpm_sk_…`
показывается один раз. Дальше каждый запрос машины шлёт его в заголовке — он приоритетнее cookie:

```
Authorization: Bearer zbpm_sk_XXXXXXXX
```

Для машин — только API-ключ (не логин/пароль). Ниже подразумевается `-H "$AUTH"`, где `AUTH="Authorization: Bearer zbpm_sk_…"`.

### Шаг 1 — нарисовать схему (Camunda Modeler)
Скачайте **Camunda Modeler**, создайте диаграмму типа **BPMN (Camunda 8 / Cloud)** — важно именно C8, движок читает
Zeebe-расширения (`zeebe:*`) как Camunda 8. Нарисуйте `Start → User Task → End`. Кликните по процессу → **Process ID**
= `vacation` (будущий `key`). На шагах панель справа проставляет `zeebe:*` за вас: service task —
`<zeebe:taskDefinition type="send-email" retries="3"/>`; user task — `<zeebe:userTask/>` +
`<zeebe:assignmentDefinition assignee="ivan" candidateGroups="hr"/>`; форма — `<zeebe:formDefinition
externalReference="…"/>`. Поэтому реальные C8-модели идут **без правок файла**.

### Шаг 2 — задеплоить и запустить
Деплой (`POST /process-definitions`, тело JSON `{bpmn}`, требует SUPER_ADMIN) и запуск (`POST /process-instances`) —
см. примеры в разделе [REST API](#rest-api). Повторный деплой того же `Process ID` = новая версия; старые экземпляры
доедут по своей.

### Шаг 3 — User Tasks (человек в процессе): полный цикл
Ваш внешний Tasklist/портал:
1. **Инбокс** — поллинг (webhook «новая задача» пока нет):
   `GET /user-tasks?candidateGroup=hr&state=CREATED&page=0&size=50` (или `?assignee=ivan`). Ответ — страница
   `UserTask`: `id, processInstanceId, bpmnElementId, name, formKey, assignee, candidateGroups, createdAt`.
2. **Данные формы** — `GET /variables?processInstanceId=<id>`; какую форму рисовать — по `formKey` (ваш фронт мапит
   ключ на компонент; валидация ввода — через Variable Schema, ниже).
3. **Завершить** — вернуть результат:
   ```bash
   curl -X POST https://<host>/user-tasks/<taskId>/complete -H "$AUTH" -H 'Content-Type: application/json' \
     -d '{"variables":[{"name":"approved","type":"BOOLEAN","value":"true"}]}'
   ```
   Движок сам продвинет токен (например, шлюз по `approved` выберет ветку).

### Шаг 4 — Service Tasks (машина в процессе)
Два способа получить работу — подробно в разделе [Сервис-задачи: написание воркера](#сервис-задачи-написание-воркера):
**A) RabbitMQ push** (Java, стартер `JobHandler`, очередь `zorrobpm.jobs.<type>`) — рекомендуется; **B) REST-поллинг**
(любой язык): `GET /service-tasks?job=<type>&state=CREATED` → `POST /service-tasks/{id}/complete` (успех, с
переменными) или `/fail` (`{message, retries?}`). Семантика `fail`/retries/инцидентов — там же.

### Свой внешний фронтенд + Variable Schema
UI ходит в API через ваш BFF (ключ на бэкенде, не в браузере). Движок форм **не рендерит**, но хранит **JSON Schema
2020-12** артефакты (`ElementArtifact`, `kind=VARIABLE_SCHEMA`) и привязывает к элементам
(`GET/POST /process-definitions/{key}/element-bindings`, версия пиннится к версии определения). BFF: получить схему
входных переменных элемента → **провалидировать** ввод → `POST …/complete`. Расширения `x-ui`/`x-builder` в схеме
несут метаданные для рендера полей. Дизайн — [ADR-6](docs/adr/ADR-6-element-artifact-variable-schema.md).

### End-to-end: «Отпуск»
`Start → User Task «Согласовать» → Exclusive Gateway (по approved) → [да] Service Task send-email → End ; [нет] End`.
```bash
AUTH="Authorization: Bearer zbpm_sk_…"
# деплой и старт — см. REST API; затем:
TASK=$(curl -s "https://<host>/user-tasks?candidateGroup=hr&state=CREATED" -H "$AUTH" | jq -r '.data[0].id')
curl -X POST https://<host>/user-tasks/$TASK/complete -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"variables":[{"name":"approved","type":"BOOLEAN","value":"true"}]}'
# движок дошёл до service task send-email → ваш воркер выполнил → End.
```

### Частые ошибки
- **401/403 везде** — нет/просрочен токен; для машин `Authorization: Bearer zbpm_sk_…`. Читать может любой
  аутентифицированный; энфорс прав записи по владельцу ещё раскатывается (см. [Ограничения](#ограничения-и-замечания-по-проду)).
- **Застряло на service task** — воркер не берёт джобы этого типа или исчерпал retries → `GET /incidents`. `getJob()`
  воркера должен == `zeebe:taskDefinition type`.
- **User task не в инбоксе** — проверьте фильтр (`assignee` vs `candidateGroup`) и маркер `zeebe:userTask`.
- **Шлюз → инцидент** — ни одно условие не истинно и нет **default flow**. Задайте default.
- **Переменная не читается в FEEL** — неверный `type` (число как `STRING`). Число → `LONG`/`DOUBLE`, объект → `JSON`.
