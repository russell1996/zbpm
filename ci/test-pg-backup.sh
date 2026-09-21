#!/usr/bin/env bash
# WO-OPS-1 test: доказывает скрипт бэкапа ci/pg-backup.sh на реальных артефактах
# (G-N: везде гоняется НАСТОЯЩИЙ ci/pg-backup.sh, копий логики нет).
#
# Usage: bash ci/test-pg-backup.sh [red|retention|noleak|pinned|cycle|all]
#   red        PRE-FIX RED: наивная ретенция `find -mtime +N` (то, от чего WO
#              предупреждает) держит файл возрастом N+полсуток, который обязан уйти.
#              Тест падает с expected:<deleted> but was:<kept> — это и есть RED.
#   retention  GREEN: настоящий скрипт на файлах с контролируемым mtime удаляет
#              старше N суток (включая границу N+2ч, где наивный -mtime врёт),
#              свежие не трогает, N из окружения уважает.
#   noleak     GREEN: прогон с канареечным паролем — секрета нет ни в stdout,
#              ни в stderr (критерий 5 WO).
#   pinned     GREEN (P-42): один старый файл неудаляем (падающий rm-шим) — скрипт
#              всё равно exit 0 и удаляет остальные.
#   cycle      GREEN: полный backup→restore на эфемерном postgres:16 — сид, дамп
#              через скрипт, снос volume, чистый postgres, pg_restore, сверка данных.
#   all        red ожидается красным отдельно; здесь — retention+noleak+pinned.
#
# Requires docker для cycle; остальные моды — без docker. Run from the repo root.
set -euo pipefail

MECH="${1:-all}"

guard_no_secret_echo() {
  # Страж критерия 5 на сам прод-скрипт: пароль не должен уходить в echo/printf/set -x.
  # Комментарии могут упоминать PGPASSWORD словами — матчим только исполнимые строки.
  if grep -nE 'echo.*DB_PASSWORD|printf.*DB_PASSWORD|set -[a-z]*x' ci/pg-backup.sh \
       | grep -vE '^[0-9]+:\s*#' | grep -q .; then
    echo "FAIL: ci/pg-backup.sh печатает \$DB_PASSWORD или включает xtrace"
    exit 1
  fi
  echo "OK: no DB_PASSWORD echo/printf, no set -x in ci/pg-backup.sh"
}

if [ "$MECH" = "red" ]; then
  # PRE-FIX: наивный `find -mtime +14` на файле возрастом 14.5 суток.
  WORK="$(mktemp -d)"
  trap 'rm -rf "$WORK"' EXIT
  touch -d "@$(( $(date +%s) - 14*86400 - 43200 ))" "$WORK/old.dump"
  touch "$WORK/fresh.dump"
  find "$WORK" -maxdepth 1 -name '*.dump' -type f -mtime +14 -delete
  if [ -f "$WORK/old.dump" ]; then
    echo "FAIL: naive 'find -mtime +14' kept a 14.5-day-old file — expected:<deleted> but was:<kept> (this is the expected RED)"
    exit 1
  fi
  echo "unexpected: naive -mtime removed the boundary file"
  exit 2

elif [ "$MECH" = "retention" ]; then
  WORK="$(mktemp -d)"
  trap 'rm -rf "$WORK"' EXIT
  now="$(date +%s)"
  touch -d "@$((now - 15*86400))" "$WORK/fifteen.dump"   # 15 суток — уйти
  touch -d "@$((now - 14*86400 - 7200))" "$WORK/boundary.dump"  # 14с+2ч — уйти (наивный -mtime держит!)
  touch -d "@$((now - 13*86400))" "$WORK/thirteen.dump"  # 13 суток — держать
  touch "$WORK/fresh.dump"                               # сейчас — держать
  BACKUP_DIR="$WORK" RETENTION_DAYS=14 PG_BACKUP_SKIP_DUMP=1 bash ci/pg-backup.sh
  rc=$?
  [ "$rc" -eq 0 ] || { echo "FAIL: backup script exited $rc on retention-only run"; exit 1; }
  [ ! -f "$WORK/fifteen.dump" ] || { echo "FAIL: 15-day-old file survived — expected:<deleted> but was:<kept>"; exit 1; }
  [ ! -f "$WORK/boundary.dump" ] || { echo "FAIL: 14.5-day-old file survived — expected:<deleted> but was:<kept>"; exit 1; }
  [ -f "$WORK/thirteen.dump" ] || { echo "FAIL: 13-day-old file removed — expected:<kept> but was:<deleted>"; exit 1; }
  [ -f "$WORK/fresh.dump" ] || { echo "FAIL: fresh file removed — expected:<kept> but was:<deleted>"; exit 1; }
  echo "OK: 15d + 14.5d removed, 13d + fresh kept (RETENTION_DAYS=14 honoured)"
  # N из окружения, а не захардкожен: при N=7 восьмидневный файл уходит, шестидневный живёт.
  touch -d "@$((now - 8*86400))" "$WORK/eight.dump"
  touch -d "@$((now - 6*86400))" "$WORK/six.dump"
  BACKUP_DIR="$WORK" RETENTION_DAYS=7 PG_BACKUP_SKIP_DUMP=1 bash ci/pg-backup.sh >/dev/null
  [ ! -f "$WORK/eight.dump" ] || { echo "FAIL: RETENTION_DAYS=7 ignored for 8-day-old file"; exit 1; }
  [ -f "$WORK/six.dump" ] || { echo "FAIL: 6-day-old file removed under N=7 — expected:<kept> but was:<deleted>"; exit 1; }
  echo "OK: RETENTION_DAYS=7 removes 8-day-old file, keeps 6-day-old file"
  guard_no_secret_echo
  echo "Tests run: 1, Failures: 0"
  echo "PASS: test-pg-backup.sh (retention mode)"

elif [ "$MECH" = "noleak" ]; then
  WORK="$(mktemp -d)"
  trap 'rm -rf "$WORK"' EXIT
  CANARY="woops1-canary-9f31x7"
  touch "$WORK/fresh.dump"
  out="$(BACKUP_DIR="$WORK" RETENTION_DAYS=14 PG_BACKUP_SKIP_DUMP=1 \
    DB_NAME=d DB_USERNAME=u DB_PASSWORD="$CANARY" bash ci/pg-backup.sh 2>&1)"
  rc=$?
  echo "$out"
  [ "$rc" -eq 0 ] || { echo "FAIL: script exited $rc"; exit 1; }
  printf '%s' "$out" | grep -qF "$CANARY" \
    && { echo "FAIL: password leaked into script output — expected:<absent> but was:<present>"; exit 1; }
  echo "OK: canary password absent from stdout+stderr"
  guard_no_secret_echo
  echo "Tests run: 1, Failures: 0"
  echo "PASS: test-pg-backup.sh (noleak mode)"

elif [ "$MECH" = "pinned" ]; then
  # P-42: rm падает на одном конкретном файле (шим), остальные удаляются, exit 0.
  WORK="$(mktemp -d)"
  trap 'rm -rf "$WORK"' EXIT
  mkdir -p "$WORK/bin"
  cat > "$WORK/bin/rm" <<'EOF'
#!/bin/sh
for a in "$@"; do
  case "$a" in *pinned.dump) echo "fake-rm: cannot remove $a (simulated)" >&2; exit 1;; esac
done
exec /bin/rm "$@"
EOF
  chmod +x "$WORK/bin/rm"
  now="$(date +%s)"
  touch -d "@$((now - 15*86400))" "$WORK/pinned.dump" "$WORK/other.dump"
  touch "$WORK/fresh.dump"
  out="$(PATH="$WORK/bin:$PATH" BACKUP_DIR="$WORK" RETENTION_DAYS=14 \
    PG_BACKUP_SKIP_DUMP=1 bash ci/pg-backup.sh 2>&1)"
  rc=$?
  echo "$out"
  echo "exit code with one pinned file: $rc"
  [ "$rc" -eq 0 ] || { echo "FAIL: single undeletable file aborted the job (P-42)"; exit 1; }
  echo "$out" | grep -q "could not remove old backup.*pinned.dump, continuing" \
    || { echo "FAIL: no 'continuing' warning for the pinned file"; exit 1; }
  [ ! -f "$WORK/other.dump" ] || { echo "FAIL: removable old file survived"; exit 1; }
  [ -f "$WORK/fresh.dump" ] || { echo "FAIL: fresh file removed"; exit 1; }
  echo "OK: pinned file warned+kept, other old file removed, exit 0"
  guard_no_secret_echo
  echo "Tests run: 1, Failures: 0"
  echo "PASS: test-pg-backup.sh (pinned mode)"

elif [ "$MECH" = "cycle" ]; then
  command -v docker >/dev/null 2>&1 || { echo "SKIP: no docker"; exit 2; }
  C="ops1-proof-pg"
  WORK="$(mktemp -d)"
  trap 'docker rm -f "$C" >/dev/null 2>&1 || true; rm -rf "$WORK"' EXIT
  docker rm -f "$C" >/dev/null 2>&1 || true
  DB=proofdb; U=proofuser; P="woops1-cycle-pw"
  wait_pg() {
    # Проба настоящим запросом, а не только pg_isready: во время bootstrap'а
    # initdb сервер успевает ответить isready между рестартами и тут же уйти в
    # "shutting down" — ловили живьём. Ждём, пока проходит реальный SELECT.
    local i
    for i in $(seq 1 60); do
      if docker exec -e PGPASSWORD="$P" "$C" psql -h 127.0.0.1 -U "$U" -d "$DB" \
           -tA -c "SELECT 1;" 2>/dev/null | grep -q 1; then
        echo "postgres accepting real queries after ~${i}s"
        return 0
      fi
      sleep 1
    done
    echo "FAIL: postgres never accepted queries"
    docker logs "$C" 2>&1 | tail -n 20
    return 1
  }
  echo "== start seed postgres =="
  docker run -d --name "$C" -e POSTGRES_DB="$DB" -e POSTGRES_USER="$U" \
    -e POSTGRES_PASSWORD="$P" postgres:16-alpine >/dev/null
  wait_pg
  docker exec "$C" psql -U "$U" -d "$DB" -v ON_ERROR_STOP=1 -q -c \
    "CREATE TABLE wo_ops1_proof(id serial primary key, payload text not null); INSERT INTO wo_ops1_proof(payload) VALUES ('alpha-1'),('beta-2'),('gamma-3');"
  before="$(docker exec "$C" psql -U "$U" -d "$DB" -tA -c "SELECT count(*), md5(string_agg(payload, ',' ORDER BY id)) FROM wo_ops1_proof;")"
  echo "before backup: $before"
  echo "== dump via REAL ci/pg-backup.sh =="
  # BACKUP_DIR не создан заранее (в отличие от $WORK самого mktemp -d, у которого
  # mode 700 по умолчанию и без нашего кода) — так проверка permissions ниже реально
  # тестирует `mkdir -m 700` в скрипте, а не совпадение с чужим дефолтом.
  BDIR="$WORK/backups"
  BACKUP_DIR="$BDIR" PG_CONTAINER="$C" DB_NAME="$DB" DB_USERNAME="$U" DB_PASSWORD="$P" \
    bash ci/pg-backup.sh
  dump="$(ls -t "$BDIR"/*.dump | head -1)"
  [ -s "$dump" ] || { echo "FAIL: dump file missing or empty"; exit 1; }
  echo "dump: $dump ($(stat -c%s "$dump") bytes)"
  # Дамп — полная прод-БД (PII, хэши паролей): world-readable недопустим.
  dperm="$(stat -c%a "$BDIR")"; fperm="$(stat -c%a "$dump")"
  [ "$dperm" = "700" ] || { echo "FAIL: BACKUP_DIR perms expected:<700> but was:<$dperm>"; exit 1; }
  [ "$fperm" = "600" ] || { echo "FAIL: dump file perms expected:<600> but was:<$fperm>"; exit 1; }
  echo "OK: BACKUP_DIR=700, dump file=600 (not world-readable)"
  # Валидный -Fc: pg_restore --list читает оглавление.
  docker cp "$dump" "$C:/tmp/restore.dump"
  docker exec "$C" pg_restore --list /tmp/restore.dump | grep -q "wo_ops1_proof" \
    || { echo "FAIL: dump TOC has no wo_ops1_proof table"; exit 1; }
  echo "OK: dump is valid custom format, TOC names wo_ops1_proof"
  echo "== destroy volume, start CLEAN postgres =="
  docker rm -f "$C" >/dev/null
  docker run -d --name "$C" -e POSTGRES_DB="$DB" -e POSTGRES_USER="$U" \
    -e POSTGRES_PASSWORD="$P" postgres:16-alpine >/dev/null
  wait_pg
  tables_before="$(docker exec "$C" psql -U "$U" -d "$DB" -tA -c "SELECT count(*) FROM pg_tables WHERE schemaname='public';")"
  echo "tables on clean instance before restore: $tables_before"
  docker cp "$dump" "$C:/tmp/restore.dump"
  docker exec -e PGPASSWORD="$P" "$C" pg_restore -U "$U" -d "$DB" /tmp/restore.dump
  after="$(docker exec "$C" psql -U "$U" -d "$DB" -tA -c "SELECT count(*), md5(string_agg(payload, ',' ORDER BY id)) FROM wo_ops1_proof;")"
  rows="$(docker exec "$C" psql -U "$U" -d "$DB" -tA -c "TABLE wo_ops1_proof ORDER BY id;")"
  echo "after restore: $after"
  echo "$rows"
  [ "$before" = "$after" ] \
    || { echo "FAIL: data mismatch — expected:<$before> but was:<$after>"; exit 1; }
  echo "OK: backup→restore cycle complete, count+md5 identical"
  echo "Tests run: 1, Failures: 0"
  echo "PASS: test-pg-backup.sh (cycle mode)"

elif [ "$MECH" = "all" ]; then
  bash ci/test-pg-backup.sh retention
  bash ci/test-pg-backup.sh noleak
  bash ci/test-pg-backup.sh pinned

else
  echo "usage: $0 [red|retention|noleak|pinned|cycle|all]"; exit 2
fi
