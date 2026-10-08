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

# WO-REL-67: build-order journal. `sort -r` on CreatedAt ties (BuildKit cache
# hits stamp several tags with the SAME CreatedAt — incident 2026-10-08: the
# nightly run untagged 5f2d85a6, built but not yet deployed) falls through to
# reverse-lexicographic hex-SHA order, i.e. random. The journal
# $DEPLOY_DIR/.image-build-order-<repo> (one tag per line, oldest first,
# appended AND trimmed by the build job) is the source of truth for "newest".
# Tags absent from the journal (legacy: built before the journal existed) sort
# below every journaled tag, ordered among themselves by CreatedAt as before.
journal_file="$deploy_dir/.image-build-order-$(printf '%s' "$repo" | tr -c 'A-Za-z0-9_-' '_')"
declare -A build_rank=()
journal_len=0
if [ -f "$journal_file" ]; then
  while IFS= read -r jt || [ -n "$jt" ]; do
    [ -n "$jt" ] || continue
    journal_len=$((journal_len + 1))
    build_rank["$jt"]=$journal_len
  done < "$journal_file"
  echo "retain-images: build-order journal $journal_file (${journal_len} entries)"
fi

# WO-REL-67 invariant: a tag journaled AFTER .current_tag was built after the
# deployed one — i.e. built, not yet deployed — and is never removed, whatever
# the CreatedAt ties say. (If .current_tag is missing or predates the journal,
# every journaled tag counts as newer — bounded by the build job's trim.)
current_tag_val=""
if [ -f "$deploy_dir/.current_tag" ]; then
  current_tag_val="$(cat "$deploy_dir/.current_tag")"
fi
current_rank=0
if [ -n "$current_tag_val" ] && [ "${#build_rank[@]}" -gt 0 ]; then
  current_rank="${build_rank[$current_tag_val]:-0}"
fi
if [ "${#build_rank[@]}" -gt 0 ]; then
  for jt in "${!build_rank[@]}"; do
    if [ "${build_rank[$jt]}" -gt "$current_rank" ]; then
      already=0
      for p in $protected; do
        [ "$jt" = "$p" ] && already=1
      done
      [ "$already" -eq 0 ] && protected="$protected $jt" \
        && echo "retain-images: keeping built-not-deployed $jt (journaled after current)"
    fi
  done
fi

# List this repo's tags, newest first. Rank (journal position; 0 = legacy) is
# the primary key, CreatedAt the secondary (ISO-like → lexicographic sort).
# Format: "<rank>|<CreatedAt>|<Repository>:<Tag>"
mapfile -t raw < <(docker image ls --format '{{.CreatedAt}}|{{.Repository}}:{{.Tag}}' "$repo" | grep -v '<none>')
entries=()
if [ "${#raw[@]}" -gt 0 ]; then
  decorated=()
  for entry in "${raw[@]}"; do
    created="${entry%%|*}"
    full="${entry#*|}"
    tag="${full##*:}"
    if [ "${#build_rank[@]}" -gt 0 ]; then
      r="${build_rank[$tag]:-0}"
    else
      r=0
    fi
    decorated+=("$r|$created|$full")
  done
  # Explicit 3rd key: when rank AND CreatedAt tie (the cache-hit state),
  # GNU sort's last-resort full-line comparison ignores the per-key `r` flags
  # and goes ascending — the tie must fall through to reverse-lexicographic
  # order exactly like the old plain `sort -r` did (HOLD round 2 premise).
  mapfile -t entries < <(printf '%s\n' "${decorated[@]}" | sort -t'|' -k1,1nr -k2,2r -k3,3r)
fi

kept=0
for entry in ${entries[@]+"${entries[@]}"}; do
  full="${entry#*|*|}"
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
