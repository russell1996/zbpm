#!/usr/bin/env bash
# Tear down the Camunda 8 oracle stand (WO-OPS-30 F0).
#
# ONLY removes the two exact zbpm-cmp-* containers, by explicit name (never a wildcard,
# never `docker compose down` on a shared project). Foreign containers are untouched.
# `--keep-zeebe` leaves the zeebe up (e.g. a stand the maintainer started for the whole campaign).
set -uo pipefail
cd "$(dirname "$0")"

KEEP_ZEEBE=0
[ "${1:-}" = "--keep-zeebe" ] && KEEP_ZEEBE=1

for c in zbpm-cmp-connectors zbpm-cmp-mock zbpm-cmp-zeebe; do
  if [ "$c" = "zbpm-cmp-zeebe" ] && [ "$KEEP_ZEEBE" -eq 1 ]; then
    echo "[down] keeping ${c} (--keep-zeebe)"
    continue
  fi
  if docker ps -aq --filter "name=^${c}$" | grep -q .; then
    echo "[down] removing ${c}"
    docker rm -f "$c" >/dev/null 2>&1 || true
  else
    echo "[down] ${c} not present — skipping"
  fi
done
echo "[down] done — only zbpm-cmp-* containers were touched"
