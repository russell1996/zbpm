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
# Mode `plural` (red-team round 1, BLOCKER-1): FIVE tags journaled after
# .current_tag — MORE than keep=3. The single-undeployed fixture above passes
# on journal-rank sorting alone and never executes the `built-not-deployed`
# protection block (ci/retain-images.sh:84-93); the mutant that kills that
# block stays GREEN on it. The plural fixture pins the block: only it holds
# the undeployed tags outside the keep window (sha22222/sha11111).
#
# The REAL ci/retain-images.sh is executed (G-N: no copy of the logic inline).
# SCRIPT_UNDER_TEST override exists ONLY to replay the pre-fix artifact for the
# RED side of the POF (a pristine `git show master:ci/retain-images.sh` copy);
# in CI the harness always runs against the real script and must be GREEN.
#
# Usage: bash ci/test-retain-images-undeployed.sh [single|plural]
# Requires docker; run from the repo root.
set -euo pipefail

MODE="${1:-single}"
SCRIPT="${SCRIPT_UNDER_TEST:-ci/retain-images.sh}"
REPO="zorrobpm-app-test-rel67"
TAGS=(sha00000 sha11111 sha22222 sha33333 sha44444 sha55555)
KEEP=3

if [ "$MODE" = "plural" ]; then
  CURRENT="sha00000"      # deployed long ago — everything else is newer
  UNDEPLOYED_ALL="sha11111 sha22222 sha33333 sha44444 sha55555"  # 5 > keep=3
  LEGACY="shaold00"       # same image, NOT journaled (rank 0 = legacy): must go
else
  CURRENT="sha44444"      # deployed — protected as today
  PREVIOUS="sha22222"     # rollback target, same
  UNDEPLOYED="sha11111"   # newest build (journal last), lex-smallest: old sort kills it
  GONE_OLD="sha00000"     # genuinely old (journal first, older than current): must go
fi

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
if [ "$MODE" = "plural" ]; then
  docker tag "$REPO:${TAGS[0]}" "$REPO:$LEGACY"
fi

# Prove the premise: all tags share ONE CreatedAt (that is what breaks sort -r).
times="$(docker image ls --format '{{.CreatedAt}}' "$REPO" | sort -u | wc -l)"
[ "$times" -eq 1 ] || { echo "FAIL: premise broken — expected 1 distinct CreatedAt, got $times"; exit 1; }
echo "OK: all ${#TAGS[@]} tags share a single CreatedAt (cache-hit state)"

mkdir -p "$WORK/deploy"
echo "$CURRENT" > "$WORK/deploy/.current_tag"
if [ "$MODE" = "plural" ]; then
  # No .previous_tag (retention runs in `build`, deploy writes it later) and
  # FIVE undeployed tags — more than keep: the keep window alone cannot hold
  # them, only the built-not-deployed block (retain-images.sh:84-93) can.
  journal="$WORK/deploy/.image-build-order-$(printf '%s' "$REPO" | tr -c 'A-Za-z0-9_-' '_')"
  printf '%s\n' sha00000 sha11111 sha22222 sha33333 sha44444 sha55555 > "$journal"
  echo "OK: journal holds 5 tags newer than current $CURRENT (keep=$KEEP)"
else
  echo "$PREVIOUS" > "$WORK/deploy/.previous_tag"
  # Build-order journal, oldest first. Same derivation as ci/retain-images.sh:
  # $DEPLOY_DIR/.image-build-order-<repo with [^A-Za-z0-9_-] mapped to _>.
  journal="$WORK/deploy/.image-build-order-$(printf '%s' "$REPO" | tr -c 'A-Za-z0-9_-' '_')"
  printf '%s\n' sha00000 sha22222 sha44444 sha55555 sha33333 sha11111 > "$journal"
  echo "OK: journal newest-first-tail = $UNDEPLOYED (built after $CURRENT, not deployed)"
fi

echo "== run $SCRIPT =="
rc=0
RETAIN_IMAGES_KEEP="$KEEP" DEPLOY_DIR="$WORK/deploy" bash "$SCRIPT" "$REPO" || rc=$?
echo "script exit: $rc"
[ "$rc" -eq 0 ] || { echo "FAIL: script exited $rc (P-42: must survive single-element failure)"; exit 1; }

left=$(docker image ls --format '{{.Repository}}:{{.Tag}}' "$REPO" | awk -F: '{print $2}' | sort)
echo "remaining: $left"

if [ "$MODE" = "plural" ]; then
  # WO-REL-67 invariant, plural form: EVERY tag journaled after .current_tag
  # survives even though there are MORE of them than keep. The keep window
  # holds only the 3 newest (sha55555/sha44444/sha33333); sha22222/sha11111
  # survive ONLY via the built-not-deployed block — kill that block and this
  # fails (RED), which is exactly the red-team round-1 mutation.
  for u in $UNDEPLOYED_ALL; do
    echo "$left" | grep -qx "$u" \
      || { echo "FAIL: built-not-deployed tag $u removed — expected:<kept> but was:<removed> (plural-undeployed, BLOCKER-1)"; exit 1; }
  done
  echo "OK: all 5 built-not-deployed tags survived (more than keep=$KEEP)"
  echo "$left" | grep -qx "$CURRENT" \
    || { echo "FAIL: deployed tag $CURRENT missing — expected:<kept> but was:<removed>"; exit 1; }
  echo "$left" | grep -qx "$LEGACY" \
    && { echo "FAIL: unjournaled legacy $LEGACY still present — expected:<removed> but was:<kept>"; exit 1; }
  echo "OK: deployed $CURRENT survived, unjournaled legacy $LEGACY removed (cleanup not disabled)"
  echo "Tests run: 1, Failures: 0"
  echo "PASS: test-retain-images-undeployed.sh (plural mode)"
  exit 0
fi

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
echo "PASS: test-retain-images-undeployed.sh (single mode)"
