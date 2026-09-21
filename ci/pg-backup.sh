#!/usr/bin/env bash
# WO-OPS-1: регулярный логический бэкап Postgres (pg_dump -Fc) с хоста деплоя.
#
# Запускается CI-job'ом `backup:pg` (rules: schedule-only, tags: [zbpm]) на том же
# раннере, что делает deploy, — прямой `docker exec` в `zorrobpm-postgres`, без
# сетевого доступа к БД снаружи и без нового внешнего хранилища (офсайт — follow-up).
#
# Usage: bash ci/pg-backup.sh
# Env:
#   BACKUP_DIR    — каталог бэкапов (дефолт /opt/zorro-bpm/backups). Лежит внутри
#                   $DEPLOY_DIR, но deploy'вский wipe его не задевает только потому,
#                   что `backups` добавлен в исключения wipe-find'а в job'е deploy
#                   (иначе первая же выкатка сносила бы всю ретенцию).
#   RETENTION_DAYS — держать файлы не старше N суток (дефолт 14).
#   PG_CONTAINER  — имя postgres-контейнера (дефолт zorrobpm-postgres).
#   DB_NAME / DB_USERNAME / DB_PASSWORD — из окружения; недостающее добирается из
#                   $DEPLOY_DIR/.env (того же файла, что читает compose).
#   DEPLOY_DIR    — где лежит .env на хосте (дефолт /opt/zorro-bpm).
#   PG_BACKUP_SKIP_DUMP — тест-хук (dev/test only): =1 пропустить pg_dump и выполнить
#                   только ретенцию (аналог DISK_USAGE_OVERRIDE в disk-space-check.sh).
#
# Гарантии: set -euo pipefail — сбой pg_dump/docker даёт ненулевой exit, ошибка не
# глотается. Пароль БД никогда не печатается: передаётся только через
# `-e PGPASSWORD=...` в `docker exec` и нигде не echo'ится (критерий 5 WO).
set -euo pipefail

DEPLOY_DIR="${DEPLOY_DIR:-/opt/zorro-bpm}"
BACKUP_DIR="${BACKUP_DIR:-/opt/zorro-bpm/backups}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"
PG_CONTAINER="${PG_CONTAINER:-zorrobpm-postgres}"

case "$RETENTION_DAYS" in
  ''|*[!0-9]*|0)
    echo "pg-backup: ERROR: RETENTION_DAYS must be a positive integer, got '${RETENTION_DAYS}'" >&2
    exit 1
    ;;
esac

# Добрать DB_* из хостового .env (только строки KEY=..., файл целиком не исполняем).
dotenv_val() {
  local key="$1" file="${DEPLOY_DIR}/.env" line val
  [ -f "$file" ] || return 0
  line="$(grep -E "^${key}=" "$file" | tail -n 1 || true)"
  [ -n "$line" ] || return 0
  val="${line#*=}"
  case "$val" in
    \'*\') val="${val#\'}"; val="${val%\'}";;
    \"*\") val="${val#\"}"; val="${val%\"}";;
  esac
  printf '%s' "$val"
}
[ -n "${DB_NAME:-}" ] || DB_NAME="$(dotenv_val DB_NAME)"
[ -n "${DB_USERNAME:-}" ] || DB_USERNAME="$(dotenv_val DB_USERNAME)"
[ -n "${DB_PASSWORD:-}" ] || DB_PASSWORD="$(dotenv_val DB_PASSWORD)"
DB_NAME="${DB_NAME:-zorrobpm-db}"
DB_USERNAME="${DB_USERNAME:-zorrodev}"
DB_PASSWORD="${DB_PASSWORD:-}"

mkdir -p -m 700 "$BACKUP_DIR"

ts="$(date -u +%Y%m%dT%H%M%SZ)"
final="$BACKUP_DIR/zorrobpm-db_${ts}.dump"

if [ "${PG_BACKUP_SKIP_DUMP:-0}" != "1" ]; then
  command -v docker >/dev/null 2>&1 \
    || { echo "pg-backup: ERROR: docker not found on PATH" >&2; exit 1; }
  tmp="$BACKUP_DIR/.pg-backup-${ts}.part"
  trap 'rm -f "$tmp"' EXIT
  # Пароль — только через окружение дочернего процесса, не через аргументы и не в лог.
  # umask 077 + chmod 600: дамп — полная прод-БД (PII, хэши паролей), по умолчанию
  # umask раннера (обычно 022) оставил бы его world-readable — тот же класс секрета,
  # что scrape-token в job'е deploy (там тоже chmod 400).
  (umask 077; docker exec -e PGPASSWORD="$DB_PASSWORD" "$PG_CONTAINER" \
    pg_dump -Fc -U "$DB_USERNAME" -d "$DB_NAME" > "$tmp")
  chmod 600 "$tmp"
  mv "$tmp" "$final"
  trap - EXIT
  echo "pg-backup: wrote $final ($(stat -c%s "$final") bytes)"
else
  echo "pg-backup: PG_BACKUP_SKIP_DUMP=1 (test hook) — dump skipped, retention only"
fi

# Ретенция: удалить *.dump строго старше RETENTION_DAYS суток.
#
# Именно -mmin, а не -mtime: у find сутки округляются ВНИЗ до целых дней, поэтому
# `-mtime +14` держит файл до 15 суток (floor(возраст) > 14), т.е. молча хранит N+1
# вместо N — та самая off-by-one на границе суток, о которой предупреждает WO.
# Минутная точность оставляет погрешность не более минуты, и тест
# ci/test-pg-backup.sh доказывает порог на файлах с контролируемым mtime.
cutoff_mins=$((RETENTION_DAYS * 1440))
removed=0
while IFS= read -r -d '' old; do
  # P-42: скрипт обслуживания под set -e обязан переживать отказ отдельного
  # элемента — один неудалённый файл не роняет весь бэкап-джоб.
  if rm -f "$old"; then
    removed=$((removed + 1))
  else
    echo "pg-backup: WARNING: could not remove old backup $old, continuing" >&2
  fi
done < <(find "$BACKUP_DIR" -maxdepth 1 -name '*.dump' -type f -mmin +"$cutoff_mins" -print0)

kept="$(find "$BACKUP_DIR" -maxdepth 1 -name '*.dump' -type f | wc -l | tr -d ' ')"
echo "pg-backup: retention kept ${kept}, removed ${removed} (older than ${RETENTION_DAYS}d)"
exit 0
