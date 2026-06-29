# Conventions — стандарты под стек ZBPM

Стек (проверено по коду): **Java 21**, **Spring Boot 4.0.5**, Maven reactor (10 модулей), PostgreSQL 16 (прод) /
H2 (тесты), RabbitMQ 3.13, Camunda FEEL, Liquibase. Frontend: Vue 3 + Vite + TS + Pinia + Tailwind + vue-i18n.

## Coding (Java)
- Lombok: `@RequiredArgsConstructor` (DI через final-поля), `@Slf4j`, `@SneakyThrows` (точечно).
- Null-safety: `org.jspecify.annotations.@NonNull` на публичных входах; null → информативное исключение/инцидент, не NPE.
- Сервисы — `@Service`; конфиги — `@Configuration`; не плодить статику/синглтоны с состоянием.
- Исключения движка: `EngineException` (engine-level abort, пробрасывается) vs ошибка элемента (паркуется инцидентом). Не глотать (`catch {}` пустой запрещён, V7).
- Профиль `test` исключает MQ-публикацию (`@Profile("!test")`). Тесты не должны требовать брокера.

## Naming
- Пакеты: `com.zorrodev.bpm.<module>...`; реализации в `...impl`; **не импортировать чужой `.impl`/internal** (V7).
- Контракты — HTTP-интерфейсы в `zorrobpm-contract` (`@GetExchange`/`@PostExchange`), переиспользуются клиентом и сервером.
- Тесты: интеграционные `*IntegrationTests.java` (или `*IT`), unit `*Tests.java`. Entities исключены из JaCoCo.

## API
- REST-контроллеры тонкие, `@Transactional`, делегируют в сервисы. Бизнес-логика — в engine, не в resource.
- DTO — в `zorrobpm-contract/dto`; query-параметры — `*Query` объекты. Новое поле фильтра должно быть **применено** в `QueryServiceImpl` (молча игнорируемые фильтры запрещены — это дефект).
- Изменение `zorrobpm-contract` каскадно ломает клиента — только с ADR/одобрением Claude.

## Tests (фальсифицируемость — V3)
- Каждый тест на поведение обязан иметь сильный assert на наблюдаемый результат (статус активности, переменную, число строк, инцидент), а не лог.
- **Proof-of-failure обязателен:** перед сдачей показать тест красным (откат фикса/мутация) и зелёным после.
- Реальный путь, не мок, там где проверяется реальное: engine-IT гоняются на H2 с реальной схемой Liquibase; не подменять движок in-memory заглушкой.
- Конкурентные сценарии (joins, таймеры) — тест с реальной конкуренцией/двумя «нодами», не последовательная имитация.

## Database / migrations
- Только Liquibase. Имя changeset: `YYYYMMDD-NNN-<kebab-описание>.yml` в `db/changelog/changesets/`, подключить в `db.changelog-master.yaml`.
- Идемпотентность/обратимость where possible; не править прошлые changeset — добавлять новый.
- Hot-индексы и уникальные констрейнты — отдельным changeset с обоснованием (трассировка к B*/T8).

## Observability (целевое, T8)
- Логи: `log.info("{}/{}: <action> {}: {}/{}", pi, token, type, activityId, elementId)` — единый формат «pi/token: …».
- Метрики (когда появится Micrometer): счётчики инцидентов, латентность шага, длина очереди таймеров.

## Security (secure-by-default)
- Никаких дефолтных секретов в проде (fail-fast). CORS — профильный, не `*` с credentials. Bootstrap-учётки — форс-смена пароля.
- Не логировать секреты/токены/PII. Data API — под аутентификацией (T1).
- FEEL/script исполняется из модели — деплой моделей считать привилегированной операцией (SEC6).

## Frontend
- Сущности — по-английски (Process, Service Task…), действия/кнопки — через i18n `t()`; все три локали (ru/en/kz) синхронны.
- Все ID — через `CopyableId`. Нет hardcoded URL/IP/localhost; база API — относительная `/api`.
- Верификация — `npm run build` (tsc + vite) без ошибок после каждой задачи.

## Git
- Conventional commits с module-scope: `feat(engine):`, `fix(rest):`, `test(engine):`, `docs:`, `ci:`, `chore:`.
- Feature-ветка → PR. Не амендить запушенное. Не `--no-verify`. В master — только Claude.
