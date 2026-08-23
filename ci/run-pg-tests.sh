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

export PG_PORT="${PG_PORT:-55432}"   # non-standard host port: never collide with a host-side PG on 5432 (P-23)
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
# postgres published on 127.0.0.1:$PG_PORT. -Dgroups=pg selects @Tag("pg"); each
# module's pom clears its default pg exclusion when -Dgroups is set.
#
# WO-OPS-6: BOTH modules run. `-am` pulls DEPENDENCIES only, so a single
# `-pl zorrobpm-engine -am` never reached zorrobpm-rest and its three PgITs
# (Acl12SubmissionRacePgIT / RefreshTokenRacePgIT / QueryResourceAuthzPgIT)
# never executed in CI. The rest suite runs against a FRESH database: engine's
# HotColumnIndexUsagePgIT seeds thousands of perfuser* rows into ui_users, which
# starves UiUserBootstrap of the admin seed and 401s every rest test (documented
# in WO-ACL-12). Recreating the DB between suites keeps both sets green without
# touching that foreign test.
#
# set +e so a test failure doesn't abort before we capture the exit code; the EXIT
# trap still tears postgres down.
run_pg_suite() {
  local module="$1"
  local with_am="$2"
  local am_flag=""
  if [ "$with_am" = "yes" ]; then am_flag="-am"; fi
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
      -pl "$module" \
      $am_flag \
      -Dsurefire.skip=true \
      -Dgroups=pg \
      -Dzbpm.excludedGroups= \
      -Dsurefire.failIfNoSpecifiedTests=false \
      -DPG_HOST=127.0.0.1 \
      -DPG_PORT="$PG_PORT" \
      -DPG_DB="$PG_DB" \
      -DPG_USER="$PG_USER" \
      -DPG_PASSWORD="$PG_PASSWORD"
  local rc=$?
  set -e
  echo "=== $module PG suite exited with code $rc ==="
  return $rc
}

# Install rest's DEPENDENCIES into the shared ~/.m2 WITHOUT running their tests.
# The rest suite must NOT use -am: that would drag engine failsafe (and its
# HotColumnIndexUsagePgIT ui_users pollution) back into the same database the
# rest tests need clean.
install_rest_deps() {
  echo "=== Installing zorrobpm-rest dependencies (tests skipped) ==="
  docker run --rm \
    -v "$(pwd)":/build -w /build \
    -e MAVEN_OPTS="${MAVEN_OPTS:--Xmx1g}" \
    -v "${HOME}/.m2:/root/.m2" \
    maven:3.9.9-eclipse-temurin-21 \
    mvn -B -ntp install -pl zorrobpm-rest -am -DskipTests
}

recreate_schema() {
  echo "=== Resetting public schema for the next suite ==="
  docker compose -f "$COMPOSE" -p "$PROJECT" exec -T postgres \
    psql -U "$PG_USER" -d "$PG_DB" -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"
}

echo "=== PG suite 1/2: zorrobpm-engine ==="
if ! run_pg_suite zorrobpm-engine yes; then
  echo "=== FAILED: engine PG suite ==="
  exit 1
fi

recreate_schema

echo "=== PG suite 2/2: zorrobpm-rest ==="
if ! install_rest_deps; then
  echo "=== FAILED: installing rest dependencies ==="
  exit 1
fi
if ! run_pg_suite zorrobpm-rest no; then
  echo "=== FAILED: rest PG suite ==="
  exit 1
fi

echo "=== all PG suites passed ==="
exit 0
