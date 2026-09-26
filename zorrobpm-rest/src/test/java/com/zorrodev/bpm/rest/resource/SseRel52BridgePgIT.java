package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberEntity;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessMemberRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.PasswordHasher;
import com.zorrodev.bpm.engine.security.Principal;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * WO-REL-52, критерии A1+A2: нагрузочный harness на реальном PostgreSQL.
 *
 * <p>A1: N SSE-клиентов × 50 событий/с — измеренная латентность и число SQL
 * на событие ДО и ПОСЛЕ фикса (числа — в отчёте, не проза). A2: число
 * SQL-запросов liveness-проверки на событие перестаёт расти линейно с числом
 * клиентов (группировка по principal: 1 row-read на пользователя, а не
 * 1 на клиента).
 *
 * <p>Full-context: реальный бин SseEventStreamService, реальная БД (PG),
 * реальные DomainEvent-ряды (позиции назначает FeedPositionAssigner, как в
 * SseRevocationIT). SQL считается Hibernate-статистикой (прецедент
 * QueryServiceBulkLoadingIntegrationTests — включается программно, без
 * отдельного контекста).
 *
 * <p>Прогон: governance/runbooks/pg-it-run.md
 * (docker compose -f ci/docker-compose.pg.yml ...).
 */
@Tag("pg")
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SseRel52BridgePgIT {

    @Autowired private SseEventStreamService sseEventStreamService;
    @Autowired private UiUserRepository userRepository;
    @Autowired private ProcessRepository processRepository;
    @Autowired private ProcessDefinitionRepository processDefinitionRepository;
    @Autowired private ProcessMemberRepository processMemberRepository;
    @Autowired private PasswordHasher passwordHasher;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private com.zorrodev.bpm.engine.repository.DomainEventRepository domainEventRepository;
    @Autowired private com.zorrodev.bpm.engine.scheduler.FeedPositionAssigner feedPositionAssigner;

    private static String cfg(String key, String dflt) {
        String v = System.getenv(key);
        if (v == null) v = System.getProperty(key);
        return v != null ? v : dflt;
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        String host = cfg("PG_HOST", "127.0.0.1");
        String port = cfg("PG_PORT", "55432");
        String db = cfg("PG_DB", "zorrobpm-db");
        String user = cfg("PG_USER", "zorrodev");
        String pass = cfg("PG_PASSWORD", "zorrodev");

        registry.add("spring.datasource.url",
            () -> "jdbc:postgresql://" + host + ":" + port + "/" + db + "?sslmode=disable");
        registry.add("spring.datasource.username", () -> user);
        registry.add("spring.datasource.password", () -> pass);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.liquibase.enabled", () -> "true");
    }

    @BeforeEach
    void clearListeners() {
        sseEventStreamService.clearEventListeners();
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private UUID createUser(String prefix) {
        UUID id = UUID.randomUUID();
        UiUserEntity u = new UiUserEntity();
        u.setId(id);
        u.setUsername(prefix + "-" + id.toString().substring(0, 8));
        u.setPasswordHash(passwordHasher.hash("SseRel52!secure"));
        u.setFullName(prefix + " User");
        u.setEmail(prefix + "-" + id.toString().substring(0, 8) + "@example.com");
        u.setRole("USER");
        u.setUserType("HUMAN");
        u.setActive(true);
        u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        userRepository.save(u);
        return id;
    }

    private String pdIdOf(UUID processId) {
        ProcessEntity proc = processRepository.findById(processId).orElseThrow();
        return processDefinitionRepository.findAll().stream()
            .filter(d -> proc.getDefinitionKey().equals(d.getKey()))
            .findFirst().orElseThrow().getId().toString();
    }

    /**
     * WO-REL-52 A1: штамп t0 ставится МЕЖДУ save ряда и fan-out (sequence
     * известен сразу после save — до assign/onDomainEvent), иначе синхронный
     * fan-out обгоняет штамп и семпл теряется (поймано: 43 семпла из 25000).
     * t0 включает assignPendingPositions — честно, это часть пути доставки.
     */
    private long pushEvent(String pdId) {
        return pushEventStamped(pdId, null);
    }

    private long pushEventStamped(String pdId, Map<Long, Long> sentAtNanos) {
        com.zorrodev.bpm.engine.entity.DomainEventEntity e =
            new com.zorrodev.bpm.engine.entity.DomainEventEntity();
        e.setId(UUID.randomUUID());
        e.setType("process-instance.started");
        e.setVersion(1);
        e.setOccurredAt(Instant.now());
        e.setData(Map.of());
        e.setProcessDefinitionId(UUID.fromString(pdId));
        com.zorrodev.bpm.engine.entity.DomainEventEntity saved = domainEventRepository.save(e);
        long realSequence = saved.getSequence();
        if (sentAtNanos != null) {
            sentAtNanos.put(realSequence, System.nanoTime());
        }
        feedPositionAssigner.assignPendingPositions();
        sseEventStreamService.onDomainEvent(
            "{\"sequence\":" + realSequence + ",\"id\":\"" + saved.getId() + "\","
                + "\"type\":\"process-instance.started\",\"version\":1,"
                + "\"occurredAt\":\"2026-09-25T00:00:00Z\","
                + "\"processDefinitionId\":\"" + pdId + "\","
                + "\"processInstanceId\":\"" + UUID.randomUUID() + "\",\"data\":{}}");
        return realSequence;
    }

    /**
     * A1+A2: 500 клиентов (50 пользователей × 10 вкладок — per-subject cap,
     * SseEventStreamService.maxClientsPerSubject) × 50 событий — латентность
     * p99 окна + SQL liveness на событие. Группировка: ≤2 SQL/пользователь/
     * событие (securityState + liveView-права из кэша после первого события),
     * итого окно ~50×(1–2) SQL, а не 500×2.
     *
     * <p>p99 — измерение, не порог: абсолютная латентность зависит от железа
     * стенда, границей регрессии служит SQL-стоимость (детерминирована).
     * P-67: конкретные числа (prepareQueryCount дельта + p99 мс), не «быстро».
     */
    @org.junit.jupiter.api.Timeout(value = 300, unit = TimeUnit.SECONDS)
    @Test
    void fiveHundredClientsFiftyUsers_fiftyEvents_boundedLivenessQueries() throws Exception {
        statistics().setStatisticsEnabled(true);

        String key = "sseRel52_" + UUID.randomUUID();
        UUID processId = UUID.randomUUID();
        ProcessEntity proc = new ProcessEntity();
        proc.setId(processId);
        proc.setDefinitionKey(key);
        proc.setName("SSE rel52 proc");
        proc.setCreatedAt(Instant.now());
        processRepository.save(proc);
        ProcessDefinitionEntity pd = new ProcessDefinitionEntity();
        pd.setId(UUID.randomUUID());
        pd.setKey(key);
        pd.setName("SSE rel52 def");
        pd.setVersion(1);
        pd.setSha256("sha-rel52-" + UUID.randomUUID());
        pd.setCreatedAt(Instant.now());
        processDefinitionRepository.save(pd);
        String pdId = pdIdOf(processId);

        // 50 пользователей × 10 клиентов (вкладок) = 500 потоков (WO A1:
        // 500 клиентов; per-subject cap = 10 — больше вкладок на юзера
        // даст 429, см. registerClientInternal).
        int users = 50;
        int perUser = 10;
        List<UUID> userIds = new ArrayList<>();
        List<String> clientIds = new ArrayList<>();
        List<CountDownLatch> delivered = new ArrayList<>();
        Map<String, Integer> clientIndex = new ConcurrentHashMap<>();
        Map<Long, Long> sentAtNanos = new ConcurrentHashMap<>();
        List<Long> latenciesNanos = new CopyOnWriteArrayList<>();
        for (int u = 0; u < users; u++) {
            UUID userId = createUser("sse-rel52");
            userIds.add(userId);
            ProcessMemberEntity pm = new ProcessMemberEntity();
            pm.setProcessId(processId);
            pm.setUserId(userId);
            pm.setRole("OWNER");
            pm.setAddedBy(userId);
            pm.setAddedAt(Instant.now());
            processMemberRepository.save(pm);
            Principal principal = new Principal.UserPrincipal(userId, "rel52-" + u, "USER");
            for (int c = 0; c < perUser; c++) {
                SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
                String clientId = sseEventStreamService.registerClient(
                    emitter, principal, null, null, null);
                clientIndex.put(clientId, clientIds.size());
                clientIds.add(clientId);
                delivered.add(new CountDownLatch(1));
            }
        }
        // Прогрев: первое событие заполняет rights-кэш (30s TTL) и L2-пути —
        // измеряем устоявшееся окно, не холодный старт. WO-QW-5 (NEW2-14):
        // Awaitility на факте доставки прогревочного события хотя бы одному
        // клиенту вместо фиксированного sleep (P-10/WO-OPS-14 — стена 2с либо
        // ждёт зря, либо не дожидается на медленном раннере). Хук —
        // существующий listener-интерфейс (REL-47 §7.2: вызывается ПОСЛЕ
        // реального send), нового прод-кода не потребовалось.
        java.util.concurrent.atomic.AtomicBoolean warmupDelivered =
            new java.util.concurrent.atomic.AtomicBoolean(false);
        sseEventStreamService.addEventListener((cid, envelope) -> warmupDelivered.set(true));
        pushEvent(pdId);
        await().atMost(java.time.Duration.ofSeconds(30)).untilTrue(warmupDelivered);

        sseEventStreamService.clearEventListeners();
        sseEventStreamService.addEventListener((cid, envelope) -> {
            // Латентность доставки: listener вызывается ПОСЛЕ send в emitter
            // (REL-47 §7.2) — это user-visible задержка события, не enqueue.
            Object seqObj = envelope.get("sequence");
            Long t0 = seqObj instanceof Number n ? sentAtNanos.get(n.longValue()) : null;
            if (t0 != null) {
                latenciesNanos.add(System.nanoTime() - t0);
            }
            Integer idx = clientIndex.get(cid);
            if (idx != null) {
                delivered.get(idx).countDown();
            }
        });

        statistics().clear();
        long queriesBefore = statistics().getPrepareStatementCount();
        int events = 50;
        for (int i = 0; i < events; i++) {
            pushEventStamped(pdId, sentAtNanos);
        }
        // Ждём доставки всем 500 клиентам (хотя бы по одному событию окна).
        for (CountDownLatch latch : delivered) {
            assertThat(latch.await(120, TimeUnit.SECONDS)).isTrue();
        }
        long queriesAfter = statistics().getPrepareStatementCount();
        long windowQueries = queriesAfter - queriesBefore;

        // A1: p99 латентности доставки по всем семплам окна (один семпл =
        // одно событие одному клиенту; listener — после send).
        List<Long> sorted = new ArrayList<>(latenciesNanos);
        java.util.Collections.sort(sorted);
        double p99ms = sorted.isEmpty() ? -1
            : sorted.get((int) Math.ceil(0.99 * sorted.size()) - 1) / 1_000_000.0;

        // A2: liveness-SQL окна НЕ растёт как клиенты×события. Верхняя граница
        // щедрая (событийные записи + позиции + права), но ловит линейный
        // рост по клиентам: 500 клиентов × 50 событий × 2 SQL = 50000 при
        // старом per-client lookup; группировка держит окно на ~порядки ниже.
        // Измерено на реальном PG (тот же тест, тот же стенд):
        //   ДО, 100 клиентов (per-client lookup): 5152 SQL / 50 событий = 103/событие
        //   ПОСЛЕ, 500 клиентов (группировка):     2602 SQL / 50 событий =  52/событие,
        //     p99 доставки 29.4мс на 25000 семплах (listener после send).
        // Конкретные числа — в лог (A1), ассерт — граница регрессии.
        long perEvent = windowQueries / events;
        org.slf4j.LoggerFactory.getLogger(SseRel52BridgePgIT.class).info(
            "SSE REL-52 A1: {} clients ({} users) x {} events: {} SQL total, {} SQL/event, p99 delivery latency {} ms ({} samples)",
            clientIds.size(), users, events, windowQueries, perEvent, String.format("%.1f", p99ms), sorted.size());
        assertThat(perEvent)
            .as("liveness+delivery SQL per event must not scale with client count (500 clients, 50 users)")
            .isLessThan(100);
        assertThat(sorted)
            .as("latency window measured (listener fired after send for window events)")
            .isNotEmpty();

        for (String clientId : clientIds) {
            sseEventStreamService.removeClient(clientId);
        }
    }
}
