#!/usr/bin/env bash
# WO-REL-65: harness устойчивости deploy-джобы (критерии 2–3).
#
# Извлекает НАСТОЯЩИЕ блоки из .gitlab-ci.yml (G-N: никакой копии логики здесь —
# только извлечение по маркерам и запуск в песочнице) и прогоняет их в режимах:
#
#   prewipe-ok      все источники копирования есть в чекауте  -> exit 0
#   prewipe-missing одного источника нет                      -> exit != 0, файл назван,
#                    DEPLOY_DIR-песочница не тронута (защита от полуснесённого хоста)
#   trap-fail       падение ПОСЛЕ бэкапа, ДО restore           -> exit-код проброшен,
#                    бэкап ЦЕЛ, путь напечатан (бэкап не сносится на пути падения)
#   trap-ok         штатный путь (бэкапа нет / restore был)    -> exit 0, пустышка удалена
#   wipe-rootowned  root-owned каталог bind-mount'а в DEPLOY_DIR -> извлечённая строка
#                    wipe (sudo) чистит всё, exit 0; исключения (.env, теги, backups) целы
#   wipe-plain-red  та же песочница, но БЕЗ sudo (форма строки до 73fa2937, сверена
#                    с историей ниже) -> MUST FAIL с Permission denied: это RED-доказательство
#                    опасности, ради которой wipe идёт под sudo (P-42: отказ отдельного
#                    элемента; здесь элемент — root-owned каталог Docker'а)
#
# Без аргументов — все режимы. Нужны bash + git; wipe-rootowned — ещё и passwordless
# sudo (как у deploy-раннера; иначе режим SKIP'ается, а не падает).
# Код выхода: 0 — всё зелёное, 1 — хоть один FAIL, 2 — ошибка использования.
#
# Примечание про P-42: wipe намеренно НЕ терпит отказ отдельного элемента (fail-closed:
# лучше упавший wipe до копирования, чем полуснесённый DEPLOY_DIR + докатка поверх).
# Устойчивость к известной опасности даёт sudo + pre-wipe-проверка, а не `|| true`.

set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CI_YML="$ROOT/.gitlab-ci.yml"
PASS=0; FAILED=0; SKIPPED=0

msg()  { printf '%s\n' "$*"; }
ok()   { PASS=$((PASS+1)); msg "HARNESS OK      $1"; }
fail() { FAILED=$((FAILED+1)); msg "HARNESS FAILURE $1"; }
skip() { SKIPPED=$((SKIPPED+1)); msg "HARNESS SKIP    $1"; }

# --- извлечение НАСТОЯЩЕГО текста (G-N) --------------------------------------
# Печатает строки между маркерами (сами маркеры и `#`-комментарии — нет);
# `- ` префикс и `- |` форма снимаются, остальное — побайтово как в YAML.
extract_block() { # begin end
  awk -v b="$1" -v e="$2" '
    index($0, b) { inblock=1; pipemode=0; next }
    index($0, e) { inblock=0; next }
    !inblock { next }
    /^[[:space:]]*#/ { next }
    /^[[:space:]]*-[[:space:]]*\|[[:space:]]*$/ { pipemode=1; next }
    pipemode { sub(/^      /, ""); print; next }
    { sub(/^[[:space:]]*-[[:space:]]*/, ""); print }
  ' "$CI_YML"
}

# Однострочная `- <команда>` строка deploy-блока по префиксу команды.
extract_line() { # <command-prefix>
  grep -E "^[[:space:]]*-[[:space:]]*$1" "$CI_YML" | head -1 \
    | sed -E 's/^[[:space:]]*-[[:space:]]*//'
}

Sandbox="$(mktemp -d /tmp/rel65-harness-XXXXXX)"
cleanup() {
  rm -rf "$Sandbox" 2>/dev/null || true
  # wipe-plain-red оставляет root-owned остатки — добиваем через docker-root, чтобы
  # не сорить /tmp root-owned мусором (P-35: чистое дерево/окружение после себя).
  if [ -e "$Sandbox" ] && have_docker; then
    docker run --rm -v "$(dirname "$Sandbox"):/s" alpine:3.20 rm -rf "/s/$(basename "$Sandbox")" 2>/dev/null || true
  fi
  rm -rf "$Sandbox" 2>/dev/null || true
}
trap cleanup EXIT

# --- prewipe-ok / prewipe-missing --------------------------------------------
mode_prewipe() { # <mode>
  local mode="$1"
  local checkout="$Sandbox/checkout-$mode" deploy="$Sandbox/deploy-$mode"
  mkdir -p "$checkout" "$deploy"
  # Дословный текст проверки из YAML (комментарии/маркеры сняты экстрактором выше).
  extract_block "WO-REL-65 BEGIN pre-wipe-sources" "WO-REL-65 END pre-wipe-sources" \
    > "$Sandbox/prewipe-run.sh"
  local sources
  sources="$(grep -oE 'for src in .*; do' "$Sandbox/prewipe-run.sh" | sed -E 's/for src in //; s/;[[:space:]]*do//')"
  [ -n "$sources" ] || { fail "prewipe-$mode: cannot extract source list from YAML"; return; }
  # Песочный «чекаут»: только существование файлов (проверка — `test -e`).
  local first_missing=""
  for src in $sources; do
    if [ "$mode" = "prewipe-missing" ] && [ -z "$first_missing" ]; then
      first_missing="$src"  # этот источник «забыли довезти» — как 30-*.conf в инциденте
      continue
    fi
    if [[ "$src" == *.conf || "$src" == *.yml ]]; then
      mkdir -p "$checkout/$(dirname "$src")"; touch "$checkout/$src"
    else
      mkdir -p "$checkout/$src"; touch "$checkout/$src/.keep"
    fi
  done
  echo "sentinel" > "$deploy/docker-compose.yml"  # уже задеплоенное — должно пережить отказ
  local out rc=0
  out="$(cd "$checkout" && DEPLOY_DIR="$deploy" bash "$Sandbox/prewipe-run.sh" 2>&1)" || rc=$?
  if [ "$mode" = "prewipe-ok" ]; then
    if [ "$rc" -eq 0 ]; then ok "prewipe-ok: full source set passes (exit 0)";
    else fail "prewipe-ok: exit $rc, want 0 :: $out"; fi
  else
    if [ "$rc" -ne 0 ] && [[ "$out" == *"$first_missing"* ]] && [[ "$out" == *"refusing to wipe"* ]] \
        && [ -f "$deploy/docker-compose.yml" ]; then
      ok "prewipe-missing: exit $rc, names '$first_missing', deployed sentinel untouched"
    else
      fail "prewipe-missing: rc=$rc out='$out' sentinel_present=$([ -f "$deploy/docker-compose.yml" ] && echo yes || echo no)"
    fi
  fi
}

# --- trap-fail / trap-ok -------------------------------------------------------
mode_trap() { # <mode>
  local mode="$1"
  local func_file="$Sandbox/cleanup.sh"
  extract_line "scrape_token_cleanup\(\)" > "$func_file"
  [ -s "$func_file" ] || { fail "trap-$mode: cannot extract scrape_token_cleanup from YAML"; return; }
  local bak="$Sandbox/token.bak"
  echo "live-scrape-token-bytes" > "$bak"; chmod 600 "$bak"
  local saved="0" trigger="0" want_rc=0
  if [ "$mode" = "trap-fail" ]; then saved="1"; trigger="7"; want_rc=7; fi
  local out rc=0
  # cleanup вызывается в SUBSHELL: её `exit "$rc"` гасит только сабшелл, не harness.
  out="$(export SCRAPE_TOKEN_BAK="$bak" SCRAPE_TOKEN_SAVED="$saved" DEPLOY_DIR="/opt/zorro-bpm";
         bash -c "source \"$func_file\"; ( exit $trigger ); ( scrape_token_cleanup )" 2>&1)" || rc=$?
  if [ "$mode" = "trap-fail" ]; then
    if [ "$rc" -eq "$want_rc" ] && [ -f "$bak" ] \
        && [ "$(cat "$bak")" = "live-scrape-token-bytes" ] && [[ "$out" == *"preserved at $bak"* ]]; then
      ok "trap-fail: exit $want_rc propagates, backup intact, path printed"
    else
      fail "trap-fail: rc=$rc bak_present=$([ -f "$bak" ] && echo yes || echo no) out='$out'"
    fi
  else
    if [ "$rc" -eq 0 ] && [ ! -e "$bak" ]; then
      ok "trap-ok: exit 0, empty placeholder removed"
    else
      fail "trap-ok: rc=$rc bak_still_present=$([ -e "$bak" ] && echo yes || echo no) out='$out'"
    fi
  fi
}

# Привилегия для wipe-режимов: на deploy-раннере есть passwordless sudo (сам deploy
# на нём держится) — тогда строка wipe гоняется ДОСЛОВНО. Здесь sudo нет, поэтому
# root-owned содержимое создаётся и сносится через docker-root; эквивалентность
# `sudo X` (как runner) ≡ `X` (как root) — прямая, а литерал `sudo` в строке пинает
# DeployCopyListGuardTest.wipeSurvivesRootOwnedLeftovers. Вывод всегда говорит, каким
# путём шли, — «зелёный у меня» без указания пути про CI не говорит ничего (P-40).
have_sudo() { sudo -n true 2>/dev/null; }
have_docker() { docker ps >/dev/null 2>&1; }

wipe_sandbox() { # <tag> — песочный DEPLOY_DIR со всеми классами содержимого
  local deploy="$Sandbox/deploy-$1"
  rm -rf "$deploy"; mkdir -p "$deploy"
  echo "DB_PASSWORD=x" > "$deploy/.env"
  echo "abc123" > "$deploy/.current_tag"; echo "def456" > "$deploy/.previous_tag"
  mkdir -p "$deploy/backups"; echo "dump" > "$deploy/backups/pg.dump"
  echo "old" > "$deploy/docker-compose.yml"
  mkdir -p "$deploy/ci/observability"; echo "tok" > "$deploy/ci/observability/scrape-token"
  # root-owned каталог: ровно то, что Docker создаёт из отсутствующего bind-источника.
  mkdir -p "$deploy/ci/rabbitmq/conf.d"
  if have_sudo; then
    sudo mkdir -p "$deploy/ci/rabbitmq/conf.d/30-management-path-prefix.conf"
    sudo touch "$deploy/ci/rabbitmq/conf.d/30-management-path-prefix.conf/inner"
  elif have_docker; then
    docker run --rm -v "$deploy:/d" alpine:3.20 sh -c \
      "mkdir -p /d/ci/rabbitmq/conf.d/30-management-path-prefix.conf && touch /d/ci/rabbitmq/conf.d/30-management-path-prefix.conf/inner && chown -R 0:0 /d/ci/rabbitmq" >/dev/null
  else
    echo "NEED-PRIVILEGE"
    return 1
  fi
  # Честная проверка предпосылки: владелец реально 0, иначе «Permission denied» ниже
  # доказывал бы не опасность, а артефакт маппинга UID.
  [ "$(stat -c %u "$deploy/ci/rabbitmq/conf.d/30-management-path-prefix.conf")" = "0" ] \
    || { echo "NEED-PRIVILEGE"; return 1; }
  printf '%s' "$deploy"
}

# Запуск извлечённой строки wipe с привилегией (sudo дословно / docker-root).
priv_run_wipe() { # <line> <deploy> — печатает вывод, код возврата = код wipe
  local line="$1" deploy="$2"
  if have_sudo; then
    msg "  (privilege path: passwordless sudo — the line runs VERBATIM)"
    DEPLOY_DIR="$deploy" bash -c "$line" 2>&1
  else
    msg "  (privilege path: docker-root — same rm text as root; the literal 'sudo' is pinned by the JUnit guard)"
    docker run --rm -v "$deploy:/deploy" -e DEPLOY_DIR=/deploy alpine:3.20 sh -c \
      "${line//sudo /}" 2>&1
  fi
}

mode_wipe() { # <mode>
  local mode="$1" line deploy
  line="$(extract_line 'find "\$DEPLOY_DIR"')"
  [ -n "$line" ] || { fail "wipe-$mode: cannot extract wipe line from YAML"; return; }
  if [ "$mode" = "wipe-plain-red" ]; then
    # Реконструкция строки ДО 73fa2937: тот же текст минус `sudo ` (в отчёте сверено
    # побайтово с `git show 73fa2937^:.gitlab-ci.yml` — здесь падает, а не правится).
    line="${line//sudo /}"
  elif ! have_sudo && ! have_docker; then
    skip "wipe-rootowned: neither passwordless sudo nor docker here (deploy runner has sudo)"
    return
  fi
  deploy="$(wipe_sandbox "$mode")" || { skip "wipe-$mode: cannot stage a root-owned dir here"; return; }
  local out rc=0
  if [ "$mode" = "wipe-plain-red" ]; then
    out="$(DEPLOY_DIR="$deploy" bash -c "$line" 2>&1)" || rc=$?
  else
    out="$(priv_run_wipe "$line" "$deploy")" || rc=$?
  fi
  if [ "$mode" = "wipe-plain-red" ]; then
    # Локаль раннера непредсказуема (здесь — русская `rm`), поэтому матчим сам факт
    # отказа `rm`, а не английский текст: rc != 0 + каталог цел + жалоба rm в выводе.
    if [ "$rc" -ne 0 ] && [ -d "$deploy/ci/rabbitmq/conf.d/30-management-path-prefix.conf" ] \
        && [[ "$out" == *"rm:"* ]]; then
      ok "wipe-plain-red: exit $rc, root-owned dir survives, rm refused (job 558464 reproduced)"
    else
      fail "wipe-plain-red: rc=$rc rootdir_present=$([ -d "$deploy/ci/rabbitmq/conf.d/30-management-path-prefix.conf" ] && echo yes || echo no) out='$out'"
    fi
    return
  fi
  local ok_state=1
  [ "$rc" -eq 0 ] || ok_state=0
  [ -f "$deploy/.env" ] && [ -f "$deploy/.current_tag" ] && [ -f "$deploy/backups/pg.dump" ] || ok_state=0
  [ ! -e "$deploy/docker-compose.yml" ] && [ ! -e "$deploy/ci" ] || ok_state=0
  if [ "$ok_state" -eq 1 ]; then
    ok "wipe-rootowned: exit 0, root-owned leftovers gone, .env/tags/backups intact"
  else
    fail "wipe-rootowned: rc=$rc (host state or leftovers wrong)"
  fi
}

# --- main ----------------------------------------------------------------------
usage() { echo "usage: $0 [prewipe-ok|prewipe-missing|trap-fail|trap-ok|wipe-rootowned|wipe-plain-red]..." >&2; exit 2; }
MODES=("$@")
[ "${#MODES[@]}" -eq 0 ] && MODES=(prewipe-ok prewipe-missing trap-fail trap-ok wipe-rootowned wipe-plain-red)
for m in "${MODES[@]}"; do
  case "$m" in
    prewipe-ok)      mode_prewipe prewipe-ok ;;
    prewipe-missing) mode_prewipe prewipe-missing ;;
    trap-fail)       mode_trap trap-fail ;;
    trap-ok)         mode_trap trap-ok ;;
    wipe-rootowned)  mode_wipe wipe-rootowned ;;
    wipe-plain-red)  mode_wipe wipe-plain-red ;;
    *) usage ;;
  esac
done
msg "HARNESS SUMMARY: $PASS passed, $FAILED failed, $SKIPPED skipped"
[ "$FAILED" -eq 0 ]
