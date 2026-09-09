#!/usr/bin/env bash
# WO-SCALE-3 — post-run analysis for run-soak.sh's log directory.
# Prints evidence for each of the WO's numbered criteria; does NOT itself
# render a verdict — read the output against governance/workorders/
# WO-SCALE-3-multi-instance-soak-proof.md and decide.
#
# Usage: ci/soak/analyze-soak.sh <out_dir> [nginx_base]

set -uo pipefail

OUT_DIR="${1:?usage: analyze-soak.sh <out_dir> [nginx_base]}"
NGINX_BASE="${2:-http://127.0.0.1:18800}"
ADMIN_USER="${ADMIN_USER:-admin}"
ADMIN_PASSWORD="${ZORROBPM_DEFAULT_ADMIN_PASSWORD:?set ZORROBPM_DEFAULT_ADMIN_PASSWORD}"

section() { echo; echo "==== $* ===="; }

TOKEN=$(curl -sS -X POST "$NGINX_BASE/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"${ADMIN_USER}\",\"password\":\"${ADMIN_PASSWORD}\"}" | jq -r '.token')
AUTH=(-H "Authorization: Bearer $TOKEN")

section "1. Deploy-race version sequencing (soak-boundary, concurrent 3-replica deploys)"
all_versions=$(cat "$OUT_DIR"/deploy-race-round-*.jsonl 2>/dev/null | jq -r '.processes[]?.version // empty' | sort -n)
total=$(echo "$all_versions" | grep -c . || true)
distinct=$(echo "$all_versions" | sort -un | grep -c . || true)
echo "responses with a version: $total, distinct version numbers: $distinct"
if [ "$total" != "$distinct" ]; then
    echo "!! DUPLICATE VERSION NUMBERS FOUND — advisory lock did not serialize across replicas:"
    echo "$all_versions" | sort -n | uniq -d
else
    echo "OK: every concurrent deploy round produced a distinct version, no collisions."
fi
err_count=$(cat "$OUT_DIR"/deploy-race-round-*.jsonl 2>/dev/null | grep -c '"status":5\|Internal Server Error\|"code":"INTERNAL_ERROR"' || true)
echo "500-ish error bodies across all deploy-race rounds: $err_count (expect 0)"

section "2. soak-boundary exactly-once interrupting timer"
resp=$(curl -sS "$NGINX_BASE/process-instances?processDefinitionKey=soak-boundary&pageSize=200" "${AUTH[@]}")
pi_ids=$(echo "$resp" | jq -r '.data[].id')
total_pi=$(echo "$pi_ids" | grep -c . || true)
echo "soak-boundary instances found (first page, up to 200): $total_pi"
dup_end=0
checked=0
for id in $pi_ids; do
    checked=$((checked + 1))
    [ "$checked" -gt 50 ] && break   # sample, not full O(n) sweep against a live server
    acts=$(curl -sS "$NGINX_BASE/process-instances/$id/activities" "${AUTH[@]}")
    ends=$(echo "$acts" | jq -r '.[] | select(.bpmnElementId=="endEvent" or .bpmnElementId=="escalationEnd") | select(.status=="COMPLETED") | .bpmnElementId')
    n=$(echo "$ends" | grep -c . || true)
    if [ "$n" -gt 1 ]; then
        dup_end=$((dup_end + 1))
        echo "!! instance $id ended at BOTH endEvent and escalationEnd: $ends"
    fi
done
echo "sampled $((checked - 1)) instances, $dup_end with a double-end (expect 0)"

section "3. soak-timer-cycle repeating start timer (no per-request driver)"
resp=$(curl -sS "$NGINX_BASE/process-instances?processDefinitionKey=soak-timer-cycle&pageSize=200" "${AUTH[@]}")
total_cycle=$(echo "$resp" | jq -r '.totalElements')
echo "soak-timer-cycle instances created: $total_cycle"
echo "expected ballpark: run duration / 5s cycle (R/PT5S) — compare against this run's actual wall-clock duration; large deficit = missed cycles, any exact-duplicate-timestamp cluster = double-fire (inspect manually if the ballpark is off)."

section "4. soak-usertask cross-replica start+complete (no sticky session)"
starts=$(wc -l < "$OUT_DIR/usertask-starts.jsonl" 2>/dev/null || echo 0)
completions=$(wc -l < "$OUT_DIR/usertask-completions.jsonl" 2>/dev/null || echo 0)
comp_errors=$(grep -c '"completedVia".*"response":{"code"' "$OUT_DIR/usertask-completions.jsonl" 2>/dev/null || true)
echo "usertask starts logged: $starts, completions attempted: $completions, completion error bodies: $comp_errors"
echo "per-replica completion spread:"
grep -o '"completedVia":"[^"]*"' "$OUT_DIR/usertask-completions.jsonl" 2>/dev/null | sort | uniq -c

section "5. SSE cross-replica delivery (ADR-7 AMQP fan-out)"
for f in "$OUT_DIR/sse-direct-app1.log" "$OUT_DIR/sse-via-nginx.log"; do
    n=$(grep -c '^event:' "$f" 2>/dev/null || echo 0)
    echo "$f: $n events received"
done
echo "cross-check: pick a few processInstanceId values from boundary-starts.jsonl / usertask-starts.jsonl"
echo "and confirm they appear in BOTH sse logs (not just the log of the replica nginx happened to route"
echo "the start to) — that's the actual AMQP fan-out proof, not just 'both logs are non-empty':"
sample_ids=$(jq -r '.id // empty' "$OUT_DIR/usertask-starts.jsonl" 2>/dev/null | head -5)
for id in $sample_ids; do
    in_direct=$(grep -c "$id" "$OUT_DIR/sse-direct-app1.log" 2>/dev/null || echo 0)
    in_nginx=$(grep -c "$id" "$OUT_DIR/sse-via-nginx.log" 2>/dev/null || echo 0)
    echo "  $id : direct-app1=$in_direct via-nginx=$in_nginx"
done

section "6. Cluster-wide rate limit (WO-SCALE-2 Postgres-backed, not per-JVM)"
echo "status codes seen per replica for the deliberately-wrong-password probe:"
jq -r '"\(.replica) \(.status)"' "$OUT_DIR/ratelimit-probe.jsonl" 2>/dev/null | sort | uniq -c
echo
echo "expect: 200s early on, then ALL THREE replicas start returning the SAME lockout status"
echo "(429/423 — check RateLimitFilter for the exact code) at roughly the same probe round —"
echo "if only one replica's probes start failing while the other two keep succeeding past the"
echo "configured capacity, the bucket is NOT actually shared cluster-wide."

section "Done"
echo "Raw logs remain in $OUT_DIR for manual follow-up (jq is your friend)."
