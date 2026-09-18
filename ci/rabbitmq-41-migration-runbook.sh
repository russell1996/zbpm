# WO-OBS-9 — RabbitMQ 3.13 → 4.1: rolling-миграция single-node брокера
# (runbook оператора; НЕ автоскрипт — каждый шаг с проверкой руками).
#
# Предусловия: backup volume rabbitmq-data (снапшот/копия ДО drain),
# окно простоя согласовано (single-node = минуты без брокера — см. шаг 2),
# образ 4.1 + digest сверен с пином в docker-compose.yml перед стартом.
# Выполняется на прод-хосте CTO (не из CI). Откат ПОСЛЕ старта 4.x на том же
# volume — НЕВОЗМОЖЕН (см. раздел "Точка невозврата" внизу): единственный
# путь назад — пустой volume + restore из снапшота + 3.13-образ.

set -u

# Шаг 0 — зафиксировать исходное состояние (в отчёт/тикетец mig-рации).
docker exec -u rabbitmq zorrobpm-rabbitmq rabbitmq-diagnostics status | grep -iE "rabbitmq version|erlang configuration"
docker exec -u rabbitmq zorrobpm-rabbitmq rabbitmq-diagnostics -q list_queues name type durable messages messages_ready
docker exec -u rabbitmq zorrobpm-rabbitmq rabbitmq-diagnostics -q list_feature_flags name state | grep -E "khepri|rabbitmq_4"
docker exec -u rabbitmq zorrobpm-rabbitmq rabbitmq-plugins list -m -e

# Шаг 1 — drain: перевести ноду в maintenance (закрыть клиентские коннекты,
# пережить их переподключение уже некуда — single-node, поэтому это начало окна).
docker exec -u rabbitmq zorrobpm-rabbitmq rabbitmq-upgrade drain
# Ожидать: вывод drain ok. App-консьюмеры отвалятся и начнут ретраить
# (CachingConnectionFactory recovery) — это нормально.

# Шаг 2 — stop + backup: остановить стек, скопировать volume.
cd /opt/zorro-bpm   # DEPLOY_DIR (см. .gitlab-ci.yml deploy)
docker compose stop rabbitmq
docker run --rm -v zorrobpm_rabbitmq-data:/data -v /root/rabbit-backup:/backup \
  alpine tar czf /backup/rabbitmq-data-pre41-$(date -u +%Y%m%dT%H%M%SZ).tar.gz -C /data .
ls -la /root/rabbit-backup/   # архив на месте, размер ненулевой — иначе СТОП.

# Шаг 3 — swap образа: в docker-compose.yml заменить строку image rabbitmq на
# 4.1-пин (тег+digest — из того же коммита, что CI-файл ci/docker-compose.rabbit.yml).
# Пример (точный digest — из compose, не из этого файла):
#   image: rabbitmq:4.1-management-alpine@sha256:<...>
# Проверить, что conf.d-монт 20-per-object-metrics.conf на месте (ключ валиден в 4.x).

# Шаг 4 — старт 4.x на СТАРОМ volume + замер конверсии.
time docker compose up -d rabbitmq
# Ждать readiness тем же способом, что CI-скрипт (НЕ root-exec — см. run-rabbit-tests.sh):
until docker compose exec -T -u rabbitmq rabbitmq rabbitmq-diagnostics -q ping 2>/dev/null; do sleep 2; done
# Замер: время от `up -d` до ping-ok = окно простоя (ожидается ~секунды при
# тысячах сообщений; CQv1→v2-конверсия ~5ms на сообщение по замеру WO-OBS-9).
# Проверить в логах конверсию И сохранность побайтово:
docker compose logs rabbitmq 2>&1 | grep -E "Converting queue|converted .* from v1 to v2|Server startup complete|ERROR"
docker compose exec -T -u rabbitmq rabbitmq rabbitmq-diagnostics -q list_queues name type durable messages messages_ready

# Шаг 5 — включить стабильные фиче-флаги новой версии (делается ПОСЛЕ старта):
docker compose exec -T -u rabbitmq rabbitmq rabbitmqctl enable_feature_flag rabbitmq_4.1.0
# NB: rabbitmq_4.0.0 включается каскадом сам. khepri_db НЕ включать без отдельного
# решения CTO (opt-in флаг; single-node на Mnesia работает — см. Точку невозврата).

# Шаг 6 — verify очередей/консьюмеров: app стартует и переподключается сам
# (single-node drain закрыл коннекты, recovery — на клиенте).
docker compose up -d app
curl -fsS http://localhost:${APP_PORT:-8080}/actuator/health | grep -q '"status":"UP"'
# В management UI: обе очереди classic, consumers>0 на complete-очереди,
# messages = значения шага 0 минус обработанное за окно.
# Prometheus: rabbitmq:15692 up, метрики {queue=...} с labels (per-object mount).

# Шаг 7 — smoke: прогнать одну service-task через очередь end-to-end
# (deploy + complete через UI/API), затем закрыть окно.

# --- Точка невозврата (проверено прогоном WO-OBS-9, не теорией) ---
# После ПЕРВОГО старта 4.x на volume классики лежат в формате v2. Откат на
# 3.13-образ ТЕМ ЖЕ volume стартует и МОЛЧА конвертирует их обратно v2→v1
# (проверено: "Converting queue ... from v2 to v1", 0 ошибок, сообщения целы).
# НО: (1) вендор downgrade официально НЕ поддерживает и не тестирует;
# (2) Khepri-миграция метаданных (флаг khepri_db, opt-in, в 4.1 по умолчанию
# ВЫКЛЮЧЕН — Mnesia остаётся) — вот настоящая односторонняя дверь: после
# включения khepri_db назад дороги нет вообще (Mnesia-снапшот несовместим).
# Поэтому: держи khepri_db ВЫКЛЮЧЕННЫМ до отдельного решения; держи снапшот
# шага 2 до закрытия окна + 24ч; откат = ПУСТОЙ volume + restore снапшота +
# 3.13-образ, а не "просто вернуть тег".
