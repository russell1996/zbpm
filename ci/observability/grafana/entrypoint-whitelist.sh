#!/bin/sh
# WO-OBS-6: resolve nginx's actual container IP for GF_AUTH_PROXY_WHITELIST at
# startup instead of a hardcoded/pinned value. Incident 2026-09-14: pinning a
# static IP required declaring an explicit ipam.config.subnet on the shared
# compose network, which conflicted with an unrelated pre-existing docker
# network on the host and took prod down. Docker's embedded DNS resolves the
# `frontend` compose service to whatever IP it was actually assigned THIS run
# -- correct on any host, with no static IP and no per-host .env tuning.
#
# GRAFANA_PROXY_WHITELIST in .env still works as an explicit override for an
# unusual topology; this only fills in when it's unset.
set -e

if [ -z "$GF_AUTH_PROXY_WHITELIST" ]; then
  FRONTEND_IP=$(getent hosts frontend 2>/dev/null | awk '{print $1}' | head -1)
  if [ -z "$FRONTEND_IP" ]; then
    FRONTEND_IP=$(nslookup frontend 2>/dev/null | awk '/^Address: /{print $2}' | tail -1)
  fi
  if [ -z "$FRONTEND_IP" ]; then
    echo "FATAL: cannot resolve 'frontend' service IP for GF_AUTH_PROXY_WHITELIST -- refusing to start with the proxy-auth whitelist unset (would fail closed anyway, but fail loudly here instead of a confusing Grafana-side rejection)" >&2
    exit 1
  fi
  export GF_AUTH_PROXY_WHITELIST="${FRONTEND_IP}/32"
  echo "GF_AUTH_PROXY_WHITELIST resolved to ${GF_AUTH_PROXY_WHITELIST}"
fi

exec /run.sh "$@"
