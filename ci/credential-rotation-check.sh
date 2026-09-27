#!/usr/bin/env bash
# NEW2-07 / deploy-incident 2026-09-27: гейт на дефолтные DB/RabbitMQ пароли — до того,
# как деплой снесёт старый рабочий контейнер, а не после.
#
# Инцидент: WO-SEC-80 добавил fail-fast (DbRabbitPasswordValidator) внутрь самого
# приложения — правильная защита, но она срабатывает ВНУТРИ Spring-контекста, уже
# ПОСЛЕ того, как `docker compose up -d --remove-orphans` снёс старый работающий
# контейнер и поднял новый. Новый контейнер валится на старте (FATAL: default
# 'zorrodev'), старый уже нет — прод падает (502) до тех пор, пока кто-то не заметит
# и не сыграет rollback вручную. Аудит (NEW2-07) предсказал это заранее ("до деплоя —
# обязательная проверка prod .env"), но проверка осталась ручным пунктом чек-листа,
# который один раз забыли выполнить перед нажатием manual deploy — ровно тот класс
# ошибки, который держится памятью, а не механизмом (см. remaining истории проекта).
#
# Проверяет DEPLOY_DIR/.env ДО любых деструктивных шагов деплоя. Дефолт 'zorrodev' —
# ровно то значение, что зашито в docker-compose.yml (${DB_PASSWORD:-zorrodev} и т.п.)
# и что проверяет CredentialsValidator/DbRabbitPasswordValidator в самом приложении.
#
# Usage: bash ci/credential-rotation-check.sh
# Env:  DEPLOY_DIR (default /opt/zorro-bpm)

set -euo pipefail

deploy_dir="${DEPLOY_DIR:-/opt/zorro-bpm}"
env_file="$deploy_dir/.env"
default_value="zorrodev"

if [ ! -f "$env_file" ]; then
    echo "credential-rotation-check: $env_file не существует — первый деплой на хост, ротация невозможна до первого запуска. Пропускаю (создастся при этом деплое)."
    exit 0
fi

fail=0
for var in DB_PASSWORD RABBITMQ_PASSWORD; do
    value="$(grep -E "^${var}=" "$env_file" 2>/dev/null | tail -1 | cut -d= -f2- || true)"
    if [ -z "$value" ] || [ "$value" = "$default_value" ]; then
        echo "ERROR: $var в $env_file отсутствует или равен дефолту ('$default_value') — приложение упадёт на старте (DbRabbitPasswordValidator, WO-SEC-80)." >&2
        fail=1
    fi
done

if [ "$fail" -ne 0 ]; then
    echo "ERROR: ротация паролей не выполнена — см. governance/runbooks/credential-rotation.md. Отказываю деплою ДО того, как снесён работающий контейнер." >&2
    exit 1
fi

echo "credential-rotation-check: DB_PASSWORD/RABBITMQ_PASSWORD не дефолтные — OK"
exit 0
