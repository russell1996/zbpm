# Documentation Inventory & Decisions Registry (Mission 0)

> Reviewer: CTO/QA governor. Date: 2026-06-29. Method: every doc read on disk; roadmap claims
> spot-verified against source (V1). One source of truth target: `docs/README.md` (tracker + ONE roadmap).

## Legend
- **KEEP** — accurate, distinct, still needed. **MERGE** — fold useful content into the single source, then drop original. **DROP** — stale / duplicate / superseded / contradicts code.

## Registry

| # | Path | What it is | Date | Actuality | Duplicate / overlap | Decision |
|---|------|-----------|------|-----------|---------------------|----------|
| 1 | `docs/roadmap.md` | Audit-based eng roadmap (T1–T9) | 2026-06-23 | **Current; verified vs code** | superset of CAMUNDA8 plan's open items | **KEEP → becomes canonical roadmap in docs/README.md** |
| 2 | `README.md` (root) | Main project README (user-facing) | 2026-06-23 | Current | — | **KEEP** (verify §"Поддержка BPMN" vs code) |
| 3 | `docs/deployment.md` | Deployment runbook (CI/CD, Docker, prod topology) | 2026-06-21 | Current | — | **KEEP** (operational, distinct) |
| 4 | `docs/camunda8-compatibility.md` | C8 compatibility **matrix** (as-is, per BPMN construct) | 2026-06-22 | Mostly current | conceptual overlap w/ #5 | **KEEP** (Mission 1 as-is reference; resolve timeCycle vs code) |
| 5 | `docs/CAMUNDA8_COMPATIBILITY_PLAN.md` | C8 **backlog** — P0/P1/P2 mostly DONE & merged | 2026-06-22 | Largely historical | open items ⊂ #1; matrix ⊂ #4 | **MERGE** residual open items → roadmap, then **DROP** |
| 6 | `docs/multi-tenant-authorization-plan.md` | Multitenancy design (clients + API keys) | 2026-06-23 | Current (design for T5) | referenced by roadmap T5 | **KEEP** (design doc; link from roadmap) |
| 7 | `docs/frontend-roadmap.md` | Frontend product vision & architecture (3004 lines) | 2026-06-21 | Aspirational; partly stale | **parallel roadmap** (conflicts w/ #1) | **DECISION NEEDED** (keep as vision / trim / fold) |
| 8 | `docs/ui-backend-backlog.md` | UI↔backend backlog (filters ignored, missing GETs) | 2026-06-22 | Partly DONE | overlaps #1, #9 | **MERGE** open items → roadmap (UI track), then **DROP** |
| 9 | `docs/frontend-api-gaps.md` | G1–G10 backend gaps for frontend | 2026-06-21 | Several DONE (G6–G8, DMN) | subset of #8 + .mimocode/backend-gaps.md | **DROP** (superseded by #8) |
| 10 | `.mimocode/plans/1782166325332-tidy-sailor.md` | Full technical audit (667 lines) — SOURCE of #1 | 2026-06-22 | Current as-is analysis | source of roadmap | **KEEP/RELOCATE** → seed of `docs/analysis/` (Mission 1) |
| 11 | `.mimocode/plans/1782072821626-calm-tiger.md` | nginx white-screen fix plan (done) | done | Historical | — | **DROP** (one-off, shipped) |
| 12 | `.mimocode/memory/*.md` (7) | Mimo's operational memory | live | Mimo-owned | — | **KEEP, do not touch** (AI-coder state) |
| 13 | `.mimocode/skills/zbpm-modes/*` | Mimo's mode skill | live | Mimo-owned | — | **KEEP, do not touch** |

## Contradictions — RESOLVED by code-truth (Mission 0.5)
1. **timeCycle**: ✅ RESOLVED — IMPLEMENTED in code (`scheduler/TimerExpressions.java`: ISO `R[n]/<dur>` + Spring cron; wired in `BpmnParseServiceImpl`, `ActivityServiceImpl`, `TimerStartJobExecutor`). The `.mimocode` memory ("not supported") is STALE. Stated correctly in `docs/README.md` §2.
2. **Three roadmaps**: ✅ RESOLVED — consolidated into one in `docs/README.md` §3 (T-track + U-track + C-track). #5 dropped (merged), #7 archived to `_archive/frontend-roadmap-vision.md`, #1 folded.
3. **Filters / single-entity GETs**: ✅ RESOLVED — verified DONE in code: single-entity GET + `/activities` (`QueryContract.java:33-55`), DMN API (`DmnContract`), and timer-jobs + message-subscriptions read (`QueryContract.java:58-61`). Stale gap-docs (#8/#9) dropped; residual frontend wiring captured as U1.

## Execution log (2026-06-29, branch `chore/docs-consolidation`, NOT pushed to master)
- CREATED `docs/README.md` (single tracker + one roadmap, code-verified).
- DROP (git rm): `roadmap.md` (folded), `frontend-api-gaps.md`, `CAMUNDA8_COMPATIBILITY_PLAN.md`, `ui-backend-backlog.md` (residuals merged).
- ARCHIVE (git mv): `frontend-roadmap.md` → `_archive/frontend-roadmap-vision.md` (+ superseded banner).
- KEEP: `README.md` (root), `deployment.md`, `camunda8-compatibility.md`, `multi-tenant-authorization-plan.md`, `_audit/doc-inventory.md`.
- UNTOUCHED: `.mimocode/**` (Mimo-owned operational state). `calm-tiger.md` left in place (Mimo's dir; historical only).

## Branch hygiene
All 18 `feature/*` + `docs/*` branches are `ahead:0` of master (fully merged). Stale — safe to prune; no lost work.
