#!/usr/bin/env bash
# WO-SCALE-3 — multi-instance soak load generator. CTO-local tool, not CI,
# not shipped to prod. Run against `docker compose -f docker-compose.multi.yml
# up -d` (postgres+rabbitmq+app1/2/3+nginx all healthy first).
#
# What it drives, and why each piece is here — see governance/workorders/
# WO-SCALE-3-multi-instance-soak-proof.md for the criteria this exists to
# produce evidence for:
#
#   1. Concurrent deploys of the SAME process key fired at three DIFFERENT
#      replicas simultaneously, repeated every round — WO-SCALE-1's
#      pg_advisory_xact_lock version-sequencing claim, under real
#      concurrent multi-JVM contention (not single-JVM multi-thread, which
#      the existing PgIT already covers).
#   2. soak-boundary instances started via nginx round-robin (so no one
#      replica can be relied on to run the whole lifecycle) — interrupting
#      boundary timer must fire exactly once cluster-wide.
#   3. soak-timer-cycle deployed ONCE, left running — a repeating start
#      timer with no per-request driver at all; any double-fire here is a
#      pure server-side scheduler race.
#   4. soak-usertask instances started via nginx, completed via a
#      DIFFERENT direct replica port than a naive same-connection client
#      would reuse — ordinary no-sticky-session correctness.
#   5. Two SSE clients: one straight to app1, one via nginx (so its replica
#      is whatever round-robin picks) — proves ADR-7's AMQP cross-replica
#      fan-out, not nginx routing coincidence.
#   6. Repeated failed logins aimed at ALL THREE replicas directly for the
#      same account/IP — proves the WO-SCALE-2 Postgres-backed rate limit
#      is shared cluster-wide, not per-JVM Caffeine (which WO-SCALE-2
#      removed for exactly this reason).
#
# Everything is logged to $OUT_DIR as newline-delimited JSON / raw SSE text
# for ci/soak/analyze-soak.sh (or manual jq) to verify after the run.
# Nothing here decides pass/fail by itself — the CTO's own read of the
# WO-SCALE-3 criteria against these logs does that.

set -uo pipefail

NGINX_BASE="${NGINX_BASE:-http://127.0.0.1:18800}"
REPLICAS=("http://127.0.0.1:18091" "http://127.0.0.1:18092" "http://127.0.0.1:18093")
ADMIN_USER="${ADMIN_USER:-admin}"
ADMIN_PASSWORD="${ZORROBPM_DEFAULT_ADMIN_PASSWORD:?set ZORROBPM_DEFAULT_ADMIN_PASSWORD (same value as .env) before running}"
# WO-SEC-14: fresh admin has forcePasswordChange=true, which blocks every path
# except /me/password until cleared — the rig changes it once to a fixed
# soak-only password and uses THAT from then on. Safe to hardcode: this
# account only ever exists inside the throwaway soak-postgres volume.
SOAK_ADMIN_PASSWORD="Soak-Rig-$(date +%Y)-multi-instance!x"
DURATION_SECONDS="${DURATION_SECONDS:-14400}"   # default 4h; "not less than several hours" per WO
DEPLOY_RACE_INTERVAL="${DEPLOY_RACE_INTERVAL:-30}"
USERTASK_ROUND_INTERVAL="${USERTASK_ROUND_INTERVAL:-3}"
OUT_DIR="${OUT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/out/$(date -u +%Y%m%dT%H%M%SZ)}"

mkdir -p "$OUT_DIR"
echo "logging to $OUT_DIR"

log() { echo "[$(date -u +%H:%M:%S)] $*" | tee -a "$OUT_DIR/run.log" >&2; }

# ---- auth: one token per replica (they don't share an in-JVM session, JWT
# is stateless, but logging in against each keeps replica identity clear in
# the rate-limit test below) ----
COOKIE_JAR="$OUT_DIR/cookies.txt"
login() {
    local base="$1"
    curl -sS -c "$COOKIE_JAR" -X POST "$base/auth/login" \
        -H 'Content-Type: application/json' \
        -d "{\"username\":\"${ADMIN_USER}\",\"password\":\"${ADMIN_PASSWORD}\"}"
}

log "waiting for all 3 replicas + nginx to answer /actuator/health..."
for base in "${REPLICAS[@]}" "$NGINX_BASE"; do
    for _ in $(seq 1 60); do
        code=$(curl -sS -o /dev/null -w '%{http_code}' "$base/actuator/health" 2>/dev/null || echo 000)
        [ "$code" = "200" ] && break
        sleep 2
    done
    log "  $base -> $code"
done

# WO-SEC-14 forcePasswordChange: a brand-new admin's password is
# ADMIN_PASSWORD (the default); a re-run against a stack whose soak-postgres
# volume survived from a previous run already has it changed to
# SOAK_ADMIN_PASSWORD. Try the default first; if that login itself fails
# (wrong password = already changed), fall back to the soak password —
# whichever works, ADMIN_PASSWORD is updated to match so the rest of the
# script (and the deploy-race / rate-limit probes later, which re-login
# per replica) all use the right one.
TOKEN_JSON=$(login "$NGINX_BASE")
TOKEN=$(echo "$TOKEN_JSON" | jq -r '.token // empty')
if [ -z "$TOKEN" ]; then
    ADMIN_PASSWORD="$SOAK_ADMIN_PASSWORD"
    TOKEN_JSON=$(login "$NGINX_BASE")
    TOKEN=$(echo "$TOKEN_JSON" | jq -r '.token // empty')
    if [ -z "$TOKEN" ]; then
        log "FATAL: could not log in as $ADMIN_USER via $NGINX_BASE with either the default or soak password: $TOKEN_JSON"
        exit 1
    fi
    log "logged in with the already-changed soak password (re-run against a surviving stack)"
else
    log "logged in with the default password (first run against this stack)"
fi
AUTH=(-H "Authorization: Bearer $TOKEN")

# Clear forcePasswordChange if this is a first run (no-op, 401-and-ignored,
# on a re-run where it's already cleared and currentPassword no longer
# matches ADMIN_PASSWORD... except ADMIN_PASSWORD was just set to the
# CURRENT password above in both branches, so currentPassword is always
# correct here — this only actually changes anything the first time.
if [ "$ADMIN_PASSWORD" != "$SOAK_ADMIN_PASSWORD" ]; then
    curl -sS -X PUT "$NGINX_BASE/me/password" "${AUTH[@]}" -H 'Content-Type: application/json' \
        -d "{\"currentPassword\":\"${ADMIN_PASSWORD}\",\"newPassword\":\"${SOAK_ADMIN_PASSWORD}\"}" \
        > "$OUT_DIR/password-change.json" 2>&1
    ADMIN_PASSWORD="$SOAK_ADMIN_PASSWORD"
    TOKEN_JSON=$(login "$NGINX_BASE")
    TOKEN=$(echo "$TOKEN_JSON" | jq -r '.token // empty')
    if [ -z "$TOKEN" ]; then
        log "FATAL: password-change step ran but re-login with the new password failed: $TOKEN_JSON"
        exit 1
    fi
    AUTH=(-H "Authorization: Bearer $TOKEN")
    log "changed admin password to the soak-only password, forcePasswordChange cleared"
fi

# ---- one-time: deploy soak-timer-cycle (criterion 3 — no per-request driver) ----
deploy_once() {
    local file="$1" key="$2"
    local content
    content=$(python3 -c "import json,sys; print(json.dumps(open(sys.argv[1]).read()))" "$file")
    curl -sS -X POST "$NGINX_BASE/deployments" "${AUTH[@]}" \
        -H 'Content-Type: application/json' \
        -d "{\"description\":\"soak $key\",\"resources\":[{\"type\":\"BPMN\",\"content\":${content}}]}"
}
log "deploying soak-timer-cycle once (repeating R/PT5S start timer)..."
resp=$(deploy_once "$(dirname "${BASH_SOURCE[0]}")/soak-timer-cycle.bpmn" soak-timer-cycle)
echo "$resp" >> "$OUT_DIR/deploy-timer-cycle.jsonl"
log "  -> $(echo "$resp" | jq -c '.processes // .' 2>/dev/null || echo "$resp")"

log "deploying soak-usertask once (baseline, no race needed for this one)..."
resp=$(deploy_once "$(dirname "${BASH_SOURCE[0]}")/soak-usertask.bpmn" soak-usertask)
echo "$resp" >> "$OUT_DIR/deploy-usertask.jsonl"
log "  -> $(echo "$resp" | jq -c '.processes // .' 2>/dev/null || echo "$resp")"

# ---- SSE clients: one direct to app1, one via nginx round-robin ----
start_sse() {
    local base="$1" out="$2"
    curl -sS -N "${AUTH[@]}" -H 'Accept: text/event-stream' \
        "$base/events/stream" >> "$out" 2>&1 &
    echo $!
}
SSE_DIRECT_PID=$(start_sse "${REPLICAS[0]}" "$OUT_DIR/sse-direct-app1.log")
SSE_NGINX_PID=$(start_sse "$NGINX_BASE" "$OUT_DIR/sse-via-nginx.log")
log "SSE clients started: direct-app1 pid=$SSE_DIRECT_PID, via-nginx pid=$SSE_NGINX_PID"

cleanup() {
    log "stopping SSE clients..."
    kill "$SSE_DIRECT_PID" "$SSE_NGINX_PID" 2>/dev/null
}
trap cleanup EXIT INT TERM

BOUNDARY_BPMN="$(dirname "${BASH_SOURCE[0]}")/soak-boundary.bpmn"
BOUNDARY_RAW=$(cat "$BOUNDARY_BPMN")

# Per-round the deploy-race must push content that DIFFERS from the last
# round (otherwise an identical-content deploy is idempotent and just
# returns the existing version — no version-number race to test), while
# staying byte-identical across the 3 concurrent calls WITHIN a round (so
# they genuinely race for the same next version). Vary a harmless
# attribute — the process id (= the key) stays "soak-boundary".
round_boundary_content() {
    local r="$1"
    python3 -c "import json,sys; print(json.dumps(sys.argv[1].replace('name=\"soak-boundary\"', 'name=\"soak-boundary r'+sys.argv[2]+'\"')))" "$BOUNDARY_RAW" "$r"
}

# The JWT access token expires (~30 min observed). Renew it periodically —
# well inside the TTL — via POST /auth/refresh with the refresh cookie from
# login. Refresh uses its OWN rate-limit bucket (refresh:), NOT login:ip:,
# so the rate-limit probe (which hammers login:ip: on a throwaway account)
# can never starve token renewal. Falls back to a full re-login if refresh
# fails (e.g. refresh token itself aged out).
relogin() {
    local j t
    j=$(curl -sS -c "$COOKIE_JAR" -b "$COOKIE_JAR" -X POST "$NGINX_BASE/auth/refresh" \
        -H 'Content-Type: application/json')
    t=$(echo "$j" | jq -r '.token // empty')
    if [ -z "$t" ]; then
        log "WARNING: /auth/refresh gave no token ($j) — falling back to full login"
        j=$(login "$NGINX_BASE")
        t=$(echo "$j" | jq -r '.token // empty')
    fi
    if [ -n "$t" ]; then
        TOKEN="$t"
        AUTH=(-H "Authorization: Bearer $TOKEN")
    else
        log "WARNING: token renewal failed entirely, keeping old token: $j"
    fi
}
RELOGIN_EVERY_ROUNDS="${RELOGIN_EVERY_ROUNDS:-180}"

START_TIME=$(date +%s)
END_TIME=$((START_TIME + DURATION_SECONDS))
last_deploy_race=0
round=0

log "starting main loop for ${DURATION_SECONDS}s (until $(date -u -d "@$END_TIME" +%H:%M:%S 2>/dev/null || date -u -r "$END_TIME" +%H:%M:%S))"

while [ "$(date +%s)" -lt "$END_TIME" ]; do
    round=$((round + 1))
    now=$(date +%s)

    if [ $((round % RELOGIN_EVERY_ROUNDS)) -eq 0 ]; then
        relogin
        log "round $round: refreshed access token"
    fi

    # --- criterion 1: concurrent deploy race of soak-boundary against 3
    # different replicas at once, every DEPLOY_RACE_INTERVAL seconds ---
    if [ $((now - last_deploy_race)) -ge "$DEPLOY_RACE_INTERVAL" ]; then
        last_deploy_race=$now
        race_out="$OUT_DIR/deploy-race-round-$round.jsonl"
        round_content=$(round_boundary_content "$round")
        pids=()
        for base in "${REPLICAS[@]}"; do
            (curl -sS -X POST "$base/deployments" "${AUTH[@]}" \
                -H 'Content-Type: application/json' \
                -d "{\"description\":\"soak-boundary race round $round\",\"resources\":[{\"type\":\"BPMN\",\"content\":${round_content}}]}" \
                >> "$race_out" 2>&1; echo >> "$race_out") &
            pids+=($!)
        done
        for p in "${pids[@]}"; do wait "$p"; done
        versions=$(jq -r '.processes[]?.version // empty' "$race_out" 2>/dev/null | sort -n | tr '\n' ',')
        # Within one round the 3 concurrent deploys race for the same next
        # version: correct outcomes are all-same (dedup won — one physically
        # committed, the other two saw it and returned it) OR distinct
        # consecutive (each committed) — NEVER a duplicate NON-existing
        # version pair with a gap, and NEVER a 500 / constraint-violation body.
        log "round $round: deploy race versions=[$versions] (all-same or consecutive OK; a 500 body or a duplicated fresh version is the bug)"
    fi

    # --- criterion 2: start soak-boundary via nginx round-robin ---
    start_resp=$(curl -sS -X POST "$NGINX_BASE/process-instances" "${AUTH[@]}" \
        -H 'Content-Type: application/json' \
        -d '{"processDefinitionKey":"soak-boundary","variables":[]}')
    echo "$start_resp" >> "$OUT_DIR/boundary-starts.jsonl"

    # --- criterion 4: start soak-usertask via nginx, then complete its
    # user task via a DIRECT replica port chosen round-robin by the script
    # itself (not necessarily the one nginx picked for the start) ---
    ut_start_resp=$(curl -sS -X POST "$NGINX_BASE/process-instances" "${AUTH[@]}" \
        -H 'Content-Type: application/json' \
        -d '{"processDefinitionKey":"soak-usertask","variables":[]}')
    ut_pi_id=$(echo "$ut_start_resp" | jq -r '.id // empty')
    echo "$ut_start_resp" >> "$OUT_DIR/usertask-starts.jsonl"
    if [ -n "$ut_pi_id" ]; then
        complete_base="${REPLICAS[$((round % 3))]}"
        task_resp=$(curl -sS "$complete_base/user-tasks?processInstanceId=$ut_pi_id&completed=false&pageSize=5" "${AUTH[@]}")
        task_id=$(echo "$task_resp" | jq -r '.data[0].id // empty')
        if [ -n "$task_id" ]; then
            comp_resp=$(curl -sS -X POST "$complete_base/user-tasks/$task_id/complete" "${AUTH[@]}" \
                -H 'Content-Type: application/json' -d '{"variables":[]}')
            echo "{\"round\":$round,\"processInstanceId\":\"$ut_pi_id\",\"completedVia\":\"$complete_base\",\"response\":$comp_resp}" >> "$OUT_DIR/usertask-completions.jsonl"
        else
            log "round $round: WARNING no open user task found for $ut_pi_id via $complete_base (task list said: $task_resp)"
        fi
    fi

    # --- criterion 6: repeated failed logins aimed at ALL THREE replicas
    # directly, to build up the shared attempt count. Uses a THROWAWAY
    # username (never admin) so it exercises the cluster-wide login:ip: and
    # login:account:<probe> buckets WITHOUT locking out admin's own token
    # refresh. A per-replica-consistent lockout status (429/423) proves the
    # bucket is shared, not per-JVM. ---
    if [ $((round % 5)) -eq 0 ]; then
        for base in "${REPLICAS[@]}"; do
            code=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$base/auth/login" \
                -H 'Content-Type: application/json' \
                -d '{"username":"soak-ratelimit-probe","password":"wrong-on-purpose-soak"}')
            echo "{\"round\":$round,\"replica\":\"$base\",\"status\":$code}" >> "$OUT_DIR/ratelimit-probe.jsonl"
        done
    fi

    if [ $((round % 20)) -eq 0 ]; then
        log "round $round done ($(( $(date +%s) - START_TIME ))s elapsed)"
    fi

    sleep "$USERTASK_ROUND_INTERVAL"
done

log "main loop finished after $round rounds. Logs in $OUT_DIR"
log "SSE clients are still attached (see trap) — Ctrl-C or let the script exit to stop them."
