#!/usr/bin/env bash
# WO-OPS-3-DISK: disk-space gate for the deploy job — the durable fix for the
# 2026-07-28 incident where a full disk made BOTH deploy and rollback impossible
# (`printf: write error: No space left on device` during rollback).
#
# Behaviour (thresholds in percent, env-tunable):
#   usage <= DISK_CLEAN_THRESHOLD (85)  -> exit 0, nothing to do.
#   usage >  DISK_CLEAN_THRESHOLD       -> prune build cache (>168h) and stopped
#                                          containers, then RE-MEASURE.
#   usage >  DISK_HARD_LIMIT (90) after cleanup -> refuse to deploy (exit 1) with a
#                                          clear message. Deploying onto a full disk
#                                          would strand the stack (no write, no
#                                          rollback) — refusing is the fix.
#
# No image retention here — that is WO-OPS-5 territory (ci/retain-images.sh, build
# job). No `--volumes` anywhere: Postgres data lives in a volume and must survive.
# Stopped containers are pruned (they cannot run again anyway); running containers
# are never touched.
#
# Usage: bash ci/disk-space-check.sh
# Env:  DEPLOY_DIR (default /opt/zorro-bpm), DISK_CLEAN_THRESHOLD (85),
#       DISK_HARD_LIMIT (90), DISK_USAGE_OVERRIDE (test/dev override of the measured
#       usage, so the gate can be exercised without filling a real disk).
set -euo pipefail

deploy_dir="${DEPLOY_DIR:-/opt/zorro-bpm}"
clean_at="${DISK_CLEAN_THRESHOLD:-85}"
hard_limit="${DISK_HARD_LIMIT:-90}"

# Measure the highest usage across the paths deploy actually depends on: the deploy
# dir and the docker data dir (images/build-cache live there). `df` works for any
# path — it reports the mount that contains it.
measure_usage() {
    if [ -n "${DISK_USAGE_OVERRIDE:-}" ]; then
        echo "$DISK_USAGE_OVERRIDE"
        return
    fi
    local use=0 pct p
    for p in "$deploy_dir" /var/lib/docker; do
        pct="$(df --output=pcent "$p" 2>/dev/null | tail -1 | tr -d ' %')"
        # df failed (path missing?) — skip, do not guess
        [ -n "$pct" ] && [ "$pct" -gt "$use" ] 2>/dev/null && use="$pct"
    done
    echo "$use"
}

usage="$(measure_usage)"

if [ "$usage" -gt "$clean_at" ]; then
    echo "disk-space: usage ${usage}% > clean threshold ${clean_at}% — pruning build cache and stopped containers"
    # P-42: a maintenance script under `set -e` must survive a single operation's
    # failure. Either prune may legitimately fail (daemon busy, buildkit hiccup);
    # the cleanup must continue and the FINAL decision must come from the re-measured
    # usage, not from the exit code of a hygiene step.
    docker builder prune -af --filter "until=168h" || echo "disk-space: builder prune failed, continuing"
    docker container prune -f || echo "disk-space: container prune failed, continuing"
    usage="$(measure_usage)"
    echo "disk-space: usage after cleanup: ${usage}%"
fi

if [ "$usage" -gt "$hard_limit" ]; then
    echo "ERROR: disk usage ${usage}% exceeds hard limit ${hard_limit}% even after cleanup — refusing to deploy" >&2
    echo "ERROR: on a full disk the rollback would fail too ('No space left on device') — free space first" >&2
    exit 1
fi

echo "disk-space: usage ${usage}% <= ${hard_limit}% — OK"
exit 0