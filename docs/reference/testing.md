# Разработка и тесты

> Как собрать, какие наборы тестов есть, правила качества. Вход в документацию — [README](../../README.md), карта — [docs/README.md](../README.md).

## Требования

- **JDK 21** (`java.version=21` во всех POM; Docker-сборка — Temurin 21).
- Maven 3.9+
- Docker + Docker Compose (для быстрого старта)

## Локальная разработка

```bash
# Собрать всё и прогнать тесты (unit + интеграционные на H2)
JAVA_HOME=/путь/к/jdk-21 mvn clean verify

# Запустить только модуль приложения (нужны доступные Postgres + RabbitMQ)
mvn -pl zorrobpm-app -am spring-boot:run
```

Docker-сборка образа пропускает тесты (`-DskipTests`); запускайте `mvn verify` локально/в CI как контроль качества.

## Тесты и контроль качества

```bash
# backend: unit + интеграционные на H2 (весь reactor)
mvn clean verify

# PG-набор на настоящем PostgreSQL 16 и интеграция с RabbitMQ (нужен Docker)
bash ci/run-pg-tests.sh
bash ci/run-rabbit-tests.sh

# frontend (из zorrobpm-frontend/)
npm test                 # unit (vitest)
npm run test:browser     # браузерные тесты в Chromium
npm run build            # typecheck (vue-tsc) + сборка
```

- Интеграционный тест подхватывается CI только если класс называется `*IntegrationTests` (или `*PgIT` / `*ChaosIT`); имя `*IT` ни в какой набор не попадает.
- После теста JUnit-расширение проверяет, что завершённые/отменённые инстансы не оставили живых активностей, токенов, таймеров и маркеров multi-instance; отчёт нарушений — `zorrobpm-engine/target/leak-invariant-report.txt` (режим отчёта, не роняет тесты).
- Сверка с Camunda 8.5 — набор оракула в `docs/analysis/c8-oracle/` (Zeebe 8.5.0 в Docker), правила — гейты G-P и G-Q в [`CONTRIBUTING.md`](../../CONTRIBUTING.md).
