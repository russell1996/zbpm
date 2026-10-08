#!/usr/bin/env bash
# WO-REL-69 П.3: self-test for the G2 docs-only carve-out in ci/wo-report-gate.sh.
#
# Why a NEW script (not a section in ci/test-gate-selftest.sh): that script
# drives ci/test-gate.sh (the src/main→test coverage gate) and asserts its exit
# codes — G2 lives in ci/wo-report-gate.sh, a different gate with a different
# CLI (WO-ID + report path, diff via $WO_GATE_BASE). Fixtures asserting G2
# behaviour cannot live there; they live here, next to the gate they pin.
#
# Builds throwaway commits on a temp branch, runs wo-report-gate.sh on a
# fixture report for each, asserts the G2 LINE (not the exit code — G5/G7 and
# friends fail on fixture reports by design; only the G2 verdict is in scope).
#
# Usage: bash ci/test-wo-report-gate-selftest.sh [base-ref]
#   base-ref defaults to HEAD. Temp branch is deleted on exit (trap).

set -euo pipefail

BASE="${1:-HEAD}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GATE="$ROOT/ci/wo-report-gate.sh"

# --- dirty-tree guard (same precedent as ci/test-gate-selftest.sh) -----------
if ! git diff --cached --quiet || ! git diff --quiet; then
  echo "selftest: refusing to run on a dirty tree"
  exit 2
fi

ORIG_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
ORIG_SHA="$(git rev-parse HEAD)"
TMP="wo-gate-selftest-$$"
FAILED=0

cleanup() {
  git reset -q --hard "$ORIG_SHA" 2>/dev/null || true
  git switch -q "$ORIG_BRANCH" 2>/dev/null || git switch -q --detach "$ORIG_SHA" 2>/dev/null || true
  git branch -q -D "$TMP" 2>/dev/null || true
  rm -f /tmp/wo-report-gate-selftest.report.$$.md /tmp/wo-report-gate-selftest.out.$$
}
trap cleanup EXIT

git switch -q -c "$TMP" "$BASE"

commit_all() { # commit_all <message> -> prints new HEAD sha
  # Inline identity so the script works on CI runners (no global git config)
  # and in fresh clones / detached HEAD / empty $HOME (P-40).
  git -c user.name=selftest -c user.email=selftest@local commit -q -m "$1"
  git rev-parse HEAD
}

# Minimal fixture report: PRE-DONE present (G1), NO build evidence at all.
write_report() {
  cat > /tmp/wo-report-gate-selftest.report.$$.md <<'EOF'
# SELFTEST fixture report

## PRE-DONE

fixture report — no build output quoted on purpose.
EOF
}

# expect_g2 <label> <base> <head> <expected: ok|fail> <grep-pattern>
expect_g2() {
  local label="$1" base="$2" head="$3" want="$4" pattern="$5" got=0 out
  set +e
  out="$(WO_GATE_BASE="$base" bash "$GATE" SELFTEST /tmp/wo-report-gate-selftest.report.$$.md 2>&1)"
  printf '%s\n' "$out" > /tmp/wo-report-gate-selftest.out.$$
  set -e
  if [ "$want" = "ok" ]; then
    if printf '%s\n' "$out" | grep -q "OK.*$pattern"; then
      echo "SELFTEST OK      $label (G2 passes as expected)"
    else
      echo "SELFTEST FAILURE $label - expected a G2 OK line matching '$pattern'"
      cat /tmp/wo-report-gate-selftest.out.$$
      FAILED=$((FAILED + 1))
    fi
  else
    if printf '%s\n' "$out" | grep -q "FAIL.*$pattern"; then
      echo "SELFTEST OK      $label (G2 fails as expected)"
    else
      echo "SELFTEST FAILURE $label - expected a G2 FAIL line matching '$pattern'"
      cat /tmp/wo-report-gate-selftest.out.$$
      FAILED=$((FAILED + 1))
    fi
  fi
}

write_report
BASE_SHA="$(git rev-parse "$BASE")"

# F1. docs-only (the WO-ACL-23 shape: root README.md + docs/**) → G2 OK.
printf '%s\n' "" "# WO-REL-69 selftest fixture (docs-only)" >> README.md
git add README.md
mkdir -p docs
printf '%s\n' "# selftest fixture" "" "docs-only probe." > docs/rel69-selftest-note.md
git add docs/rel69-selftest-note.md
head="$(commit_all "selftest: docs-only (README.md + docs/**)")"
expect_g2 "docs-only (README.md + docs/**) -> G2 OK" "$BASE_SHA" "$head" ok "G2 docs-only branch"
BASE_SHA="$head"

# F2. docs + ONE .java → G2 FAIL (full mvn evidence required, report has none).
mkdir -p zorrobpm-engine/src/main/java/com/zorrodev/bpm/selftest
printf '%s\n' "package com.zorrodev.bpm.selftest; // WO-REL-69 selftest fixture" \
  > zorrobpm-engine/src/main/java/com/zorrodev/bpm/selftest/SelftestStub.java
git add zorrobpm-engine/src/main/java/com/zorrodev/bpm/selftest/SelftestStub.java
head="$(commit_all "selftest: docs + one .java")"
expect_g2 "docs + one .java -> G2 FAIL" "$BASE_SHA" "$head" fail "G2 no full"
BASE_SHA="$head"

# F3. docs + compose tweak → G2 FAIL (config is not docs).
printf '%s\n' "# WO-REL-69 selftest fixture (must be reverted by trap)" >> docker-compose.yml
git add docker-compose.yml
head="$(commit_all "selftest: docs + compose")"
expect_g2 "docs + compose -> G2 FAIL" "$BASE_SHA" "$head" fail "unclear what kind of build evidence"

if [ "$FAILED" -ne 0 ]; then
  echo "SELFTEST: $FAILED case(s) FAILED - wo-report-gate.sh G2 regressed."
  exit 1
fi
echo "SELFTEST: ALL PASS - wo-report-gate.sh G2 behaves as specified."
exit 0
