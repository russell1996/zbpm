---
description: "Merge feature branch into master with conventional commit message and optional push"
---

# Git Feature Merge

Standardized workflow for merging completed feature branches into master in the ZorroBPM project.

## Pre-flight

```powershell
cd "c:\Users\1\vscode\projects\zorro\zbpm"
git status
git log --oneline -5
```

Confirm feature branch is committed and all tests pass (`mvn-build` command).

## Merge to Master

```powershell
cd "c:\Users\1\vscode\projects\zorro\zbpm"
git checkout master
git pull --ff-only origin master
git merge --no-ff <feature-branch> -m "Merge: <descriptive title>

<1-2 sentence summary of what was added/fixed>

BPMN-<number>, BPMN-<number>: <short element names>"
git log --oneline -4
```

### Commit Message Convention

The merge message format used in this project:

```
Merge: <concise feature name>

<optional 1-2 line description>

BPMN-<id>: <element name>, BPMN-<id>: <element name>
```

Examples from trajectory:
- `Merge: BPMN execution correctness (single-instance reliability) + RabbitMQ DLQ (MR !1)`
- `Merge: executable message and timer start events (BPMN-12, BPMN-13)`
- `Merge: BPMN-24/25 send and receive tasks`
- `Merge: BPMN coverage expansion (event definitions, error handling, timer/message boundaries)`

## Push to Origin

```powershell
git push origin master
```

## Create GitLab MR (before merge, alternative flow)

If using GitLab MR flow instead of local merge:

```powershell
git push -u origin <feature-branch> `
  -o merge_request.create `
  -o merge_request.target=master `
  -o merge_request.title="<Merge: description>" `
  -o merge_request.remove_source_branch
```

## Cleanup

```powershell
git branch -d <feature-branch>
```

## Error Handling

- `git pull --ff-only` fails → remote master has diverged; do `git pull --rebase origin master` first
- `git merge` conflicts → resolve, then `git add .` + `git commit` + re-run push
- `git push` fails → check if remote is ahead, pull first
