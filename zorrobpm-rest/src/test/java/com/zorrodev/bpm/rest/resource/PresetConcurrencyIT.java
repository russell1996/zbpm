package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.UpdatePresetDTO;
import com.zorrodev.bpm.contract.dto.VariablePresetDTO;
import com.zorrodev.bpm.contract.exception.ApiException;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberId;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.ProcessMemberRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.repository.VariablePresetRepository;
import com.zorrodev.bpm.engine.security.PasswordHasher;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.VariablePresetService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-VT-1 раунд 2 (Б-1, Б-2 + ServicePrincipal-покрытие).
 *
 * <p>Два реальных потока/транзакции (V6): каждый поток зовёт настоящий
 * {@code VariablePresetService} (синглтон-прокси, {@code @Transactional} открывает
 * отдельную транзакцию на поток). Тестовый метод сам НЕ транзакционный.
 * Изолированная mem-БД + LOCK_TIMEOUT с запасом — прецедент
 * {@code CompletionInstanceLockIT} (потоки держат FOR UPDATE дольше дефолтной
 * секунды H2).
 *
 * <p>Б-1: два потока одновременно правят один шаблон с одной версией —
 * ровно один 200 (версия стала 1, данные победителя целы), второй —
 * 409 PRESET_CONFLICT. Без построчного лока оба видят v0 и оба пишут v1
 * (потерянное обновление, 20/20 у красной команды).
 *
 * <p>Б-2: 10 потоков × 25 созданий (уникальные имена, один владелец/ключ) —
 * итог ровно 200, остальные 50 — PRESET_LIMIT_EXCEEDED. Без лока владельца
 * count-pre-check не атомарен (207&gt;200 у красной команды).
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:vt1race;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000"
})
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PresetConcurrencyIT {

    /** Раундов гонки Б-1 внутри теста (красная команда показала 20/20). */
    private static final int UPDATE_ROUNDS = 20;
    /** Потоков × созданий в гонке Б-2 (250 попыток при лимите 200). */
    private static final int CREATE_THREADS = 10;
    private static final int CREATE_PER_THREAD = 25;

    @Autowired private VariablePresetService presetService;
    @Autowired private VariablePresetRepository presetRepository;
    @Autowired private UiUserRepository userRepository;
    @Autowired private ProcessMemberRepository processMemberRepository;
    @Autowired private ProcessRepository processRepository;
    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private PasswordHasher passwordHasher;

    private UUID updateOwnerId;
    private UUID createOwnerId;
    private UUID grantsOwnerId;
    private Principal updateOwner;
    private Principal createOwner;
    private String processKey;
    private UUID processId;

    @BeforeAll
    void setup() throws Exception {
        // Свои пользователи (по одному на тест — квоты 200/ключ/владелец
        // независимы, порядок тестов значения не имеет).
        updateOwnerId = createAndSaveUser("vt1raceupd");
        createOwnerId = createAndSaveUser("vt1racecrt");
        grantsOwnerId = createAndSaveUser("vt1racegrt");
        updateOwner = new Principal.UserPrincipal(updateOwnerId, "vt1raceupd", "USER");
        createOwner = new Principal.UserPrincipal(createOwnerId, "vt1racecrt", "USER");

        String bpmn = Files.readString(
            Paths.get("src/test/files/assignee-task.bpmn"), StandardCharsets.UTF_8);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        processKey = model.getKey();
        ProcessEntity process = processRepository.findByDefinitionKey(processKey).orElseThrow();
        processId = process.getId();

        addMember(processId, updateOwnerId, "DESIGNER");
        addMember(processId, createOwnerId, "DESIGNER");
        addMember(processId, grantsOwnerId, "DESIGNER");
    }

    // ==================== Б-1: concurrent update одной версии ====================

    @Test
    void concurrentUpdate_sameVersion_onlyOneWins_secondGets409() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < UPDATE_ROUNDS; round++) {
                VariablePresetDTO created = presetService.create(updateOwner, payload(
                    "race-u-" + round + "-" + UUID.randomUUID(), null, "PRIVATE"));
                UUID id = created.getId();

                CyclicBarrier gate = new CyclicBarrier(2);
                Callable<Object> attempt = () -> {
                    gate.await(15, TimeUnit.SECONDS);
                    UpdatePresetDTO dto = new UpdatePresetDTO();
                    dto.setDescription("winner-" + Thread.currentThread().getName());
                    dto.setVersion(0);
                    try {
                        return presetService.update(updateOwner, id, dto);
                    } catch (ApiException e) {
                        return e;
                    }
                };
                Future<Object> f1 = pool.submit(attempt);
                Future<Object> f2 = pool.submit(attempt);
                Object r1 = f1.get(30, TimeUnit.SECONDS);
                Object r2 = f2.get(30, TimeUnit.SECONDS);

                List<Object> results = List.of(r1, r2);
                long ok = results.stream().filter(r -> r instanceof VariablePresetDTO).count();
                long conflict = results.stream()
                    .filter(r -> r instanceof ApiException e
                        && e.getStatus() == HttpStatus.CONFLICT
                        && "PRESET_CONFLICT".equals(e.getCode()))
                    .count();
                assertThat(ok).as("раунд %d: ровно один поток побеждает (итоги %s)", round, results).isEqualTo(1);
                assertThat(conflict).as("раунд %d: второй получает 409 PRESET_CONFLICT", round).isEqualTo(1);

                // Данные победителя целы, версия ровно 1 (не 2, не потеряно).
                VariablePresetDTO winner = (VariablePresetDTO)
                    results.stream().filter(r -> r instanceof VariablePresetDTO).findFirst().orElseThrow();
                assertThat(winner.getVersion()).as("раунд %d: версия победителя", round).isEqualTo(1);
                VariablePresetDTO reread = presetService.get(updateOwner, id);
                assertThat(reread.getVersion()).as("раунд %d: финальная версия", round).isEqualTo(1);
                assertThat(reread.getDescription())
                    .as("раунд %d: описание победителя не задето", round)
                    .isEqualTo(winner.getDescription());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ==================== Б-2: concurrent create против лимита 200 ====================

    @Test
    void concurrentCreate_limitNeverExceeded_rejectedWithLimitCode() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CREATE_THREADS);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger okCount = new AtomicInteger();
        List<ApiException> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < CREATE_THREADS; t++) {
                final int thread = t;
                futures.add(pool.submit(() -> {
                    try {
                        start.await(15, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    for (int i = 0; i < CREATE_PER_THREAD; i++) {
                        try {
                            presetService.create(createOwner, payload(
                                "race-c-" + thread + "-" + i + "-" + UUID.randomUUID(), null, "PRIVATE"));
                            okCount.incrementAndGet();
                        } catch (ApiException e) {
                            failures.add(e);
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(120, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        int total = CREATE_THREADS * CREATE_PER_THREAD;
        long inDb = presetRepository.countByOwnerUserIdAndProcessDefinitionKey(createOwnerId, processKey);
        assertThat(inDb)
            .as("лимит 200 держится под гонкой (попыток %d, ok %d, отказов %d)",
                total, okCount.get(), failures.size())
            .isEqualTo(200);
        assertThat(okCount.get()).as("ровно 200 созданий принято").isEqualTo(200);
        assertThat(failures)
            .as("все отказы — PRESET_LIMIT_EXCEEDED, других причин нет")
            .hasSize(total - 200)
            .allMatch(e -> e.getStatus() == HttpStatus.CONFLICT
                && "PRESET_LIMIT_EXCEEDED".equals(e.getCode()));
    }

    // ==================== ServicePrincipal через гранты (оформительское) ====================

    @Test
    void servicePrincipal_withStartGrant_createsAndReads() {
        Principal sa = servicePrincipal(grantsOwnerId, Set.of("START", "VIEW_MEMBERS"));
        VariablePresetDTO created = presetService.create(sa, payload(
            "sa-create-" + UUID.randomUUID(), null, "PRIVATE"));
        assertThat(created.getOwnerUserId()).isEqualTo(grantsOwnerId);

        VariablePresetDTO read = presetService.get(sa, created.getId());
        assertThat(read.getId()).isEqualTo(created.getId());
    }

    @Test
    void servicePrincipal_withoutGrant_createForbidden_andForeignPrivateInvisible() {
        Principal saBare = servicePrincipal(grantsOwnerId, Set.of());
        assertThatThrownBy(() ->
            presetService.create(saBare, payload("sa-bare-" + UUID.randomUUID(), null, "PRIVATE")))
            .isInstanceOf(ApiException.class)
            .matches(e -> ((ApiException) e).getStatus() == HttpStatus.FORBIDDEN
                && "PRESET_FORBIDDEN".equals(((ApiException) e).getCode()));

        // Чужой PRIVATE для ключа без гранта — 404, не 403 (не раскрываем).
        VariablePresetDTO foreignPrivate = presetService.create(updateOwner, payload(
            "sa-foreign-" + UUID.randomUUID(), null, "PRIVATE"));
        assertThatThrownBy(() -> presetService.get(saBare, foreignPrivate.getId()))
            .isInstanceOf(ApiException.class)
            .matches(e -> ((ApiException) e).getStatus() == HttpStatus.NOT_FOUND
                && "PRESET_NOT_FOUND".equals(((ApiException) e).getCode()));
    }

    @Test
    void servicePrincipal_viewerGrant_readsProcess_butCannotEdit() {
        // Создатель — ДРУГОЙ пользователь (иначе ключ идёт owner-путём:
        // actor сервис-ключа — его владелец, и тест был бы вакуумен).
        VariablePresetDTO shared = presetService.create(updateOwner, payload(
            "sa-shared-" + UUID.randomUUID(), null, "PROCESS"));

        Principal saViewer = servicePrincipal(grantsOwnerId, Set.of("VIEW_MEMBERS"));
        assertThat(presetService.get(saViewer, shared.getId()).getId()).isEqualTo(shared.getId());

        UpdatePresetDTO upd = new UpdatePresetDTO();
        upd.setDescription("sa-edit");
        upd.setVersion(0);
        assertThatThrownBy(() -> presetService.update(saViewer, shared.getId(), upd))
            .isInstanceOf(ApiException.class)
            .matches(e -> ((ApiException) e).getStatus() == HttpStatus.FORBIDDEN
                && "PRESET_FORBIDDEN".equals(((ApiException) e).getCode()));
    }

    // ==================== helpers ====================

    private Principal servicePrincipal(UUID ownerUserId, Set<String> permissions) {
        // Гранты лежат на processId (модель WO-INT-4: процесс → права ключа).
        return new Principal.ServicePrincipal(UUID.randomUUID(), ownerUserId,
            permissions.isEmpty() ? Map.of()
                : Map.of(processId, new Principal.Grant(permissions, false)));
    }

    private VariablePresetService.PresetPayload payload(String name, String ref, String visibility) {
        ProcessVariable v = new ProcessVariable();
        v.setName("a");
        v.setType(ProcessVariableType.STRING);
        v.setValue("1");
        return new VariablePresetService.PresetPayload(
            processKey, "START", ref, name, null, List.of(v), visibility);
    }

    private UUID createAndSaveUser(String username) {
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash(passwordHasher.hash("pass" + username.charAt(username.length() - 1)));
        user.setFullName(username);
        user.setRole("USER");
        user.setActive(true);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user).getId();
    }

    private void addMember(UUID processId, UUID userId, String role) {
        ProcessMemberId id = new ProcessMemberId(processId, userId);
        if (processMemberRepository.existsById(id)) {
            return;
        }
        ProcessMemberEntity pm = new ProcessMemberEntity();
        pm.setProcessId(processId);
        pm.setUserId(userId);
        pm.setRole(role);
        pm.setAddedBy(userId);
        pm.setAddedAt(Instant.now());
        processMemberRepository.save(pm);
    }
}
