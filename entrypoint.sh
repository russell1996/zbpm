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

exec setpriv --reuid=zorrobpm --regid=zorrobpm --init-groups java -jar /app/app.jar "$@"
