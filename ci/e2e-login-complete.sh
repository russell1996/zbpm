#!/usr/bin/env bash
# WO-TEST-12: сквозной E2E login → complete против РЕАЛЬНОГО backend.
#
# Что делает: собирает app+frontend из ТЕКУЩЕГО дерева, поднимает полный
# compose-стенд (postgres:16 + rabbitmq:4.1, prod-профиль), ждёт healthy,
# гоняет Playwright-спек e2e/login-complete.e2e.ts настоящим Chromium,
# на падении печатает хвост логов app, роняет стек.
#
# Использование:
#   bash ci/e2e-login-complete.sh                  # полный цикл
#   KEEP_E2E_UP=1 bash ci/e2e-login-complete.sh    # оставить стек для отладки
#   E2E_SKIP_BUILD=1 bash ci/e2e-login-complete.sh # стек уже собран/поднят
#                                                  # (только прогнать спек)
#
# Переменные (всё с дефолтами — чистый прогон без .env).
# ВНИМАНИЕ: дефолты вычисляются ВНУТРИ скрипта (openssl — в строке ниже).
# Снаружи задавай либо ВСЕ ТРИ (E2E_ADMIN_PASSWORD / E2E_ADMIN_NEW_PASSWORD /
# E2E_JWT_SECRET), либо НИ ОДНУ: скрипт поднимает стек ВНУТРИ себя, и секрет,
# сгенерированный снаружи в shell, НЕ совпадёт с тем, что увидит app, если
# shell не экспортировал его до вызова (поймано живым 401: shell сгенерил
# один, скрипт из-за :-дефолта — другой).
#   E2E_ADMIN_PASSWORD / E2E_ADMIN_NEW_PASSWORD — bootstrap-admin и его смена
#     (forcePasswordChange у fresh bootstrap — часть сценария, не помеха).
#     Дефолты — сильные (fail-fast валидаторы SEC-14/SEC-68/SEC-80 иначе
#     не дадут стенду стартовать).
#   E2E_JWT_SECRET — дефолт: свежий `openssl rand -base64 48` каждый прогон.
#   APP_PORT / FRONTEND_PORT — дефолты 8080/8081.
#   APP_TAG / FRONTEND_TAG — дефолты e2e-test-12 (НЕ :latest, чтобы не
#     затирать локальные образы).
#
# Предусловие: локальный стек `zorrobpm-*` остановлен (container_name в
# compose фиксированы — два стека на одних именах не живут; скрипт
# проверяет это fail-fast, а не молча).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# WO-QW-5 (NEW2-13): project-scoped имена — E2E-стенд не конфликтует с чужим
# dev-стеком на хосте. Раньше container_name были фиксированы
# (zorrobpm-app/...) — два стека на одних именах физически не живут, скрипт
# fail-fast'ился при запущенном dev-стеке. Теперь суффикс проекта выносит их
# в отдельное пространство (COMPOSE_PROJECT_NAME переименовывает И
# container_name — compose честно ругается, что оба заданы; поэтому
# container_name снимаем override-файлом ci/docker-compose.e2e.yml ниже, а
# сеть/volume именуем от проекта — см. тот файл).
export COMPOSE_PROJECT_NAME="${E2E_PROJECT_NAME:-zorrobpm-e2e}"
COMPOSE="docker compose -f docker-compose.yml -f ci/docker-compose.e2e.yml"

E2E_ADMIN_PASSWORD="${E2E_ADMIN_PASSWORD:-E2eAdminStr0ng!Pass}"
E2E_ADMIN_NEW_PASSWORD="${E2E_ADMIN_NEW_PASSWORD:-E2eNewStr0ng!Pass12}"
# Rate-limit окно login — 5/мин SHARED на весь стенд (ключ — IP/аккаунт,
# персистентно в БД запущенного app). PBKDF2-600k делает каждый логин
# ~100ms+, а 429-ответ НЕ отличим от 401 без чтения тела — спек обязан делать
# РОВНО ОДИН логин на пароль и НЕ ретраить: любая диагностика/повторный
# прогон против ТОГО ЖЕ поднятого стенда жрёт окно и врёт 401 (поймано
# живьём дважды: и curl-дебаг, и повторный playwright-прогон). Поэтому:
# down -v ПЕРЕД каждым прогоном (свежий bootstrap + пустое окно) — ниже.
E2E_JWT_SECRET="${E2E_JWT_SECRET:-$(openssl rand -base64 48)}"
# E2E-дефолты живут в ci/docker-compose.e2e.yml (рядом с чужими стеками:
# 5432/5672/8080/8081 на хосте заняты). APP_PORT/FRONTEND_PORT ниже — только
# для wait_for/curl и E2E_FRONTEND_URL, сами публикации — в override-файле.
E2E_APP_PORT="${E2E_APP_PORT:-58080}"
E2E_FRONTEND_PORT="${E2E_FRONTEND_PORT:-58081}"
export E2E_ADMIN_PASSWORD E2E_ADMIN_NEW_PASSWORD E2E_FRONTEND_URL="http://localhost:${E2E_FRONTEND_PORT}"
export APP_TAG="${APP_TAG:-e2e-test-12}" FRONTEND_TAG="${FRONTEND_TAG:-e2e-test-12}"
# Fail-fast креды (SEC-9/SEC-14) — обязательны, compose требует их через :?.
export ZORROBPM_JWT_SECRET="$E2E_JWT_SECRET"
export ZORROBPM_DEFAULT_ADMIN_PASSWORD="$E2E_ADMIN_PASSWORD"
export APP_PORT="$E2E_APP_PORT" FRONTEND_PORT="$E2E_FRONTEND_PORT"

TEST_EXIT=0
# Хвост логов печатаем в файл, а не в stdout: json-логи app хоронят вывод
# playwright под сотнями строк (поймано живьём — причину падения пришлось
# искать в test-results, а не в логе скрипта).
E2E_APP_LOG_TAIL="${E2E_APP_LOG_TAIL:-/tmp/e2e-app-tail.log}"

cleanup() {
  # P-42: teardown не должен маскировать exit-код теста — чистим с || true,
  # выходим с запомненным кодом. KEEP_E2E_UP=1 — оставить стек для отладки.
  if [ "${KEEP_E2E_UP:-0}" = "1" ]; then
    echo "[e2e] KEEP_E2E_UP=1 — стек оставлен (down вручную: $COMPOSE down -v)"
    exit "$TEST_EXIT"
  fi
  $COMPOSE down -v || true
  exit "$TEST_EXIT"
}
trap cleanup EXIT

# --- 0. Preflight: чужой стек на ТЕХ ЖЕ проектных именах? --------------------
# WO-QW-5: фильтр — по нашему project-суффиксу, а не по голым zorrobpm-*:
# dev-стек (`zorrobpm-*`, проект `zorrobpm`) больше не считается чужим —
# он живёт в своём пространстве имён. Чужим считается только второй E2E-стенд
# (тот же COMPOSE_PROJECT_NAME) — два E2E-прогона на одних именах не живут.
# Проверяем ТОЛЬКО когда скрипт сам поднимает стек: при E2E_SKIP_BUILD=1 стек
# уже поднят (нами же или вручную) — это норма, а не конфликт.
if [ "${E2E_SKIP_BUILD:-0}" != "1" ]; then
  # `docker ps` без -a: только ЗАПУЩЕННЫЕ чужие. Имена — с project-суффиксом
  # (COMPOSE_PROJECT_NAME выше): dev-стек `zorrobpm-*` сюда не попадает.
  E2E_SUFFIX="${COMPOSE_PROJECT_NAME:-zorrobpm-e2e}"
  FOREIGN_UP="$(docker ps --format '{{.Names}} {{.Image}}' \
    | awk -v sfx="$E2E_SUFFIX" '$1==sfx"-app"||$1==sfx"-frontend"||$1==sfx"-postgres"||$1==sfx"-rabbitmq"' \
    | grep -v "${APP_TAG:-e2e-test-12}" || true)"
  if [ -n "$FOREIGN_UP" ]; then
    echo "[e2e] FAIL: другой E2E-стенд $E2E_SUFFIX уже запущен — останови его" >&2
    echo "$FOREIGN_UP" >&2
    echo "[e2e] (dev-стек zorrobpm-* больше не мешает: у него своё пространство имён)" >&2
    TEST_EXIT=1
    exit 1
  fi
fi

# --- 1. Сборка из текущего дерева (ОБЯЗАТЕЛЬНО: образ обязан -----------------
# содержать код текущей ветки; готовый :latest может быть собран из master
# БЕЗ незамерженных WO — поймано живьём: стенд стартовал без SEC-80
# валидатора и упал на DB_PASSWORD=zorrodev, а спек врал про login).
# E2E_SKIP_BUILD=1 — только пропуск ПЕРЕСБОРКИ (фронт уже собран под /api),
# app при этом обязан быть собран из текущего дерева заранее (тег APP_TAG).
if [ "${E2E_SKIP_BUILD:-0}" != "1" ]; then
  echo "[e2e] build app (текущее дерево, backend для E2E — честный)…"
  $COMPOSE build app
  # Standalone-топология: nginx проксирует /api/ → app:8080, бандл обязан
  # звать относительный /api (доктрина из Dockerfile: дефолт VITE_API_URL=/
  # рассчитан на внешний прод-прокси, не на compose).
  echo "[e2e] build frontend (VITE_API_URL=/api под compose-nginx)…"
  $COMPOSE build --build-arg VITE_API_URL=/api frontend
fi

# --- 2. Подъём (чистый старт ОБЯЗАТЕЛЕН: volumes сносятся, иначе -------------
# bootstrap-admin и его forcePasswordChange — от прошлого прогона, а login
# rate-limit окно (5/мин, персистентно в БД) — забито дебагом, и сценарий
# врёт 401. down -v ПЕРЕД up -d, всегда, без флагов-исключений) --------------
echo "[e2e] down -v (чистый старт: volumes сносятся всегда)…"
$COMPOSE down -v || true
# down -v НЕ удаляет volumes, занятые остановленными контейнерами чужого
# проекта с тем же именем (live-поймано: volume пережил down, bootstrap
# пропустил сид, спек получил чужой пароль). Проверяем пустоту жёстко.
# WO-QW-5: имена volumes — с project-префиксом (compose именует
# `<project>_<volume>`): dev-объёмы `zorrobpm_*` сюда не попадают.
E2E_VOL_PREFIX="${COMPOSE_PROJECT_NAME:-zorrobpm-e2e}"
for v in "${E2E_VOL_PREFIX}_postgres-data" "${E2E_VOL_PREFIX}_rabbitmq-data" "${E2E_VOL_PREFIX}_app-files"; do
  if docker volume inspect "$v" >/dev/null 2>&1; then
    echo "[e2e] FAIL: volume $v пережил down -v — удали вручную:" >&2
    echo "[e2e]   docker volume rm $v  (или останови чужой стек на нём)" >&2
    TEST_EXIT=1
    exit 1
  fi
done
echo "[e2e] up (fresh volumes — bootstrap-admin пересоздаётся каждый прогон)…"
$COMPOSE up -d

wait_for() {
  local url="$1" name="$2" tries="${3:-60}"
  for ((i = 1; i <= tries; i++)); do
    if curl -sf -o /dev/null "$url"; then
      echo "[e2e] $name OK ($url)"
      return 0
    fi
    sleep 5
  done
  echo "[e2e] FAIL: $name не поднялся за $((tries * 5))с ($url)" >&2
  return 1
}

if ! wait_for "http://localhost:${E2E_APP_PORT}/actuator/health" "backend"; then
  echo "[e2e] --- app logs (tail → $E2E_APP_LOG_TAIL) ---" >&2
  $COMPOSE logs --tail=80 app > "$E2E_APP_LOG_TAIL" 2>&1 || true
  TEST_EXIT=1
  exit 1
fi
if ! wait_for "http://localhost:${E2E_FRONTEND_PORT}/healthz" "frontend"; then
  echo "[e2e] --- frontend logs (tail) ---" >&2
  $COMPOSE logs --tail=40 frontend >&2 || true
  TEST_EXIT=1
  exit 1
fi

# --- 3. Спек ----------------------------------------------------------------------
echo "[e2e] playwright: login → tasks → live SSE → complete…"
if ! (cd zorrobpm-frontend && npx playwright test); then
  TEST_EXIT=1
  echo "[e2e] FAIL: спек красный — хвост логов app → $E2E_APP_LOG_TAIL" >&2
  $COMPOSE logs --tail=80 app > "$E2E_APP_LOG_TAIL" 2>&1 || true
  exit 1
fi

echo "[e2e] GREEN: login → tasks → live SSE → complete против реального backend"
# Успех: стек больше не нужен — роняем СРАЗУ, а не в trap (иначе следующий
# прогон/дебаг увидит stale-данные: чужой пароль, забитое rate-limit окно).
# KEEP_E2E_UP=1 останавливает и здесь (стек нужен для ручной диагностики).
if [ "${KEEP_E2E_UP:-0}" != "1" ]; then
  $COMPOSE down -v || true
fi
