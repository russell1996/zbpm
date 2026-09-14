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
# directory here). The JVM below runs as zorrobpm (uid 10001) with supplementary
# groups RESET by setpriv --init-groups — so a docker group_add membership would NOT
# survive to the JVM, and the file must stay readable by Prometheus (uid 65534).
# Sharing is arranged via the FILE's group instead of process memberships:
# owner stays 65534 (deploy convention — Prometheus reads as OWNER, zero assumptions
# about its groups, prometheus service untouched), group zorrobpm (10001) + 640, so
# the JVM writes as group member. Prometheus never needs group bits (it IS the owner).
# Java only truncates content, which preserves owner/group/mode — no chmod/chown here
# would survive a rewrite anyway, and none is needed. Missing/non-regular path
# (no overlay, or Docker created a directory) → skip silently; the JVM logs loudly.
SCRAPE_TOKEN_FILE="${SCRAPE_TOKEN_FILE:-/etc/prometheus/scrape-token}"
if [ -f "$SCRAPE_TOKEN_FILE" ]; then
  chgrp zorrobpm "$SCRAPE_TOKEN_FILE"
  chmod 640 "$SCRAPE_TOKEN_FILE"
fi

exec setpriv --reuid=zorrobpm --regid=zorrobpm --init-groups java -jar /app/app.jar "$@"
