#!/usr/bin/env bash
# WO-REL-69 П.3 + WO-REL-70 part B: self-test for the G2 docs-only carve-out
# and the G24 shadcn-vue check in ci/wo-report-gate.sh.
# WO-OPS-31: self-test for the G25 (LEAK-INVARIANT, CONTRIBUTING.md G-P) and
# G26 (FEEL-PARITY, CONTRIBUTING.md G-Q) report-statement checks.
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
TOTAL=0

cleanup() {
  git reset -q --hard "$ORIG_SHA" 2>/dev/null || true
  git switch -q "$ORIG_BRANCH" 2>/dev/null || git switch -q --detach "$ORIG_SHA" 2>/dev/null || true
  git branch -q -D "$TMP" 2>/dev/null || true
  rm -f /tmp/wo-report-gate-selftest.report.$$.md \
        /tmp/wo-report-gate-selftest.report.$$.plain.md \
        /tmp/wo-report-gate-selftest.report.$$.with.md \
        /tmp/wo-report-gate-selftest.report.$$.na.md \
        /tmp/wo-report-gate-selftest.report.$$.quoted.md \
        /tmp/wo-report-gate-selftest.report.$$.nabare.md \
        /tmp/wo-report-gate-selftest.report.$$.heading.md \
        /tmp/wo-report-gate-selftest.report.$$.table.md \
        /tmp/wo-report-gate-selftest.report.$$.empty.md \
        /tmp/wo-report-gate-selftest.report.$$.bullet.md \
        /tmp/wo-report-gate-selftest.report.$$.draft.md \
        /tmp/wo-report-gate-selftest.report.$$.shortna.md \
        /tmp/wo-report-gate-selftest.report.$$.tilde.md \
        /tmp/wo-report-gate-selftest.report.$$.indented.md \
        /tmp/wo-report-gate-selftest.report.$$.cyr7.md \
        /tmp/wo-report-gate-selftest.report.$$.cyr8.md \
        /tmp/wo-report-gate-selftest.report.$$.fencedmarker.md \
        /tmp/wo-report-gate-selftest.report.$$.tildemarker.md \
        /tmp/wo-report-gate-selftest.report.$$.indentedmarker.md \
        /tmp/wo-report-gate-selftest.report.$$.indentedmulti.md \
        /tmp/wo-report-gate-selftest.report.$$.nashort.md \
        /tmp/wo-report-gate-selftest.report.$$.nadraft.md \
        /tmp/wo-report-gate-selftest.report.$$.indent2.md \
        /tmp/wo-report-gate-selftest.out.$$
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
  TOTAL=$((TOTAL + 1))
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

# expect_g24 <label> <base> <head> <expected: ok|fail> <grep-pattern>
# Same driver as expect_g2, but asserts the G24 verdict line (WO-REL-70).
expect_g24() {
  local label="$1" base="$2" head="$3" want="$4" pattern="$5" out
  TOTAL=$((TOTAL + 1))
  set +e
  out="$(WO_GATE_BASE="$base" bash "$GATE" SELFTEST /tmp/wo-report-gate-selftest.report.$$.md 2>&1)"
  printf '%s\n' "$out" > /tmp/wo-report-gate-selftest.out.$$
  set -e
  if [ "$want" = "ok" ]; then
    if printf '%s\n' "$out" | grep -q "OK.*$pattern"; then
      echo "SELFTEST OK      $label (G24 passes as expected)"
    else
      echo "SELFTEST FAILURE $label - expected a G24 OK line matching '$pattern'"
      cat /tmp/wo-report-gate-selftest.out.$$
      FAILED=$((FAILED + 1))
    fi
  else
    if printf '%s\n' "$out" | grep -q "FAIL.*$pattern"; then
      echo "SELFTEST OK      $label (G24 fails as expected)"
    else
      echo "SELFTEST FAILURE $label - expected a G24 FAIL line matching '$pattern'"
      cat /tmp/wo-report-gate-selftest.out.$$
      FAILED=$((FAILED + 1))
    fi
  fi
}

# expect_marker <label> <base> <head> <expected: ok|fail|skip> <grep-pattern> <report-path>
# WO-OPS-31: asserts the G25 (LEAK-INVARIANT) / G26 (FEEL-PARITY) verdict line.
# "skip" additionally requires that NO FAIL line for that marker was printed — a skipped
# check must not also report a failure, or the case would pass for the wrong reason.
expect_marker() {
  local label="$1" base="$2" head="$3" want="$4" pattern="$5" report="$6" out ln ok=0
  ln="${BASH_LINENO[0]}"
  TOTAL=$((TOTAL + 1))
  set +e
  out="$(WO_GATE_BASE="$base" bash "$GATE" SELFTEST "$report" 2>&1)"
  printf '%s\n' "$out" > /tmp/wo-report-gate-selftest.out.$$
  set -e
  case "$want" in
    ok)
      if printf '%s\n' "$out" | grep -q "OK.*$pattern"; then ok=1; fi
      ;;
    fail)
      if printf '%s\n' "$out" | grep -q "FAIL.*$pattern"; then ok=1; fi
      ;;
    skip)
      if printf '%s\n' "$out" | grep -q "SKIP.*$pattern" \
         && ! printf '%s\n' "$out" | grep -q "FAIL.*$pattern"; then ok=1; fi
      ;;
  esac
  if [ "$ok" -eq 1 ]; then
    echo "SELFTEST OK      $label ($pattern '$want' as expected)"
  else
    echo "SELFTEST FAILURE $label - ci/test-wo-report-gate-selftest.sh:$ln expected a '$want' line matching '$pattern'"
    cat /tmp/wo-report-gate-selftest.out.$$
    FAILED=$((FAILED + 1))
  fi
}

# F16. the "at least 8 characters" rule must count CHARACTERS, not bytes: under LC_ALL=C bash
#      counts bytes, so a 7-character Cyrillic value would measure 14 and slip through. The gate
#      pins a UTF-8 locale itself, so the check must behave the same under LC_ALL=C.
expect_marker_cyr() { # <label> <report> <expected: ok|fail>
  local label="$1" report="$2" want="$3" out ok=0 ln="${BASH_LINENO[0]}"
  TOTAL=$((TOTAL + 1))
  set +e
  out="$(LC_ALL=C WO_GATE_BASE="$BASE_SHA" bash "$GATE" SELFTEST "$report" 2>&1)"
  set -e
  if [ "$want" = "ok" ]; then
    if printf '%s\n' "$out" | grep -q "OK.*G25"; then ok=1; fi
  else
    if printf '%s\n' "$out" | grep -q "FAIL.*G25"; then ok=1; fi
  fi
  if [ "$ok" -eq 1 ]; then
    echo "SELFTEST OK      $label (LC_ALL=C: G25 '$want' as expected)"
  else
    echo "SELFTEST FAILURE $label - ci/test-wo-report-gate-selftest.sh:$ln expected a G25 '$want' line under LC_ALL=C"
    # `|| true`: the gate may print no G25 line at all (e.g. against a gate without G25) — under
    # `set -euo pipefail` a failing grep in this pipeline would abort the whole selftest before
    # the summary, so the failure count would never be printed.
    printf '%s\n' "$out" | grep -E 'G25' | head -3 || true
    FAILED=$((FAILED + 1))
  fi
}

# expect_offbranch_skip <label> <report>
# Off-branch (nothing ahead of the base) the G25/G26 checks have NO diff to judge, and the gate
# must say so instead of evaluating an empty diff. The maintainer security review (WO-OPS-31 HOLD r1) showed
# that the mutation "снятие ON_BRANCH-гейта" stayed GREEN under the old selftest: both the
# off-branch case and the "branch touches no engine file" case printed a SKIP line, so the two
# were indistinguishable. The two are told apart by their WORDING — "off-branch" = there is no
# diff at all, "branch touches no ... file" = a verdict about a real diff.
expect_offbranch_skip() {
  local label="$1" report="$2" out ok=0 ln="${BASH_LINENO[0]}"
  TOTAL=$((TOTAL + 1))
  set +e
  out="$(WO_GATE_BASE="$BASE_SHA" bash "$GATE" SELFTEST "$report" 2>&1)"
  set -e
  if printf '%s\n' "$out" | grep -q "G25/G26 skipped (off-branch" \
     && ! printf '%s\n' "$out" | grep -q "branch touches no BPMN-execution file"; then
    ok=1
  fi
  if [ "$ok" -eq 1 ]; then
    echo "SELFTEST OK      $label (off-branch announcement as expected)"
  else
    echo "SELFTEST FAILURE $label - ci/test-wo-report-gate-selftest.sh:$ln expected the off-branch G25/G26 skip, and NOT the 'branch touches no ... file' skip"
    printf '%s\n' "$out" | grep -E 'G2[56]' | head -3 || true
    FAILED=$((FAILED + 1))
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
BASE_SHA="$head"

# --- WO-REL-70 G24 fixtures (bare UI tags vs shadcn-vue) ----------------------
# Each commit is diffed against its own parent (chained BASE_SHA), so every
# fixture pins exactly one G24 branch.
# F4. added bare <button> in a page .vue → G24 FAIL.
mkdir -p zorrobpm-frontend/src/pages
cat > zorrobpm-frontend/src/pages/SelftestG24Probe.vue <<'EOF'
<template><button class="x" @click="ok">probe</button></template>
EOF
git add zorrobpm-frontend/src/pages/SelftestG24Probe.vue
head="$(commit_all "selftest: bare button in page vue")"
expect_g24 "bare <button> in page .vue -> G24 FAIL" "$BASE_SHA" "$head" fail "G24 added bare UI tag"
BASE_SHA="$head"

# F5. probe deleted (pure delete adds no lines) → G24 N/A.
git rm -q zorrobpm-frontend/src/pages/SelftestG24Probe.vue
head="$(commit_all "selftest: probe deleted")"
expect_g24 "deleted .vue only -> G24 N/A" "$BASE_SHA" "$head" ok "G24 no .vue changes"
BASE_SHA="$head"

# F6. bare <button> but under src/components/ui/** → G24 OK (exempt).
mkdir -p zorrobpm-frontend/src/components/ui
cat > zorrobpm-frontend/src/components/ui/SelftestG24Widget.vue <<'EOF'
<template><button class="x" @click="ok">primitive probe</button></template>
EOF
git add zorrobpm-frontend/src/components/ui/SelftestG24Widget.vue
head="$(commit_all "selftest: bare button under components/ui")"
expect_g24 "bare <button> under components/ui -> G24 OK" "$BASE_SHA" "$head" ok "G24 only src/components/ui"
BASE_SHA="$head"

# F7. bare <input> in pages/ but allowlisted → G24 OK.
cat > zorrobpm-frontend/src/pages/SelftestG24Probe.vue <<'EOF'
<template><input class="x" value="probe"></template>
EOF
printf '%s\n' "zorrobpm-frontend/src/pages/SelftestG24Probe.vue" >> ci/g24-allowlist.txt
git add zorrobpm-frontend/src/pages/SelftestG24Probe.vue ci/g24-allowlist.txt
head="$(commit_all "selftest: allowlisted bare input")"
expect_g24 "allowlisted bare <input> -> G24 OK" "$BASE_SHA" "$head" ok "G24 only src/components/ui"
BASE_SHA="$head"

# F8. hand-made overlay (`fixed inset-0`) in a non-exempt page → G24 FAIL.
cat > zorrobpm-frontend/src/pages/SelftestG24Overlay.vue <<'EOF'
<template><div class="fixed inset-0 z-50">probe overlay</div></template>
EOF
git add zorrobpm-frontend/src/pages/SelftestG24Overlay.vue
head="$(commit_all "selftest: hand-made overlay")"
expect_g24 "fixed inset-0 overlay -> G24 FAIL" "$BASE_SHA" "$head" fail "G24 added bare UI tag"
BASE_SHA="$head"

# F9. overlay removed, allowlist restored, shadcn-only page left → G24 OK.
git rm -q zorrobpm-frontend/src/pages/SelftestG24Overlay.vue \
  zorrobpm-frontend/src/pages/SelftestG24Probe.vue \
  zorrobpm-frontend/src/components/ui/SelftestG24Widget.vue
git checkout "$BASE" -- ci/g24-allowlist.txt 2>/dev/null || true
cat > zorrobpm-frontend/src/pages/SelftestG24Clean.vue <<'EOF'
<script setup>import { Button } from '@/components/ui/button'</script>
<template><Button @click="ok">probe</Button></template>
EOF
git add -A zorrobpm-frontend/src/pages ci/g24-allowlist.txt
head="$(commit_all "selftest: probes removed, shadcn-only page")"
expect_g24 "shadcn-only .vue -> G24 OK" "$BASE_SHA" "$head" ok "G24 no bare UI tags"
BASE_SHA="$head"

# F10. docs-only commit (no .vue at all) → G24 N/A.
git rm -q zorrobpm-frontend/src/pages/SelftestG24Clean.vue
printf '%s\n' "" "# WO-REL-70 selftest fixture (no vue)" >> README.md
git add README.md
head="$(commit_all "selftest: clean page removed, docs-only")"
expect_g24 "no .vue in diff -> G24 N/A" "$BASE_SHA" "$head" ok "G24 no .vue changes"
BASE_SHA="$head"

# --- WO-OPS-31 G25/G26 fixtures (LEAK-INVARIANT / FEEL-PARITY statements) --------
# CONTRIBUTING.md G-P (2026-10-14) and G-Q (2026-10-15) require the REPORT to carry a statement.
# The TRIGGER is the branch diff (git, merge-base) — never the report's prose — so a report
# cannot talk its way into or out of the requirement.
#
# Report fixtures (the diff is the same in a group of cases; only the report changes):
#   plain   — no marker at all
#   with    — both markers as real statements
#   na      — both markers as "n/a — <reason>"
#   quoted  — the markers appear ONLY inside a fenced block (quoted test output)
#   nabare  — "LEAK-INVARIANT: n/a" with no reason after it
cat > /tmp/wo-report-gate-selftest.report.$$.plain.md <<'EOF'
# SELFTEST fixture report (plain)

## PRE-DONE

fixture report — no LEAK-INVARIANT / FEEL-PARITY statement on purpose.
EOF

cat > /tmp/wo-report-gate-selftest.report.$$.with.md <<'EOF'
# SELFTEST fixture report (with statements)

## PRE-DONE

LEAK-INVARIANT: проверено в SelftestLeakProbeIntegrationTests (1/1)

FEEL-PARITY: матрица docs/analysis/feel-iomapping-conformance-matrix.md, 1 кейс match
EOF

cat > /tmp/wo-report-gate-selftest.report.$$.na.md <<'EOF'
# SELFTEST fixture report (n/a with a reason)

## PRE-DONE

LEAK-INVARIANT: n/a — фикстура селфтеста, исполнение BPMN не меняется

FEEL-PARITY: n/a — фикстура селфтеста, вычисление FEEL не меняется
EOF

cat > /tmp/wo-report-gate-selftest.report.$$.quoted.md <<'EOF'
# SELFTEST fixture report (marker only inside a quoted test output)

## PRE-DONE

```
[ERROR]   SelftestLeakProbeIntegrationTests.someCase:42 [LEAK-INVARIANT: no open service task (job) left]
[ERROR]   SelftestLeakProbeIntegrationTests.otherCase:43 [FEEL-PARITY: matrix missing]
```
EOF

cat > /tmp/wo-report-gate-selftest.report.$$.nabare.md <<'EOF'
# SELFTEST fixture report (n/a without a reason)

## PRE-DONE

LEAK-INVARIANT: n/a

FEEL-PARITY: матрица docs/analysis/feel-iomapping-conformance-matrix.md, 1 кейс match
EOF

cat > /tmp/wo-report-gate-selftest.report.$$.heading.md <<'EOF'
# SELFTEST fixture report (statement in a numbered section heading)

## PRE-DONE

### 6. FEEL-PARITY: матрица, оракул, кейсы корня

**6.1 Матрица.** docs/analysis/feel-iomapping-conformance-matrix.md — 1 кейс match.

### 8. LEAK-INVARIANT: проверено в SelftestLeakProbeIntegrationTests (1/1)
EOF

cat > /tmp/wo-report-gate-selftest.report.$$.table.md <<'EOF'
# SELFTEST fixture report (marker only mentioned inside a criteria table)

## PRE-DONE

| # | критерий | статус | где |
|---|---|---|---|
| 8 | `LEAK-INVARIANT:` / `FEEL-PARITY:` строки в отчёте | ✅ | §8 / §6 |
EOF

cat > /tmp/wo-report-gate-selftest.report.$$.empty.md <<'EOF'
# SELFTEST fixture report (marker present, nothing stated)

## PRE-DONE

LEAK-INVARIANT:

FEEL-PARITY: TODO
EOF

cat > /tmp/wo-report-gate-selftest.report.$$.bullet.md <<'EOF'
# SELFTEST fixture report (statement as a bullet and as a checklist item)

## PRE-DONE

- FEEL-PARITY: матрица docs/analysis/feel-iomapping-conformance-matrix.md, 1 кейс match

- [x] LEAK-INVARIANT: проверено в SelftestLeakProbeIntegrationTests (1/1)
EOF

cat > /tmp/wo-report-gate-selftest.report.$$.draft.md <<'EOF'
# SELFTEST fixture report (draft text instead of a statement)

## PRE-DONE

LEAK-INVARIANT: TODO — проверим позже

FEEL-PARITY: TBD
EOF

cat > /tmp/wo-report-gate-selftest.report.$$.shortna.md <<'EOF'
# SELFTEST fixture report (n/a with a short but real reason)

## PRE-DONE

LEAK-INVARIANT: n/a — нет

FEEL-PARITY: n/a — нет
EOF

cat > /tmp/wo-report-gate-selftest.report.$$.tilde.md <<'EOF'
# SELFTEST fixture report (marker only inside a ~~~ fenced block)

## PRE-DONE

~~~
[ERROR]   SelftestLeakProbeIntegrationTests.someCase:42 [LEAK-INVARIANT: no open service task (job) left]
[ERROR]   SelftestLeakProbeIntegrationTests.otherCase:43 [FEEL-PARITY: matrix missing]
~~~
EOF

cat > /tmp/wo-report-gate-selftest.report.$$.indented.md <<'EOF'
# SELFTEST fixture report (marker only inside an indented code block)

## PRE-DONE

    [ERROR]   SelftestLeakProbeIntegrationTests.someCase:42 [LEAK-INVARIANT: no open service task (job) left]
EOF

cat > /tmp/wo-report-gate-selftest.report.$$.cyr7.md <<'EOF'
# SELFTEST fixture report (7 Cyrillic characters after the marker)

## PRE-DONE

LEAK-INVARIANT: провере
EOF

cat > /tmp/wo-report-gate-selftest.report.$$.cyr8.md <<'EOF'
# SELFTEST fixture report (8 Cyrillic characters after the marker)

## PRE-DONE

LEAK-INVARIANT: проверено
EOF

# --- WO-OPS-31 round 4 (maintainer HOLD r1): the marker INSIDE a code block, at the start of a line ---
# The old "quoted" fixture above puts the marker in the MIDDLE of a line ("[ERROR] ... [LEAK-INVARIANT:
# ...]"), so it would not have been counted as a statement even WITHOUT the block-skipping rule —
# which is exactly why the maintainer security review's mutation M2 (remove `inblock { prev_blank = 0; next }`)
# stayed GREEN. These fixtures put the marker at the START of a line inside a block: that line
# IS a statement shape, and only the block rule can reject it.
cat > /tmp/wo-report-gate-selftest.report.$$.fencedmarker.md <<'EOF'
# SELFTEST fixture report (marker at the start of a line inside a fenced block)

## PRE-DONE

```
LEAK-INVARIANT: проверено в SelftestLeakProbeIntegrationTests (1/1)
FEEL-PARITY: матрица docs/analysis/feel-iomapping-conformance-matrix.md, 1 кейс match
```
EOF

cat > /tmp/wo-report-gate-selftest.report.$$.tildemarker.md <<'EOF'
# SELFTEST fixture report (marker at the start of a line inside a ~~~ fenced block)

## PRE-DONE

~~~
LEAK-INVARIANT: проверено в SelftestLeakProbeIntegrationTests (1/1)
FEEL-PARITY: матрица docs/analysis/feel-iomapping-conformance-matrix.md, 1 кейс match
~~~
EOF

cat > /tmp/wo-report-gate-selftest.report.$$.indentedmarker.md <<'EOF'
# SELFTEST fixture report (marker at the start of an indented code-block line)

## PRE-DONE

    LEAK-INVARIANT: проверено в SelftestLeakProbeIntegrationTests (1/1)
    FEEL-PARITY: матрица docs/analysis/feel-iomapping-conformance-matrix.md, 1 кейс match
EOF

cat > /tmp/wo-report-gate-selftest.report.$$.indentedmulti.md <<'EOF'
# SELFTEST fixture report (marker on the SECOND line of an indented code block)

## PRE-DONE

    [ERROR]   SelftestLeakProbeIntegrationTests.someCase:42 [LEAK-INVARIANT: no open service task (job) left]
    LEAK-INVARIANT: проверено в SelftestLeakProbeIntegrationTests (1/1)
    [ERROR]   SelftestLeakProbeIntegrationTests.otherCase:43 [FEEL-PARITY: matrix missing]
    FEEL-PARITY: матрица docs/analysis/feel-iomapping-conformance-matrix.md, 1 кейс match
EOF

cat > /tmp/wo-report-gate-selftest.report.$$.nashort.md <<'EOF'
# SELFTEST fixture report (n/a whose reason says nothing)

## PRE-DONE

LEAK-INVARIANT: n/a — ok

FEEL-PARITY: n/a — ok
EOF

cat > /tmp/wo-report-gate-selftest.report.$$.nadraft.md <<'EOF'
# SELFTEST fixture report (n/a whose reason is a draft)

## PRE-DONE

LEAK-INVARIANT: n/a — TODO

FEEL-PARITY: n/a — TBD
EOF

cat > /tmp/wo-report-gate-selftest.report.$$.indent2.md <<'EOF'
# SELFTEST fixture report (statement indented by TWO spaces — not a CommonMark code block)

## PRE-DONE

  LEAK-INVARIANT: проверено в SelftestLeakProbeIntegrationTests (1/1)

  FEEL-PARITY: матрица docs/analysis/feel-iomapping-conformance-matrix.md, 1 кейс match
EOF

# F11. worker module touched (FEEL/ioMapping/worker semantics), engine untouched:
#      G26 fires, G25 does not.
mkdir -p zorrobpm-http-connector/src/main/java/com/zorrodev/bpm/httpconnector
printf '%s\n' "package com.zorrodev.bpm.httpconnector; // WO-OPS-31 selftest fixture" \
  > zorrobpm-http-connector/src/main/java/com/zorrodev/bpm/httpconnector/SelftestHttpWorkerProbe.java
git add zorrobpm-http-connector/src/main/java/com/zorrodev/bpm/httpconnector/SelftestHttpWorkerProbe.java
head="$(commit_all "selftest: http-connector worker probe")"
expect_marker "worker module, plain report -> G26 FAIL" "$BASE_SHA" "$head" fail "G26" \
  /tmp/wo-report-gate-selftest.report.$$.plain.md
expect_marker "worker module, plain report -> G25 SKIP" "$BASE_SHA" "$head" skip "G25" \
  /tmp/wo-report-gate-selftest.report.$$.plain.md
expect_marker "worker module, report with statements -> G26 OK" "$BASE_SHA" "$head" ok "G26" \
  /tmp/wo-report-gate-selftest.report.$$.with.md
expect_marker "worker module, report with n/a + reason -> G26 OK" "$BASE_SHA" "$head" ok "G26" \
  /tmp/wo-report-gate-selftest.report.$$.na.md
expect_marker "worker module, statement in a numbered heading -> G26 OK" "$BASE_SHA" "$head" ok "G26" \
  /tmp/wo-report-gate-selftest.report.$$.heading.md
expect_marker "worker module, marker only in a criteria table -> G26 FAIL" "$BASE_SHA" "$head" fail "G26" \
  /tmp/wo-report-gate-selftest.report.$$.table.md
expect_marker "worker module, empty value / 'TODO' -> G26 FAIL" "$BASE_SHA" "$head" fail "G26" \
  /tmp/wo-report-gate-selftest.report.$$.empty.md
expect_marker "worker module, statement as bullet/checklist -> G26 OK" "$BASE_SHA" "$head" ok "G26" \
  /tmp/wo-report-gate-selftest.report.$$.bullet.md
expect_marker "worker module, draft text (TODO — …) -> G26 FAIL" "$BASE_SHA" "$head" fail "G26" \
  /tmp/wo-report-gate-selftest.report.$$.draft.md
expect_marker "worker module, 'n/a — нет' (short but real reason) -> G26 OK" "$BASE_SHA" "$head" ok "G26" \
  /tmp/wo-report-gate-selftest.report.$$.shortna.md
expect_marker "worker module, marker only in a ~~~ block -> G26 FAIL" "$BASE_SHA" "$head" fail "G26" \
  /tmp/wo-report-gate-selftest.report.$$.tilde.md
BASE_SHA="$head"

# F12. engine handler touched (BPMN execution) → G25 fires.
mkdir -p zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/handler
printf '%s\n' "package com.zorrodev.bpm.engine.handler; // WO-OPS-31 selftest fixture" \
  > zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/handler/SelftestLeakProbe.java
git add zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/handler/SelftestLeakProbe.java
head="$(commit_all "selftest: engine handler probe")"
expect_marker "engine handler, plain report -> G25 FAIL" "$BASE_SHA" "$head" fail "G25" \
  /tmp/wo-report-gate-selftest.report.$$.plain.md
expect_marker "engine handler, report with statements -> G25 OK" "$BASE_SHA" "$head" ok "G25" \
  /tmp/wo-report-gate-selftest.report.$$.with.md
expect_marker "engine handler, report with n/a + reason -> G25 OK" "$BASE_SHA" "$head" ok "G25" \
  /tmp/wo-report-gate-selftest.report.$$.na.md
expect_marker "engine handler, marker only quoted in a code block -> G25 FAIL" "$BASE_SHA" "$head" fail "G25" \
  /tmp/wo-report-gate-selftest.report.$$.quoted.md
expect_marker "engine handler, 'n/a' without a reason -> G25 FAIL" "$BASE_SHA" "$head" fail "G25" \
  /tmp/wo-report-gate-selftest.report.$$.nabare.md
expect_marker "engine handler, marker only in a criteria table -> G25 FAIL" "$BASE_SHA" "$head" fail "G25" \
  /tmp/wo-report-gate-selftest.report.$$.table.md
expect_marker "engine handler, statement in a numbered heading -> G25 OK" "$BASE_SHA" "$head" ok "G25" \
  /tmp/wo-report-gate-selftest.report.$$.heading.md
expect_marker "engine handler, empty value / 'TODO' -> G25 FAIL" "$BASE_SHA" "$head" fail "G25" \
  /tmp/wo-report-gate-selftest.report.$$.empty.md
expect_marker "engine handler, statement as bullet/checklist -> G25 OK" "$BASE_SHA" "$head" ok "G25" \
  /tmp/wo-report-gate-selftest.report.$$.bullet.md
expect_marker "engine handler, draft text (TODO — …) -> G25 FAIL" "$BASE_SHA" "$head" fail "G25" \
  /tmp/wo-report-gate-selftest.report.$$.draft.md
expect_marker "engine handler, 'n/a — нет' (short but real reason) -> G25 OK" "$BASE_SHA" "$head" ok "G25" \
  /tmp/wo-report-gate-selftest.report.$$.shortna.md
expect_marker "engine handler, marker only in a ~~~ block -> G25 FAIL" "$BASE_SHA" "$head" fail "G25" \
  /tmp/wo-report-gate-selftest.report.$$.tilde.md
expect_marker "engine handler, marker only in an indented code block -> G25 FAIL" "$BASE_SHA" "$head" fail "G25" \
  /tmp/wo-report-gate-selftest.report.$$.indented.md
expect_marker_cyr "LC_ALL=C, 7 Cyrillic chars after the marker -> G25 FAIL" \
  /tmp/wo-report-gate-selftest.report.$$.cyr7.md fail
expect_marker_cyr "LC_ALL=C, 8 Cyrillic chars after the marker -> G25 OK" \
  /tmp/wo-report-gate-selftest.report.$$.cyr8.md ok

BASE_SHA="$head"

# F13. probes removed → the diff of this commit still lists the deleted paths, so the
#      checks keep firing; the NEXT commits are the ones with no trigger at all.
git rm -q zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/handler/SelftestLeakProbe.java \
       zorrobpm-http-connector/src/main/java/com/zorrodev/bpm/httpconnector/SelftestHttpWorkerProbe.java
head="$(commit_all "selftest: probes removed")"
BASE_SHA="$head"

# F14. TEST-only diff: a test file whose name matches the FEEL patterns. G-Q is about a WO that
#      CHANGES FEEL/ioMapping/worker semantics, so a test-only change must NOT demand the line
#      (measured noise: 2 of the last 60 merges fired only because of a test file).
mkdir -p zorrobpm-engine/src/test/java/com/zorrodev/bpm/engine/handler
printf '%s\n' "package com.zorrodev.bpm.engine.handler; // WO-OPS-31 selftest fixture" \
  > zorrobpm-engine/src/test/java/com/zorrodev/bpm/engine/handler/FeelSelftestProbe.java
git add zorrobpm-engine/src/test/java/com/zorrodev/bpm/engine/handler/FeelSelftestProbe.java
head="$(commit_all "selftest: test-only Feel file")"
expect_marker "test-only Feel file -> G26 SKIP" "$BASE_SHA" "$head" skip "G26" \
  /tmp/wo-report-gate-selftest.report.$$.plain.md
expect_marker "test-only Feel file -> G25 SKIP" "$BASE_SHA" "$head" skip "G25" \
  /tmp/wo-report-gate-selftest.report.$$.plain.md
BASE_SHA="$head"

# F15. docs-only diff (no engine/worker file) → both checks are skipped, not failed.
mkdir -p docs
printf '%s\n' "# selftest fixture" "" "docs-only probe (no engine, no worker)." > docs/ops31-selftest-note.md
git add docs/ops31-selftest-note.md
head="$(commit_all "selftest: docs-only, no G25/G26 trigger")"
expect_marker "docs-only diff -> G25 SKIP" "$BASE_SHA" "$head" skip "G25" \
  /tmp/wo-report-gate-selftest.report.$$.plain.md
expect_marker "docs-only diff -> G26 SKIP" "$BASE_SHA" "$head" skip "G26" \
  /tmp/wo-report-gate-selftest.report.$$.plain.md
BASE_SHA="$head"

# --- WO-OPS-31 round 4 (maintainer HOLD r1) ------------------------------------------------------
# Two holes the maintainer security review found in the SELFTEST (not in the gate): a mutation of the gate
# that removed a trigger branch, or a rule of the marker scanner, stayed GREEN because no
# fixture covered that branch. The fixtures below close both holes:
#   * every branch of the G25/G26 triggers gets its OWN file, and each file matches exactly one
#     branch (so a dropped branch is caught);
#   * the marker sits at the START of a line inside a code block (the old fixture had it in the
#     MIDDLE of a line, so the block rule was never exercised);
#   * the off-branch case is announced distinctly from "the branch touches no engine file".
#
# F16. one engine handler file + one FEEL file → BOTH checks fire, so the report fixtures below
#      are judged against a diff that triggers both of them.
mkdir -p zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/support
printf '%s\n' "package com.zorrodev.bpm.engine.handler; // WO-OPS-31 selftest fixture" \
  > zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/handler/SelftestLeakProbe.java
printf '%s\n' "package com.zorrodev.bpm.engine.support; // WO-OPS-31 selftest fixture" \
  > zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/support/FeelMarkerProbe.java
git add zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/handler/SelftestLeakProbe.java \
        zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/support/FeelMarkerProbe.java
head="$(commit_all "selftest: engine handler + FEEL probe (both G25 and G26 fire)")"
expect_marker "engine handler + FEEL, plain report -> G25 FAIL" "$BASE_SHA" "$head" fail "G25" \
  /tmp/wo-report-gate-selftest.report.$$.plain.md
expect_marker "engine handler + FEEL, plain report -> G26 FAIL" "$BASE_SHA" "$head" fail "G26" \
  /tmp/wo-report-gate-selftest.report.$$.plain.md
expect_marker "engine handler + FEEL, report with statements -> G25 OK" "$BASE_SHA" "$head" ok "G25" \
  /tmp/wo-report-gate-selftest.report.$$.with.md
expect_marker "engine handler + FEEL, report with statements -> G26 OK" "$BASE_SHA" "$head" ok "G26" \
  /tmp/wo-report-gate-selftest.report.$$.with.md
# M2 killer: the marker at the START of a line inside a fenced block is quoted output, not a
# statement — the gate must FAIL (and the mutation "remove the block-skipping rule" must redden
# this case).
expect_marker "marker at line start inside a ``` block -> G25 FAIL" "$BASE_SHA" "$head" fail "G25" \
  /tmp/wo-report-gate-selftest.report.$$.fencedmarker.md
expect_marker "marker at line start inside a ``` block -> G26 FAIL" "$BASE_SHA" "$head" fail "G26" \
  /tmp/wo-report-gate-selftest.report.$$.fencedmarker.md
expect_marker "marker at line start inside a ~~~ block -> G25 FAIL" "$BASE_SHA" "$head" fail "G25" \
  /tmp/wo-report-gate-selftest.report.$$.tildemarker.md
expect_marker "marker at line start inside a ~~~ block -> G26 FAIL" "$BASE_SHA" "$head" fail "G26" \
  /tmp/wo-report-gate-selftest.report.$$.tildemarker.md
expect_marker "marker at line start in an indented code block -> G25 FAIL" "$BASE_SHA" "$head" fail "G25" \
  /tmp/wo-report-gate-selftest.report.$$.indentedmarker.md
expect_marker "marker at line start in an indented code block -> G26 FAIL" "$BASE_SHA" "$head" fail "G26" \
  /tmp/wo-report-gate-selftest.report.$$.indentedmarker.md
expect_marker "marker on the second line of an indented code block -> G25 FAIL" "$BASE_SHA" "$head" fail "G25" \
  /tmp/wo-report-gate-selftest.report.$$.indentedmulti.md
expect_marker "marker on the second line of an indented code block -> G26 FAIL" "$BASE_SHA" "$head" fail "G26" \
  /tmp/wo-report-gate-selftest.report.$$.indentedmulti.md
# a statement indented by TWO spaces is not a CommonMark code block — it IS a statement
expect_marker "statement indented by two spaces -> G25 OK" "$BASE_SHA" "$head" ok "G25" \
  /tmp/wo-report-gate-selftest.report.$$.indent2.md
expect_marker "statement indented by two spaces -> G26 OK" "$BASE_SHA" "$head" ok "G26" \
  /tmp/wo-report-gate-selftest.report.$$.indent2.md
# "n/a — ok": the reason says nothing (and the value is long enough to pass the 8-character
# floor) — the n/a branch of the statement checker must catch it
expect_marker "'n/a — ok' (reason says nothing) -> G25 FAIL" "$BASE_SHA" "$head" fail "G25" \
  /tmp/wo-report-gate-selftest.report.$$.nashort.md
expect_marker "'n/a — ok' (reason says nothing) -> G26 FAIL" "$BASE_SHA" "$head" fail "G26" \
  /tmp/wo-report-gate-selftest.report.$$.nashort.md
# "n/a — TODO": the reason is a draft, not a reason
expect_marker "'n/a — TODO' (draft as the reason) -> G25 FAIL" "$BASE_SHA" "$head" fail "G25" \
  /tmp/wo-report-gate-selftest.report.$$.nadraft.md
expect_marker "'n/a — TODO' (draft as the reason) -> G26 FAIL" "$BASE_SHA" "$head" fail "G26" \
  /tmp/wo-report-gate-selftest.report.$$.nadraft.md
BASE_SHA="$head"

# T1. G25 trigger branch `service/impl/` — the file matches NO other branch.
mkdir -p zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/service/impl
printf '%s\n' "package com.zorrodev.bpm.engine.service.impl; // WO-OPS-31 selftest fixture" \
  > zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/service/impl/SelftestServiceImplProbe.java
git add zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/service/impl/SelftestServiceImplProbe.java
head="$(commit_all "selftest: engine service/impl probe")"
expect_marker "engine service/impl -> G25 FAIL" "$BASE_SHA" "$head" fail "G25" \
  /tmp/wo-report-gate-selftest.report.$$.plain.md
expect_marker "engine service/impl -> G25 OK" "$BASE_SHA" "$head" ok "G25" \
  /tmp/wo-report-gate-selftest.report.$$.with.md
expect_marker "engine service/impl -> G26 SKIP" "$BASE_SHA" "$head" skip "G26" \
  /tmp/wo-report-gate-selftest.report.$$.plain.md
BASE_SHA="$head"

# T2. G25 trigger branch `service/db/` — the branch WO-C8-48 needed (see report §9).
mkdir -p zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/service/db
printf '%s\n' "package com.zorrodev.bpm.engine.service.db; // WO-OPS-31 selftest fixture" \
  > zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/service/db/SelftestServiceDbProbe.java
git add zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/service/db/SelftestServiceDbProbe.java
head="$(commit_all "selftest: engine service/db probe")"
expect_marker "engine service/db -> G25 FAIL" "$BASE_SHA" "$head" fail "G25" \
  /tmp/wo-report-gate-selftest.report.$$.plain.md
expect_marker "engine service/db -> G25 OK" "$BASE_SHA" "$head" ok "G25" \
  /tmp/wo-report-gate-selftest.report.$$.with.md
expect_marker "engine service/db -> G26 SKIP" "$BASE_SHA" "$head" skip "G26" \
  /tmp/wo-report-gate-selftest.report.$$.plain.md
BASE_SHA="$head"

# T3. G25 trigger branch `bpmn/` (parsing/validation of the executed model).
mkdir -p zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/bpmn
printf '%s\n' "package com.zorrodev.bpm.engine.bpmn; // WO-OPS-31 selftest fixture" \
  > zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/bpmn/SelftestBpmnProbe.java
git add zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/bpmn/SelftestBpmnProbe.java
head="$(commit_all "selftest: engine bpmn probe")"
expect_marker "engine bpmn -> G25 FAIL" "$BASE_SHA" "$head" fail "G25" \
  /tmp/wo-report-gate-selftest.report.$$.plain.md
expect_marker "engine bpmn -> G25 OK" "$BASE_SHA" "$head" ok "G25" \
  /tmp/wo-report-gate-selftest.report.$$.with.md
expect_marker "engine bpmn -> G26 SKIP" "$BASE_SHA" "$head" skip "G26" \
  /tmp/wo-report-gate-selftest.report.$$.plain.md
BASE_SHA="$head"

# T4. G26 trigger branch `*Feel*.java` (FEEL evaluation/validation).
printf '%s\n' "package com.zorrodev.bpm.engine.support; // WO-OPS-31 selftest fixture" \
  > zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/support/FeelSelftestProbe.java
git add zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/support/FeelSelftestProbe.java
head="$(commit_all "selftest: engine Feel probe")"
expect_marker "engine Feel file -> G26 FAIL" "$BASE_SHA" "$head" fail "G26" \
  /tmp/wo-report-gate-selftest.report.$$.plain.md
expect_marker "engine Feel file -> G26 OK" "$BASE_SHA" "$head" ok "G26" \
  /tmp/wo-report-gate-selftest.report.$$.with.md
expect_marker "engine Feel file -> G25 SKIP" "$BASE_SHA" "$head" skip "G25" \
  /tmp/wo-report-gate-selftest.report.$$.plain.md
BASE_SHA="$head"

# T5. G26 trigger branch `ElementSupport` (io-mapping).
printf '%s\n' "package com.zorrodev.bpm.engine.support; // WO-OPS-31 selftest fixture" \
  > zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/support/ElementSupport.java
git add zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/support/ElementSupport.java
head="$(commit_all "selftest: engine ElementSupport probe")"
expect_marker "engine ElementSupport file -> G26 FAIL" "$BASE_SHA" "$head" fail "G26" \
  /tmp/wo-report-gate-selftest.report.$$.plain.md
expect_marker "engine ElementSupport file -> G26 OK" "$BASE_SHA" "$head" ok "G26" \
  /tmp/wo-report-gate-selftest.report.$$.with.md
expect_marker "engine ElementSupport file -> G25 SKIP" "$BASE_SHA" "$head" skip "G25" \
  /tmp/wo-report-gate-selftest.report.$$.plain.md
BASE_SHA="$head"

# T6. G26 trigger branch `*ScriptService*.java` (FEEL script execution).
printf '%s\n' "package com.zorrodev.bpm.engine.service; // WO-OPS-31 selftest fixture" \
  > zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/service/ScriptServiceSelftestProbe.java
git add zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/service/ScriptServiceSelftestProbe.java
head="$(commit_all "selftest: engine ScriptService probe")"
expect_marker "engine ScriptService file -> G26 FAIL" "$BASE_SHA" "$head" fail "G26" \
  /tmp/wo-report-gate-selftest.report.$$.plain.md
expect_marker "engine ScriptService file -> G26 OK" "$BASE_SHA" "$head" ok "G26" \
  /tmp/wo-report-gate-selftest.report.$$.with.md
expect_marker "engine ScriptService file -> G25 SKIP" "$BASE_SHA" "$head" skip "G25" \
  /tmp/wo-report-gate-selftest.report.$$.plain.md
BASE_SHA="$head"

# T7. G26 trigger branch `*Worker*.java` (worker classes outside the two worker modules).
mkdir -p zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/worker
printf '%s\n' "package com.zorrodev.bpm.engine.worker; // WO-OPS-31 selftest fixture" \
  > zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/worker/SelftestWorkerProbe.java
git add zorrobpm-engine/src/main/java/com/zorrodev/bpm/engine/worker/SelftestWorkerProbe.java
head="$(commit_all "selftest: engine Worker probe")"
expect_marker "engine Worker file -> G26 FAIL" "$BASE_SHA" "$head" fail "G26" \
  /tmp/wo-report-gate-selftest.report.$$.plain.md
expect_marker "engine Worker file -> G26 OK" "$BASE_SHA" "$head" ok "G26" \
  /tmp/wo-report-gate-selftest.report.$$.with.md
expect_marker "engine Worker file -> G25 SKIP" "$BASE_SHA" "$head" skip "G25" \
  /tmp/wo-report-gate-selftest.report.$$.plain.md
BASE_SHA="$head"

# T8. G26 trigger branch `zorrobpm-job-handler-spring-boot-starter/src/main/**` (the file name
#      matches NO generic branch, so this case pins the module branch alone).
mkdir -p zorrobpm-job-handler-spring-boot-starter/src/main/java/com/zorrodev/bpm/handler
printf '%s\n' "package com.zorrodev.bpm.handler; // WO-OPS-31 selftest fixture" \
  > zorrobpm-job-handler-spring-boot-starter/src/main/java/com/zorrodev/bpm/handler/SelftestStarterProbe.java
git add zorrobpm-job-handler-spring-boot-starter/src/main/java/com/zorrodev/bpm/handler/SelftestStarterProbe.java
head="$(commit_all "selftest: job-handler starter probe")"
expect_marker "job-handler starter module -> G26 FAIL" "$BASE_SHA" "$head" fail "G26" \
  /tmp/wo-report-gate-selftest.report.$$.plain.md
expect_marker "job-handler starter module -> G26 OK" "$BASE_SHA" "$head" ok "G26" \
  /tmp/wo-report-gate-selftest.report.$$.with.md
expect_marker "job-handler starter module -> G25 SKIP" "$BASE_SHA" "$head" skip "G25" \
  /tmp/wo-report-gate-selftest.report.$$.plain.md
BASE_SHA="$head"

# T9. G26 trigger branch `zorrobpm-http-connector/src/main/**` (the file name matches NO generic
#      branch, so this case pins the module branch alone).
mkdir -p zorrobpm-http-connector/src/main/java/com/zorrodev/bpm/httpconnector
printf '%s\n' "package com.zorrodev.bpm.httpconnector; // WO-OPS-31 selftest fixture" \
  > zorrobpm-http-connector/src/main/java/com/zorrodev/bpm/httpconnector/SelftestConnectorProbe.java
git add zorrobpm-http-connector/src/main/java/com/zorrodev/bpm/httpconnector/SelftestConnectorProbe.java
head="$(commit_all "selftest: http-connector probe")"
expect_marker "http-connector module -> G26 FAIL" "$BASE_SHA" "$head" fail "G26" \
  /tmp/wo-report-gate-selftest.report.$$.plain.md
expect_marker "http-connector module -> G26 OK" "$BASE_SHA" "$head" ok "G26" \
  /tmp/wo-report-gate-selftest.report.$$.with.md
expect_marker "http-connector module -> G25 SKIP" "$BASE_SHA" "$head" skip "G25" \
  /tmp/wo-report-gate-selftest.report.$$.plain.md
BASE_SHA="$head"

# F17. off-branch (nothing ahead of the base): the checks have no diff to judge and must SAY so.
#      BASE_SHA == HEAD here, so the gate sees an empty range.
expect_offbranch_skip "off-branch (nothing ahead of the base) -> G25/G26 announced as off-branch" \
  /tmp/wo-report-gate-selftest.report.$$.plain.md

if [ "$FAILED" -ne 0 ]; then
  echo "SELFTEST: $FAILED case(s) FAILED - wo-report-gate.sh G2/G24/G25/G26 regressed."
  echo "Tests run: $TOTAL, Failures: $FAILED, Errors: 0"
  exit 1
fi
echo "SELFTEST: ALL PASS - wo-report-gate.sh G2/G24/G25/G26 behave as specified."
echo "Tests run: $TOTAL, Failures: 0, Errors: 0"
exit 0
