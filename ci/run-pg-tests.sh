#!/usr/bin/env bash
# WO-PROC-8: Run PG-only integration tests (@Tag("pg")) against a real postgres:16.
#
# Usage: ci/run-pg-tests.sh
#   Starts postgres via ci/docker-compose.pg.yml, waits for health, runs the PG-IT
#   failsafe tests in a Maven 21 container, then always tears postgres down.
#   Exit code = mvn exit code, so any PG-IT failure fails the CI job.
#
# Uses ci/docker-compose.pg.yml (postgres only) — NOT the root docker-compose.yml,
# whose `app` service requires ZORROBPM_JWT_SECRET/ADMIN_PASSWORD (no default) and
# would abort `docker compose up` on interpolation before postgres ever starts.
#
# Environment variables (override defaults):
#   PG_PORT     — host port for postgres (default: 5432)
#   PG_DB       — database name (default: zorrobpm-db)
#   PG_USER     — database user (default: zorrodev)
#   PG_PASSWORD — database password (default: zorrodev)
#   MAVEN_OPTS  — JVM args for Maven (default: -Xmx1g)

set -euo pipefail

export PG_PORT="${PG_PORT:-5432}"
export PG_DB="${PG_DB:-zorrobpm-db}"
export PG_USER="${PG_USER:-zorrodev}"
export PG_PASSWORD="${PG_PASSWORD:-zorrodev}"

COMPOSE="ci/docker-compose.pg.yml"
PROJECT="zbpm-pgci"

# Always tear postgres down — even if tests fail or the script is interrupted —
# so no container/volume/port is leaked onto the shared runner between pipelines.
cleanup() { docker compose -f "$COMPOSE" -p "$PROJECT" down -v >/dev/null 2>&1 || true; }
trap cleanup EXIT

echo "=== WO-PROC-8: Starting postgres:16 on host port $PG_PORT ==="
docker compose -f "$COMPOSE" -p "$PROJECT" up -d

echo "=== Waiting for postgres to be healthy ==="
RETRIES=30
until docker compose -f "$COMPOSE" -p "$PROJECT" exec -T postgres pg_isready -U "$PG_USER" -d "$PG_DB" -q 2>/dev/null; do
  RETRIES=$((RETRIES - 1))
  if [ "$RETRIES" -le 0 ]; then
    echo "ERROR: postgres did not become ready in time"
    docker compose -f "$COMPOSE" -p "$PROJECT" logs postgres
    exit 1
  fi
  echo "  waiting... ($RETRIES retries left)"
  sleep 1
done
echo "=== postgres is ready ==="

# Run PG-only tests inside a Maven container with --network host to reach the host
# postgres published on 127.0.0.1:$PG_PORT. -Dgroups=pg selects @Tag("pg"); the pom
# clears its default pg exclusion when -Dgroups is set (see zorrobpm-engine/pom.xml).
# set +e so a test failure doesn't abort before we capture the exit code; the EXIT
# trap still tears postgres down.
echo "=== Running PG-IT: mvn -Dgroups=pg verify ==="
set +e
docker run --rm \
  --network host \
  -v "$(pwd)":/build -w /build \
  -e PG_HOST=127.0.0.1 \
  -e PG_PORT="$PG_PORT" \
  -e PG_DB="$PG_DB" \
  -e PG_USER="$PG_USER" \
  -e PG_PASSWORD="$PG_PASSWORD" \
  -e MAVEN_OPTS="${MAVEN_OPTS:--Xmx1g}" \
  maven:3.9.9-eclipse-temurin-21 \
  mvn -B -ntp clean verify \
    -pl zorrobpm-engine \
    -am \
    -Dsurefire.skip=true \
    -Dgroups=pg \
    -Dzbpm.excludedGroups= \
    -Dsurefire.failIfNoSpecifiedTests=false \
    -DPG_HOST=127.0.0.1 \
    -DPG_PORT="$PG_PORT" \
    -DPG_DB="$PG_DB" \
    -DPG_USER="$PG_USER" \
    -DPG_PASSWORD="$PG_PASSWORD"
MVN_EXIT=$?
set -e

echo "=== mvn exited with code $MVN_EXIT ==="
exit $MVN_EXIT
