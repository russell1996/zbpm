#!/usr/bin/env bash
# WO-REL-67: сторож свежести бэкапа — падает видимо, если newest *.dump в
# BACKUP_DIR старше BACKUP_FRESHNESS_MAX_AGE_HOURS (дефолт 26: ночная
# периодичность 24 ч + 2 ч запаса на очередь раннера/скью часов).
#
# Вызывается в backup:pg ПОСЛЕ pg-backup.sh — доказывает артефакт этой же джобы
# end-to-end (тихий отказ записи всё равно краснит джобу, а не оставляет зелёное
# "бэкап прошёл"). Пустой каталог (дампа нет вообще) — тоже FAIL, не SKIP:
# молчание здесь и есть тот самый потерянный бэкап 06→07.10.
#
# Usage: bash ci/check-backup-freshness.sh
# Env: BACKUP_DIR (дефолт /opt/zorro-bpm/backups),
#      BACKUP_FRESHNESS_MAX_AGE_HOURS (дефолт 26, целое > 0).
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-/opt/zorro-bpm/backups}"
MAX_AGE_HOURS="${BACKUP_FRESHNESS_MAX_AGE_HOURS:-26}"

case "$MAX_AGE_HOURS" in
  ''|*[!0-9]*|0)
    echo "check-backup-freshness: ERROR: BACKUP_FRESHNESS_MAX_AGE_HOURS must be a positive integer, got '${MAX_AGE_HOURS}'" >&2
    exit 1
    ;;
esac

[ -d "$BACKUP_DIR" ] \
  || { echo "check-backup-freshness: FAIL: no backup dir $BACKUP_DIR — expected:<fresh dump> but was:<no dir>"; exit 1; }

newest=""
newest_mtime=0
while IFS= read -r -d '' f; do
  mt="$(stat -c %Y "$f")"
  if [ "$mt" -gt "$newest_mtime" ]; then
    newest_mtime="$mt"
    newest="$f"
  fi
done < <(find "$BACKUP_DIR" -maxdepth 1 -name '*.dump' -type f -print0)

[ -n "$newest" ] \
  || { echo "check-backup-freshness: FAIL: no *.dump in $BACKUP_DIR — expected:<fresh dump> but was:<none>"; exit 1; }

now="$(date +%s)"
age_hours=$(( (now - newest_mtime) / 3600 ))
if [ "$age_hours" -gt "$MAX_AGE_HOURS" ]; then
  echo "check-backup-freshness: FAIL: newest dump $newest is ${age_hours}h old (limit ${MAX_AGE_HOURS}h) — expected:<fresh> but was:<stale>"
  exit 1
fi
echo "check-backup-freshness: OK: newest dump $newest is ${age_hours}h old (limit ${MAX_AGE_HOURS}h)"
exit 0
