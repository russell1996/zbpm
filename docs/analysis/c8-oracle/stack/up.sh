#!/usr/bin/env bash
# Bring up the Camunda 8 oracle stand (WO-OPS-30 F0).
#
# ONLY manages the two zbpm-cmp-* containers defined in docker-compose.yml. It NEVER
# touches foreign containers (raxon-*, wo*, zbpm-*-pgci*, zorrobpm-*, tooling-*, ...).
#
# Two zeebe topologies are handled transparently:
#   1. Fresh stand-up   — zeebe is brought up from this compose file; connectors reach it
#                         by compose service name `zeebe:26500` (same network).
#   2. Reuse external   — a zbpm-cmp-zeebe already runs (e.g. started by the maintainer for the
#                         campaign). Its published ports are bound to 127.0.0.1, so the
#                         connectors reach it via its BRIDGE IP on its own network
#                         (C8_ORACLE_GATEWAY=<ip>:26500 + `docker network connect`).
#                         It is reused, NOT recreated.
set -uo pipefail
cd "$(dirname "$0")"
export COMPOSE_PROJECT_NAME=zbpm-cmp

ZEEBE="zbpm-cmp-zeebe"
CONN="zbpm-cmp-connectors"
MOCK="zbpm-cmp-mock"
HEALTH_URL="http://localhost:39600/actuator/health"

running() { [ -n "$(docker ps -q --filter "name=^$1$" 2>/dev/null)" ]; }
zeebe_healthy() { curl -fsS --max-time 5 "$HEALTH_URL" >/dev/null 2>&1; }

zeebe_up() { running "$ZEEBE" && zeebe_healthy; }
zeebe_in_my_project() {
  [ "$(docker inspect "$ZEEBE" --format '{{index .Config.Labels "com.docker.compose.project"}}' 2>/dev/null)" = "$COMPOSE_PROJECT_NAME" ]
}

# --- 1) Zeebe ---------------------------------------------------------------------------
if zeebe_up; then
  echo "[up] ${ZEEBE} already up + healthy — reusing, NOT recreating"
else
  # A stopped container with this name (left by an earlier run of this or another
  # compose project) makes `docker compose up` fail with a name conflict. It is ours
  # by name and is not running, so remove it before the fresh stand-up.
  if docker inspect "$ZEEBE" >/dev/null 2>&1 && ! running "$ZEEBE"; then
    echo "[up] removing stopped stale ${ZEEBE} container ..."
    docker rm -f "$ZEEBE" >/dev/null 2>&1 || true
  fi
  echo "[up] starting ${ZEEBE} (fresh stand-up) ..."
  docker compose up -d zeebe
  echo "[up] waiting for zeebe health (${HEALTH_URL}) ..."
  ok=0
  for _ in $(seq 1 60); do
    if zeebe_up; then ok=1; break; fi
    sleep 2
  done
  if [ "$ok" -ne 1 ]; then
    echo "[up] ERROR: zeebe not healthy within 120s — inspect with: docker logs ${ZEEBE}" >&2
    exit 1
  fi
fi

# --- 2) Connectors ----------------------------------------------------------------------
# Resolve the gateway address the connectors need for the CURRENT zeebe.
if zeebe_in_my_project; then
  GATEWAY="zeebe:26500"
  ZEEBE_NET=""
else
  ZEEBE_NET="$(docker inspect "$ZEEBE" --format '{{range $k,$v := .NetworkSettings.Networks}}{{$k}}{{end}}')"
  ZEEBE_IP="$(docker inspect "$ZEEBE" --format '{{range $k,$v := .NetworkSettings.Networks}}{{$v.IPAddress}}{{end}}')"
  GATEWAY="${ZEEBE_IP}:26500"
fi

current_gw() {
  docker inspect "$CONN" --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null \
    | grep '^ZEEBE_CLIENT_BROKER_GATEWAY-ADDRESS=' | head -1 | cut -d= -f2-
}

if running "$CONN" && [ "$(current_gw)" = "$GATEWAY" ]; then
  echo "[up] ${CONN} already up with gateway ${GATEWAY} — leaving it"
else
  echo "[up] (re)starting ${CONN} with ZEEBE gateway ${GATEWAY} ..."
  docker rm -f "$CONN" >/dev/null 2>&1 || true
  C8_ORACLE_GATEWAY="$GATEWAY" docker compose up -d connectors
  # For an external zeebe, attach the connectors to zeebe's network so its in-container
  # gateway port (26500) is routable by bridge IP. (No-op for a fresh compose-network zeebe.)
  if [ -n "$ZEEBE_NET" ]; then
    docker network connect "$ZEEBE_NET" "$CONN" 2>/dev/null || echo "[up] note: already on ${ZEEBE_NET}"
  fi
fi

# --- 3) Mock target (WO-OPS-30 F2b) ----------------------------------------------------
# The shared HTTP target both engines hit. Started from the compose file so the route
# table and the case list come from the repository (never from a hand-made container).
if running "$MOCK"; then
  echo "[up] ${MOCK} already up — leaving it"
else
  echo "[up] starting ${MOCK} (shared mock target) ..."
  docker rm -f "$MOCK" >/dev/null 2>&1 || true
  docker compose up -d mock
  sleep 1
fi

# --- 4) Report --------------------------------------------------------------------------
echo "[up] zeebe health:  $(curl -fsS --max-time 5 "$HEALTH_URL")"
echo "[up] zeebe:      $(docker ps --filter "name=^${ZEEBE}$" --format '{{.Names}} {{.Status}}')"
echo "[up] connectors: $(docker ps --filter "name=^${CONN}$" --format '{{.Names}} {{.Status}}') (gateway ${GATEWAY})"
echo "[up] mock:       $(docker ps --filter "name=^${MOCK}$" --format '{{.Names}} {{.Status}}') (http://zbpm-cmp-mock:8080 in-network, http://127.0.0.1:18099 from the host)"
echo "[up] verify connectors connected:  docker logs ${CONN} 2>&1 | grep -c 'Connection refused'  # expect 0"
echo "[up] done — only zbpm-cmp-* containers were started"
