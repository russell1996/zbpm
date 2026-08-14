#!/usr/bin/env bash
# WO-OPS-5 test: proves bounded retention for SHA-tagged runtime images on a real
# docker set, and — in the prune-f mode — that the pre-fix mechanism fails it.
#
# Usage: bash ci/test-retain-images.sh [prune-f|retain|inuse]
#   prune-f  simulate the PRE-FIX mechanism (deploy's `docker image prune -f`) and
#            assert the WO criterion against it — MUST FAIL (this is the RED side).
#   retain   run the ACTUAL ci/retain-images.sh artifact (G-N: no copy of the logic
#            inline) — MUST PASS (GREEN side).
#   inuse    pin one candidate image with a container (P-42): the script must exit 0,
#            keep the pinned image, and STILL remove the other old unprotected image —
#            a maintenance script under `set -e` must survive a single element's
#            failure instead of stopping the whole cleanup.
#
# Builds K+3 SHA-like tagged images, lays out .current_tag/.previous_tag in a temp
# deploy dir, applies the chosen mechanism, then asserts:
#   - the newest RETAIN_IMAGES_KEEP survive,
#   - the protected .current_tag/.previous_tag tags survive even if older than the
#     limit (rollback safety),
#   - the oldest unprotected tag is gone,
#   - :latest is untouched.
# Cleans up the images it built. Requires docker; run from the repo root.
set -euo pipefail

MECH="${1:-retain}"
REPO="zorrobpm-app-test-ops5"
KEEP=3
TAGS=(sha11111 sha22222 sha33333 sha44444 sha55555 sha66666)
CURRENT="sha33333"      # deployed — older than the newest KEEP, must survive as protected
PREVIOUS="sha22222"     # rollback target, same
PINNED="sha44444"       # inuse mode: first removal candidate, pinned by a container

WORK="$(mktemp -d)"
trap 'docker rm -f ops5-pin >/dev/null 2>&1 || true; docker image rm -f $(docker image ls --format "{{.Repository}}:{{.Tag}}" "$REPO" | awk -F: "{print \$1\":\"\$2}") >/dev/null 2>&1 || true; rm -rf "$WORK"' EXIT

cat > "$WORK/Dockerfile" <<'EOF'
FROM alpine:3.20
ARG MARK
RUN echo "$MARK" > /mark
EOF

echo "== build ${#TAGS[@]} SHA-like images =="
for t in "${TAGS[@]}"; do
  docker build -q --build-arg MARK="$t" -t "$REPO:$t" -f "$WORK/Dockerfile" "$WORK" >/dev/null
  sleep 0.5   # distinct CreatedAt → deterministic ordering
done
[ "$(docker image ls --format '{{.Repository}}:{{.Tag}}' "$REPO" | wc -l)" -eq "${#TAGS[@]}" ] \
  || { echo "FAIL: expected ${#TAGS[@]} images"; exit 1; }

mkdir -p "$WORK/deploy"
echo "$CURRENT" > "$WORK/deploy/.current_tag"
echo "$PREVIOUS" > "$WORK/deploy/.previous_tag"

echo "== apply mechanism: $MECH =="
if [ "$MECH" = "prune-f" ]; then
  # PRE-FIX mechanism (deploy job today): `docker image prune -f` — dangling only.
  docker image prune -f >/dev/null
elif [ "$MECH" = "retain" ]; then
  RETAIN_IMAGES_KEEP="$KEEP" DEPLOY_DIR="$WORK/deploy" bash ci/retain-images.sh "$REPO"
elif [ "$MECH" = "inuse" ]; then
  # P-42: pin the FIRST removal candidate with a container (stopped is enough —
  # docker refuses to remove an image a container references). KEEP=2 makes two
  # candidates: sha44444 (pinned) and sha11111 (older, must still be removed).
  # Without `|| true` in retain-images.sh the script dies on sha44444 and sha11111
  # survives too — the cleanup stops at the first in-use image (P-42).
  docker create --name ops5-pin -t "$REPO:$PINNED" >/dev/null
  RETAIN_IMAGES_KEEP=2 DEPLOY_DIR="$WORK/deploy" bash ci/retain-images.sh "$REPO"
else
  echo "usage: $0 [prune-f|retain|inuse]"; exit 2
fi

left=$(docker image ls --format '{{.Repository}}:{{.Tag}}' "$REPO" | awk -F: '{print $2}' | sort)
echo "remaining: $left"

# WO criterion 1: after a series of builds the image count is bounded.
count=$(echo "$left" | grep -c .)
if [ "$MECH" = "inuse" ]; then
  # KEEP=2 (newest) + current/previous protected + 1 pinned = at most 5.
  expected_min="$((2 + 2 + 1))"
  if [ "$count" -gt "$expected_min" ]; then
    echo "FAIL: image count $count exceeds keep=2 + protected + pinned ($expected_min)"
    echo "expected: at most $expected_min images after cleanup, got $count"
    exit 1
  fi
else
  expected_min="$((KEEP + 2))"   # newest KEEP + protected current/previous
  if [ "$count" -gt "$expected_min" ]; then
    echo "FAIL: image count $count exceeds keep=$KEEP + protected ($expected_min)"
    echo "expected: at most $expected_min images after cleanup, got $count"
    exit 1
  fi
fi

# WO criteria 2+3: .previous_tag / .current_tag images must survive.
for must_have in "$PREVIOUS" "$CURRENT"; do
  echo "$left" | grep -qx "$must_have" \
    || { echo "FAIL: protected tag $must_have missing"; exit 1; }
done
echo "OK: protected $PREVIOUS (previous) and $CURRENT (current) survived"

if [ "$MECH" = "retain" ]; then
  # Only the real mechanism may remove the oldest unprotected tag.
  echo "$left" | grep -qx "sha11111" \
    && { echo "FAIL: oldest sha11111 still present after retain"; exit 1; }
  echo "OK: oldest unprotected sha11111 removed"
elif [ "$MECH" = "inuse" ]; then
  # P-42: the pinned image could NOT be removed (docker refuses), so it surviving
  # is expected — but the cleanup must NOT have stopped at it: the older
  # unprotected sha11111 must still be gone. On the pre-fix script `set -e` dies
  # at sha44444 and sha11111 survives too, which this check catches.
  echo "$left" | grep -qx "$PINNED" \
    || { echo "FAIL: pinned $PINNED missing — script removed an image in use?"; exit 1; }
  echo "OK: pinned $PINNED survived (in use, expected)"
  echo "$left" | grep -qx "sha11111" \
    && { echo "FAIL: oldest sha11111 still present — cleanup stopped at in-use $PINNED (P-42)"; exit 1; }
  echo "OK: cleanup continued past in-use image — oldest sha11111 removed"
fi

# :latest must never be touched.
docker build -q -t "$REPO:latest" -f "$WORK/Dockerfile" --build-arg MARK=latest "$WORK" >/dev/null
if [ "$MECH" = "retain" ] || [ "$MECH" = "inuse" ]; then
  # Second pass: idempotency + :latest safety. In inuse mode the pinned image
  # still conflicts (expected) — its stderr noise is silenced here; the first
  # pass above already asserted the P-42 behaviour explicitly.
  if [ "$MECH" = "inuse" ]; then
    RETAIN_IMAGES_KEEP=2 DEPLOY_DIR="$WORK/deploy" bash ci/retain-images.sh "$REPO" >/dev/null 2>&1
  else
    RETAIN_IMAGES_KEEP="$KEEP" DEPLOY_DIR="$WORK/deploy" bash ci/retain-images.sh "$REPO" >/dev/null 2>&1
  fi
fi
docker image ls --format '{{.Repository}}:{{.Tag}}' "$REPO" | grep -qx "$REPO:latest" \
  || { echo "FAIL: :latest removed"; exit 1; }
echo "OK: :latest survives"

echo "PASS: test-retain-images.sh ($MECH mode)"