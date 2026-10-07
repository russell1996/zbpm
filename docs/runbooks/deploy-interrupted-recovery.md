# Runbook: восстановление после прерванного деплоя (WO-REL-65)

Инцидент 2026-10-07 (джоба 558464): deploy-джоба упала на шаге очистки
(`Permission denied` на root-owned каталоге bind-mount'а) ПОСЛЕ того, как wipe уже
удалил `docker-compose*.yml` и `ci/observability/`, а `trap … rm -f` снёс резервную
копию scrape-token. Хост остался без compose-файлов, без токена, со старыми
контейнерами. Начиная с WO-REL-65 три механики делают повтор маловероятным
(pre-wipe-проверка источников, `sudo` в wipe, бэкап переживает падение), но runbook
ниже действует на любом состоянии хоста.

## 1. Что остаётся на хосте после падения (читать сверху вниз)

| Шаг деплоя | При падении ЗДЕСЬ на хосте |
|---|---|
| pre-wipe-проверка (`WO-REL-65 BEGIN pre-wipe-sources`) | Ничего не тронуто: DEPLOY_DIR цел, бэкапа ещё нет. Причина — в логе джобы (`deploy copy source missing in checkout: <путь>`): в чекаут не долетел файл из списка копирования. Чинится в репозитории, не на хосте. |
| wipe (`find … -exec sudo rm -rf`) | DEPLOY_DIR частично снесён (какие записи верхнего уровня уцелели — смотреть `ls -la /opt/zorro-bpm`). `.env`/теги отката/`backups` исключены из wipe и целы. |
| копирование рантайм-бандла (`cp -a …`) | DEPLOY_DIR снесён, бандл докатан частично. Состав бандла — блок `WO-OPS-4b O-03` в `.gitlab-ci.yml`. |
| restore scrape-token (`mv … && chown …`) | Бандл на месте, токен — только в бэкапе (см. п.2). |
| `docker compose up -d` / smoke | Файлы на месте, стек старый или наполовину поднятый — смотреть `docker compose ps`. |

## 2. Scrape-token: где искать и как вернуть

1. **Сначала — бэкап джобы.** Начиная с WO-REL-65 cleanup-функция `scrape_token_cleanup`
   при падении ДО restore файл НЕ удаляет, а печатает путь:
   `deploy failed before scrape-token restore — host backup preserved at /tmp/scrape-token-bak-<pipeline>-XXXXXXXX`.
   Забрать с раннера (`/tmp` раннера, не прод-хоста), положить на хост:
   ```
   sudo cp /tmp/scrape-token-bak-<…> /opt/zorro-bpm/ci/observability/scrape-token
   sudo chown 65534:65534 /opt/zorro-bpm/ci/observability/scrape-token
   sudo chmod 400 /opt/zorro-bpm/ci/observability/scrape-token
   ```
2. **Если бэкапа нет (падение до WO-REL-65 или раннер уже прибран)** — токен живёт в
   памяти контейнера prometheus (он смонтировал файл ДО сноса по inode):
   ```
   docker exec <prometheus-контейнер> cat /etc/prometheus/scrape-token
   ```
   и дальше те же три команды. Перезапускать prometheus ДО возврата файла нельзя:
   после рестарта mount разрешится в новый (пустой) файл и старый токен пропадёт.
3. **Если нет и контейнера** — токен перевыпускается как новый API-ключ приложения
   (порядок как при первом bootstrap'е, см. WO-OBS-6): положить в тот же путь с теми
   же владельцем/правами, затем `docker compose up -d --force-recreate prometheus`
   (иначе prometheus продолжит смотреть в orphan-inode — инцидент 2026-09-14).

## 3. Возврат compose-файлов и перезапуск

1. Убедиться, что источники копирования есть в чекауте (та же pre-wipe-проверка,
   руками): `docker-compose.yml`, `docker-compose.observability.yml`,
   `ci/observability/prometheus.yml`,
   `ci/observability/rabbitmq/conf.d/20-per-object-metrics.conf`,
   `ci/rabbitmq/conf.d/30-management-path-prefix.conf`,
   `ci/observability/grafana/{provisioning,dashboards,entrypoint-whitelist.sh}`.
2. Root-owned остатки bind-mount'ов (каталог вместо файла — `ls -la`, владелец root):
   удаляет владелец/оператор вручную (`sudo rm -rf`), деплой сам их сносит начиная
   с хотфикса `73fa2937`, но проверять глазами обязательно.
3. Перезапустить deploy-джобу (кнопка) или докатать бандл теми же `cp -a`, затем
   `docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d`
   из `/opt/zorro-bpm` и smoke (`/actuator/health` → `"status":"UP"`).

## 4. Проверка после восстановления

- `docker compose ps` — весь стек UP (app, postgres, rabbitmq, prometheus, grafana, exporters);
- `curl -H "Authorization: Bearer $(sudo cat /opt/zorro-bpm/ci/observability/scrape-token)" http://localhost:8080/actuator/prometheus | grep zbpm_` — метрики идут;
- RabbitMQ management отвечает по префиксу (`/rabbitmq/api/overview`, 200 с воркерскими кредами) — иначе повторился сценарий инцидента (брокер без conf.d, приложение с суффиксом);
- `.current_tag`/`.previous_tag` на месте — откат возможен.
