#!/usr/bin/env bash
# WO-AUDIT-1: Run RabbitMQ-broker integration tests (@Tag("rabbit")) against a real rabbitmq:3.13.
#
# Usage: ci/run-rabbit-tests.sh
#   Starts rabbitmq via ci/docker-compose.rabbit.yml, waits for health, runs the rabbit-IT
#   failsafe tests in a Maven 21 container, then always tears the broker down.
#   Exit code = mvn exit code, so any rabbit-IT failure fails the CI job.
#
# Mirrors ci/run-pg-tests.sh (WO-PROC-8) deliberately: same trap-cleanup, same
# --network host reachability, same env-plumbing rationale (Surefire/Failsafe forks
# inherit env reliably, Maven -D does not always reach the fork — P-23).
#
# Environment variables (override defaults):
#   RABBIT_PORT     — host port for AMQP (default: 5673, non-standard on purpose:
#                     never collide with a host-side 5672, same premise as PG_PORT)
#   RABBIT_USER     — broker user (default: zorrodev)
#   RABBIT_PASSWORD — broker password (default: zorrodev)
#   MAVEN_OPTS      — JVM args for Maven (default: -Xmx1g)

set -euo pipefail

export RABBIT_PORT="${RABBIT_PORT:-5673}"
export RABBIT_USER="${RABBIT_USER:-zorrodev}"
export RABBIT_PASSWORD="${RABBIT_PASSWORD:-zorrodev}"

COMPOSE="ci/docker-compose.rabbit.yml"
PROJECT="zbpm-rabbitci"

# Always tear the broker down — even if tests fail or the script is interrupted —
# so no container/volume/port is leaked onto the shared runner between pipelines.
# Failures are ECHOED, not hidden: a half-removed volume once poisoned a later run
# (.erlang.cookie eacces) while `>/dev/null || true` swallowed the evidence.
cleanup() {
  if ! docker compose -f "$COMPOSE" -p "$PROJECT" down -v 2>&1 | tail -3; then
    echo "WARNING: rabbit teardown failed — inspect volumes/networks manually"
  fi
}
trap cleanup EXIT

echo "=== WO-AUDIT-1: Starting rabbitmq:3.13 on host port $RABBIT_PORT ==="
docker compose -f "$COMPOSE" -p "$PROJECT" up -d

echo "=== Waiting for rabbitmq to be healthy ==="
# NOTE: exec MUST run as the rabbitmq user (-u rabbitmq). `docker compose exec`
# defaults to root, and an early root-owned rabbitmq-diagnostics run creates
# /var/lib/rabbitmq/.erlang.cookie owned by root — the broker (uid rabbitmq) then
# dies at boot with "eacces" and never becomes ready. Found live: 3/3 script runs
# with plain exec poisoned the boot; manual (late) pings did not.
RETRIES=60
until docker compose -f "$COMPOSE" -p "$PROJECT" exec -T -u rabbitmq rabbitmq rabbitmq-diagnostics -q ping 2>/dev/null; do
  RETRIES=$((RETRIES - 1))
  if [ "$RETRIES" -le 0 ]; then
    echo "ERROR: rabbitmq did not become ready in time"
    docker compose -f "$COMPOSE" -p "$PROJECT" logs rabbitmq
    exit 1
  fi
  echo "  waiting... ($RETRIES retries left)"
  sleep 2
done
echo "=== rabbitmq is ready ==="

# Run rabbit-only tests inside a Maven container with --network host to reach the
# broker published on 127.0.0.1:$RABBIT_PORT. -Dgroups=rabbit selects @Tag("rabbit");
# the test reads host/port/user/pass from RABBITMQ_* env (defaults = historic
# localhost:5672/zorrodev, unchanged).
set +e
docker run --rm \
  --network host \
  -v "${BUILD_DIR:-$(pwd)}":/build -w /build \
  -e RABBITMQ_HOST=127.0.0.1 \
  -e RABBITMQ_PORT="$RABBIT_PORT" \
  -e RABBITMQ_USER="$RABBIT_USER" \
  -e RABBITMQ_PASSWORD="$RABBIT_PASSWORD" \
  -e MAVEN_OPTS="${MAVEN_OPTS:--Xmx1g}" \
  maven:3.9.9-eclipse-temurin-21 \
  mvn -B -ntp clean verify \
    -pl zorrobpm-rest \
    -am \
    -Dsurefire.skip=true \
    -Dgroups=rabbit \
    -Dzbpm.excludedGroups= \
    -Dsurefire.failIfNoSpecifiedTests=false \
    -DRABBITMQ_HOST=127.0.0.1 \
    -DRABBITMQ_PORT="$RABBIT_PORT" \
    -DRABBITMQ_USER="$RABBIT_USER" \
    -DRABBITMQ_PASSWORD="$RABBIT_PASSWORD"
rc=$?
set -e
echo "=== rabbit suite exited with code $rc ==="
exit $rc
