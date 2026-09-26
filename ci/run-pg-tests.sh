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
#   CI_PIPELINE_ID — when set (GitLab CI), project name gets suffixed so two
#                    parallel pipelines never share one postgres (WO-OPS-11 F26a).

set -euo pipefail

export PG_PORT="${PG_PORT:-55432}"   # non-standard host port: never collide with a host-side PG on 5432 (P-23)
export PG_DB="${PG_DB:-zorrobpm-db}"
export PG_USER="${PG_USER:-zorrodev}"
export PG_PASSWORD="${PG_PASSWORD:-zorrodev}"

# WO-OPS-11 F26: поговаривают, порты тоже фиксированы. PG_PORT уже параметризован
# (CI ставит 55432), но два параллельных pipeline на одном раннере делят и порт, и
# project. Разводим: суффикс по CI_PIPELINE_ID + порт со сдвигом от того же ID.
# Локально (без CI_PIPELINE_ID) поведение побайтово прежнее.
if [ -n "${CI_PIPELINE_ID:-}" ]; then
  SUFFIX="$CI_PIPELINE_ID"
  PROJECT="zbpm-pgci-${SUFFIX}"
  # Сдвиг порта детерминирован от ID, в пределах 55432..57432 (2000 слотов, коллизия
  # двух одновременно бегущих pipeline практически исключена, а выход за диапазон
  # невозможен по построению).
  PG_PORT="$((55432 + (SUFFIX % 2000)))"
  export PG_PORT
else
  PROJECT="zbpm-pgci"
fi

COMPOSE="ci/docker-compose.pg.yml"

# Always tear postgres down — even if tests fail or the script is interrupted —
# so no container/volume/port is leaked onto the shared runner between pipelines.
cleanup() { docker compose -f "$COMPOSE" -p "$PROJECT" down -v >/dev/null 2>&1 || true; }
trap cleanup EXIT

# The zbpm_m2 cache volume is a shared, PERSISTENT named volume (not per-run) --
# whatever UID last wrote into it owns its content. Before every use, force it
# back to the CURRENT invoker's UID: cheap (chown, not a fresh download) and
# idempotent. Without this, a volume first populated by a different UID (e.g.
# root, from a run before --user was added, or a different runner) makes every
# subsequent --user run fail with AccessDeniedException -- caught on real CI
# (pipeline 169520, commit 5de447ce) right after --user was added below.
ensure_m2_ownership() {
  docker run --rm -v zbpm_m2:/tmp/.m2 alpine:3.20 chown -R "$(id -u):$(id -g)" /tmp/.m2
}

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
  ensure_m2_ownership
  set +e
  docker run --rm \
    --user "$(id -u):$(id -g)" \
    --network host \
    -v "${BUILD_DIR:-$(pwd)}":/build -w /build \
    -v zbpm_m2:/tmp/.m2 \
    -e PG_HOST=127.0.0.1 \
    -e PG_PORT="$PG_PORT" \
    -e PG_DB="$PG_DB" \
    -e PG_USER="$PG_USER" \
    -e PG_PASSWORD="$PG_PASSWORD" \
    -e MAVEN_OPTS="${MAVEN_OPTS:--Xmx1g}" \
    maven:3.9.9-eclipse-temurin-21 \
    mvn -B -ntp -Dmaven.repo.local=/tmp/.m2/repository clean verify \
      -pl "$module" \
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
  local rc=$?
  set -e
  echo "=== $module PG suite exited with code $rc ==="
  return $rc
}

# Install rest's DEPENDENCIES and then verify rest INSIDE THE SAME container:
# the container has no persistent ~/.m2, so a separate install step would throw
# the sibling SNAPSHOTs away before the rest suite could resolve them. Rest must
# NOT use -am for verify: that would drag engine failsafe (and its
# HotColumnIndexUsagePgIT ui_users pollution) back into the database the rest
# tests need clean.
run_rest_suite() {
  ensure_m2_ownership
  set +e
  docker run --rm \
    --user "$(id -u):$(id -g)" \
    --network host \
    -v "${BUILD_DIR:-$(pwd)}":/build -w /build \
    -v zbpm_m2:/tmp/.m2 \
    -e PG_HOST=127.0.0.1 \
    -e PG_PORT="$PG_PORT" \
    -e PG_DB="$PG_DB" \
    -e PG_USER="$PG_USER" \
    -e PG_PASSWORD="$PG_PASSWORD" \
    -e MAVEN_OPTS="${MAVEN_OPTS:--Xmx1g}" \
    maven:3.9.9-eclipse-temurin-21 \
    bash -c "mvn -B -ntp -Dmaven.repo.local=/tmp/.m2/repository install -pl zorrobpm-rest -am -DskipTests -q \
      && mvn -B -ntp -Dmaven.repo.local=/tmp/.m2/repository clean verify \
        -pl zorrobpm-rest \
        -Dsurefire.skip=true \
        -Dgroups=pg \
        -Dzbpm.excludedGroups= \
        -Dsurefire.failIfNoSpecifiedTests=false \
        -DPG_HOST=127.0.0.1 \
        -DPG_PORT=$PG_PORT \
        -DPG_DB=$PG_DB \
        -DPG_USER=$PG_USER \
        -DPG_PASSWORD=$PG_PASSWORD"
  local rc=$?
  set -e
  echo "=== zorrobpm-rest PG suite exited with code $rc ==="
  return $rc
}

reset_schema() {
  echo "=== Resetting public schema for the next suite ==="
  docker compose -f "$COMPOSE" -p "$PROJECT" exec -T postgres \
    psql -U "$PG_USER" -d "$PG_DB" -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"
}

echo "=== PG suite 1/2: zorrobpm-engine ==="
if ! run_pg_suite zorrobpm-engine; then
  echo "=== FAILED: engine PG suite ==="
  exit 1
fi

reset_schema

echo "=== PG suite 2/2: zorrobpm-rest ==="
if ! run_rest_suite; then
  echo "=== FAILED: rest PG suite ==="
  exit 1
fi

echo "=== all PG suites passed ==="
exit 0
