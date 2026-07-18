#!/usr/bin/env bash
# WO-PROC-8: Run PG-only integration tests against a real postgres:16.
#
# Usage: ci/run-pg-tests.sh
#   Starts postgres via docker compose, waits for health, runs mvn -Dgroups=pg,
#   then stops postgres. Exit code = mvn exit code (fails the CI job on test failure).
#
# Environment variables (override defaults):
#   PG_PORT        — host port for postgres (default: 5432)
#   PG_DB          — database name (default: zorrobpm-db)
#   PG_USER        — database user (default: zorrodev)
#   PG_PASSWORD    — database password (default: zorrodev)
#   MAVEN_OPTS     — JVM args for Maven (default: -Xmx1g)

set -euo pipefail

export PG_PORT="${PG_PORT:-5432}"
export PG_DB="${PG_DB:-zorrobpm-db}"
export PG_USER="${PG_USER:-zorrodev}"
export PG_PASSWORD="${PG_PASSWORD:-zorrodev}"

echo "=== WO-PROC-8: Starting postgres:16 on port $PG_PORT ==="
docker compose up -d postgres

echo "=== Waiting for postgres to be healthy ==="
RETRIES=30
until docker compose exec postgres pg_isready -U "$PG_USER" -d "$PG_DB" -q 2>/dev/null; do
  RETRIES=$((RETRIES - 1))
  if [ "$RETRIES" -le 0 ]; then
    echo "ERROR: postgres did not become ready in time"
    docker compose logs postgres
    docker compose down
    exit 1
  fi
  echo "  waiting... ($RETRIES retries left)"
  sleep 1
done
echo "=== postgres is ready ==="

# Run PG-only tests inside a Maven container with --network host to reach the host postgres.
# The host postgres is on port $PG_PORT; the container uses localhost (via host networking).
echo "=== Running PG-IT: mvn -Dgroups=pg ==="
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
    -Dgroups=pg \
    -Dsurefire.failIfNoSpecifiedTests=false \
    -DPG_HOST=127.0.0.1 \
    -DPG_PORT="$PG_PORT" \
    -DPG_DB="$PG_DB" \
    -DPG_USER="$PG_USER" \
    -DPG_PASSWORD="$PG_PASSWORD" \
    ; MVN_EXIT=$?

echo "=== Stopping postgres ==="
docker compose down

echo "=== mvn exited with code $MVN_EXIT ==="
exit $MVN_EXIT
