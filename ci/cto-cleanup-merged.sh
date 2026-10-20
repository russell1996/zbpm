#!/usr/bin/env bash
# Автоуборка после мержа (maintainer-инструмент; вызывается из post-merge после пуша).
#
# 2026-10-20 (слова владельца: «почему сразу не удаляешь, что это висит… не допускай такого в правила»):
# после мержей оставались 90 локальных веток, 40+ рабочих копий и 3 мёртвые ветки на GitLab. Правило
# «убирай за собой» жило только в памяти и снова терялось — поэтому оно теперь механическое.
#
# Что делает (только БЕЗОПАСНОЕ, ничего необратимого сверх `git branch -d`):
#  1) рабочие копии ревью `zbpm-review-*` и рабочие копии исполнителей, чья ветка уже влита в master,
#     если в них нет живого процесса tooling и нет незакоммиченных tracked-правок → `git worktree remove`;
#  2) локальные ветки, влитые в master (не master, не занятые рабочей копией) → `git branch -d`
#     (откажет сам, если ветка не влита — `-D` НЕ используется никогда);
#  3) ветки на origin (GitLab), чей кончик — предок master (кроме master) → `git push origin --delete`;
#  4) НЕ удаляет, а ПЕЧАТАЕТ предупреждение о невлитых ветках старше 14 дней без рабочей копии
#     (решение «выбросить работу» — всегда осознанное, а не автоматическое).
# Каждое удаление пишется в review-logs/cleanup.log вместе с хешем кончика (для восстановления).
# `--dry-run` — только показать. Рабочие копии с живым процессом и ветки с работой не трогаются.

set -u
DRY=0; [ "${1:-}" = "--dry-run" ] && DRY=1
ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || exit 0
cd "$ROOT" || exit 0
[ "$(git rev-parse --abbrev-ref HEAD 2>/dev/null)" = "master" ] || exit 0
LOG="${CLEANUP_LOG:-$ROOT/../review-logs/cleanup.log}"
say() { echo "cleanup: $*"; [ $DRY = 0 ] && echo "$(date '+%F %T') $*" >> "$LOG" 2>/dev/null; }
busy() { # КТО-ТО использует каталог? любой процесс с cwd внутри ИЛИ docker-контейнер с монтированием каталога
  local w="$1" p c
  for p in $(ls /proc 2>/dev/null | grep -E '^[0-9]+$'); do
    c="$(readlink /proc/$p/cwd 2>/dev/null)"
    case "$c" in "$w"|"$w"/*) return 0;; esac
  done
  if command -v docker >/dev/null 2>&1; then
    for id in $(docker ps -q 2>/dev/null); do
      docker inspect -f '{{range .Mounts}}{{.Source}}{{"\n"}}{{end}}' "$id" 2>/dev/null | grep -qE "^$w(/|$)" && return 0
    done
  fi
  return 1
}

# 1) рабочие копии
git worktree list --porcelain | awk '/^worktree /{w=$2} /^branch /{b=$2; sub("refs/heads/","",b)} /^detached/{b="(detached)"} /^$/{if(w!="") print w"|"b; w="";b=""} END{if(w!="") print w"|"b}' | while IFS='|' read -r w b; do
  [ "$w" = "$ROOT" ] && continue
  busy "$w" && continue
  [ -n "$(git -C "$w" status --short --untracked-files=no 2>/dev/null)" ] && continue
  ok=0
  # копия ревью — только если ей больше 3 часов (приёмка может стоять в очереди и ещё не «занимать» каталог)
  case "$(basename "$w")" in zbpm-review-*) [ $(( $(date +%s) - $(stat -c %Y "$w" 2>/dev/null || echo 0) )) -gt 10800 ] && ok=1;; esac
  if [ "$b" != "" ] && [ "$b" != "(detached)" ] && git merge-base --is-ancestor "$b" master 2>/dev/null; then ok=1; fi
  [ $ok = 1 ] || continue
  tip="$(git -C "$w" rev-parse --short HEAD 2>/dev/null)"
  if [ $DRY = 1 ]; then say "[dry] убрал бы рабочую копию $(basename "$w") ($b @ $tip)"
  else git worktree remove --force "$w" >/dev/null 2>&1 && say "рабочая копия $(basename "$w") ($b @ $tip) убрана"; fi
done
git worktree prune

# 2) локальные ветки, влитые в master
used="$(git worktree list --porcelain | awk '/^branch /{b=$2; sub("refs/heads/","",b); print b}')"
git for-each-ref refs/heads --format='%(refname:short)' | while read -r b; do
  [ "$b" = "master" ] && continue
  echo "$used" | grep -qx "$b" && continue
  git merge-base --is-ancestor "$b" master 2>/dev/null || continue
  tip="$(git rev-parse --short "$b")"
  if [ $DRY = 1 ]; then say "[dry] удалил бы ветку $b ($tip)"
  else git branch -d "$b" >/dev/null 2>&1 && say "ветка $b ($tip) удалена (влита)"; fi
done

# 3) ветки на origin, влитые в master
if git remote get-url origin >/dev/null 2>&1 && git fetch origin --prune >/dev/null 2>&1; then
  git for-each-ref refs/remotes/origin --format='%(refname:short)' | grep -vE '^origin$|origin/HEAD|origin/master$' | while read -r r; do
    git merge-base --is-ancestor "$r" master 2>/dev/null || continue
    name="${r#origin/}"; tip="$(git rev-parse --short "$r")"
    if [ $DRY = 1 ]; then say "[dry] удалил бы origin/$name ($tip)"
    else git push origin --delete "$name" >/dev/null 2>&1 && say "origin/$name ($tip) удалена (влита)"; fi
  done
fi

# 4) предупреждение о невлитых старых ветках (не удаляем)
used="$(git worktree list --porcelain | awk '/^branch /{b=$2; sub("refs/heads/","",b); print b}')"
limit=$(( $(date +%s) - 14*86400 ))
git for-each-ref refs/heads refs/remotes/origin --format='%(refname:short) %(committerdate:unix)' | grep -vE ' ' >/dev/null 2>&1
git for-each-ref refs/heads refs/remotes/origin --format='%(refname:short) %(committerdate:unix)' | while read -r b ts; do
  [ "$b" = "master" ] || [ "$b" = "origin/master" ] || [ "$b" = "origin" ] || [ "$b" = "origin/HEAD" ] && continue
  echo "$used" | grep -qx "$b" && continue
  git merge-base --is-ancestor "$b" master 2>/dev/null && continue
  [ "$ts" -lt "$limit" ] && echo "cleanup: ВНИМАНИЕ — невлитая ветка $b старше 14 дней без рабочей копии: влить, довести или удалить осознанно"
done
exit 0
