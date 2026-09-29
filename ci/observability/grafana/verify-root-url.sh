#!/usr/bin/env bash
# WO-REL-61 — regression proof for the Grafana root_url / Origin mismatch.
#
# What it proves: with the compose defaults (GF_SERVER_PROTOCOL/GRAFANA_DOMAIN unset)
# Grafana resolves root_url to http://localhost/grafana/ (old dev behavior, unchanged),
# and with prod-like values it resolves to the real public origin — the value Grafana 11's
# CSRF middleware actually uses for its Origin-vs-Host check on POST /api/ds/query and
# the live websocket.
#
# Why not via the overlay: the overlay needs the whole stack (app/frontend/nginx) and the
# prod TLS edge; this script isolates exactly the Grafana behavior on the PINNED production
# image (same digest as docker-compose.observability.yml) with the repo's own grafana.ini.
#
# Usage:  ci/observability/grafana/verify-root-url.sh [IMAGE]
# Requires: docker, curl. Exits 0 only if EVERY check passes; every check runs even if an
# earlier one fails (P-42 spirit — one red assertion must not hide the rest).
set -u
cd "$(dirname "$0")/../../.." || exit 2

IMAGE="${1:-grafana/grafana:11.4.4-security-01@sha256:be4cc4527cb68f959038f1fbe9e334aec866fc029724818d0941970175ef7788}"
RED_PORT=13061
GREEN_PORT=13062

fail=0
check() { # check <name> <expected> <actual>
  if [ "$2" = "$3" ]; then
    printf '  ok    %s (=%s)\n' "$1" "$2"
  else
    printf '  FAIL  %s: expected %s, got %s\n' "$1" "$2" "$3"
    fail=1
  fi
}

wait_up() { # wait_up <port>
  for _ in $(seq 1 30); do
    [ "$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$1/api/health" 2>/dev/null)" = "200" ] && return 0
    sleep 2
  done
  return 1
}

PASS_RED=rel61redpass123
PASS_GREEN=rel61greenpass123
docker rm -f rel61-red rel61-green >/dev/null 2>&1 || true
trap 'docker rm -f rel61-red rel61-green >/dev/null 2>&1 || true' EXIT

# RED container: exactly the pre-fix env (no GF_SERVER_PROTOCOL/GF_SERVER_DOMAIN).
docker run -d --rm --name rel61-red -p "127.0.0.1:${RED_PORT}:3000" \
  -e GF_SECURITY_ADMIN_USER=admin -e "GF_SECURITY_ADMIN_PASSWORD=${PASS_RED}" \
  -e 'GF_SERVER_ROOT_URL=%(protocol)s://%(domain)s/grafana/' \
  -e GF_SERVER_SERVE_FROM_SUB_PATH=true \
  -v "$PWD/ci/observability/grafana/grafana.ini:/etc/grafana/grafana.ini:ro" \
  "$IMAGE" >/dev/null
# GREEN container: prod-like values via the NEW compose knobs.
docker run -d --rm --name rel61-green -p "127.0.0.1:${GREEN_PORT}:3000" \
  -e GF_SECURITY_ADMIN_USER=admin -e "GF_SECURITY_ADMIN_PASSWORD=${PASS_GREEN}" \
  -e GF_SERVER_PROTOCOL=http -e GF_SERVER_DOMAIN=example.test \
  -e 'GF_SERVER_ROOT_URL=%(protocol)s://%(domain)s/grafana/' \
  -e GF_SERVER_SERVE_FROM_SUB_PATH=true \
  -v "$PWD/ci/observability/grafana/grafana.ini:/etc/grafana/grafana.ini:ro" \
  "$IMAGE" >/dev/null

wait_up "$RED_PORT" || { echo "FAIL red container never came up"; exit 1; }
wait_up "$GREEN_PORT" || { echo "FAIL green container never came up"; exit 1; }

# 1. Resolved root_url — absolute redirect Location is built from the RESOLVED AppUrl,
#    while /api/admin/settings only echoes the raw %(protocol)s template.
check "red root_url resolves to old default" \
  "http://localhost/grafana/" \
  "$(curl -s -o /dev/null -D - "http://127.0.0.1:${RED_PORT}/" | grep -i '^location:' | tr -d '\r' | awk '{print $2}')"
check "green root_url resolves to public domain" \
  "http://example.test/grafana/" \
  "$(curl -s -o /dev/null -D - "http://127.0.0.1:${GREEN_PORT}/" | grep -i '^location:' | tr -d '\r' | awk '{print $2}')"

# Authenticated sessions through the public Host (as nginx would present it).
RED_CK=$(mktemp); GREEN_CK=$(mktemp)
curl -s -c "$RED_CK" -o /dev/null -H "Host: example.test" -H 'Content-Type: application/json' \
  -d "{\"user\":\"admin\",\"password\":\"${PASS_RED}\"}" "http://127.0.0.1:${RED_PORT}/grafana/login"
curl -s -c "$GREEN_CK" -o /dev/null -H "Host: example.test" -H 'Content-Type: application/json' \
  -d "{\"user\":\"admin\",\"password\":\"${PASS_GREEN}\"}" "http://127.0.0.1:${GREEN_PORT}/grafana/login"

# 2. Same-host Origin passes the CSRF check (query reaches the handler: 400 "No queries
#    found", not 401/403) — on BOTH containers. The point of #1 is not the 403 decision
#    (the middleware compares Origin to the request Host, not to root_url) but that the
#    browser's real Origin only ever EQUALS the Host when root_url is configured right.
check "red POST ds/query, Origin==Host passes CSRF" "400" \
  "$(curl -s -b "$RED_CK" -o /dev/null -w '%{http_code}' -H "Host: example.test" \
    -H 'Content-Type: application/json' -H "Origin: https://example.test" \
    -d '{"queries":[]}' "http://127.0.0.1:${RED_PORT}/grafana/api/ds/query")"
check "green POST ds/query, Origin==Host passes CSRF" "400" \
  "$(curl -s -b "$GREEN_CK" -o /dev/null -w '%{http_code}' -H "Host: example.test" \
    -H 'Content-Type: application/json' -H "Origin: https://example.test" \
    -d '{"queries":[]}' "http://127.0.0.1:${GREEN_PORT}/grafana/api/ds/query")"

# 3. Foreign Origin is rejected on both (the live prod symptom shape: POST → 403
#    "origin not allowed", websocket upgrade → 403, safe GET untouched by the check).
check "red POST ds/query, foreign Origin rejected" "403" \
  "$(curl -s -b "$RED_CK" -o /dev/null -w '%{http_code}' -H "Host: example.test" \
    -H 'Content-Type: application/json' -H "Origin: https://other.test" \
    -d '{"queries":[]}' "http://127.0.0.1:${RED_PORT}/grafana/api/ds/query")"
check "green POST ds/query, foreign Origin rejected" "403" \
  "$(curl -s -b "$GREEN_CK" -o /dev/null -w '%{http_code}' -H "Host: example.test" \
    -H 'Content-Type: application/json' -H "Origin: https://other.test" \
    -d '{"queries":[]}' "http://127.0.0.1:${GREEN_PORT}/grafana/api/ds/query")"
check "green WS live/ws, Origin==Host upgrades" "101" \
  "$(curl -s -b "$GREEN_CK" -o /dev/null -w '%{http_code}' -H "Host: example.test" \
    -H "Origin: http://example.test" -H "Connection: Upgrade" -H "Upgrade: websocket" \
    -H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
    "http://127.0.0.1:${GREEN_PORT}/grafana/api/live/ws")"
check "green WS live/ws, foreign Origin rejected" "403" \
  "$(curl -s -b "$GREEN_CK" -o /dev/null -w '%{http_code}' -H "Host: example.test" \
    -H "Origin: https://other.test" -H "Connection: Upgrade" -H "Upgrade: websocket" \
    -H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
    "http://127.0.0.1:${GREEN_PORT}/grafana/api/live/ws")"
rm -f "$RED_CK" "$GREEN_CK"

if [ "$fail" -eq 0 ]; then echo "ALL CHECKS PASSED"; else echo "SOME CHECKS FAILED"; fi
exit "$fail"
