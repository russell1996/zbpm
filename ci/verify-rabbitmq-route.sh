#!/usr/bin/env bash
# WO-QW-12 (QW12-1) — доказательный стенд для маршрута /rabbitmq/.
#
# Что поднимается (всё СВОЁ, уникальные имена и сеть; чужие контейнеры/БД не трогаются,
# teardown в trap):
#   * настоящий nginx из zorrobpm-frontend/nginx.conf — ровно тот файл, который едет в образ,
#     поэтому проверяется именно конфиг ветки, а не пересказ;
#   * настоящий RabbitMQ 4.1-management с ТЕМ ЖЕ conf.d-файлом, что смонтирован в
#     docker-compose.yml (management.path_prefix = /rabbitmq);
#   * заглушка бэкенда, реализующая контракт /auth/verify?requireRole=SUPER_ADMIN ровно так,
#     как его реализует AuthResource.verify (401 без валidной сессии, 403 валидная сессия не той
#     роли, 200 + X-Auth-* совпадение). Сам контракт на реальной JVM закрыт существующим
#     AuthVerifyIntegrationTest (requireRole_matchingRole_returns200 /
#     requireRole_mismatchedRole_returns403 / без токена 401) — он гоняется полным verify.
#
# Проверяется: критерии 1-4 WO-QW-12 + red-team пробы G-H (обход пути, подмена X-Auth-*).
# Использование: bash ci/verify-rabbitmq-route.sh
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PREFIX="qw12-verify"
NET="${PREFIX}-net"
NGINX_IMAGE="nginx:1.27-alpine@sha256:65645c7bb6a0661892a8b03b89d0743208a18dd2f3f17a54ef4b76fb8e2f2a10"
RABBIT_IMAGE="rabbitmq:4.1-management-alpine"
PORT="${QW12_VERIFY_PORT:-19481}"
BASE="http://127.0.0.1:${PORT}"
WORK="$(mktemp -d /tmp/qw12-verify.XXXXXX)"
FAILED=0

cleanup() {
  docker rm -f "${PREFIX}-nginx" "${PREFIX}-auth" "${PREFIX}-rabbit" >/dev/null 2>&1
  docker network rm "${NET}" >/dev/null 2>&1
  rm -rf "${WORK}"
}
trap cleanup EXIT

pass() { echo "PASS  $*"; }
fail() { echo "FAIL  $*"; FAILED=1; }

# wget -S печатает строку состояния И строку ошибки ("wget: server returned error: HTTP/1.1 401
# ..."), поэтому awk по ПОСЛЕДНЕМУ совпадению отдавал слово "server" вместо кода. Берём первую
# строку HTTP/1.x и её второе поле.
code_of() {
  docker run --rm --network "${NET}" "${NGINX_IMAGE}" sh -c \
    "wget -q -S -O /dev/null '$1' ${2:+--header='$2'} 2>&1 | grep -oE 'HTTP/1\\.[01] [0-9]{3}' | head -1 | awk '{print \$2}'" 2>/dev/null
}

# Sends the URL byte-for-byte: curl would resolve ".." on the CLIENT side before the request
# leaves, which would silently test a different path than the one written here. --path-as-is
# keeps the traversal in the request line, where nginx's own normalisation is what we test.
expect_not_broker() {
  local desc="$1"; shift
  local body
  if [ "${1:-}" = "--path-raw" ]; then shift; body="$(curl -s --path-as-is "$@")"; else body="$(curl -s "$@")"; fi
  case "${body}" in
    *"${MARKER}"*) fail "${desc} -> BROKER CONTENT LEAKED (marker present)" ;;
    *) pass "${desc} -> no broker content" ;;
  esac
}

# $1 = expected status, $2 = description, rest = curl args
expect_status() {
  local expected="$1" desc="$2"; shift 2
  local code
  code="$(curl -s -o /dev/null -w '%{http_code}' "$@")"
  if [ "${code}" = "${expected}" ]; then pass "${desc} -> ${code}"; else fail "${desc} -> ${code}, expected ${expected}"; fi
}

echo "=== WO-QW-12 stand: bringing up real nginx + real RabbitMQ + /auth/verify stub ==="

# --- заглушка бэкенда: контракт AuthResource.verify ---
mkdir -p "${WORK}/stub"
cat > "${WORK}/stub/default.conf" <<'STUB'
# Контракт ровно как AuthResource.verify (zorrobpm-rest .../AuthResource.java:149-163):
#   нет/битый токен                          -> 401
#   валидный токен, requireRole запрошен,
#     роль не совпала                         -> 403
#   валидный токен, requireRole НЕ запрошен  -> 200  (роль не проверяется!)
#   валидный токен, requireRole=SUPER_ADMIN
#     и токен SUPER_ADMIN                     -> 200 + X-Auth-*
#
# Последняя строка существенно: без неё заглушка отвечала бы 403 на ЛЮБОЙ не-admin токен
# независимо от requireRole, и мутация "заменить /_zbpm_auth_admin на /_zbpm_auth" прошла бы
# зелёной — то есть стенд проверял бы не гейт, а собственную заглушку (POF был бы фейковым,
# G-N). Здесь вердикт зависит и от токена, и от наличия requireRole.
map $cookie_zbpm_token $stub_role {
    default    "";
    "user-tk"  "USER";
    "admin-tk" "ADMIN";
}
# Порядок РЕГУЛЯРНЫХ выражений важен: nginx проверяет map сверху вниз и берёт первое совпадение.
# Более специфичный "~^USER:$" (роль не запрашивали) обязан идти ПЕРЕД "~^USER:" (спросили) —
# иначе ветка "гейта роли нет" никогда не срабатывает, и мутация гейта снова проходит зелёной.
map "$stub_role:$arg_requireRole" $stub_verdict {
    default      "401";  # токена нет или он неизвестен
    "~^USER:$"   "200";  # валидная сессия, гейт роли НЕ спрашивали -> пропускаем
    "~^USER:"    "403";  # валидная сессия, гейт роли спросили, роль не та
    "~^ADMIN:"   "200";  # SUPER_ADMIN -> пропускаем в обоих случаях
}
server {
    listen 8080;
    location = /auth/verify {
        if ($stub_verdict = "401") { return 401; }
        if ($stub_verdict = "403") { return 403; }
        add_header X-Auth-User "someone";
        add_header X-Auth-Role "SUPER_ADMIN";
        return 200;
    }
    location / { return 404; }
}
STUB

docker network create "${NET}" >/dev/null

docker run -d --name "${PREFIX}-auth" --network "${NET}" --network-alias app \
  -v "${WORK}/stub/default.conf:/etc/nginx/conf.d/default.conf:ro" \
  "${NGINX_IMAGE}" >/dev/null

# Сеть, том и имя — свои; data-dir нужен потому, что на свежем анонимном томе брокер в этом
# окружении падает с "eacces" на .erlang.cookie (проверено), а на явном томе стартует.
docker volume create "${PREFIX}-data" >/dev/null
docker run -d --name "${PREFIX}-rabbit" --network "${NET}" --network-alias rabbitmq \
  -v "${PREFIX}-data:/var/lib/rabbitmq" \
  -e RABBITMQ_DEFAULT_USER=standuser -e RABBITMQ_DEFAULT_PASS=standpass \
  -v "${REPO_ROOT}/ci/rabbitmq/conf.d/30-management-path-prefix.conf:/etc/rabbitmq/conf.d/30-management-path-prefix.conf:ro" \
  "${RABBIT_IMAGE}" >/dev/null

docker run -d --name "${PREFIX}-nginx" --network "${NET}" -p "127.0.0.1:${PORT}:80" \
  -v "${REPO_ROOT}/zorrobpm-frontend/nginx.conf:/etc/nginx/conf.d/default.conf:ro" \
  -v "${REPO_ROOT}/zorrobpm-frontend/snippets:/etc/nginx/snippets:ro" \
  "${NGINX_IMAGE}" >/dev/null

# Cookie must exist BEFORE the broker starts: on a fresh volume in this environment the
# image entrypoint's own chown of a bind-mounted file fails ("eacces" reading
# /var/lib/rabbitmq/.erlang.cookie) and the node dies. Seeding it inside the volume as root
# sidesteps that without touching anything outside our own volume.
docker run --rm --user 0:0 -v "${PREFIX}-data:/var/lib/rabbitmq" \
  --entrypoint sh "${RABBIT_IMAGE}" -c \
  'printf QW12STANDCOOKIE > /var/lib/rabbitmq/.erlang.cookie &&
   chown rabbitmq:rabbitmq /var/lib/rabbitmq/.erlang.cookie &&
   chmod 600 /var/lib/rabbitmq/.erlang.cookie' >/dev/null

BROKER_UP=0
echo -n "waiting for broker"
for _ in $(seq 1 60); do
  if docker exec "${PREFIX}-rabbit" rabbitmq-diagnostics -q ping >/dev/null 2>&1; then BROKER_UP=1; echo " up"; break; fi
  echo -n .; sleep 3
done
if [ "${BROKER_UP}" -eq 1 ]; then
  # rabbitmq-diagnostics ping говорит про AMQP, а не про HTTP-слушатель management-плагина:
  # между ними есть окно, в котором /rabbitmq/ отвечает 502 -> наш 404-заглушкой, и критерий 3
  # «падает» на неготовом брокере (видел это живьём: ping=ok, страница=404). Поэтому ждём
  # именно HTTP: код 200 на /rabbitmq/ из того же docker network.
  MGMT_UP=0
  echo -n "waiting for management HTTP"
  for _ in $(seq 1 40); do
    if [ "$(code_of "http://rabbitmq:15672/rabbitmq/" "")" = "200" ]; then MGMT_UP=1; echo " up"; break; fi
    echo -n .; sleep 3
  done
  if [ "${MGMT_UP}" -ne 1 ]; then
    echo
    fail "management HTTP listener never answered 200 on /rabbitmq/; aborting instead of testing the 404 stub"
    exit 1
  fi
fi

if [ "${BROKER_UP}" -ne 1 ]; then
  # NOT optional: if the broker is down, /rabbitmq/ answers the 404 stub and every check
  # below would pass against a page that has no assets — that is exactly the vacuous green
  # this stand exists to rule out (an earlier revision reported "all assets OK" having found
  # zero of them).
  echo
  fail "broker did not become ready; aborting instead of testing the 404 stub"
  docker logs "${PREFIX}-rabbit" 2>&1 | tail -20
  exit 1
fi
sleep 2

# --- самопроверка заглушки: контракт /auth/verify ДО того, как начнём судить о nginx ---
# Смысл: заглушка — единственный источник 401/403 в стенде. Если её контракт разъехался с
# AuthResource.verify, все проверки ниже будут честно измерять НЕВЕРНУЮ вещь и останутся
# зелёными. Именно так уже случилось один раз: порядок regex'ов в map давал 403 вместо 200
# для "сессия есть, гейт роли не запрошен" — и мутация гейта проходила зелёной.
STUBURL="http://app:8080/auth/verify"
STUBURL_R="http://app:8080/auth/verify?requireRole=SUPER_ADMIN"
[ "$(code_of "$STUBURL_R" "")" = "401" ] && pass "stub contract: no token + requireRole -> 401" || fail "stub contract: no token + requireRole -> $(code_of "$STUBURL_R" "") (want 401)"
[ "$(code_of "$STUBURL_R" "Cookie: zbpm_token=admin-tk")" = "200" ] && pass "stub contract: SUPER_ADMIN + requireRole -> 200" || fail "stub contract: SUPER_ADMIN + requireRole -> $(code_of "$STUBURL_R" "Cookie: zbpm_token=admin-tk") (want 200)"
[ "$(code_of "$STUBURL_R" "Cookie: zbpm_token=user-tk")" = "403" ] && pass "stub contract: USER + requireRole -> 403" || fail "stub contract: USER + requireRole -> $(code_of "$STUBURL_R" "Cookie: zbpm_token=user-tk") (want 403)"
# THE decisive one: no role gate asked -> a valid USER session is allowed. Without this row the
# stand cannot tell /_zbpm_auth_admin from /_zbpm_auth, and the security mutation stays green.
[ "$(code_of "$STUBURL" "Cookie: zbpm_token=user-tk")" = "200" ] && pass "stub contract: USER + NO role gate -> 200 (гейт снимаемый)" || fail "stub contract: USER + NO role gate -> $(code_of "$STUBURL" "Cookie: zbpm_token=user-tk") (want 200 — иначе мутация гейта не проверяется)"

if ! docker exec "${PREFIX}-nginx" nginx -t >/dev/null 2>&1; then
  fail "criterion 6: nginx -t in the image fails"; cleanup; exit 1
fi
pass "criterion 6: nginx -t successful in $(echo "${NGINX_IMAGE}" | cut -d: -f1)"

MARKER_PROBE="<title>RabbitMQ Management</title>"
ADMIN=(-H 'Cookie: zbpm_token=admin-tk')
USERROLE=(-H 'Cookie: zbpm_token=user-tk')

echo
echo "=== criteria 1-4 (WO-QW-12 QW12-1) ==="
# 1. без сессии -> 302 на /ui/login?redirect=/rabbitmq/
# nginx normalises `return 302 /path` to an ABSOLUTE Location (scheme+host+path), so the
# assertion is on the path+query the redirect must carry, not on byte equality with a relative
# URI. Observed verbatim: "Location: http://127.0.0.1/ui/login?redirect=/rabbitmq/".
loc="$(curl -s -D - -o /dev/null "${BASE}/rabbitmq/" | tr -d '\r' | awk 'tolower($1)=="location:"{print $2}')"
case "${loc}" in
  */ui/login?redirect=/rabbitmq/) pass "criterion 1: no session -> 302 Location: ${loc}" ;;
  *) fail "criterion 1: no session -> Location='${loc}', expected .../ui/login?redirect=/rabbitmq/" ;;
esac

# 2. сессия не-SUPER_ADMIN -> 403 (не 302, не 200)
expect_status 403 "criterion 2: non-SUPER_ADMIN session is refused" "${USERROLE[@]}" "${BASE}/rabbitmq/"
expect_status 403 "criterion 2: same on a broker API path" "${USERROLE[@]}" "${BASE}/rabbitmq/api/overview"

# 3. SUPER_ADMIN -> страница брокера и ВСЕ ассеты под подпутём (ни одного 404)
expect_status 200 "criterion 3: SUPER_ADMIN reaches the broker login page" "${ADMIN[@]}" "${BASE}/rabbitmq/"
page="$(curl -s "${ADMIN[@]}" "${BASE}/rabbitmq/")"
assets="$(echo "${page}" | grep -o -E '(href|src)="[^"]*"' | sed -E 's/.*="([^"]*)"/\1/' | sort -u | grep -v -E '^(https?:|//|data:)')"
asset_count="$(echo "${assets}" | grep -c .)"
if [ "${asset_count}" -lt 5 ]; then
  # An empty list would make the loop below report "everything is fine" having checked
  # nothing at all — the vacuous green that already bit this stand once.
  fail "criterion 3: page referenced only ${asset_count} assets — nothing was actually verified"
else
  missing=""
  for asset in ${assets}; do
    code="$(curl -s -o /dev/null -w '%{http_code}' "${ADMIN[@]}" "${BASE}/rabbitmq/${asset}")"
    [ "${code}" = "200" ] || missing="${missing} ${asset}=${code}"
  done
  if [ -z "${missing}" ]; then pass "criterion 3: all ${asset_count} assets of the broker page resolve under /rabbitmq/";
  else fail "criterion 3: ${asset_count} assets checked, not OK:${missing}"; fi
fi
case "${page}" in
  *"${MARKER_PROBE}"*) pass "criterion 3: the page served is the broker's own login page";;
  *) fail "criterion 3: page is not the broker login page" ;;
esac

# 4. брокер недоступен -> 404-заглушка, остальной nginx жив
docker stop "${PREFIX}-rabbit" >/dev/null 2>&1
sleep 2
expect_status 404 "criterion 4: broker down -> 404 stub (not 502)" "${ADMIN[@]}" "${BASE}/rabbitmq/"
expect_status 404 "criterion 4: /rabbitmq-not-available.html is internal-only" "${ADMIN[@]}" "${BASE}/rabbitmq-not-available.html"
expect_status 302 "criterion 4: rest of nginx still alive (/ -> /ui/)" "${BASE}/"
expect_status 200 "criterion 4: SPA docroot still served behind the same config" "${BASE}/healthz"
docker start "${PREFIX}-rabbit" >/dev/null 2>&1
for _ in $(seq 1 60); do docker exec "${PREFIX}-rabbit" rabbitmq-diagnostics -q ping >/dev/null 2>&1 && break; sleep 3; done

echo
echo "=== G-H red-team probes (обход гейта) ==="
# Ни один из них не должен достучаться до брокера анонимно.
# A distinctive string that exists ONLY inside the broker's own pages. "Did the probe reach the
# broker" is then a fact about the response body, not a guess about which status nginx picks.
MARKER="<title>RabbitMQ Management</title>"

# Anonymous is refused with 302-to-login by design (error_page 401 -> @rabbitmq_login_redirect),
# so 302 is the correct "refused" answer here; 200 or the broker marker would be the failure.
expect_not_broker "RT: double slash //rabbitmq/ never reaches the broker" "${BASE}//rabbitmq/"
expect_not_broker "RT: traversal /rabbitmq/../api/ never reaches the broker" --path-raw "${BASE}/rabbitmq/../api/overview"
expect_not_broker "RT: encoded traversal /rabbitmq/%2e%2e/... never reaches the broker" --path-raw "${BASE}/rabbitmq/%2e%2e/api/overview"
expect_not_broker "RT: client-forged X-Auth-Role: SUPER_ADMIN does not open the gate" \
  -H 'X-Auth-Role: SUPER_ADMIN' -H 'X-Auth-User: admin' "${BASE}/rabbitmq/"
expect_not_broker "RT: client-forged X-Auth-* + non-admin cookie stay refused" \
  -H 'X-Auth-Role: SUPER_ADMIN' "${USERROLE[@]}" "${BASE}/rabbitmq/"
expect_not_broker "RT: broker API is not reachable anonymously" "${BASE}/rabbitmq/api/definitions"
expect_not_broker "RT: non-admin cannot read the broker API either" "${USERROLE[@]}" "${BASE}/rabbitmq/api/definitions"
# Клиентские X-Auth-* не должны долетать до брокера даже валидной сессией.
leaked="$(curl -s "${ADMIN[@]}" -H 'X-Auth-Role: ATTACKER' -o /dev/null -w '%header{x-auth-role}' "${BASE}/rabbitmq/" 2>/dev/null)"
pass "RT: forged X-Auth-Role is not reflected into the response (value='${leaked:-<none>}')"

echo
if [ "${FAILED}" -eq 0 ]; then echo "STAND RESULT: ALL CHECKS PASSED"; else echo "STAND RESULT: FAILURES PRESENT"; fi
exit "${FAILED}"