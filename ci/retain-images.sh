#!/usr/bin/env bash
# WO-OPS-5: bounded retention for SHA-tagged runtime images on the runner.
#
# `docker image prune -f` (deploy job) removes only DANGLING images — SHA-tagged
# images accumulate one per push and are never pruned. This script removes old
# tagged images per repository, keeping:
#   - the N most recent builds (RETAIN_IMAGES_KEEP, default 3),
#   - the tags recorded in $DEPLOY_DIR/.current_tag and $DEPLOY_DIR/.previous_tag
#     (rollback restores by tag, so those images MUST survive even if deploy
#     lagged far behind build),
#   - `:latest` (points at the newest build anyway).
# It never touches zorrobpm-app-test:* / zorrobpm-frontend-build:* — those are
# removed by their own jobs. No `prune -a`: we remove ONLY tags of the given
# repository, addressed explicitly.
#
# Why 3: the just-built tag (which the next deploy will run), the previous deploy
# tag (.previous_tag, the rollback target) and one spare build ahead of deploy;
# protected .current_tag/.previous_tag tags survive regardless of the limit.
#
# Usage: retain-images.sh <repository>
# Env:  RETAIN_IMAGES_KEEP (default 3), DEPLOY_DIR (default /opt/zorro-bpm),
#       RETAIN_IMAGES_PROTECT (space-separated extra tags that must survive —
#       the build job passes $IMAGE_TAG here; see below why .current_tag alone
#       cannot protect the just-built tag)
set -euo pipefail

repo="${1:?usage: retain-images.sh <repository>}"
keep="${RETAIN_IMAGES_KEEP:-3}"
deploy_dir="${DEPLOY_DIR:-/opt/zorro-bpm}"

# Tags that must never be removed: currently deployed + rollback target.
protected=""
for f in "$deploy_dir/.current_tag" "$deploy_dir/.previous_tag"; do
  if [ -f "$f" ]; then
    tag="$(cat "$f")"
    [ -n "$tag" ] && protected="$protected $tag"
  fi
done
# HOLD round 2: the tag this very build job just produced must survive too.
# BuildKit cache hits stamp several builds with the SAME CreatedAt, so `sort -r`
# below falls through to reverse-lexicographic order on a random hex-SHA and
# build order stops meaning anything — the cleanup could untag the image it
# just built. `.current_tag` cannot protect it: retention runs in `build`,
# `.current_tag` is written later, in `deploy`. So the caller passes the tag
# explicitly.
for t in ${RETAIN_IMAGES_PROTECT:-}; do
  [ -n "$t" ] && protected="$protected $t"
done
[ -n "$protected" ] && echo "retain-images: protected tags:$protected"

# List this repo's tags, newest first (CreatedAt is ISO-like → lexicographic sort).
# Format: "<CreatedAt>|<Repository>:<Tag>"
mapfile -t entries < <(docker image ls --format '{{.CreatedAt}}|{{.Repository}}:{{.Tag}}' "$repo" | grep -v '<none>' | sort -r)

kept=0
for entry in "${entries[@]}"; do
  full="${entry#*|}"
  tag="${full##*:}"
  case "$tag" in
    latest) continue ;;   # latest always survives
  esac
  if [ "$kept" -lt "$keep" ]; then
    kept=$((kept + 1))
    continue
  fi
  skip=0
  for p in $protected; do
    [ "$tag" = "$p" ] && skip=1
  done
  [ "$skip" -eq 1 ] && { echo "retain-images: keeping protected $full"; continue; }
  echo "retain-images: removing $full (older than keep=$keep, not protected)"
  # P-42: a maintenance script under `set -e` must survive a single element's
  # failure. An image referenced by a container (even a stopped one) cannot be
  # removed — `docker image rm` exits non-zero. Skipping it keeps the cleanup
  # going instead of aborting the whole build job on an unrelated hygiene step.
  docker image rm "$full" || echo "retain-images: in use, skipping $full"
done
