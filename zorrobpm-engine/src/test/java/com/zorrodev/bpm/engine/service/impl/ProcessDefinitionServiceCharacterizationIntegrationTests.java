package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.ProcessDefinitionsQueryParameters;
import com.zorrodev.bpm.contract.exception.ApiException;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ElementArtifactBindingEntity;
import com.zorrodev.bpm.engine.entity.FormArtifactKind;
import com.zorrodev.bpm.engine.entity.FormEntity;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.repository.BpmnRepository;
import com.zorrodev.bpm.engine.repository.ElementArtifactBindingRepository;
import com.zorrodev.bpm.engine.repository.FormRepository;
import com.zorrodev.bpm.engine.repository.MessageStartSubscriptionRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.TimerStartJobRepository;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.FileService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-DEBT-5a — Phase 0 characterization of {@code ProcessDefinitionServiceImpl}
 * (BPMN deploy). FULLY REAL wiring on H2 (no mocks at all): real parse, real repos,
 * real file store, real DBService, real model cache. Mock-based unit coverage already
 * exists in {@code ProcessDefinitionServiceImplTest}; this class pins OBSERVABLE
 * behavior against a real database.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
class ProcessDefinitionServiceCharacterizationIntegrationTests {

    @Autowired private ProcessDefinitionService service;
    @Autowired private ProcessDefinitionRepository processDefinitionRepository;
    @Autowired private BpmnRepository bpmnRepository;
    @Autowired private MessageStartSubscriptionRepository messageStartSubscriptionRepository;
    @Autowired private TimerStartJobRepository timerStartJobRepository;
    @Autowired private ElementArtifactBindingRepository bindingRepository;
    @Autowired private FormRepository formRepository;
    @Autowired private BpmnService bpmnService;
    @Autowired private DBService dbService;
    @Autowired private FileService fileService;

    private final List<UUID> cleanupPdIds = new ArrayList<>();
    private final List<String> cleanupKeys = new ArrayList<>();
    private final List<UUID> cleanupBindings = new ArrayList<>();
    private final List<UUID> cleanupForms = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (String key : cleanupKeys) {
            try {
                dbService.deleteMessageStartSubscriptionsByKey(key);
            } catch (Exception ignored) {
            }
            try {
                dbService.deleteTimerStartJobsByKey(key);
            } catch (Exception ignored) {
            }
        }
        for (UUID id : cleanupBindings) {
            bindingRepository.deleteById(id);
        }
        for (UUID id : cleanupForms) {
            formRepository.deleteById(id);
        }
        for (UUID id : cleanupPdIds) {
            bpmnRepository.deleteById(id);
            processDefinitionRepository.deleteById(id);
        }
        cleanupPdIds.clear();
        cleanupKeys.clear();
        cleanupBindings.clear();
        cleanupForms.clear();
    }

    private static String uniq(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String plainBpmn(String key, String name) throws Exception {
        return Files.readString(Path.of("src/test/files/test1.bpmn"))
            .replace("BPMNPlane_1\" bpmnElement=\"test1", "BPMNPlane_1\" bpmnElement=\"" + key)
            .replace("id=\"test1\" name=\"test1\"", "id=\"" + key + "\" name=\"" + name + "\"");
    }

    private static String messageStartBpmn(String key, String messageName) throws Exception {
        return Files.readString(Path.of("src/test/files/test-rel15-msg-deploy.bpmn"))
            .replace("rel15-msg-deploy", key)
            .replace("rel15-msg-received", messageName);
    }

    private static String timerStartBpmn(String key) throws Exception {
        return Files.readString(Path.of("src/test/files/test-rel15-timer-deploy.bpmn"))
            .replace("rel15-timer-deploy", key);
    }

    private static String sha256(String bpmn) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return Base64.getEncoder().encodeToString(digest.digest(bpmn.getBytes(StandardCharsets.UTF_8)));
    }

    // ==================== happy path ====================

    @Test
    void addProcessDefinition_plainDeploy_createsVersionFileAndCache() throws Exception {
        String key = uniq("pd");
        String bpmn = plainBpmn(key, key);

        ProcessDefinition result = service.addProcessDefinition(bpmn);

        assertThat(result.getKey()).isEqualTo(key);
        assertThat(result.getVersion()).isEqualTo(1);
        // Version row is ACTIVE in the DB
        ProcessDefinitionEntity row = processDefinitionRepository.findById(result.getId()).orElseThrow();
        assertThat(row.getDeploymentState()).isEqualTo(ProcessDefinitionEntity.STATE_ACTIVE);
        assertThat(row.getSha256()).isEqualTo(sha256(bpmn));
        // BPMN file persisted and readable back
        assertThat(fileService.getFileBytes(result.getId())).hasValue(bpmn);
        // Model cache filled (observable after commit)
        assertThat(bpmnService.getProcessDefinitionModelById(result.getId()).getKey()).isEqualTo(key);
        cleanupPdIds.add(result.getId());
    }

    @Test
    void addProcessDefinition_messageStart_registersSubscription() throws Exception {
        String key = uniq("pdm");
        String msg = "msg-" + UUID.randomUUID().toString().substring(0, 8);
        ProcessDefinition result = service.addProcessDefinition(messageStartBpmn(key, msg));

        assertThat(result.getVersion()).isEqualTo(1);
        boolean present = messageStartSubscriptionRepository.findAll().stream()
            .anyMatch(s -> key.equals(s.getProcessKey()) && msg.equals(s.getMessageName()));
        assertThat(present).as("message start subscription row for the deployed key").isTrue();
        cleanupPdIds.add(result.getId());
        cleanupKeys.add(key);
    }

    @Test
    void addProcessDefinition_timerStart_registersTimerJob() throws Exception {
        String key = uniq("pdt");
        ProcessDefinition result = service.addProcessDefinition(timerStartBpmn(key));

        assertThat(result.getVersion()).isEqualTo(1);
        boolean present = timerStartJobRepository.findAll().stream()
            .anyMatch(j -> key.equals(j.getProcessKey()));
        assertThat(present).as("timer start job row for the deployed key").isTrue();
        cleanupPdIds.add(result.getId());
        cleanupKeys.add(key);
    }

    @Test
    void addProcessDefinition_secondDeployNewContent_newVersionOldUntouched() throws Exception {
        String key = uniq("pdv");
        ProcessDefinition first = service.addProcessDefinition(plainBpmn(key, key + "-v1"));
        ProcessDefinition second = service.addProcessDefinition(plainBpmn(key, key + "-v2"));

        assertThat(first.getVersion()).isEqualTo(1);
        assertThat(second.getVersion()).isEqualTo(2);
        assertThat(second.getId()).isNotEqualTo(first.getId());
        // Old version row untouched (still ACTIVE, old sha)
        ProcessDefinitionEntity oldRow = processDefinitionRepository.findById(first.getId()).orElseThrow();
        assertThat(oldRow.getDeploymentState()).isEqualTo(ProcessDefinitionEntity.STATE_ACTIVE);
        assertThat(oldRow.getSha256()).isNotEqualTo(second.getSha256());
        assertThat(processDefinitionRepository.findMaxByKey(key)).hasValue(2);
        cleanupPdIds.add(first.getId());
        cleanupPdIds.add(second.getId());
    }

    @Test
    void addProcessDefinition_version2_carriesForwardBindingsWithRepin() throws Exception {
        String key = uniq("pdc");
        ProcessDefinition first = service.addProcessDefinition(plainBpmn(key, key + "-v1"));
        // Seed: form artifact v3 + binding of v1 pinned to v1
        UUID formId = UUID.randomUUID();
        FormEntity form = new FormEntity();
        form.setId(formId);
        form.setFormKey("cb-" + key);
        form.setVersion(3);
        form.setKind(FormArtifactKind.FORM_JS);
        form.setSchemaJson("{\"components\":[]}");
        form.setCreatedAt(Instant.now());
        formRepository.saveAndFlush(form);
        cleanupForms.add(formId);
        UUID bindingId = UUID.randomUUID();
        ElementArtifactBindingEntity b = new ElementArtifactBindingEntity();
        b.setId(bindingId);
        b.setProcessDefinitionId(first.getId());
        b.setProcessDefinitionVersion(1);
        b.setElementId("startEvent");
        b.setArtifactKey("cb-" + key);
        b.setArtifactVersion(1);
        b.setCreatedAt(Instant.now());
        bindingRepository.saveAndFlush(b);
        cleanupBindings.add(bindingId);

        ProcessDefinition second = service.addProcessDefinition(plainBpmn(key, key + "-v2"));

        assertThat(second.getVersion()).isEqualTo(2);
        // WO-VM-9a carry-forward: v2 gets the binding re-pinned to the CURRENT artifact version
        List<ElementArtifactBindingEntity> v2bindings =
            bindingRepository.findByProcessDefinitionId(second.getId());
        assertThat(v2bindings).hasSize(1);
        assertThat(v2bindings.get(0).getElementId()).isEqualTo("startEvent");
        assertThat(v2bindings.get(0).getArtifactKey()).isEqualTo("cb-" + key);
        assertThat(v2bindings.get(0).getArtifactVersion()).isEqualTo(3);
        cleanupBindings.add(v2bindings.get(0).getId());
        cleanupPdIds.add(first.getId());
        cleanupPdIds.add(second.getId());
    }

    // ==================== idempotent repair (WO-REL-15) ====================

    @Test
    void addProcessDefinition_pendingRow_redeployRepairsWithoutDuplicate() throws Exception {
        String key = uniq("pdr");
        String msg = "msg-" + UUID.randomUUID().toString().substring(0, 8);
        String bpmn = messageStartBpmn(key, msg);
        // Simulate an interrupted deploy: version row PENDING, no artifacts at all
        UUID id = UUID.randomUUID();
        ProcessDefinitionEntity pending = new ProcessDefinitionEntity();
        pending.setId(id);
        pending.setKey(key);
        pending.setName(key);
        pending.setVersion(1);
        pending.setSha256(sha256(bpmn));
        pending.setCreatedAt(Instant.now());
        pending.setDeploymentState(ProcessDefinitionEntity.STATE_PENDING);
        processDefinitionRepository.saveAndFlush(pending);
        cleanupPdIds.add(id);
        cleanupKeys.add(key);

        ProcessDefinition result = service.addProcessDefinition(bpmn);

        // Same row repaired, not duplicated
        assertThat(result.getId()).isEqualTo(id);
        assertThat(result.getVersion()).isEqualTo(1);
        assertThat(processDefinitionRepository.findById(id).orElseThrow().getDeploymentState())
            .isEqualTo(ProcessDefinitionEntity.STATE_ACTIVE);
        long rows = processDefinitionRepository.findAll().stream()
            .filter(e -> key.equals(e.getKey())).count();
        assertThat(rows).isEqualTo(1);
        // Missing artifacts re-assembled
        assertThat(fileService.getFileBytes(id)).hasValue(bpmn);
        boolean sub = messageStartSubscriptionRepository.findAll().stream()
            .anyMatch(s -> key.equals(s.getProcessKey()));
        assertThat(sub).isTrue();
        assertThat(bpmnService.getProcessDefinitionModelById(id).getKey()).isEqualTo(key);
    }

    @Test
    void addProcessDefinition_activeRedeploySameSha_fastPathNoDuplicate() throws Exception {
        String key = uniq("pdf");
        String bpmn = plainBpmn(key, key);
        ProcessDefinition first = service.addProcessDefinition(bpmn);

        ProcessDefinition second = service.addProcessDefinition(bpmn);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getVersion()).isEqualTo(1);
        long rows = processDefinitionRepository.findAll().stream()
            .filter(e -> key.equals(e.getKey())).count();
        assertThat(rows).isEqualTo(1);
        cleanupPdIds.add(first.getId());
    }

    // ==================== WO-REL-18 ====================

    @Test
    void addProcessDefinition_serviceTaskWithoutJob_400() throws Exception {
        String bpmn = Files.readString(Path.of("src/test/files/test-service-task-no-job.bpmn"));

        assertThatThrownBy(() -> service.addProcessDefinition(bpmn))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> {
                ApiException api = (ApiException) e;
                assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(api.getCode()).isEqualTo("SERVICE_TASK_MISSING_JOB");
            });
        // Rejected before any write: no version row for this content
        assertThat(processDefinitionRepository.findBySha256(sha256(bpmn))).isEmpty();
    }

    // ==================== getters ====================

    @Test
    void getProcessDefinitionById_foundAndMissing() throws Exception {
        String key = uniq("pdg");
        ProcessDefinition created = service.addProcessDefinition(plainBpmn(key, key));

        assertThat(service.getProcessDefinitionById(created.getId()))
            .hasValueSatisfying(d -> {
                assertThat(d.getKey()).isEqualTo(key);
                assertThat(d.getVersion()).isEqualTo(1);
                assertThat(d.getSha256()).isEqualTo(created.getSha256());
            });
        assertThat(service.getProcessDefinitionById(UUID.randomUUID())).isEmpty();
        cleanupPdIds.add(created.getId());
    }

    @Test
    void getProcessDefinitions_allowedPdIdsFilter() throws Exception {
        String keyA = uniq("pda");
        String keyB = uniq("pdb");
        ProcessDefinition a = service.addProcessDefinition(plainBpmn(keyA, keyA));
        ProcessDefinition b = service.addProcessDefinition(plainBpmn(keyB, keyB));
        ProcessDefinitionsQueryParameters params = new ProcessDefinitionsQueryParameters();
        params.setPageIndex(0);
        params.setPageSize(20);
        params.setProcessDefinitionKey(keyA);

        // Empty set → default DENY (WO-ARCH-1a pattern): totally empty page
        PagedDataDTO<ProcessDefinition> denied =
            service.getProcessDefinitions(params, java.util.Set.of());
        assertThat(denied.getTotalElements()).isZero();
        assertThat(denied.getData()).isEmpty();

        // Null → no filtering (delegates to the unfiltered overload)
        PagedDataDTO<ProcessDefinition> all = service.getProcessDefinitions(params, null);
        assertThat(all.getData()).extracting(ProcessDefinition::getKey).contains(keyA);

        // Non-empty set → only allowed ids
        PagedDataDTO<ProcessDefinition> filtered =
            service.getProcessDefinitions(params, java.util.Set.of(a.getId()));
        assertThat(filtered.getData()).extracting(ProcessDefinition::getKey).containsExactly(keyA);

        // Unfiltered overload with key filter
        PagedDataDTO<ProcessDefinition> byKey = service.getProcessDefinitions(params);
        assertThat(byKey.getData()).extracting(ProcessDefinition::getKey).containsExactly(keyA);
        cleanupPdIds.add(a.getId());
        cleanupPdIds.add(b.getId());
    }
}
