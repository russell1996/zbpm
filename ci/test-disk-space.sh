#!/usr/bin/env bash
# WO-OPS-3-DISK test: proves the deploy disk gate (ci/disk-space-check.sh) on
# substituted usage values (no real disk is filled), and that the pre-fix deploy
# (no gate at all) fails the WO criterion.
#
# Usage: bash ci/test-disk-space.sh [no-check|over-95|normal-80|over-86|prune-fail|containers]
#   no-check   simulate the PRE-FIX deploy (no disk gate) with 95% usage and assert the
#              WO criterion against it — MUST FAIL (this is the RED side).
#   over-95    run the ACTUAL ci/disk-space-check.sh with usage=95 — MUST exit 1 with
#              a clear message (GREEN side of criterion 1, the POF).
#   normal-80  usage=80 — MUST exit 0 and NOT prune (nothing above the clean threshold).
#   over-86    usage=86 (> clean 85) — MUST prune, re-measure, exit 0 (<= hard 90).
#   prune-fail usage=86, but `docker builder prune` FAILS (fake docker in PATH) —
#              MUST survive the failure (P-42), still run container prune, exit 0.
#   containers a REAL running + stopped container: cleanup must remove only the
#              stopped one; `grep --volumes` on the prod script must be empty.
#
# Runs the REAL ci/disk-space-check.sh artifact (G-N: no copy of the logic inline).
# Requires docker for prune-fail/containers; run from the repo root.
set -euo pipefail

MECH="${1:-retain-all}"
REAL_DOCKER="$(command -v docker)"

echo "== WO-OPS-3-DISK test mode: $MECH =="

if [ "$MECH" = "no-check" ]; then
    # PRE-FIX deploy had no disk gate: the job just continued into `compose up`
    # regardless of usage. Simulate that deploy on a 95% full disk.
    set +e
    DISK_USAGE_OVERRIDE=95 bash -c 'echo "deploy: compose up (pre-fix: no disk gate)"; exit 0'
    rc=$?
    set -e
    echo "pre-fix deploy exit code on 95% usage: $rc"
    if [ "$rc" -eq 0 ]; then
        echo "FAIL: pre-fix deploy exited 0 on a 95% full disk — expected: non-zero (gate must refuse deploy), actual: 0 — criterion 1 violated (this is the expected RED)"
        exit 1
    fi
    echo "unexpected: pre-fix deploy did not exit 0"
    exit 2

elif [ "$MECH" = "over-95" ]; then
    set +e
    DISK_USAGE_OVERRIDE=95 bash ci/disk-space-check.sh
    rc=$?
    set -e
    echo "disk-space-check exit code: $rc"
    [ "$rc" -ne 0 ] || { echo "FAIL: gate allowed deploy on 95% usage"; exit 1; }
    echo "OK: gate refused deploy on 95% usage (exit $rc)"

elif [ "$MECH" = "normal-80" ]; then
    set +e
    out="$(DISK_USAGE_OVERRIDE=80 bash ci/disk-space-check.sh 2>&1)"
    rc=$?
    set -e
    echo "$out"
    echo "disk-space-check exit code: $rc"
    [ "$rc" -eq 0 ] || { echo "FAIL: gate failed on normal 80% usage"; exit 1; }
    echo "$out" | grep -q "pruning build cache" \
        && { echo "FAIL: cleanup ran at 80% (below clean threshold 85)"; exit 1; }
    echo "OK: usage 80% -> exit 0, no cleanup"

elif [ "$MECH" = "over-86" ]; then
    set +e
    out="$(DISK_USAGE_OVERRIDE=86 bash ci/disk-space-check.sh 2>&1)"
    rc=$?
    set -e
    echo "$out"
    echo "disk-space-check exit code: $rc"
    [ "$rc" -eq 0 ] || { echo "FAIL: gate failed after cleanup on 86%"; exit 1; }
    echo "$out" | grep -q "pruning build cache" \
        || { echo "FAIL: cleanup did NOT run above clean threshold 85"; exit 1; }
    echo "OK: usage 86% -> cleanup ran, exit 0 (<= hard limit 90)"

elif [ "$MECH" = "prune-fail" ]; then
    # P-42: a maintenance script under `set -e` must survive a single element's
    # failure. `docker builder prune` fails (fake docker shim), the script must
    # continue, run container prune, and still decide by re-measured usage.
    WORK="$(mktemp -d)"
    trap 'rm -rf "$WORK"' EXIT
    mkdir -p "$WORK/bin"
    cat > "$WORK/bin/docker" <<EOF
#!/bin/sh
if [ "\$1" = "builder" ] && [ "\$2" = "prune" ]; then
  echo "fake-docker: builder prune FAILS (simulated)" >&2
  exit 1
fi
exec "$REAL_DOCKER" "\$@"
EOF
    chmod +x "$WORK/bin/docker"
    set +e
    out="$(PATH="$WORK/bin:$PATH" DISK_USAGE_OVERRIDE=86 bash ci/disk-space-check.sh 2>&1)"
    rc=$?
    set -e
    echo "$out"
    echo "disk-space-check exit code with failing builder prune: $rc"
    [ "$rc" -eq 0 ] || { echo "FAIL: single prune failure aborted the cleanup (P-42)"; exit 1; }
    echo "$out" | grep -q "builder prune failed, continuing" \
        || { echo "FAIL: no 'continuing' message after builder prune failure"; exit 1; }
    echo "OK: builder prune failed, script continued, exit 0"

elif [ "$MECH" = "containers" ]; then
    # Criterion 3: cleanup must not touch volumes or RUNNING containers; stopped
    # containers are pruned. Use real docker.
    WORK="$(mktemp -d)"
    trap 'docker rm -f ops3-run ops3-stopped >/dev/null 2>&1 || true; rm -rf "$WORK"' EXIT
    docker create --name ops3-stopped alpine:3.20 echo stopped >/dev/null 2>&1 || true
    docker run -d --name ops3-run alpine:3.20 sleep 300 >/dev/null 2>&1
    set +e
    out="$(DISK_USAGE_OVERRIDE=86 bash ci/disk-space-check.sh 2>&1)"
    rc=$?
    set -e
    echo "$out"
    echo "disk-space-check exit code: $rc"
    [ "$rc" -eq 0 ] || { echo "FAIL: gate failed in containers mode"; exit 1; }
    docker inspect ops3-run >/dev/null 2>&1 \
        || { echo "FAIL: RUNNING container was removed by cleanup"; exit 1; }
    docker inspect ops3-stopped >/dev/null 2>&1 \
        && { echo "FAIL: stopped container survived container prune"; exit 1; }
    echo "OK: running container survived, stopped container pruned"

else
    echo "usage: $0 [no-check|over-95|normal-80|over-86|prune-fail|containers]"; exit 2
fi

# Criterion 3/5 guard (all modes): the prod script must never touch volumes or
# duplicate OPS-5 image retention. Comments may name those tools (they explain
# what the script must NOT do) — match only executable lines.
grep -nE -- "--volumes|image prune|image rm|retain-images" ci/disk-space-check.sh \
  | grep -vE '^[0-9]+:\s*#' \
  && { echo "FAIL: disk-space-check.sh touches volumes or image retention (OPS-5 territory)"; exit 1; }
echo "OK: no --volumes, no image prune/rm, no retain-images call in disk-space-check.sh"

echo "Tests run: 1, Failures: 0"
echo "PASS: test-disk-space.sh ($MECH mode)"