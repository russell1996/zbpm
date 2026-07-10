# Независимый аудит ZorroBPM CE — Fable (2026-07-10)

> Независимый аудитор (модель Fable), запущен CTO после закрытия всего backlog (16 WO).
> Метод: всё проверено по диску на ветке `master`. Выводы CTO/Mimo не принимались на веру.
> **CTO-верификация:** ключевые находки S1 (CRITICAL), S4 (HIGH), S6 (MEDIUM) перепроверены
> по диску и ПОДТВЕРЖДЕНЫ. S1: fail-fast только для точного профиля `"prod"`, `jwt-secret`
> задан лишь в `application-prod.yml` → дефолтный профиль стартует с публичным дефолт-секретом.

## 1. Executive summary

**Security:** 1 CRITICAL, 3 HIGH, 7 MEDIUM, 5 LOW = **16 находок**. Большинство — остаточные
после 16 WO или недозакрытые самими WO (SEC-2, SEC-7, FEAT-2, FEAT-4).

**Зрелость кода:** средняя. Криптопримитивы (PBKDF2 120k, HMAC-SHA256, constant-time compare,
случайные refresh-токены) корректны. Но периметр аутентификации держится на самодельном
сравнении строк пути в сервлет-фильтре и на хрупком fail-fast только для литерала `prod` —
две системные слабости, дающие обход.

**Зрелость процесса:** правила (AGENTS.md, V1-V10, HARD GATES, P-1..P-16) необычно проработаны,
но не самоисполняются: энфорсмент = ручное чтение диска одним CTO, который сам пишет спеки.
Минимум 5 багов имеют корень в постановке/верификации CTO. Единая точка отказа не устранена.

## 2. Security-находки

| # | Файл:строка | Sev | Сценарий | Фикс |
|---|---|---|---|---|
| S1 | `TokenService.java:38-46` + нет `jwt-secret` в дефолт-профиле | **CRITICAL** | Fail-fast только при активном профиле с точным именем `prod` И secret==дефолт. Дефолтный профиль стартует на публичном `change-me-dev-secret...` → атакующий подписывает HS256 `{role:ADMIN}` → полный админ без пароля. `production`/`prod-eu` тоже мимо. | Fail-fast при `secret==DEFAULT` ВСЕГДА, кроме явного `dev`; убрать дефолт из кода. |
| S2 | `RateLimitFilter.java:61-66` | **HIGH** | `getClientIp` берёт первый `X-Forwarded-For`. Рандомный XFF в каждом запросе → новый bucket → рейт-лимит обходится. В дефолт-профиле лимит `enabled:false`. | Trusted-proxy allowlist; IP из соединения; вкл. по умолчанию. |
| S3 | `JwtAuthFilter.java:47-64,74` | **HIGH** | Авторизация по `getRequestURI()` + `equals/startsWith`. `/users;x=1` и `//users` не матчат гейт, но Spring роутит в `UserResource` → обход ADMIN/data-API гейта. Единственная точка энфорсмента. | Spring Security matchers / нормализация пути; `@PreAuthorize` defense-in-depth. |
| S4 | `AuthResource.java:143-158` + `auth.ts:52` | **HIGH** | `logout` чистит только `refresh_token`, НЕ `zbpm_token` (httpOnly, JS не трогает). Access stateless, TTL 12ч → на общей машине переиспользование до 12ч. | Сервер чистит `zbpm_token` maxAge=0; короткий TTL; deny-list по jti. |
| S5 | `ServiceTaskListener.java:39`; `ActivityServiceImpl.java:1032,1066`; `DmnServiceImpl.java:129,137` | MEDIUM | SEC-7 неполон. INFO-логи значений: весь `detail.getVariables()` (PII payload при каждом service-task), результаты FEEL/DMN (кредитные решения, суммы). `grep getValue` их не поймал. | Values/results/variables → DEBUG/маска; скан всех сервисов. |
| S6 | `RuntimeResource.java:67-81`; `DBServiceImpl.java:390-403` | MEDIUM | FEAT-2 неполон. Cancel чистит timer_jobs+message_subscriptions, НЕ signal-подписки (`deleteSignalSubscriptionsByProcessInstanceId` нет). Broadcast сигнала матчит зомби-подписку отменённого инстанса. | Очистка signal-подписок (+токенов) в cancel-транзакции. |
| S7 | `UiUserServiceImpl.java:41-53`; `UiUserBootstrap.java:27,41` | MEDIUM | `forcePasswordChange` не энфорсится: login выдаёт полный ADMIN-токен игнорируя флаг. Дефолт admin/admin. Mimo прямо спрашивал в mimo-to-cto (SEC-2), WO закрыли. | Блокировать привилегии пока флаг стоит; рандомный дефолт-пароль. |
| S8 | `UiUserServiceImpl.java:45-48` | MEDIUM | Enumeration по времени: несуществующий юзер → быстрый return; существующий → PBKDF2 ~100мс. Перебор валидных логинов. | Dummy-hash на ветке «user not found». |
| S9 | `OutboxPollerService.java:33-45` | MEDIUM | Нет SKIP LOCKED/ShedLock/лимита. 2+ ноды → двойная публикация (markPublished спасает от двойной пометки, не публикации). Неидемпотентный consumer → двойное исполнение. | Атомарный claim (SKIP LOCKED)/single-lock; идемпотентность; batch limit. |
| S10 | `AuthResource.java:91-141` | MEDIUM | Ротация refresh есть, но нет reuse-detection: предъявление отозванного токена не отзывает семью (кража не детектится). | Revoked-но-предъявленный → revokeAllByUserId. |
| S11 | `GlobalExceptionHandler.java:85-91` | MEDIUM | `handleGenericRuntime` отдаёт `ex.getMessage()` клиенту на 500 (утечка SQL/деталей) и ничего не логирует. | Generic клиенту, детали в лог. |
| S12 | `WebConfiguration.java:14-21` | LOW | CSRF-токена нет; защита на SameSite=Strict+CORS. При ослаблении SameSite → CSRF на POST. | Явный CSRF или инвариант SameSite=Strict с тестом. |
| S13 | `guard.ts:12-20` | LOW | Гейт проверяет только `requiresAdmin`, не аутентификацию. | Добавить `isAuthenticated`. |
| S14 | `ScriptServiceImpl.java:36,44` | LOW | Перепутаны метки логов (`evaluateScript`→«Expression result»). | Поменять местами. |
| S15 | `AuthResource.java:52` | LOW | maxAge access-cookie 7 дней, JWT exp 12ч: cookie переживает токен. | Синхронизировать с TTL. |
| S16 | `ProcessEngineEventsServiceImpl.java:27,33` | LOW | `convertAndSend` закомментирован: runtime/definition события не публикуются (мёртвый код). | Реализовать/удалить. |

**Проверено и чисто:** FEEL RCE нет (Camunda FEEL sandbox, нет Groovy/Nashorn); path traversal нет
(`FileServiceImpl` строит путь из UUID); `alg:none` невозможен (verify пересчитывает HS256);
PasswordHasher корректен; **REL-2 outbox-мина реально устранена** (enqueue синхронен в транзакции);
assignee-check не обходится (нет claim/assign-эндпоинта); markPublished идемпотентен по записи.

## 3. Process/governance-дефекты

1. **Scope-мина в SEC-7 (дефект метода CTO):** `git grep getValue` как основание scope — ложноотрицательный, пропустил `getVariables()`/`result` логи (S5). Нарушение V1 со стороны CTO. Класса «неполный grep как scope» в P-1..P-16 нет.
2. **WO закрыт при поднятом Mimo пункте (SEC-2/S7):** Mimo спрашивал про forcePasswordChange, WO помечен merged, энфорс не сделан. CTO-side P-5.
3. **Неполнота как класс не покрыта:** FEAT-2 (signals, S6), SEC-7 (один сайт, S5). Нет правила «обработал один вариант семейства — проверь все родственные (signal/message/timer подписки; все места логирования)».
4. **Нет security-чек-листа ревью:** нет «не аутентифицируй по getRequestURI-строке», «stateless-JWT нельзя отозвать», «не отдавай ex.getMessage()», «проверь дефолтный профиль». Все S1-S4 попали бы под него.
5. **Нет правила про негативные тесты:** G-B требует тест на критерий, но не abuse/negative (не-assignee→403, отозванный refresh→отказ).
6. **`@Profile("!test")` = дыра покрытия:** OutboxPoller, ServiceTaskEnqueue, ProcessEngineEvents исключены из test-профиля; реальный путь покрыт только юнитом (и S16 мёртвый код никем не замечен).
7. **`_index.md` рассинхрон:** «Security 7/7» при 8 SEC-WO, обрезанная строка, «15/15+1».
8. **P-паттерны только про Mimo:** ошибки постановки CTO (S1/S5/S6/S7 + SEC-5/REL-2) не собраны в «CTO spec-review checklist» — самый дорогой класс (автор спеки = ревьюер).

## 4. Оценка AI-команды

**Сильные:** исключительно проработанные правила (V1-V10, HARD GATES, жёсткий DoD с proof-of-failure,
запрет ложного «готово», СТОП-список); двусторонний журнал + P-паттерны = обучающая петля;
верная изоляция (worktree, master-merge только CTO); Mimo фиксирует «вне scope» (V7).

**Слабые:** единая точка отказа реальна (≥5 багов из постановки CTO); энфорсмент 100% ручной
(CI зелёный ≠ критерий доказан; proof-of-failure = проза в markdown); изоляция honor-system
(G-E/G-G на тексте промпта, не git-правах); security-домен без экспертизы/статанализа.

**Риски масштабирования:** 2+ Mimo → гонки на общих `cto-to-mimo.md`/`_index.md`, ревью-бутылочное
горло; смена домена → P-паттерны с нуля, security-слепота остаётся.

## 5. Приоритизированный план (новые WO)

**Security:** WO-SEC-9 fail-fast дефолт-секрета все профили [CRITICAL, S]; WO-SEC-10 auth на Spring
Security matchers [HIGH, M, S3+S1/S4 defense]; WO-SEC-11 logout чистит access + reuse-detection
[HIGH, M, S4/S10/S15]; WO-SEC-12 rate-limit trusted-proxy + вкл [HIGH, S, S2]; WO-SEC-13 PII из логов
все сервисы [MED, M, S5]; WO-SEC-14 cancel чистит signal-подписки [MED, S, S6]; WO-SEC-15 энфорс
forcePasswordChange [MED, M, S7]; WO-SEC-16 мелочи (S8 timing, S11 500-утечка, S9 outbox lock) [MED, M].

**Процесс/автоматизация:** мехенфорс DoD в CI (gitleaks, JaCoCo-порог + флаг main-класс-без-тестов,
semgrep под S1-S4/S11) [High]; proof-of-failure как артефакт (RED/GREEN коммит-пара / surefire-репорт)
[High]; negative-test gate — расширить G-B abuse-тестом [Med]; починить `_index.md` [Low].

**AI-команда:** второй независимый ревьюер (обязателен для CRITICAL/HIGH и security-домена) —
ревьюит спеку CTO ДО выдачи и код ПОСЛЕ [High]; CTO spec-review checklist (персистится ли поле по
живому пути; проверен ли дефолтный профиль; grep-scope полон; все родственные сущности; отзываем ли
артефакт) [High]; метрики (escape-rate, rework-итерации, доля спека-дефектов, proof-compliance) [Med];
готовность к параллельности (per-WO файлы, claim-механизм, агрегатор) [Med].

**Ключевой вывод:** backlog закрыт по форме (16/16, CI зелёный), но независимая проверка вскрыла
1 CRITICAL и 3 HIGH, корень большинства — постановка/верификация единственного CTO (S1 дефолт-профиль,
S5 grep-scope, S6 неполный cleanup, S7 незакрытый флаг). Пока спека и ревью на одном агенте без
машинного энфорса и второго ревьюера — этот класс промахов воспроизводится.
