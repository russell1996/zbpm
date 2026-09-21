# Runbook: восстановление Postgres из бэкапа WO-OPS-1

Бэкапы делает job `backup:pg` (скрипт `ci/pg-backup.sh`): `pg_dump -Fc` живой
прод-БД через `docker exec zorrobpm-postgres`, файлы
`/opt/zorro-bpm/backups/zorrobpm-db_<UTC-timestamp>.dump`, ретенция 14 суток.
Формат `-Fc` (custom) — восстанавливается `pg_restore`, проверено живым циклом
(см. «Проверено» внизу): сид → дамп через скрипт → снос volume → чистый
postgres → `pg_restore` → данные совпали побайтово (count+md5).

## Вариант A — восстановление в отдельную БД (без остановки приложения)

Безопасный путь: прод продолжает работать, восстанавливаем рядом и сверяем.

```bash
BACKUP=/opt/zorro-bpm/backups/zorrobpm-db_<timestamp>.dump   # выбрать свежий: ls -t
RESTORE_DB=zorrobpm-restore-$(date -u +%Y%m%d)

# 1. Файл бэкапа — внутрь контейнера (pg_restore читает локальный файл).
docker cp "$BACKUP" zorrobpm-postgres:/tmp/restore.dump

# 2. Пустая БД-приёмник.
docker exec zorrobpm-postgres psql -U "$DB_USERNAME" -d postgres \
  -c "CREATE DATABASE $RESTORE_DB;"

# 3. Восстановление. Пароль — только через окружение, не светить в истории shell.
docker exec -e PGPASSWORD="$DB_PASSWORD" zorrobpm-postgres \
  pg_restore -U "$DB_USERNAME" -d "$RESTORE_DB" /tmp/restore.dump

# 4. Целостность: количество таблиц + счётчики ключевых таблиц.
docker exec zorrobpm-postgres psql -U "$DB_USERNAME" -d "$RESTORE_DB" -c \
  "SELECT count(*) FROM pg_tables WHERE schemaname='public';"
docker exec zorrobpm-postgres psql -U "$DB_USERNAME" -d "$RESTORE_DB" -c \
  "SELECT count(*) FROM process_instances;"
docker exec zorrobpm-postgres psql -U "$DB_USERNAME" -d "$DB_NAME" -c \
  "SELECT count(*) FROM process_instances;"
# Счётчики приёмника и источника обязаны совпасть (с поправкой на записи,
# пришедшие в прод между дампом и сверкой).

# 5. Уборка.
docker exec zorrobpm-postgres rm /tmp/restore.dump
# Временную БД удалить после сверки:
#   docker exec zorrobpm-postgres psql -U "$DB_USERNAME" -d postgres -c "DROP DATABASE $RESTORE_DB;"
```

Имена ключевых таблиц сверять с живой схемой (`\dt`), список выше — ориентир,
а не догма: что именно считать «ключевым», решает состояние схемы на момент
восстановления.

## Вариант B — полное восстановление вместо прод-БД (приложение остановить)

Только при потере прод-данных. Приложение будет остановлено, простой — на всё
время `pg_restore`.

```bash
BACKUP=/opt/zorro-bpm/backups/zorrobpm-db_<timestamp>.dump
cd /opt/zorro-bpm

# 1. Остановить приложение (БД и остальные сервисы не трогаем).
docker compose -f docker-compose.yml -f docker-compose.observability.yml stop app frontend

# 2. Файл внутрь контейнера + восстановление поверх существующей БД.
docker cp "$BACKUP" zorrobpm-postgres:/tmp/restore.dump
docker exec -e PGPASSWORD="$DB_PASSWORD" zorrobpm-postgres \
  pg_restore --clean --if-exists -U "$DB_USERNAME" -d "$DB_NAME" /tmp/restore.dump
# --clean роняет объекты перед пересозданием; без --clean старые строки,
# которых нет в дампе, останутся (смесь эпох) — для disaster recovery нужен --clean.

# 3. Проверка как в шаге 4 варианта A (таблицы + счётчики против ожидаемых).

# 4. Поднять приложение обратно.
docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d
curl -fsS http://localhost:${APP_PORT:-8080}/actuator/health | grep -q '"status":"UP"'

# 5. Уборка /tmp/restore.dump как выше.
```

## Если бэкапа нет / бэкап битый

`pg_restore --list <файл>` обязан показать оглавление с именами таблиц —
быстрая проверка валидности `-Fc` без восстановления:

```bash
docker cp "$BACKUP" zorrobpm-postgres:/tmp/check.dump
docker exec zorrobpm-postgres pg_restore --list /tmp/check.dump | head -30
```

Пустое оглавление / ошибка чтения = бэкап невалиден: брать предыдущий файл
ретенции (`ls -t`), чинить причину (место на диске — `ci/disk-space-check.sh`,
упавший postgres — логи `docker compose logs postgres`).

## Проверено

Живой цикл на эфемерном `postgres:16-alpine` (`ci/test-pg-backup.sh cycle`):
сид 3 строк → дамп через настоящий `ci/pg-backup.sh` (2804 байта) → контейнер
и volume снесены → чистый postgres (0 таблиц в `public`) → `pg_restore` →
`count + md5` до/после идентичны (`3|834b18b6060bb273e1a2cb6041168349`).
Ретенция и отсутствие пароля в логе — `ci/test-pg-backup.sh retention/noleak`.
