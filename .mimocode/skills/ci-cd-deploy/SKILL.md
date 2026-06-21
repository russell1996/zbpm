---
description: "ZorroBPM CI/CD: GitLab pipeline, versioned Docker images, deploy and rollback"
---

# CI/CD & Deployment

How ZorroBPM is built, packaged and deployed. Single-host model on a self-hosted GitLab shell runner
(tag `zbpm`) that has Docker + Docker Compose. No external registry — images are built and tagged on the
deploy host, and the previous tag is recorded for rollback.

## Pipeline (`.gitlab-ci.yml`)

```
test  ->  package  ->  deploy  ->  rollback
```

- **test:backend** — `mvn -B -ntp clean verify` inside `maven:3.9.9-eclipse-temurin-21` (full reactor:
  unit + Failsafe integration tests). Publishes the `zorrobpm-ce` jar + JUnit reports.
- **test:frontend** — `npm ci && npm run build` inside `node:22-alpine` (`tsc` typecheck + Vite build).
- **package** — `docker build` backend (`Dockerfile`) and frontend (`zorrobpm-frontend/Dockerfile`),
  each tagged `:$CI_COMMIT_SHORT_SHA` **and** `:latest` (image versioning).
- **deploy** (auto on default branch, manual elsewhere) — copies `.current_tag` to `.previous_tag`, syncs
  sources to `$DEPLOY_DIR=/opt/zorro-bpm` (preserving host `.env` + rollback bookkeeping), then
  `APP_TAG=$SHA FRONTEND_TAG=$SHA docker compose up -d`, writes `.current_tag`.
- **rollback** (manual) — reads `.previous_tag` and `docker compose up -d` with that tag.

Image tags survive `docker image prune -f` (it only removes dangling images), so the rollback target stays
available on the host.

## Topology

- External nginx proxy (`https://zorro.i-smet.kz`) -> `frontend` container (`:${FRONTEND_PORT}` -> :80).
- `frontend` (nginx) serves the SPA and proxies `/api/` -> `app:8080` over the Docker network (service
  name, not an IP).
- `app` (backend) -> `postgres`, `rabbitmq`. Secrets come from the host `.env` (never committed); defaults
  in `.env.example`.

## Manual build / verify (local, mirrors CI)

```powershell
# backend (see mvn-build skill for the green/red parsing)
$env:JAVA_HOME = "C:\Users\1\AppData\Local\Programs\Eclipse Adoptium\jdk-21.0.10"
& "C:\Users\1\AppData\Local\Programs\Apache\Maven\maven-3.9.15\bin\mvn.cmd" clean verify

# frontend
cd zorrobpm-frontend; npm ci; npm run build

# full stack
docker compose build; docker compose up -d; docker compose ps
```

## Key files

| Purpose | Path |
|---|---|
| Pipeline | `.gitlab-ci.yml` |
| Backend image | `Dockerfile` (+ root `.dockerignore`) |
| Frontend image | `zorrobpm-frontend/Dockerfile`, `nginx.conf` |
| Stack | `docker-compose.yml` |
| Config defaults | `.env.example` (host `.env` holds real secrets) |
| Deploy/runbook docs | `docs/deployment.md`, `README.md` |

## Stopping condition

`test` + `package` green, `docker compose up` healthy, deploy reachable at `https://zorro.i-smet.kz`,
rollback path verified (`.previous_tag` present).
