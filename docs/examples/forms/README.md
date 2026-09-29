# Пример встроенной формы — «Заявление на отпуск»

Демонстрирует эпик форм (ADR-3) end-to-end: стартовая форма → запуск → задача согласования с той же формой.

Файлы:
- [`vacation-request.form.json`](vacation-request.form.json) — form-js схема (ФИО, руководитель, тип, дата, дни, срочно).
- [`vacation.bpmn`](vacation.bpmn) — процесс: start (форма) → user-task «Согласование» (assignee `=managerId`, форма) → end.

Ключ формы `vacationRequest` = `formId` в BPMN → резолвится в схему (WO-FORM-2).

## Вариант A — через UI (проще)
1. `/ui/admin/forms` (SUPER_ADMIN) → «Создать» → визуальный редактор form-js → вставь/собери форму → «Сохранить»
   с ключом `vacationRequest`. (Либо импортируй `vacation-request.form.json`.)
2. Задеплой `vacation.bpmn` как процесс (раздел процессов → загрузить BPMN).
3. `/ui/processes/vacation/start` → заполни стартовую форму → «Запустить».
4. Задача согласования уйдёт пользователю из `managerId`; он откроет её и увидит ту же форму.

## Вариант B — через API (curl)
Прод отдаёт бэкенд в корне (`https://test-zbpm.telecom.kz/...`). Ниже — базовый хост `$H`.

```bash
H=https://test-zbpm.telecom.kz

# 1) Логин SUPER_ADMIN → JWT
TOKEN=$(curl -s -X POST $H/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<пароль>"}' | jq -r .token)

# 2) Деплой формы (schema — строка JSON; кладём файл как строку через jq)
curl -s -X POST $H/forms -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "$(jq -n --arg k vacationRequest --rawfile s vacation-request.form.json '{key:$k, schema:$s}')"

# 3) Деплой процесса (BPMN)
curl -s -X POST $H/process-definitions -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "$(jq -n --rawfile b vacation.bpmn '{bpmn:$b}')"

# 4) Получить стартовую форму (схема + type)
curl -s $H/process-definitions/vacation/start-form -H "Authorization: Bearer $TOKEN"

# 5) Запуск с данными формы (initiator — через X-On-Behalf-Of)
curl -s -X POST $H/process-instances -H "Authorization: Bearer $TOKEN" \
  -H 'X-On-Behalf-Of: emp-42' -H 'Content-Type: application/json' -d '{
    "processDefinitionKey":"vacation",
    "variables":[
      {"name":"employeeName","type":"STRING","value":"Иванов И.И."},
      {"name":"managerId","type":"STRING","value":"mgr-7"},
      {"name":"vacationType","type":"STRING","value":"annual"},
      {"name":"dateFrom","type":"STRING","value":"2026-08-01"},
      {"name":"days","type":"STRING","value":"5"}
    ]}'

# Пропустишь required (напр. без managerId) → 400 VALIDATION_ERROR (серверная валидация, WO-FORM-5)

# 6) Задача согласования у mgr-7:
curl -s "$H/user-tasks?assignee=mgr-7" -H "Authorization: Bearer $TOKEN"
# Форма задачи (схема + prefill из переменных):
curl -s $H/user-tasks/<TASK_ID>/form -H "Authorization: Bearer $TOKEN"
# Согласовать:
curl -s -X POST $H/user-tasks/<TASK_ID>/complete -H "Authorization: Bearer $TOKEN" \
  -H 'X-On-Behalf-Of: mgr-7' -H 'Content-Type: application/json' \
  -d '{"variables":[{"name":"approved","type":"STRING","value":"true"}]}'
```

## Что где сработало
- `formId="vacationRequest"` → схема из таблицы `form` (FORM-1/2).
- `assignee="=managerId"` → FEEL-резолв → задача ушла `mgr-7` (INT-1).
- required-поля проверяются на сервере → 400 (FORM-5).
- `X-On-Behalf-Of` → initiator/аудит (INT-2).
- Форма рисуется form-js в UI (FORM-3), редактируется в `/ui/admin/forms` (FORM-6).
