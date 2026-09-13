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

exec setpriv --reuid=zorrobpm --regid=zorrobpm --init-groups java -jar /app/app.jar "$@"
