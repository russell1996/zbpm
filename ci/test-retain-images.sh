#!/usr/bin/env bash
# WO-OPS-5 test: proves ci/retain-images.sh bounded retention on a real docker set.
#
# Builds K+3 SHA-like tagged images, lays out .current_tag/.previous_tag in a temp
# deploy dir, runs the ACTUAL ci/retain-images.sh artifact (G-N: no copy of the
# logic inline), and asserts:
#   - RED: `docker image prune -f` (the pre-fix mechanism) leaves every tagged image
#   - GREEN: after retain-images.sh the newest RETAIN_IMAGES_KEEP survive, the
#     protected .current_tag/.previous_tag tags survive even if older than the limit,
#     the oldest unprotected tag is gone, and :latest is untouched.
# Cleans up the images it built. Requires docker; run from the repo root:
#   bash ci/test-retain-images.sh
set -euo pipefail

REPO="zorrobpm-app-test-ops5"
KEEP=3
TAGS=(sha11111 sha22222 sha33333 sha44444 sha55555 sha66666)
CURRENT="sha33333"      # deployed, older than the newest KEEP → must survive as protected
PREVIOUS="sha22222"     # rollback target, same

WORK="$(mktemp -d)"
trap 'docker image rm -f $(docker image ls --format "{{.Repository}}:{{.Tag}}" "$REPO" | awk -F: "{print \$1\":\"\$2}") >/dev/null 2>&1 || true; rm -rf "$WORK"' EXIT

cat > "$WORK/Dockerfile" <<'EOF'
FROM alpine:3.20
ARG MARK
RUN echo "$MARK" > /mark
EOF

echo "== build $((${#TAGS[@]})) SHA-like images =="
for t in "${TAGS[@]}"; do
  docker build -q --build-arg MARK="$t" -t "$REPO:$t" -f "$WORK/Dockerfile" "$WORK" >/dev/null
  sleep 0.5   # distinct CreatedAt → deterministic ordering
done
[ "$(docker image ls --format '{{.Repository}}:{{.Tag}}' "$REPO" | wc -l)" -eq "${#TAGS[@]}" ] \
  || { echo "FAIL: expected ${#TAGS[@]} images"; exit 1; }

echo "== RED: pre-fix mechanism (prune -f) leaves all tagged images =="
before=$(docker image ls --format '{{.Repository}}:{{.Tag}}' "$REPO" | wc -l)
docker image prune -f >/dev/null
after=$(docker image ls --format '{{.Repository}}:{{.Tag}}' "$REPO" | wc -l)
[ "$before" -eq "$after" ] && [ "$after" -eq "${#TAGS[@]}" ] \
  || { echo "FAIL: prune -f changed tagged count $before -> $after"; exit 1; }
echo "OK: $before -> $after (prune -f does not touch tagged images)"

echo "== GREEN: retain-images.sh keeps newest $KEEP + protected, removes oldest =="
mkdir -p "$WORK/deploy"
echo "$CURRENT" > "$WORK/deploy/.current_tag"
echo "$PREVIOUS" > "$WORK/deploy/.previous_tag"
RETAIN_IMAGES_KEEP="$KEEP" DEPLOY_DIR="$WORK/deploy" bash ci/retain-images.sh "$REPO"

left=$(docker image ls --format '{{.Repository}}:{{.Tag}}' "$REPO" | awk -F: '{print $2}' | sort)
echo "remaining: $left"
for must_have in sha44444 sha55555 sha66666 "$CURRENT" "$PREVIOUS"; do
  echo "$left" | grep -qx "$must_have" || { echo "FAIL: $must_have missing"; exit 1; }
done
echo "$left" | grep -qx "sha11111" && { echo "FAIL: oldest sha11111 still present"; exit 1; }
count=$(echo "$left" | grep -c .)
[ "$count" -eq "$((KEEP + 2))" ] || { echo "FAIL: expected $((KEEP + 2)) images, got $count"; exit 1; }

echo "== latest untouched =="
docker build -q -t "$REPO:latest" -f "$WORK/Dockerfile" --build-arg MARK=latest "$WORK" >/dev/null
RETAIN_IMAGES_KEEP="$KEEP" DEPLOY_DIR="$WORK/deploy" bash ci/retain-images.sh "$REPO"
docker image ls --format '{{.Repository}}:{{.Tag}}' "$REPO" | grep -qx "$REPO:latest" \
  || { echo "FAIL: :latest removed"; exit 1; }
echo "OK: :latest survives"

echo "== nonexistent DEPLOY_DIR (no deploy yet) degrades to pure keep-limit =="
RETAIN_IMAGES_KEEP="$KEEP" DEPLOY_DIR="$WORK/no-such-dir" bash ci/retain-images.sh "$REPO"
left=$(docker image ls --format '{{.Repository}}:{{.Tag}}' "$REPO" | awk -F: '{print $2}' | sort)
[ "$(echo "$left" | grep -c .)" -eq "$((KEEP + 1))" ] \
  || { echo "FAIL: expected $((KEEP + 1)) (3 newest + latest), got: $left"; exit 1; }

echo "PASS: test-retain-images.sh"