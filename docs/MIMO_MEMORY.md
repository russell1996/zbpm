# MiMo Memory — ZorroBPM Project

## Memory System

### Files
| File | Purpose | Location |
|---|---|---|
| `project-memory.md` | Full architecture reference | `.mimocode/memory/` (local) |
| `project-rules.md` | All constraints and rules | `.mimocode/memory/` (local) |
| `project-workflow.md` | Workflows, modes, startup/close | `.mimocode/memory/` (local) |
| `project-map.md` | Complete file structure | `.mimocode/memory/` (local) |
| `project-history.md` | Project timeline | `.mimocode/memory/` (local) |
| `current-focus.md` | Current state and next steps | `.mimocode/memory/` (local) |
| `backend-gaps.md` | API gaps for frontend | `.mimocode/memory/` (local) |
| `MIMO_MEMORY.md` | This file (tracked summary) | `docs/` (git-tracked) |

### Startup Workflow (MANDATORY)
Read ALL memory files before any task:
1. `docs/MIMO_MEMORY.md`
2. `.mimocode/memory/project-memory.md`
3. `.mimocode/memory/project-rules.md`
4. `.mimocode/memory/project-workflow.md`
5. `.mimocode/memory/project-map.md`
6. `.mimocode/memory/current-focus.md`
7. `.mimocode/memory/backend-gaps.md`

### Session Close Workflow (MANDATORY)
Before ending work:
1. Update `current-focus.md`
2. Update `project-memory.md` if architecture changed
3. Update `project-history.md` if significant
4. Update `backend-gaps.md` if new gaps
5. Update `project-rules.md` if new rules
6. Update `docs/MIMO_MEMORY.md`
7. Git commit

## Modes

### Frontend Mode
- Scope: `zorrobpm-frontend/**`, `docs/**`, `README.md`
- Forbidden: all backend, CI/CD, Docker
- Verify: `npm run build`

### Backend Mode
- Scope: `zorrobpm-rest/**`, `zorrobpm-rabbitmq/**`, `zorrobpm-client/**`
- Careful: `zorrobpm-contract/**`
- Verify: `mvn clean verify`

### Engine Mode
- Scope: `zorrobpm-engine/**`, `zorrobpm-contract/**`
- Verify: `mvn clean verify` + integration tests

### Architecture Mode
- Scope: any module
- Requires: user approval

## Frontend Default Rule
Frontend tasks MUST NOT modify backend without explicit permission.
If API missing → `docs/BACKEND_REQUIRED.md`

## Project
ZorroBPM CE v0.7.17-SNAPSHOT
Spring Boot 4.0.5 / Java 21 / PostgreSQL / Vue 3 + Vite 8

## Current State
See `.mimocode/memory/current-focus.md` for current focus and pending work.
