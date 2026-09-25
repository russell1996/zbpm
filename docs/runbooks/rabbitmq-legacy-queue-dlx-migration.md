# Runbook: миграция legacy-очередей `zorrobpm.jobs.*` на DLX-схему (WO-REL-51)

Очередь `zorrobpm.jobs.<type>`, созданная ДО REL-45 (голый durable без
`x-dead-letter-exchange`), хранит старое определение навсегда: брокер отвечает
`406 PRECONDITION_FAILED` на любое переобъявление с другими аргументами.
После REL-51 движок на этом 406 больше не штормит (warn ровно один раз на тип,
метрика `zbpm.jobs.legacy_queue`, ретраев нет — сообщения как шли, так и идут),
но DLQ для такого типа НЕ работает, пока оператор не мигрирует очередь.

Проверено вживую на `rabbitmq:4.1-management-alpine` (см. «Проверено» внизу):
policy-DLX паркует `rejected` в DLQ без пересоздания очереди; полный цикл
drain → delete → redeclare сохраняет все сообщения и включает нативный DLX.

## Как понять, что миграция нужна

- В логах приложения (один раз на тип при старте/первой отправке):
  `Job queue zorrobpm.jobs.<type> exists with legacy arguments (no DLX …)`.
- Метрика `zbpm.jobs.legacy_queue > 0` (`/actuator/prometheus`,
  `zbpm_jobs_legacy_queue`) — число типов без работающей DLQ. Ноль = всё
  смигрировано.
- В Management UI: очередь `zorrobpm.jobs.<type>` без `Policy` и без
  `x-dead-letter-exchange` в Features/Arguments.

## Путь A (основной) — policy-DLX: без простоя, без потери, без удаления очереди

Policy цепляет DLX к существующей очереди без её пересоздания. На каждый
legacy-тип — СВОЯ policy (pattern строго на одну очередь, routing key — её же
DLQ: один статический routing key в policy НЕ обслуживает N типов, wildcard на
все очереди сразу НЕ делать).

```bash
VHOST=%2f   # default vhost; для другого — urlencode имя
TYPE=billing
Q="zorrobpm.jobs.$TYPE"
API=https://<mgmt-host>:15671/api   # или http://…:15672
A='<mgmt-user>:<mgmt-password>'     # basic auth; пароль не светить в истории

# 1. DLQ + биндинг (те же имена, что создаёт JobQueueDeclarer — Scheme REL-45).
curl -s -u "$A" -X PUT "$API/queues/$VHOST/$Q.dlq" \
  -H 'content-type: application/json' -d '{"durable":true}'
curl -s -u "$A" -X PUT "$API/exchanges/$VHOST/zorrobpm.jobs.dlx" \
  -H 'content-type: application/json' -d '{"type":"direct","durable":true}'
curl -s -u "$A" -X POST "$API/bindings/$VHOST/e/zorrobpm.jobs.dlx/q/$Q.dlq" \
  -H 'content-type: application/json' -d "{\"routing_key\":\"$Q.dlq\"}"

# 2. Policy ровно на эту очередь.
curl -s -u "$A" -X PUT "$API/policies/$VHOST/dlx-$TYPE" \
  -H 'content-type: application/json' -d "{
    \"pattern\": \"^$(printf '%s' "$Q" | sed 's/\./\\\\./g')\$\",
    \"definition\": {
      \"dead-letter-exchange\": \"zorrobpm.jobs.dlx\",
      \"dead-letter-routing-key\": \"$Q.dlq\"
    },
    \"priority\": 10, \"apply-to\": \"queues\" }"

# 3. Проверка: policy применилась (не мгновенно — пара секунд на пропагацию).
curl -s -u "$A" "$API/queues/$VHOST/$Q" |
  python3 -c "import json,sys; q=json.load(sys.stdin);
print(q.get('policy'), q.get('effective_policy_definition'))"
# Ожидается: dlx-<type> {'dead-letter-exchange': 'zorrobpm.jobs.dlx', ...}
```

Проверка поведения: опубликовать тестовое сообщение в очередь, отвергнуть его
`reject_requeue_false` (эмуляция worker-give-up), убедиться, что копия лежит в
`$Q.dlq`, а основная очередь пуста. Тестовое сообщение после проверки удалить
(ACK) из DLQ, чтобы не путать оператора.

**Честная граница пути A:** policy — НЕ часть объявленных аргументов очереди,
поэтому движок при своём declare с `x-dead-letter-exchange` получит 406 и
останется в legacy-пине (warn один раз, метрика `zbpm.jobs.legacy_queue`
по-прежнему считает тип). DLQ при этом РАБОТАЕТ. Полная конвергенция
(метрика в 0, нативный DLX) — только путь B + рестарт приложения (пин
per-JVM, см. ниже).

## Путь B (полная конвергенция) — drain → delete → redeclare

Даёт нативную REL-45-схему (аргументы на самой очереди) и обнуляет метрику.
Делать на обслуживании: между drain и redeclare очередь отсутствует, нужен
quiesce (остановить воркеры этого типа И отправку движком — достаточно
остановить приложение целиком, это секунды).

```bash
# 1. Quiesce: остановить приложение (движок) и воркеры типа <type>.
#    (команды зависят от стенда — compose/systemd/k8s; важно: никто не пишет
#    и не читает zorrobpm.jobs.<type> во время шагов 2–4.)

# 2. Drain: вычитать ВСЁ из очереди с ACK через Management UI
#    (Get messages → Ack) или shovel в holding-очередь. Записать количество.

# 3. Удалить очередь (сообщений в ней уже нет — потерь нет):
curl -s -u "$A" -X DELETE "$API/queues/$VHOST/$Q"

# 4. Пересоздать — достаточно СТАРТА приложения: первый declare движка
#    (или стартера воркера) создаст очередь уже С DLX-аргументами и DLQ.
#    Убедиться в UI: у очереди есть x-dead-letter-exchange.

# 5. Рестарт приложения ОБЯЗАТЕЛЕН и после пути A-завершения путём B:
#    legacy-пин живёт в памяти JVM (Set legacyDeclared) и сбрасывается только
#    рестартом — без него тип останется в warn-once/молчаливом режиме, хотя
#    брокер уже новый.

# 6. Сверка: число возвращённых в очередь сообщений == числу из шага 2;
#    поведение DLQ — как в проверке пути A; метрика zbpm.jobs.legacy_queue
#    уменьшилась на 1.
```

Если quiesce невозможен (очередь пишет живой трафик): сообщения, пришедшие
МЕЖДУ drain и delete, будут потеряны удалением — тогда только путь A сейчас +
путь B в окно обслуживания. Не удалять очередь с сообщениями молча: `delete`
с непустой очередью = потеря in-flight задач.

## После миграции

- Policy пути A можно оставить навсегда (работает, проверено) или снять после
  пути B (нативная схема её перекрывает; снятие policy с нативной очереди
  ничего не меняет — аргументы уже на очереди).
- Имена очередей/routing keys НЕ менялись ни на одном шаге (инвариант REL-45):
  внешние воркеры перенастройки не требуют.

## Проверено

Живой прогон на `rabbitmq:4.1-management-alpine` (2026-09-25, WO-REL-51):

- legacy-очередь (bare durable) + 1 сообщение → policy
  `dead-letter-exchange=test.dlx + routing-key=test.q.dlq` → после пропагации
  `effective_policy_definition` содержит оба ключа → `reject_requeue_false` →
  копия в DLQ с корректными `x-death` (`reason=rejected, queue=test.q`),
  основная очередь пуста. Policy-DLX БЕЗ пересоздания очереди — работает.
- Полный цикл пути B автоматизирован:
  `LegacyQueueDlxMigrationRabbitIT` (модуль
  `zorrobpm-job-handler-spring-boot-starter`, `@Tag("rabbit")`,
  `ci/run-rabbit-tests.sh`): 3 сообщения до + 2 во время миграции, реальный
  406 движка (`isLegacyDeclared == true`, отправка не бросает), drain с ACK,
  delete, redeclare ЧЕРЕЗ НАСТОЯЩИЙ `new JobQueueDeclarer(admin).declare(...)`
  свежим инстансом (= рестарт), republish — все 5 на месте, reject паркуется в
  DLQ, имена/ключи без изменений.
