#!/usr/bin/env bash
# WO-TEST-10: Run chaos/fault-injection tests (@Tag("chaos")) against real
# infra with a real fault injector (toxiproxy), all as sibling containers.
#
# Topology (all on the default bridge network, reachable by container name):
#   chaos-pg        — postgres:16 (SUT database)
#   chaos-rabbit    — rabbitmq:3.13-management-alpine (SUT broker)
#   chaos-toxiproxy — ghcr.io/shopify/toxiproxy:2.5.0 (fault injector, :8474 API)
# The Maven container joins the same topology via --network + --link-style
# name resolution (user-defined network gives embedded DNS).
#
# Mirrors ci/run-pg-tests.sh / ci/run-rabbit-tests.sh deliberately: same
# trap-cleanup, same --user (P-42/root-owned target class), same env-plumbing
# rationale (Surefire/Failsafe forks inherit env reliably, Maven -D does not
# always reach the fork — P-23).
#
# Environment variables (override defaults):
#   CHAOS_PG_PORT     — host port for chaos postgres (default: 55433)
#   CHAOS_RABBIT_PORT — host port for chaos rabbitmq (default: 56779)
#   CHAOS_TOXI_PORT   — host port for toxiproxy API (default: 18474)
#   MAVEN_OPTS        — JVM args for Maven (default: -Xmx1g)
#   CI_PIPELINE_ID    — when set (GitLab CI), project/network names get suffixed
#                       so two parallel pipelines never share one topology (WO-OPS-11 F26a).
#
# Exit code = mvn exit code, so any chaos-IT failure fails the CI job.

set -euo pipefail

export CHAOS_PG_PORT="${CHAOS_PG_PORT:-55433}"
export CHAOS_RABBIT_PORT="${CHAOS_RABBIT_PORT:-56779}"
export CHAOS_TOXI_PORT="${CHAOS_TOXI_PORT:-18474}"

if [ -n "${CI_PIPELINE_ID:-}" ]; then
  SUFFIX="$CI_PIPELINE_ID"
  NET="zbpm-chaosci-${SUFFIX}"
  PREFIX="chaosci-${SUFFIX}"
else
  NET="zbpm-chaosci"
  PREFIX="chaosci"
fi

PG_C="${PREFIX}-pg"
RB_C="${PREFIX}-rabbit"
TOXI_C="${PREFIX}-toxiproxy"

# Always tear the topology down — even if tests fail or the script is
# interrupted — so no container/volume/port is leaked onto the shared runner.
# Order matters: containers first (detach from the network), then the network.
cleanup() {
  docker rm -f "$PG_C" "$RB_C" "$TOXI_C" >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "=== WO-TEST-10: starting chaos topology (net $NET) ==="
# Idempotent network setup: a previous killed run may have leaked the network
# (trap cleanup runs `docker network rm` which fails on non-empty networks —
# containers from the dead run still attached). Reuse-or-create, never bare create.
docker network create "$NET" >/dev/null 2>&1 || true
# P-23 premise (same as PG_PORT/RABBIT_PORT): never collide with a host-side or
# sibling-suite service — CI overrides the host ports, the containers talk inside $NET.
docker run -d --name "$PG_C" --network "$NET" \
  -e POSTGRES_DB=zorrobpm-db -e POSTGRES_USER=zorrodev -e POSTGRES_PASSWORD=zorrodev \
  -p "$CHAOS_PG_PORT:5432" \
  postgres:16-alpine@sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777 >/dev/null
docker run -d --name "$RB_C" --network "$NET" \
  -e RABBITMQ_DEFAULT_USER=zorrodev -e RABBITMQ_DEFAULT_PASS=zorrodev \
  -p "$CHAOS_RABBIT_PORT:5672" \
  rabbitmq:3.13-management-alpine@sha256:606d8c0d6b3c18d1da9afc53bc7cdb2a8d5486df91b5a9830e9e07626c9ae281 >/dev/null
docker run -d --name "$TOXI_C" --network "$NET" \
  -p "$CHAOS_TOXI_PORT:8474" \
  ghcr.io/shopify/toxiproxy@sha256:927c797a2115a193ae3a527e5a36782b938419904ac6706ca0efa029ebea58cb >/dev/null

echo "=== Waiting for postgres ==="
RETRIES=40
until docker exec "$PG_C" pg_isready -U zorrodev -d zorrobpm-db -q 2>/dev/null; do
  RETRIES=$((RETRIES - 1))
  if [ "$RETRIES" -le 0 ]; then
    echo "ERROR: chaos postgres did not become ready in time"
    docker logs "$PG_C" 2>&1 | tail -5
    exit 1
  fi
  sleep 2
done
echo "=== postgres is ready ==="

echo "=== Waiting for rabbitmq ==="
RETRIES=60
until docker exec -u rabbitmq "$RB_C" rabbitmq-diagnostics -q ping 2>/dev/null; do
  RETRIES=$((RETRIES - 1))
  if [ "$RETRIES" -le 0 ]; then
    echo "ERROR: chaos rabbitmq did not become ready in time"
    docker logs "$RB_C" 2>&1 | tail -5
    exit 1
  fi
  sleep 2
done
echo "=== rabbitmq is ready ==="

echo "=== Waiting for toxiproxy API ==="
# NOTE: toxiproxy image has neither wget nor curl — readiness is probed from the
# HOST network namespace (published $CHAOS_TOXI_PORT), like the tests themselves
# reach the API (they run on the host-side mvn container network via container name).
RETRIES=30
until curl -sf http://localhost:$CHAOS_TOXI_PORT/version 2>/dev/null | grep -q "2.5.0"; do
  RETRIES=$((RETRIES - 1))
  if [ "$RETRIES" -le 0 ]; then
    echo "ERROR: toxiproxy API did not become ready in time"
    docker logs "$TOXI_C" 2>&1 | tail -5
    exit 1
  fi
  sleep 2
done
echo "=== toxiproxy is ready ==="

# The fault plane: tests NEVER talk to PG/Rabbit directly — all SUT traffic
# flows through these two proxies, so adding a toxic actually partitions the
# SUT (a toxic on a proxy nobody uses is a no-op — caught by review, WO-TEST-10).
# Listen ports are container-internal (mvn + worker containers share $NET);
# no host publish needed for them.
echo "=== Creating fault proxies (chaos-pg, chaos-rabbit) ==="
for proxy_json in \
  '{"name":"chaos-pg","listen":"0.0.0.0:15432","upstream":"'"$PG_C"':5432"}' \
  '{"name":"chaos-rabbit","listen":"0.0.0.0:15672","upstream":"'"$RB_C"':5672"}'; do
  pname=$(printf '%s' "$proxy_json" | sed 's/.*"name":"\([^"]*\)".*/\1/')
  curl -sf -X DELETE "http://localhost:$CHAOS_TOXI_PORT/proxies/$pname" >/dev/null 2>&1 || true
  curl -sf -X POST "http://localhost:$CHAOS_TOXI_PORT/proxies" \
    -H 'Content-Type: application/json' -d "$proxy_json" >/dev/null \
    || { echo "ERROR: cannot create toxiproxy proxy $pname"; exit 1; }
  echo "  proxy $pname ready"
done

# mvn container joins the topology network so tests address PG/Rabbit/Toxiproxy
# BY CONTAINER NAME (embedded DNS); lifecycle (kill -9 the worker container)
# needs the host docker socket — mounted read-write ONLY for the chaos suite
# (the chaos job is opt-in/manual, never part of the default build).
#
# Addressing (V1, WO-TEST-10): PG_HOST/PORT point at the chaos-pg PROXY listen
# (so the whole Spring datasource incl. Liquibase/Hikari flows through the fault
# plane — a toxic on a bypassed proxy would prove nothing); CHAOS_RABBIT_* point
# at the chaos-rabbit proxy for the same reason. CHAOS_NET/UID/GID/HOST_BUILD_DIR
# let tests spawn the victim worker container on the same network/user/mounts.
# Docker Engine нужен тестам (kill -9 victim-контейнера через сокет, см.
# ChaosDocker): раннер и так в docker-группе хоста, поэтому добавляем mvn-контейнер
# в ТУ ЖЕ группу сокета (--group-add по его GID) — иначе uid 1000 внутри контейнера
# получает BindException Permission denied на /var/run/docker.sock (поймано живым
# прогоном run5). Это не расширение прав: группа сокета уже у раннера.
set +e
docker run --rm \
  --user "$(id -u):$(id -g)" \
  --group-add "$(stat -c %g /var/run/docker.sock)" \
  --network "$NET" \
  -v "${BUILD_DIR:-$(pwd)}":/build -w /build \
  -v zbpm_m2:/tmp/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -e PG_HOST="$TOXI_C" \
  -e PG_PORT=15432 \
  -e PG_DB=zorrobpm-db \
  -e PG_USER=zorrodev \
  -e PG_PASSWORD=zorrodev \
  -e CHAOS_PG_HOST="$PG_C" \
  -e CHAOS_PG_PORT=5432 \
  -e CHAOS_RABBIT_HOST="$TOXI_C" \
  -e CHAOS_RABBIT_PORT=15672 \
  -e CHAOS_TOXI_HOST="$TOXI_C" \
  -e CHAOS_TOXI_PORT=8474 \
  -e CHAOS_WORKER_PREFIX="$PREFIX" \
  -e CHAOS_NET="$NET" \
  -e CHAOS_UID="$(id -u)" \
  -e CHAOS_GID="$(id -g)" \
  -e CHAOS_HOST_BUILD_DIR="${BUILD_DIR:-$(pwd)}" \
  -e MAVEN_OPTS="${MAVEN_OPTS:--Xmx1g}" \
  maven:3.9.9-eclipse-temurin-21 \
  mvn -B -ntp -Dmaven.repo.local=/tmp/.m2/repository clean verify \
    -pl zorrobpm-engine,zorrobpm-job-handler-spring-boot-starter \
    -am \
    -Dsurefire.skip=true \
    -Dgroups=chaos \
    -Dzbpm.excludedGroups= \
    -Dsurefire.failIfNoSpecifiedTests=false
rc=$?
set -e
echo "=== chaos suite exited with code $rc ==="
exit $rc
