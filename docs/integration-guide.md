# ZorroBPM — Полное руководство по интеграции

> Для тех, кто **впервые** слышит про BPMN. Читается сверху вниз: от «что это вообще» до рабочей интеграции
> внешней системы (свой фронтенд, свои воркеры). Каждый шаг — с конкретными командами.
>
> Базовый URL API в примерах: `https://<ваш-хост>/api` (в проде nginx срезает префикс `/api` и проксирует в
> движок). Локально без nginx — напрямую `http://localhost:8080` **без** `/api`.

---

## 0. Что такое ZorroBPM простыми словами

ZorroBPM — «дирижёр» бизнес-процессов. Вы рисуете схему процесса (например, «согласование отпуска»): прямоугольники
— это шаги, ромбы — развилки, стрелки — порядок. Движок берёт эту схему и **исполняет** её: ведёт каждую заявку по
шагам, ждёт где надо людей, зовёт где надо внешние системы, помнит состояние даже после перезапуска.

Четыре роли, которые важно различать:

| Роль | Кто это | Что делает |
|---|---|---|
| **Моделлер** | человек + редактор (Camunda Modeler) | рисует схему процесса в файле `.bpmn` |
| **Движок** (ZorroBPM) | сервер | исполняет схему, хранит состояние, раздаёт работу |
| **Воркер** | ваш код (сервис) | выполняет «машинную» работу шага (service task): отправить письмо, дёрнуть API |
| **UI / фронтенд** | человек + приложение | человек видит свои задачи и нажимает кнопки (user task) |

ZorroBPM **сам ничего не рисует и не выполняет бизнес-логику** — он только оркеструет. Всю «настоящую» работу
делают ваши воркеры и ваши люди через UI. Число внешних систем движку безразлично: новая система = ещё один
API-ключ, **per-system кода в движке нет**.

---

## 1. Ключевые понятия (запомнить 8 слов)

- **Process Definition (определение процесса)** — задеплоенная схема `.bpmn`. У неё есть `key` (из атрибута
  `id` процесса в файле) и `version` (растёт при каждом новом деплое того же ключа).
- **Process Instance (экземпляр)** — одна конкретная «заявка», едущая по схеме. У неё свой `id` (UUID).
- **Token (токен)** — «фишка», отмечающая, где сейчас находится экземпляр. Параллельный шлюз делает несколько
  токенов, join их собирает.
- **Variables (переменные)** — данные экземпляра (например `amount=1000`, `applicant="Ivan"`). Живут внутри
  экземпляра, читаются в условиях (FEEL) и передаются воркерам.
- **User Task (пользовательская задача)** — шаг, который ждёт **человека** (согласовать, заполнить форму).
- **Service Task (сервисная задача)** — шаг, который ждёт **машину** (ваш воркер).
- **Job (джоба)** — единица работы service task, которую забирает воркер по типу (`zeebe:taskDefinition type`).
- **Incident (инцидент)** — «застряло»: воркер исчерпал попытки или в схеме ошибка данных. Требует вмешательства.

---

## 2. Запуск ZorroBPM (5 минут)

Нужен Docker. В корне репозитория:

```bash
# 1. Создайте .env с обязательными секретами (прод-профиль без них не стартует):
cat > .env <<'ENV'
ZORROBPM_JWT_SECRET=замените_на_вывод_openssl_rand_base64_48
ZORROBPM_DEFAULT_ADMIN_PASSWORD=ЗамениНаСвойСильныйПароль
ENV

# 2. Поднять postgres + rabbitmq + движок + фронтенд:
docker compose up -d

# 3. Открыть SPA (Operate/Tasklist/Cockpit в одном):
#    http://localhost:8081/ui/
```

Первый вход: `admin` / пароль из `ZORROBPM_DEFAULT_ADMIN_PASSWORD`. **Смените его сразу.**

---

## 3. Аутентификация — как получить доступ к API

Есть два способа. Выберите по ситуации.

### 3а. Люди (браузер / UI) → логин, JWT в cookie

```bash
curl -i -X POST https://<host>/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"ВашПароль"}'
# Ответ ставит httpOnly-cookie с JWT. Браузер дальше сам его шлёт.
```

Прочие: `GET /api/auth/me` (кто я), `POST /api/auth/refresh` (продлить), `POST /api/auth/logout`.

### 3б. Внешние системы (сервер↔сервер) → API-ключ

Человек-владелец создаёт ключ (через UI «Мой профиль → API-ключи», или `POST /api/me/api-key`). Ключ выглядит как
`zbpm_sk_XXXX…` и **показывается один раз** — сохраните его в секретах вашей системы.

Дальше **каждый** запрос вашей системы шлёт его в заголовке:

```
Authorization: Bearer zbpm_sk_XXXXXXXXXXXXXXXX
```

> Bearer-заголовок имеет приоритет над cookie. Для машин используйте **только** API-ключ, не логин/пароль.

Во всех примерах ниже подразумевается заголовок `-H "$AUTH"`, где `AUTH="Authorization: Bearer zbpm_sk_..."`.

---

## 4. Шаг за шагом: создать первую BPMN-модель

### 4.1. Чем рисовать

Скачайте бесплатный **Camunda Modeler** (desktop). Создайте новую диаграмму типа **BPMN (Camunda 8 / Cloud)** —
важно именно C8-вариант, потому что ZorroBPM понимает Zeebe-расширения (`zeebe:*`), как Camunda 8.

### 4.2. Минимальный процесс «на пальцах»

Нарисуйте слева направо: **Start Event** → **User Task** → **End Event**, соедините стрелками.

- Кликните по **процессу** (пустое поле) → задайте **Process ID** = `vacation` (это будущий `key`) и **Name**.
- Кликните по **User Task** → **Name** = «Согласовать», в разделе **Assignment** задайте исполнителя (см. §7.1).
- Сохраните файл `vacation.bpmn`.

Всё. У вас есть исполняемая схема. Никакого кода пока не написано.

### 4.3. Что такое `zeebe:*` расширения (важно)

Внутри `.bpmn` — это XML. Camunda-8-элементы несут внутри `<extensionElements>` теги `zeebe:*`, которые говорят
движку, *как* исполнять шаг:

- Service task: `<zeebe:taskDefinition type="send-email" retries="3" />` — «это работа для воркера типа `send-email`».
- User task: `<zeebe:userTask />` + `<zeebe:assignmentDefinition assignee="ivan" candidateGroups="hr" />`.
- Форма: `<zeebe:formDefinition externalReference="vacation-form" />`.
- IO mapping: `<zeebe:ioMapping>` — какие переменные подать на вход/выход шага.

Modeler проставляет их за вас, когда вы заполняете панель справа. ZorroBPM читает ровно эти теги — поэтому реальные
C8-модели исполняются **без правок файла**.

---

## 5. Задеплоить схему в движок

Деплой = «загрузить `.bpmn` в движок, чтобы по нему можно было запускать экземпляры».

**Через UI:** раздел «Process Schemas / Definitions» → Deploy → выберите файл.

**Через REST** (multipart-загрузка `.bpmn`):

```bash
curl -X POST https://<host>/api/process-definitions/deployment \
  -H "$AUTH" \
  -F "file=@vacation.bpmn"
```

Повторный деплой того же `Process ID` создаёт **новую версию** (v1 → v2 …). Уже запущенные экземпляры доедут по
своей версии; новые старты пойдут по последней. Посмотреть: `GET /api/process-definitions`.

---

## 6. Запустить экземпляр процесса

По ключу (последняя версия) с начальными переменными:

```bash
curl -X POST https://<host>/api/process-instances \
  -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{
        "processDefinitionKey": "vacation",
        "variables": [
          {"name": "applicant", "type": "STRING", "value": "Ivan"},
          {"name": "days",      "type": "LONG",   "value": "5"}
        ]
      }'
# Ответ: {"id":"<UUID экземпляра>"}
```

Можно вместо `processDefinitionKey` указать точный `processDefinitionId` (UUID конкретной версии) или
`processDefinitionKey` + `processDefinitionVersion`.

**Формат переменной** везде одинаков — объект `{name, type, value}`, где `value` всегда **строка**, а `type`
подсказывает движку, как её разобрать:

| type | пример value | как читается в FEEL |
|---|---|---|
| `STRING` | `"Ivan"` | текст |
| `LONG` | `"5"` | целое |
| `DOUBLE` | `"12.50"` | десятичное |
| `BOOLEAN` | `"true"` | булево |
| `UUID` | `"3fa8…"` | идентификатор |
| `JSON` | `"{\"city\":\"Almaty\"}"` | объект/список (доступ к полям и итерация в FEEL/DMN) |

---

## 7. User Tasks — полный цикл (человек в процессе)

User task — шаг, где процесс **останавливается и ждёт человека**. Внешняя система (ваш Tasklist/портал) показывает
человеку его задачи и отправляет результат обратно.

### 7.1. Как смоделировать (в Modeler)

На User Task в панели справа:
- **Assignment → Assignee** — конкретный пользователь (`ivan`), ИЛИ
- **Candidate groups** — группа (`hr`), из которой кто-то возьмёт задачу, ИЛИ **Candidate users**.
- **Form → External reference** (`externalReference`) — ключ формы, если форму рендерит ваш фронт (§11).
- Поставьте маркер **User Task (Zeebe)** — движок исполняет задачи с `zeebe:userTask`.

### 7.2. Внешняя система: получить список задач (инбокс)

```bash
# Все открытые задачи для группы hr:
curl "https://<host>/api/user-tasks?candidateGroup=hr&state=CREATED&page=0&size=50" -H "$AUTH"

# Задачи конкретного пользователя:
curl "https://<host>/api/user-tasks?assignee=ivan&state=CREATED" -H "$AUTH"
```

Ответ — страница (`PagedDataDTO`) с элементами `UserTask`: `id`, `processInstanceId`, `bpmnElementId`, `name`,
`formKey`, `assignee`, `candidateGroups`, `createdAt`. Одна задача: `GET /api/user-tasks/{id}`.

### 7.3. Показать форму и данные

- Текущие переменные экземпляра: `GET /api/variables?processInstanceId=<id>` — заполнить ими форму.
- Какую форму показать — по `formKey` задачи (ваш фронт мапит ключ на свой компонент; §11 — про валидацию ввода
  через Variable Schema).

### 7.4. Завершить задачу (отправить результат)

```bash
curl -X POST https://<host>/api/user-tasks/<taskId>/complete \
  -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{
        "variables": [
          {"name": "approved", "type": "BOOLEAN", "value": "true"},
          {"name": "comment",  "type": "STRING",  "value": "OK, согласовано"}
        ]
      }'
```

После этого движок сам продвинет токен дальше (например, на шлюз, который по `approved` выберет ветку). Переданные
переменные становятся переменными экземпляра.

### 7.5. Уведомления «появилась задача»

Отдельного webhook «новая задача» пока нет — внешний Tasklist **поллит** `GET /api/user-tasks?...&state=CREATED`
раз в N секунд и показывает новые. (Для service task есть push через RabbitMQ — §8.)

---

## 8. Service Tasks + воркеры — полный цикл (машина в процессе)

Service task — шаг, где процесс отдаёт работу **вашему коду** (воркеру): отправить письмо, вызвать чужой API,
посчитать. Движок останавливает токен на этом шаге, публикует **джобу**, ждёт от воркера `complete` или `fail`.

### 8.1. Как смоделировать

На Service Task в Modeler:
- **Task definition → Type** = `send-email` (это `zeebe:taskDefinition type`, «тип джобы»). Воркер объявит, что
  берёт джобы этого типа.
- **Retries** = `3` (бюджет попыток; на 0 создаётся инцидент).
- Через **IO mapping** можно подать на вход только нужные переменные.

### 8.2. Два способа получать работу

| Способ | Кому | Модель | Как |
|---|---|---|---|
| **A. RabbitMQ push** | Java-сервисам | движок сам присылает джобу в очередь | реализовать `JobHandler` (стартер) |
| **B. REST polling** | любому языку | ваш код спрашивает движок | `GET /service-tasks` → `complete`/`fail` |

### 8.3. Способ A — воркер на Java (RabbitMQ push, рекомендуемый)

Движок кладёт джобу в очередь `zorrobpm.jobs.<type>` (например `zorrobpm.jobs.send-email`). Подключите стартер
`zorrobpm-job-handler-spring-boot-starter` и реализуйте интерфейс:

```java
@Component
public class SendEmailWorker implements JobHandler {

    @Override
    public String getJob() {
        return "send-email";               // тип джобы = zeebe:taskDefinition type
    }

    @Override
    public List<ProcessVariable> handleJob(JobDetailModel job) {
        // job.getVariables() — переменные экземпляра (Map<String, ProcessVariable>)
        // job.getProcessInstanceId(), job.getServiceTaskId() — контекст
        String to = job.getVariables().get("applicant").getValue();
        emailService.send(to, "Ваша заявка принята");

        // Вернуть переменные-результат (попадут в экземпляр). Пустой список — если результата нет.
        return List.of(var("emailSent", ProcessVariableType.BOOLEAN, "true"));
    }
}
```

Стартер сам подпишется на очередь, вызовет `handleJob`, а возвращённые переменные и статус `complete` отправит
обратно движку (через служебную очередь завершения). Исключение из `handleJob` → движок трактует как провал
(уменьшает retries; на 0 — инцидент).

`JobDetailModel`, который получает воркер: `serviceTaskId`, `processInstanceId`, `processDefinitionId`,
`serviceTaskKey`, `job` (тип), `variables` (`Map<String, ProcessVariable>`).

### 8.4. Способ B — воркер на любом языке (REST polling)

Если воркер не на Java (Go, Python, Node…), поллите REST:

```bash
# 1. Забрать открытые джобы своего типа:
curl "https://<host>/api/service-tasks?job=send-email&state=CREATED&size=20" -H "$AUTH"
#   → элементы ServiceTask: id, processInstanceId, job, createdAt, retries_remaining

# 2а. Успех — завершить, вернув переменные:
curl -X POST https://<host>/api/service-tasks/<serviceTaskId>/complete \
  -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"variables":[{"name":"emailSent","type":"BOOLEAN","value":"true"}]}'

# 2б. Ошибка — сообщить о провале:
curl -X POST https://<host>/api/service-tasks/<serviceTaskId>/fail \
  -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"message":"SMTP timeout","retries":2}'
```

**Семантика `fail`:**
- `retries` **не указан** → движок уменьшает бюджет на 1. Когда дойдёт до 0 — создаётся **инцидент**.
- `retries: 0` → инцидент **сразу** (как `failJob` с 0 в Camunda), независимо от остатка.
- `retries: N>0` → выставить бюджет в N (можно и повысить — «дать ещё попыток»).

> Идемпотентность: если воркер выполнил работу, но не получил подтверждения — при повторе он снова `complete`-нет
> уже завершённую задачу. Движок это переживает, но саму побочную работу (письмо) делайте идемпотентной у себя.

---

## 9. Инциденты — когда «застряло»

Инцидент = экземпляр остановился на ошибке (воркер исчерпал retries, или в схеме ошибка данных — например условие
шлюза не выбрало ни одной ветки и нет default).

```bash
curl "https://<host>/api/incidents?state=OPEN" -H "$AUTH"          # список
curl "https://<host>/api/incidents/<id>" -H "$AUTH"                # детали
# Разрешить (после того как починили причину/данные) — можно долить переменные:
curl -X POST https://<host>/api/incidents/<id>/resolve \
  -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"variables":[]}'
```

Разрешение идемпотентно (WO-REL-5): повторный вызов не выполнит шаг дважды.

---

## 10. Отмена экземпляра

```bash
curl -X POST https://<host>/api/process-instances/<id>/cancel -H "$AUTH"
```

---

## 11. Свой внешний фронтенд (Tasklist/портал)

Если вы строите собственный UI поверх ZorroBPM, минимальный набор:

1. **Доступ** — API-ключ сервиса-бэкенда (§3б); UI ходит в API через ваш BFF, не напрямую с браузера с ключом.
2. **Инбокс** — поллинг `GET /user-tasks?...&state=CREATED` (§7.2).
3. **Форма** — по `formKey` показать свой компонент; данные — из `GET /variables`.
4. **Отправка** — `POST /user-tasks/{id}/complete` (§7.4).
5. **Старт нового процесса** — `POST /process-instances` (§6).

**Variable Schema — валидация входных переменных (опционально, полезно).** Движок форм не рендерит, но умеет
хранить **JSON Schema 2020-12** артефакты и привязывать их к элементам процесса. Ваш BFF может: получить схему
входных переменных элемента, **провалидировать** ввод до отправки, а по расширению `x-ui`/`x-builder` — понять, как
рисовать поля. Так фронт не хардкодит формы, а берёт контракт данных из движка. Детали — раздел «Variable Model».

---

## 12. End-to-end пример: «Отпуск»

Схема: **Start** → **User Task «Согласовать»** → **Exclusive Gateway** (по `approved`) →
[да] **Service Task «send-email»** → **End** ; [нет] **End «Отклонено»**.

```bash
AUTH="Authorization: Bearer zbpm_sk_..."

# 1. Деплой
curl -X POST https://<host>/api/process-definitions/deployment -H "$AUTH" -F "file=@vacation.bpmn"

# 2. Старт
PID=$(curl -s -X POST https://<host>/api/process-instances -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"processDefinitionKey":"vacation","variables":[{"name":"applicant","type":"STRING","value":"Ivan"}]}' \
  | jq -r .id)

# 3. HR видит задачу
TASK=$(curl -s "https://<host>/api/user-tasks?candidateGroup=hr&state=CREATED" -H "$AUTH" | jq -r '.data[0].id')

# 4. HR согласовал
curl -X POST https://<host>/api/user-tasks/$TASK/complete -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"variables":[{"name":"approved","type":"BOOLEAN","value":"true"}]}'

# 5. Движок сам дошёл до service task send-email → ваш воркер (§8) его выполнил → End.
curl -s "https://<host>/api/process-instances/$PID" -H "$AUTH" | jq '{state, completedAt}'
```

---

## 13. Справочник REST API

Базовый префикс в проде: `/api` (nginx срезает его перед движком). Все запросы — с `Authorization: Bearer …` или
cookie-JWT.

### Runtime (действия)
| Метод | Путь | Тело | Назначение |
|---|---|---|---|
| POST | `/process-instances` | `{processDefinitionKey\|Id\|Version, variables[]}` | запустить экземпляр |
| POST | `/process-instances/{id}/cancel` | — | отменить экземпляр |
| POST | `/user-tasks/{id}/complete` | `{variables[]}` | завершить user task |
| POST | `/service-tasks/{id}/complete` | `{variables[]}` | завершить service task (успех) |
| POST | `/service-tasks/{id}/fail` | `{message, retries?}` | провал service task |
| POST | `/incidents/{id}/resolve` | `{variables[]}` | разрешить инцидент |

### Query (чтение; пагинация `page`/`size` + фильтры)
| Метод | Путь | Назначение |
|---|---|---|
| GET | `/user-tasks` , `/user-tasks/{id}` | задачи людей (`assignee`, `candidateGroup`, `state`, `processInstanceId`) |
| GET | `/service-tasks` , `/service-tasks/{id}` | джобы (`job`, `state`, `processInstanceId`) |
| GET | `/process-instances` , `/process-instances/{id}` | экземпляры |
| GET | `/process-instances/{id}/activities` | история активностей экземпляра |
| GET | `/variables` | переменные (по `processInstanceId`) |
| GET | `/incidents` , `/incidents/{id}` | инциденты |
| GET | `/timer-jobs` , `/message-subscriptions` | ожидающие таймеры / подписки |
| GET | `/process-definitions` | задеплоенные определения |

### Deploy / Auth / прочее
| Метод | Путь | Назначение |
|---|---|---|
| POST | `/process-definitions/deployment` | загрузить `.bpmn` (multipart `file`) |
| POST/GET | `/auth/login` `/auth/refresh` `/auth/logout` , GET `/auth/me` | сессия человека |
| POST/GET/DELETE | `/me/api-key` | управление API-ключами |

---

## 14. Совместимость с Camunda 8 (что переносится «как есть»)

ZorroBPM исполняет **реальные C8-BPMN-модели без правок файлов** (совместимость ≈95% исполняемого набора — см.
корневой `README.md`). Переносятся: JSON-переменные, десятичные, multi-instance по коллекции, cron/`cycle`-таймеры,
DMN с версионированием, `zeebe:ioMapping`, корреляция сообщений по ключу, FEEL-условия.

Отличия, которые надо знать:
- **Воркеры**: вместо gRPC job-workers Camunda 8 здесь — **RabbitMQ push** (`zorrobpm.jobs.<type>`) или **REST
  polling**. Тип джобы (`zeebe:taskDefinition type`) — тот же механизм.
- **Формы**: движок форм не рендерит — это делает ваш фронт (`externalReference` + Variable Schema, §11).
- **Сверх C8**: движок дополнительно исполняет Conditional-события, Transaction-subprocess, Cancel-события
  (BPMN-стандарт, но Zeebe их не исполняет).
- **Масштабирование**: таймеры/ретенция безопасны при нескольких репликах (SKIP LOCKED); версионирование
  определений — пока на одном экземпляре.

---

## 15. Частые ошибки (FAQ)

- **401/403 на все запросы** — нет/просрочен токен. Для машин — `Authorization: Bearer zbpm_sk_…`. Читать может
  любой аутентифицированный; энфорсмент прав записи по владельцу процесса ещё раскатывается (см. README «Ограничения»).
- **Экземпляр «застрял» на service task** — воркер не подключён/не берёт джобы этого типа, либо исчерпал retries →
  `GET /incidents`. Проверьте, что `getJob()` воркера == `zeebe:taskDefinition type` в схеме.
- **User task не в инбоксе** — проверьте фильтр (`assignee` vs `candidateGroup`) и маркер `zeebe:userTask`.
- **Шлюз выбрасывает инцидент** — ни одно условие не истинно и нет **default flow**. Задайте default или полное
  покрытие условий.
- **Переменная не читается в FEEL** — неверный `type` (число прислали как `STRING`). Число → `LONG`/`DOUBLE`,
  объект → `JSON`.

---

## Variable Model (справочник для BFF)

> Продвинутый раздел: контракт входных переменных элемента через **ElementArtifact** (JSON Schema 2020-12). Позволяет
> внешнему BFF валидировать ввод и строить формы по схеме. Дизайн — в
> `docs/adr/ADR-6-element-artifact-variable-schema.md`.

- **ElementArtifact** — нейтральная сущность с явным `kind`: `FORM_JS` (форма) или `VARIABLE_SCHEMA` (JSON Schema
  входных переменных). Хранит схему + версию.
- **Привязка к элементу**: `GET/POST /process-definitions/{key}/element-bindings` — связывает `elementId` (start
  event / user task) с артефактом и **пиннит версию** артефакта к версии определения (иммутабельный контракт).
- **Резолв**: по `elementId` инстанса вернётся именно та версия артефакта, что была на момент деплоя — контракт не
  «съезжает» при новых деплоях.
- **Поток BFF**: получить `VARIABLE_SCHEMA` элемента → показать/собрать данные → **провалидировать** против JSON
  Schema 2020-12 → `POST …/complete` с переменными. Расширения `x-ui`/`x-builder` в схеме несут метаданные для
  рендера полей.
