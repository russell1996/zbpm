#!/usr/bin/env bash
# WO-QW-11 (CR-14): сторож якоря `.rules-code-changes` в .gitlab-ci.yml.
#
# Дыра: `zorrobpm-http-connector` был модулем reactor (корневой pom.xml), но его
# не было в path-якоре — MR, меняющий ТОЛЬКО файлы коннектора, молча пропускал
# backend verify, интеграционные/security-джобы и сборку образов. Новый модуль
# повторил бы дыру так же молча, поэтому проверка сверяет якорь со списком
# <modules> из pom.xml, а не с захардкоженным списком.
#
# Использование:
#   bash ci/check-code-changes-paths.sh                  # весь якорь vs reactor
#   bash ci/check-code-changes-paths.sh <файл>...        # какие пути-якоря
#       покрывают перечисленные файлы (dry-run фильтра: пусто = джобы скипнутся)
#   git show HEAD:.gitlab-ci.yml > /tmp/old.yml  # RED-проверка против старого якоря:
#   .../скрипт с переменной ANCHOR_SRC=/tmp/old.yml (см. ниже)
#
# Переменная окружения ANCHOR_SRC — откуда брать якорь (дефолт: .gitlab-ci.yml
# в корне репозитория). Нужна для RED-демонстрации на до-фиксной версии.
set -euo pipefail

ANCHOR_SRC="${ANCHOR_SRC:-.gitlab-ci.yml}"
ROOT_POM="${ROOT_POM:-pom.xml}"

# Якорные паттерны: строки вида - "path/**/*" внутри code-changes-paths.
mapfile -t PATTERNS < <(grep -oE '^\s*-\s*"[^"]+"' "$ANCHOR_SRC" \
    | sed -E 's/^\s*-\s*"//; s/"$//' \
    | grep -v '^ci/\*\*' | grep -v '^\.gitlab-ci\.yml$' || true)

# fnmatch-проверка одного файла против списка паттернов (поддерживаем только
# суффикс /**/* — именно в такой форме записаны все модульные пути якоря).
matches() {
    local file="$1"
    local pat
    for pat in "${PATTERNS[@]}"; do
        if [[ "$pat" == */\*\*/* ]]; then
            local prefix="${pat%%/\*\*/*}"
            if [[ "$file" == "$prefix"/* ]]; then
                echo "$pat"
                return 0
            fi
        elif [[ "$file" == "$pat" ]]; then
            echo "$pat"
            return 0
        fi
    done
    return 1
}

fail=0

if [ "$#" -gt 0 ]; then
    # Dry-run: какие файлы чем покрыты.
    for f in "$@"; do
        if hit="$(matches "$f")"; then
            echo "MATCH   $f  <=  $hit"
        else
            echo "NOMATCH $f  (code-jobs skipped)"
        fi
    done
    exit 0
fi

# Полная сверка: каждый reactor-модуль обязан покрываться якорем.
mapfile -t MODULES < <(grep -oE '<module>[^<]+</module>' "$ROOT_POM" \
    | sed -E 's|</?module>||g')
for mod in "${MODULES[@]}"; do
    probe="$mod/src/placeholder-check"
    if hit="$(matches "$probe")"; then
        echo "OK      $mod  <=  $hit"
    else
        echo "HOLE    $mod — нет покрытия в $ANCHOR_SRC"
        fail=1
    fi
done

exit "$fail"
