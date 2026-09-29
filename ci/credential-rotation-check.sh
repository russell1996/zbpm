#!/usr/bin/env bash
# NEW2-07 / deploy-incident 2026-09-27: гейт на дефолтные DB/RabbitMQ пароли — до того,
# как деплой снесёт старый рабочий контейнер, а не после.
#
# Инцидент: WO-SEC-80 добавил fail-fast (DbRabbitPasswordValidator) внутрь самого
# приложения — правильная защита, но она срабатывает ВНУТРИ Spring-контекста, уже
# ПОСЛЕ того, как `docker compose up -d --remove-orphans` снёс старый работающий
# контейнер и поднял новый. Новый контейнер валится на старте (FATAL: default
# 'zorrodev'), старый уже нет — прод падает (502) до тех пор, пока кто-то не заметит
# и не сыграет rollback вручную.
#
# 2026-09-27 повторный аудит (NEW3-01): первая версия этого файла руками парсила .env
# через grep+cut и сравнивала строку побайтно с 'zorrodev' — `DB_PASSWORD="zorrodev"`
# (в кавычках) или с CRLF на конце сравнивались НЕ равными строке без кавычек/CRLF и
# гейт пропускал ровно то значение, которое уронило прод. Docker compose сам снимает
# кавычки/пробелы при интерполяции — гейт обязан спрашивать ЕГО, а не угадывать его
# грамматику руками. Теперь используется `docker compose config`, которая резолвит
# .env ТОЧНО так же, как это сделает сам compose при старте контейнера.
#
# Usage: bash ci/credential-rotation-check.sh
# Env:  DEPLOY_DIR (default /opt/zorro-bpm), COMPOSE_FILE (default docker-compose.yml
#       в текущей рабочей директории — CI-job уже стоит в $CI_PROJECT_DIR)

set -euo pipefail

deploy_dir="${DEPLOY_DIR:-/opt/zorro-bpm}"
env_file="$deploy_dir/.env"
compose_file="${COMPOSE_FILE:-docker-compose.yml}"
default_value="zorrodev"

if [ ! -f "$env_file" ]; then
    echo "credential-rotation-check: $env_file не существует — первый деплой на хост, ротация невозможна до первого запуска. Пропускаю (создастся при этом деплое)."
    exit 0
fi

# `docker compose config` резолвит .env ровно так же, как сделает `up` — снимает
# кавычки, обрезает CRLF, применяет ${VAR:-default}. Спрашиваем именно про сервис
# app (единственный, где нужен реальный резолв — postgres/rabbitmq получают те же
# переменные из того же .env, проверка одного источника достаточна).
resolved_json="$(docker compose -f "$compose_file" --env-file "$env_file" config --format json 2>&1)" || {
    echo "ERROR: docker compose config упал — не могу резолвить .env, отказываю деплою до ручной проверки:" >&2
    echo "$resolved_json" >&2
    exit 1
}

fail=0
for var in DB_PASSWORD RABBITMQ_PASSWORD; do
    value="$(echo "$resolved_json" | python3 -c "
import json, sys
try:
    d = json.load(sys.stdin)
    print(d['services']['app']['environment'].get('$var', ''))
except Exception as e:
    print('PARSE_ERROR:' + str(e))
")"
    if [ -z "$value" ] || [ "$value" = "$default_value" ] || [ "${value#PARSE_ERROR}" != "$value" ]; then
        echo "ERROR: $var, как его реально увидит контейнер app (через docker compose config) — '$value'. Отсутствует, равен дефолту ('$default_value') или не удалось резолвить — приложение упадёт на старте (DbRabbitPasswordValidator, WO-SEC-80)." >&2
        fail=1
    fi
done

if [ "$fail" -ne 0 ]; then
    echo "ERROR: ротация паролей не выполнена — см. governance/runbooks/credential-rotation.md. Отказываю деплою ДО того, как снесён работающий контейнер." >&2
    exit 1
fi

echo "credential-rotation-check: DB_PASSWORD/RABBITMQ_PASSWORD (резолв docker compose config) не дефолтные — OK"
exit 0
