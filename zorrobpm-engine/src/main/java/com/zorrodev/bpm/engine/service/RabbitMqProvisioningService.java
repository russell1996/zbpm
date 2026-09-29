package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessMemberRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.Principal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * WO-INT-9: per-system RabbitMQ-креды для SYSTEM-юзеров.
 *
 * <p>Модель: логин на брокере = существующий {@code UiUser.login}, пароль —
 * отдельный секрет (не API-ключ: разные blast radius, разные поводы ротации),
 * права — производная от {@link ProcessMemberService} (членство в процессах),
 * НЕ отдельная декларация. Два разных жизненных цикла на одном логине: пароль —
 * по требованию из SPA, права — по факту членства (синк при
 * addMember/removeMember/provision через {@link #syncPermissions}).
 *
 * <p>Источник «у юзера есть брокер-аккаунт» — DB-флаг
 * {@code UiUserEntity.rabbitmqProvisioned} (решение CTO по V10-a: брокер как
 * источник не годится — лишний roundtrip на горячем membership-пути + брокер
 * может лежать). Рассинхрон флага с брокером (аккаунт удалён руками) НЕ
 * чинится сам: синк зовёт только permissions (пароль не хранится — пересоздать
 * аккаунт без ре-генерации нечем) и fail-closed 503 требует ручной
 * ре-генерации пароля (verifier HOLD #5 — комментарий v1 про self-heal был
 * неверен, исправлен здесь).
 *
 * <p>Брокер недоступен в момент синка для provisioned-юзера → 503, membership-
 * транзакция падает целиком (fail-closed, решение CTO по V10-b: DB-коммит +
 * best-effort дал бы дрейф прав шире/уже членства — дыру).
 *
 * <p>Общий аккаунт {@code zorrodev} (administrator) этим WO НЕ трогается —
 * работает параллельно до отдельного WO (переходный период).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RabbitMqProvisioningService {

    /** Vhost всех очередей движка — один на деплой; права выдаются только в нём. */
    static final String VHOST = "/";

    /**
     * WO-SEC-83 (NEW4-05): маркер «аккаунт создан ZBPM» в тегах брокер-юзера.
     * Management API ничего не знает о происхождении аккаунта — без маркера
     * {@code PUT /api/users/{login}} молча перезаписал бы пароль и снёс бы
     * теги ЧУЖОГО аккаунта (мониторинг, другое приложение на том же vhost,
     * {@code guest}). Маркер ставится при каждом нашем PUT (создание и
     * ротация), проверяется перед ним.
     */
    static final String MANAGED_TAG = "zbpm-managed";

    /**
     * Класс символов job-типов, допустимых в permissions-regex. Совпадает с
     * тем, что реально ходит в {@code JobQueueDeclarer.queueNameFor} (тип из
     * BPMN {@code zeebe:taskDefinition type} — буквы/цифры/{@code _-.}).
     * Экзотика за пределами класса — fail-closed: тип пропускается с warn
     * (воркер получит 403 и это будет видно, вместо молча широких прав).
     */
    static final Pattern SAFE_JOB_TYPE = Pattern.compile("[A-Za-z0-9_.\\-]+");

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PASSWORD_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private final UiUserRepository uiUserRepository;
    private final ProcessMemberRepository processMemberRepository;
    private final ProcessRepository processRepository;
    private final ProcessDefinitionRepository processDefinitionRepository;
    private final BpmnService bpmnService;
    private final AuditLogService auditLogService;

    /** WO-INT-9 (G-C.3): base-url Management API, рядом с trace-connections. */
    @Value("${zorrobpm.rabbitmq.management.base-url:http://localhost:15672}")
    private String managementBaseUrl;

    /** Креды app-админа брокера — те же, что у самого движка (administrator). */
    @Value("${spring.rabbitmq.username:zorrodev}")
    private String brokerAdminUser;

    @Value("${spring.rabbitmq.password:zorrodev}")
    private String brokerAdminPassword;

    private volatile HttpClient httpClient;

    /**
     * Генерирует (первый вызов — создаёт брокер-аккаунт + ставит флаг +
     * синкает права) или ротирует RabbitMQ-пароль SYSTEM-юзера.
     * Ротация permissions НЕ трогает (критерий 5 по построению — разные методы,
     * разные HTTP-вызовы; IT доказывает).
     *
     * @return пароль в открытом виде — единственный показ, не хранится
     */
    public String provisionPassword(UUID userId, Principal principal) {
        UiUserEntity user = uiUserRepository.findById(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (!"SYSTEM".equals(user.getUserType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "RabbitMQ password is a SYSTEM-account feature");
        }
        if (!user.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Cannot provision credentials for a deactivated account");
        }
        // Verifier HOLD #1 (red-team): логин, совпадающий с брокер-админом app
        // (дефолт `zorrodev`), резервируется — иначе PUT /api/users с пустыми
        // тегами снёс бы administrator-тег и перетёр пароль аккаунта самого
        // приложения (self-DoS + поломка общего аккаунта переходного периода,
        // критерий 6). Создание такого SYSTEM-логина ничем не запрещено,
        // поэтому guard стоит здесь, а не в валидации имени.
        if (user.getUsername() != null && user.getUsername().equals(brokerAdminUser)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Login is reserved for the broker administrator");
        }

        String password = generatePassword();
        // RabbitMQ password_hash: base64( salt(4 bytes) || sha256(salt || password) ).
        // На диске движка — только hash (соль внутри), plaintext живёт лишь
        // в этом вызове: уходит в PUT и возвращается вызывающему один раз.
        String passwordHash = rabbitPasswordHash(password);
        // WO-SEC-83 (NEW4-05): alreadyProvisioned = DB-флаг ЭТОГО userId.
        // Флаг — источник «нашести» для grandfather-аккаунтов WO-INT-9 (у них
        // ещё нет маркера в тегах): login уникален в нашей БД, и флаг мог
        // выставить только успешный provisionPassword этого же userId.
        upsertBrokerUser(user.getUsername(), passwordHash, user.isRabbitmqProvisioned());

        boolean firstTime = !user.isRabbitmqProvisioned();
        if (firstTime) {
            user.setRabbitmqProvisioned(true);
            uiUserRepository.save(user);
            // Права — по ПОЛНОМУ текущему набору процессов (не инкрементально):
            // при первой генерации юзер уже может состоять в процессах.
            // Ротация permissions НЕ трогает (критерий 5, решение CTO по V10-e:
            // разные жизненные циклы — синк идёт только из членства).
            // Verifier HOLD #6: если синк упал ПОСЛЕ создания брокер-юзера —
            // компенсируем удалением (иначе orphan-аккаунт с правами при
            // откате флага, которого no-op-правило больше не коснётся).
            try {
                syncPermissions(user);
            } catch (RuntimeException e) {
                try {
                    deleteBrokerUser(user.getUsername());
                } catch (Exception cleanupEx) {
                    log.warn("WO-INT-9: failed to clean up broker user {} after sync failure: {}",
                        user.getUsername(), cleanupEx.getMessage());
                }
                throw e;
            }
        }

        log.info("RabbitMQ password {} for system user={}",
            firstTime ? "provisioned" : "rotated", user.getUsername());
        auditLogService.record(principal,
            firstTime ? "RABBITMQ_PASSWORD_PROVISION" : "RABBITMQ_PASSWORD_ROTATE",
            null, userId.toString());
        return password;
    }

    /**
     * Пересчитывает и пушит permissions юзера по ПОЛНОМУ текущему набору
     * процессов, в которых он состоит. No-op на брокере, если пароль никогда
     * не генерировался (критерий 4). Вызывается из addMember/removeMember
     * (хук ниже) и из {@link #provisionPassword}.
     */
    public void syncPermissionsForUser(UUID userId) {
        UiUserEntity user = uiUserRepository.findById(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        syncPermissions(user);
    }

    private void syncPermissions(UiUserEntity user) {
        if (!user.isRabbitmqProvisioned()) {
            // Критерий 4: пароля нет — брокер-юзера нет, трогать нечего.
            return;
        }
        Set<String> jobTypes = jobTypesForUser(user.getId());
        Permissions permissions = permissionsFor(jobTypes);
        setBrokerPermissions(user.getUsername(), permissions);
        log.info("RabbitMQ permissions synced for system user={} ({} job types)",
            user.getUsername(), jobTypes.size());
    }

    /**
     * Union job-типов ВСЕХ версий каждого ключа (решение CTO по V10-d):
     * инстанс старой версии ещё бежит → его очередь обязана остаться
     * доступной; latest-only отозвал бы права у живого рантайма.
     */
    Set<String> jobTypesForUser(UUID userId) {
        Set<String> jobTypes = new HashSet<>();
        for (var membership : processMemberRepository.findByUserId(userId)) {
            String key = processRepository.findById(membership.getProcessId())
                .map(com.zorrodev.bpm.engine.entity.ProcessEntity::getDefinitionKey)
                .orElse(null);
            if (key == null) continue;
            for (ProcessDefinitionEntity version
                    : processDefinitionRepository.findAll(ProcessDefinitionRepository.byKey(key))) {
                try {
                    jobTypes.addAll(bpmnService.getProcessDefinitionModelById(version.getId()).getJobTypes());
                } catch (Exception e) {
                    // Версия без доступной модели (PENDING/FAILED деплой) —
                    // пропускаем, остальные версии всё равно дают union.
                    log.warn("Skipping job types of definition {} (no model): {}",
                        version.getId(), e.getMessage());
                }
            }
        }
        return jobTypes;
    }

    /**
     * Схема permissions (vhost {@code /}), решение CTO по V10-c + verifier HOLD #2.
     * configure покрывает declare очередей + DLQ; exchange
     * {@code zorrobpm.jobs.dlx} + биндинги объявляет сам app под
     * admin-аккаунтом (declare идемпотентен) — воркеру свои не нужны.
     * write — job-очереди + {@code zorrobpm.complete-service-task}
     * (воркер публикует completion'ы через default exchange —
     * {@code JobCompletionListener.convertAndSend(completeQueueName, …)}),
     * но ТОЛЬКО при непустом членстве: юзер с нулём процессов получает
     * deny-all целиком (иначе любой provisioned юзер без процессов мог бы
     * ковать completion'ы чужих service-task'ов — consumer
     * {@code ServiceTaskListener.on(ServiceTaskCompleteData)} не делает
     * auth-проверки, канал общий). Остаточный риск: член процесса A может
     * ковать completion'ы процесса B (общий канал, identity отправителя
     * брокер листенеру не передаёт) — архитектурное ограничение модели,
     * follow-up (per-process completion-скasing или server-side ownership-check).
     * read — consume + passive-declare + basicGet на job-очередях и их DLQ.
     * Теги нового юзера — пустые (не management, не administrator).
     */
    static Permissions permissionsFor(Set<String> jobTypes) {
        StringBuilder jobs = new StringBuilder();
        for (String job : jobTypes) {
            if (!SAFE_JOB_TYPE.matcher(job).matches()) {
                // Fail-closed: экзотика не превращается в широкие права.
                log.warn("WO-INT-9: job type '{}' outside the safe charset — "
                    + "no broker rights for it (worker will 403 visibly)", job);
                continue;
            }
            if (jobs.length() > 0) jobs.append('|');
            // Вне character-class опасна только точка — экранируем её.
            jobs.append(job.replace(".", "\\."));
        }
        boolean hasJobs = jobs.length() > 0;
        String jobAlt = hasJobs ? jobs.toString() : "a^";
        // "a^" (never-matches) вместо "^$" — Erlang re обязан принять паттерн;
        // пустое членство: deny-all по построению (verifier HOLD #2).
        String jobsAndDlq = "^zorrobpm\\.jobs\\.(" + jobAlt + ")(\\.dlq)?$";
        String jobsOnly = "^zorrobpm\\.jobs\\.(" + jobAlt + ")$";
        String write = hasJobs
            ? "(" + jobsOnly + ")|(^zorrobpm\\.complete-service-task$)"
            : jobsOnly;
        return new Permissions(jobsAndDlq, write, jobsAndDlq);
    }

    /** Пароль — 40 символов [A-Za-z0-9] через SecureRandom (240 бит). */
    static String generatePassword() {
        StringBuilder sb = new StringBuilder(40);
        for (int i = 0; i < 40; i++) {
            sb.append(PASSWORD_ALPHABET.charAt(RANDOM.nextInt(PASSWORD_ALPHABET.length())));
        }
        return sb.toString();
    }

    /**
     * RabbitMQ {@code password_hash}: {@code base64(salt || sha256(salt ||
     * password))}, соль — 4 случайных байта. Тот же алгоритм, что
     * {@code rabbitmqctl} (salted SHA-256, не bcrypt) — иначе брокер не примет.
     */
    static String rabbitPasswordHash(String password) {
        try {
            byte[] salt = new byte[4];
            RANDOM.nextBytes(salt);
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update(salt);
            sha256.update(password.getBytes(StandardCharsets.UTF_8));
            byte[] salted = new byte[salt.length + 32];
            System.arraycopy(salt, 0, salted, 0, salt.length);
            System.arraycopy(sha256.digest(), 0, salted, salt.length, 32);
            return Base64.getEncoder().encodeToString(salted);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private void upsertBrokerUser(String login, String passwordHash, boolean alreadyProvisioned) {
        // WO-SEC-83 (NEW4-05): перед PUT — GET. Существующий аккаунт без
        // маркера при первом провижининге (флаг ещё false) — ЧУЖОЙ
        // (мониторинг, другое приложение, guest): отказываем 409, не
        // перезаписываем пароль и не трогаем теги. Матрица:
        //   GET 404 → создать с маркером (флаг неважен);
        //   есть + маркер + флаг → PUT с маркером (норма: ротация своего);
        //   есть + маркер + без флага → 409 (orphan/другая инсталляция — не наш userId);
        //   есть + без маркера + флаг → PUT с маркером (наш до-маркерный аккаунт
        //     WO-INT-9, self-heal маркера при ротации);
        //   есть + без маркера + без флага → 409 (чужой).
        BrokerUser existing = getBrokerUser(login);
        if (existing != null && !existing.tags().contains(MANAGED_TAG) && !alreadyProvisioned) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Broker account '" + login + "' exists and is not managed by ZorroBPM — "
                    + "refusing to overwrite (remove it on the broker first)");
        }
        if (existing != null && existing.tags().contains(MANAGED_TAG) && !alreadyProvisioned) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Broker account '" + login + "' is managed by ZorroBPM but belongs to "
                    + "another user — refusing to take over");
        }
        // PUT /api/users/{login} создаёт, если нет (критерий: первая генерация).
        // Теги — маркер (не management, не administrator, V10-c; маркер виден
        // и в management UI как след происхождения).
        String body = "{\"password_hash\":" + jsonString(passwordHash)
            + ",\"tags\":" + jsonString(MANAGED_TAG) + "}";
        HttpResponse<String> response = mgmtPut("/api/users/" + encode(login), body);
        if (response.statusCode() != 200 && response.statusCode() != 201 && response.statusCode() != 204) {
            throw brokerUnavailable("provision broker user " + login, response);
        }
    }

    /**
     * WO-SEC-83 (NEW4-04): отзыв брокер-доступа SYSTEM-юзера (деактивация).
     * No-op, если провижининга не было (флаг false — аккаунта нет, удалять
     * нечего) или тип не SYSTEM. Иначе — {@code DELETE /api/users/{login)}
     * (атомарно убивает и аутентификацию, и все permissions одним вызовом —
     * чище, чем обнуление permissions с оставленным логином) + сброс флага.
     * Брокер недоступен → 503 наружу: вызывающая транзакция падает целиком
     * (fail-closed, зеркало V10-b — деактивация в БД при живом broker-доступе
     * была бы дрейф-дырой, а не отзывом).
     */
    public void deprovisionUser(UUID userId) {
        UiUserEntity user = uiUserRepository.findById(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (!"SYSTEM".equals(user.getUserType())) {
            return;
        }
        if (!user.isRabbitmqProvisioned()) {
            return;
        }
        deleteBrokerUser(user.getUsername());
        user.setRabbitmqProvisioned(false);
        uiUserRepository.save(user);
        log.info("RabbitMQ access revoked for system user={}", user.getUsername());
    }

    /** Брокер-юзер как его видит Management API (нужны только теги). */
    record BrokerUser(Set<String> tags) {
    }

    /**
     * WO-SEC-83 (NEW4-05): {@code GET /api/users/{login)} → теги аккаунта.
     * 404 → null (аккаунта нет — можно создавать). Парсинг — строго tags-массив
     * ответа (не подстрока по всему телу: base64-хэш дефиса не содержит, а login
     * в поле name — может, подстрочный contains дал бы false-positive «наш»).
     */
    BrokerUser getBrokerUser(String login) {
        try {
            String credentials = brokerAdminUser + ":" + brokerAdminPassword;
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(normalizeBaseUrl(managementBaseUrl) + "/api/users/" + encode(login)))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString(credentials.getBytes(StandardCharsets.UTF_8)))
                .GET()
                .build();
            HttpResponse<String> response =
                client().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 404) {
                return null;
            }
            if (response.statusCode() != 200) {
                throw brokerUnavailable("read broker user " + login, response);
            }
            return new BrokerUser(parseTags(response.body()));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "RabbitMQ broker unavailable: " + e.getMessage());
        }
    }

    /** Вытаскивает множество тегов из тела GET /api/users. */
    static Set<String> parseTags(String body) {
        Set<String> tags = new HashSet<>();
        String src = body == null ? "" : body;
        // Wire-формат реального брокера — СТРОКА: "tags":"monitoring",
        // пусто — "tags":"". Array-форма ("tags":[...]) — на всякий случай.
        java.util.regex.Matcher str =
            Pattern.compile("\"tags\"\\s*:\\s*\"([^\"]*)\"").matcher(src);
        if (str.find()) {
            for (String part : str.group(1).split(",")) {
                String tag = part.trim();
                if (!tag.isEmpty()) {
                    tags.add(tag);
                }
            }
            return tags;
        }
        java.util.regex.Matcher arr =
            Pattern.compile("\"tags\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(src);
        if (!arr.find()) {
            return tags;
        }
        for (String part : arr.group(1).split(",")) {
            String tag = part.trim();
            if (tag.length() >= 2 && tag.startsWith("\"") && tag.endsWith("\"")) {
                tags.add(tag.substring(1, tag.length() - 1));
            }
        }
        return tags;
    }

    private void setBrokerPermissions(String login, Permissions permissions) {
        String body = "{\"configure\":" + jsonString(permissions.configure())
            + ",\"write\":" + jsonString(permissions.write())
            + ",\"read\":" + jsonString(permissions.read()) + "}";
        HttpResponse<String> response =
            mgmtPut("/api/permissions/" + encode(VHOST) + "/" + encode(login), body);
        if (response.statusCode() != 200 && response.statusCode() != 201 && response.statusCode() != 204) {
            throw brokerUnavailable("set broker permissions for " + login, response);
        }
    }

    private void deleteBrokerUser(String login) {
        try {
            String credentials = brokerAdminUser + ":" + brokerAdminPassword;
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(normalizeBaseUrl(managementBaseUrl) + "/api/users/" + encode(login)))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString(credentials.getBytes(StandardCharsets.UTF_8)))
                .DELETE()
                .build();
            HttpResponse<String> response =
                client().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            // WO-SEC-83 red-team #3: 404 — идемпотентность повторной
            // деактивации (аккаунт уже удалён — не ошибка). Любой другой
            // не-2xx — fail-closed 503 БЕЗ сброса флага (иначе fail-open щель:
            // флаг сброшен, аккаунт жив).
            if (response.statusCode() != 200 && response.statusCode() != 201
                && response.statusCode() != 204 && response.statusCode() != 404) {
                throw brokerUnavailable("delete broker user " + login, response);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "RabbitMQ broker unavailable: " + e.getMessage());
        }
    }

    private HttpResponse<String> mgmtPut(String path, String jsonBody) {
        // WO-INT-9: без replace-хака — base-url передаётся как есть. Урок
        // живого прогона: "localhost → ::1 vs IPv4" был ложным следом;
        // настоящий EOF давал незакрытый InputStream stub'а в другом тесте.
        // Хостнеймы вида "rabbitmq" (compose-сеть) обязаны проходить
        // нетронутыми — молчаливая подмена ломала бы прод-wiring.
        String base = normalizeBaseUrl(managementBaseUrl);
        try {
            String credentials = brokerAdminUser + ":" + brokerAdminPassword;
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base + path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString(credentials.getBytes(StandardCharsets.UTF_8)))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
            return client().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            // Fail-closed (V10-b): брокер недоступен — синк не «best-effort»,
            // вызывающая membership-транзакция падает целиком (503).
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "RabbitMQ broker unavailable: " + e.getMessage());
        }
    }

    private static ResponseStatusException brokerUnavailable(String action, HttpResponse<String> response) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
            "RabbitMQ broker refused to " + action + " (HTTP " + response.statusCode() + ")");
    }

    static String normalizeBaseUrl(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Минимальный JSON-escape для наших значений (base64/regex — без контроля). */
    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private HttpClient client() {
        HttpClient result = httpClient;
        if (result == null) {
            synchronized (this) {
                result = httpClient;
                if (result == null) {
                    // WO-INT-9: явный HTTP/1.1. JDK HttpClient по умолчанию
                    // шлёт HTTP/2-prior-knowledge (h2c) — Cowboy/management API
                    // брокера такой PUT не понимает и рвёт соединение (EOF),
                    // GET при этом работает — поймано живым матричным прогоном
                    // (HTTP_1_1 → 201, HTTP_2 → EOF) на rabbitmq:4.1.
                    httpClient = result = HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(10))
                        .build();
                }
            }
        }
        return result;
    }

    /** Три regex permissions одного vhost (configure/write/read). */
    record Permissions(String configure, String write, String read) {
    }
}
