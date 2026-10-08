#!/usr/bin/env bash
# WO-REL-67 criterion 4 test: доказывает ci/check-backup-freshness.sh на
# фикстурах каталогов (G-N: везде гоняется НАСТОЯЩИЙ скрипт, копий логики нет).
#
# Usage: bash ci/test-check-backup-freshness.sh [fresh|stale|empty|boundary|all]
#   fresh     GREEN: дамп "сейчас" — exit 0.
#   stale     GREEN (=RED для прод-скрипта без сторожа): дамп 27 ч — exit 1,
#             видимый FAIL. POF-сторона: наивный "бэкап прошёл" без проверки
#             свежести молча зелёный на таком каталоге.
#   empty     GREEN: пустой каталог — exit 1 (молчание = потерянный бэкап).
#   boundary  GREEN: дамп 25 ч при лимите 26 — exit 0 (запас не ложно-красный).
#   all       fresh+stale+empty+boundary.
#
# No docker needed. Run from the repo root.
set -euo pipefail

MECH="${1:-all}"

run_case() {
  local name="$1" age_hours="$2" limit="$3" expect_rc="$4"
  local work
  work="$(mktemp -d)"
  trap 'rm -rf "$work"' RETURN
  if [ "$age_hours" != "none" ]; then
    touch -d "@$(( $(date +%s) - age_hours*3600 ))" "$work/zorrobpm-db_probe.dump"
  fi
  local out rc=0
  out="$(BACKUP_DIR="$work" BACKUP_FRESHNESS_MAX_AGE_HOURS="$limit" \
    bash ci/check-backup-freshness.sh 2>&1)" || rc=$?
  echo "$out"
  if [ "$rc" -ne "$expect_rc" ]; then
    echo "FAIL [$name]: expected exit $expect_rc but was $rc"
    exit 1
  fi
  echo "OK [$name]: exit $rc as expected"
}

if [ "$MECH" = "fresh" ] || [ "$MECH" = "all" ]; then
  run_case "fresh-now-limit-26" 0 26 0
fi
if [ "$MECH" = "stale" ] || [ "$MECH" = "all" ]; then
  run_case "stale-27h-limit-26" 27 26 1
fi
if [ "$MECH" = "empty" ] || [ "$MECH" = "all" ]; then
  run_case "empty-dir" none 26 1
fi
if [ "$MECH" = "boundary" ] || [ "$MECH" = "all" ]; then
  run_case "boundary-25h-limit-26" 25 26 0
fi
if [ "$MECH" = "all" ]; then
  # Лимит из окружения, а не захардкожен: 5 ч при лимите 4 — FAIL.
  run_case "env-limit-honoured" 5 4 1
fi

if [ "$MECH" = "fresh" ] || [ "$MECH" = "stale" ] || [ "$MECH" = "empty" ] \
    || [ "$MECH" = "boundary" ] || [ "$MECH" = "all" ]; then
  echo "Tests run: 1, Failures: 0"
  echo "PASS: test-check-backup-freshness.sh ($MECH mode)"
else
  echo "usage: $0 [fresh|stale|empty|boundary|all]"; exit 2
fi
