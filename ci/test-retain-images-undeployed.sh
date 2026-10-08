#!/usr/bin/env bash
# WO-REL-67 criterion 1: a tag that was BUILT but NOT YET DEPLOYED (journaled
# after .current_tag) must survive retention whatever the CreatedAt ties say.
#
# Incident 2026-10-08 (pipeline 176701 / nightly 176722): frontend tags shared
# ONE CreatedAt (BuildKit cache hit); the nightly `sort -r` fell through to
# reverse-lexicographic hex-SHA order and untagged 5f2d85a6 — built, not yet
# deployed — so `docker compose up` found no image.
#
# Fixture (the cache-hit state): 6 tags on ONE image (single CreatedAt),
# .current_tag mid-list, and the build-order journal putting the lex-SMALLEST
# tag LAST (newest build, not yet deployed). The pre-fix script removes it
# (RED); the fixed script keeps it via the journal (GREEN).
#
# The REAL ci/retain-images.sh is executed (G-N: no copy of the logic inline).
# SCRIPT_UNDER_TEST override exists ONLY to replay the pre-fix artifact for the
# RED side of the POF (a pristine `git show HEAD:ci/retain-images.sh` copy);
# in CI the harness always runs against the real script and must be GREEN.
#
# Usage: bash ci/test-retain-images-undeployed.sh
# Requires docker; run from the repo root.
set -euo pipefail

SCRIPT="${SCRIPT_UNDER_TEST:-ci/retain-images.sh}"
REPO="zorrobpm-app-test-rel67"
TAGS=(sha00000 sha11111 sha22222 sha33333 sha44444 sha55555)
CURRENT="sha44444"      # deployed — protected as today
PREVIOUS="sha22222"     # rollback target — protected as today
UNDEPLOYED="sha11111"   # newest build (journal last), lex-smallest: old sort kills it
GONE_OLD="sha00000"     # genuinely old (journal first, older than current): must go
KEEP=3

WORK="$(mktemp -d)"
trap 'docker image rm -f $(docker image ls --format "{{.Repository}}:{{.Tag}}" "$REPO" 2>/dev/null | awk -F: "{print \$1\":\"\$2}") >/dev/null 2>&1 || true; rm -rf "$WORK"' EXIT

cat > "$WORK/Dockerfile" <<'EOF'
FROM alpine:3.20
RUN echo rel67 > /mark
EOF

echo "== build one image, retag to ${#TAGS[@]} tags (cache-hit state) =="
docker build -q -t "$REPO:${TAGS[0]}" -f "$WORK/Dockerfile" "$WORK" >/dev/null
for t in "${TAGS[@]:1}"; do
  docker tag "$REPO:${TAGS[0]}" "$REPO:$t"
done
docker tag "$REPO:${TAGS[0]}" "$REPO:latest"

# Prove the premise: all tags share ONE CreatedAt (that is what breaks sort -r).
times="$(docker image ls --format '{{.CreatedAt}}' "$REPO" | sort -u | wc -l)"
[ "$times" -eq 1 ] || { echo "FAIL: premise broken — expected 1 distinct CreatedAt, got $times"; exit 1; }
echo "OK: all ${#TAGS[@]} tags share a single CreatedAt (cache-hit state)"

mkdir -p "$WORK/deploy"
echo "$CURRENT" > "$WORK/deploy/.current_tag"
echo "$PREVIOUS" > "$WORK/deploy/.previous_tag"
# Build-order journal, oldest first. Same derivation as ci/retain-images.sh:
# $DEPLOY_DIR/.image-build-order-<repo with [^A-Za-z0-9_-] mapped to _>.
journal="$WORK/deploy/.image-build-order-$(printf '%s' "$REPO" | tr -c 'A-Za-z0-9_-' '_')"
printf '%s\n' sha00000 sha22222 sha44444 sha55555 sha33333 sha11111 > "$journal"
echo "OK: journal newest-first-tail = $UNDEPLOYED (built after $CURRENT, not deployed)"

echo "== run $SCRIPT =="
rc=0
RETAIN_IMAGES_KEEP="$KEEP" DEPLOY_DIR="$WORK/deploy" bash "$SCRIPT" "$REPO" || rc=$?
echo "script exit: $rc"
[ "$rc" -eq 0 ] || { echo "FAIL: script exited $rc (P-42: must survive single-element failure)"; exit 1; }

left=$(docker image ls --format '{{.Repository}}:{{.Tag}}' "$REPO" | awk -F: '{print $2}' | sort)
echo "remaining: $left"

# WO-REL-67 invariant: built-but-not-deployed survives ANY CreatedAt tie order.
echo "$left" | grep -qx "$UNDEPLOYED" \
  || { echo "FAIL: built-not-deployed tag $UNDEPLOYED removed — expected:<kept> but was:<removed> (incident 2026-10-08)"; exit 1; }
echo "OK: built-not-deployed $UNDEPLOYED survived"

for must_have in "$PREVIOUS" "$CURRENT" latest; do
  echo "$left" | grep -qx "$must_have" \
    || { echo "FAIL: protected tag $must_have missing — expected:<kept> but was:<removed>"; exit 1; }
done
echo "OK: protected $PREVIOUS (previous), $CURRENT (current) and latest survived"

echo "$left" | grep -qx "$GONE_OLD" \
  && { echo "FAIL: genuinely old $GONE_OLD still present — expected:<removed> but was:<kept>"; exit 1; }
echo "OK: genuinely old unprotected $GONE_OLD removed (cleanup not disabled)"

echo "Tests run: 1, Failures: 0"
echo "PASS: test-retain-images-undeployed.sh"
