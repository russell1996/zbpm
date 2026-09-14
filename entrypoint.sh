#!/bin/sh
# WO-SEC-50: container starts as root ONLY to fix ownership of the files volume (which may
# already exist and be root-owned from before this change — a fresh named volume inherits the
# image's ownership on first mount, but an EXISTING one on a deployed host does not), then drops
# to the unprivileged `zorrobpm` user for the actual JVM process via setpriv (exec, not
# fork+wait, so signals still reach the java process directly for graceful shutdown).
set -e

FILES_DIR="${APP_FILES_DIR:-/app/files}"
mkdir -p "$FILES_DIR"
chown -R zorrobpm:zorrobpm "$FILES_DIR"

# WO-REL-23's logback-spring.xml added a RollingFileAppender writing to logs/app.log
# (relative to /app, the JVM's WORKDIR) -- /app itself is root-owned (image build runs as
# root, only app.jar is chowned), so the unprivileged zorrobpm user this script drops to
# below cannot create the logs/ directory and the JVM crashes at startup. Same fix as
# FILES_DIR: create + chown before dropping privileges. Caught live 2026-09-13 -- every
# deploy since 3a7c38b0 crashed immediately (FileNotFoundException: logs/app.log).
LOGS_DIR="${APP_LOGS_DIR:-/app/logs}"
mkdir -p "$LOGS_DIR"
chown -R zorrobpm:zorrobpm "$LOGS_DIR"

# WO-OBS-6: scrape-token file (bind-mounted from ci/observability/scrape-token by the
# observability overlay; deploy guarantees a placeholder so Docker never creates a
# directory here). The JVM below runs as zorrobpm (uid/gid 10001, supplementary groups
# RESET by setpriv --init-groups — so a docker group_add membership would NOT survive
# to the JVM), and Prometheus runs as uid/gid 65534 (verified live in
# prom/prometheus:v2.55.1: uid=65534(nobody) gid=65534(nogroup)).
# Sharing is arranged WITHOUT process group games and WITHOUT world-readable bits:
# owner 10001 (the JVM writes as OWNER — group-write was proven DENIED on this host
# for non-owners despite matching gid, host LSM quirk outside this WO's scope) and
# group 65534 + 640 (Prometheus reads as GROUP member — proven live). Deploy's
# placeholder shape (65534:65534/400) is adjusted here on every app start; deploy
# re-applies its own shape on the next deploy — both idempotent, convergent.
# Java only truncates content, which preserves owner/group/mode. Missing/non-regular
# path (no overlay, or Docker created a directory) → skip silently; the JVM logs loudly.
SCRAPE_TOKEN_FILE="${SCRAPE_TOKEN_FILE:-/etc/prometheus/scrape-token}"
if [ -f "$SCRAPE_TOKEN_FILE" ]; then
  chown 10001:65534 "$SCRAPE_TOKEN_FILE"
  chmod 640 "$SCRAPE_TOKEN_FILE"
fi

exec setpriv --reuid=zorrobpm --regid=zorrobpm --init-groups java -jar /app/app.jar "$@"
