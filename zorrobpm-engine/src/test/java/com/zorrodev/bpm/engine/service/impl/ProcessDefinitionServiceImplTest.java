package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.ProcessDefinitionsQueryParameters;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.service.FileService;
import com.zorrodev.bpm.engine.service.AdvisoryDeployLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * WO-DEBT-5c: orchestration tests. The service now delegates every deployment step to
 * {@link ProcessDefinitionVersioning}/{@link DeploymentArtifactRegistrar}/
 * {@link DeploymentPostCommitActions} — these tests pin THAT the right delegate is called
 * with the right arguments in the right order, not the delegate's inner behavior (owned by
 * {@code ProcessDefinitionVersioningTest}/{@code DeploymentArtifactRegistrarTest}/
 * {@code DeploymentPostCommitActionsTest}, not duplicated here).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProcessDefinitionServiceImplTest {

    @Mock
    private ProcessDefinitionRepository processDefinitionRepository;

    @Mock
    private com.zorrodev.bpm.engine.repository.ProcessRepository processRepository;

    @Mock
    private FileService fileService;

    @Mock
    private com.zorrodev.bpm.engine.repository.ElementArtifactBindingRepository bindingRepository;

    @Mock
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    @Mock
    private ProcessDefinitionVersioning versioning;

    @Mock
    private DeploymentArtifactRegistrar artifactRegistrar;

    @Mock
    private DeploymentPostCommitActions postCommitActions;

    @Mock
    private AdvisoryDeployLock advisoryDeployLock;

    private final BpmnParseServiceImpl bpmnParseService = new BpmnParseServiceImpl();

    private ProcessDefinitionServiceImpl service;

    @BeforeEach
    void setUp() {
        // TransactionTemplate.execute runs the callback directly in unit tests
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            org.springframework.transaction.support.TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(null);
        });
        when(processRepository.findByArchivedTrue()).thenReturn(List.of());
        when(processRepository.findByDefinitionKey(anyString())).thenReturn(Optional.empty());
        service = new ProcessDefinitionServiceImpl(
            processDefinitionRepository,
            processRepository,
            bpmnParseService,
            fileService,
            bindingRepository,
            transactionTemplate,
            versioning,
            artifactRegistrar,
            postCommitActions,
            advisoryDeployLock
        );
    }

    /**
     * Stubs the versioning delegate to build a real (unsaved) entity from the invocation
     * arguments, pinned to the given version. The +1 arithmetic itself is owned by
     * {@code ProcessDefinitionVersioningTest} — here the delegate is a boundary.
     */
    private void stubVersioning(String key, int version) {
        when(versioning.createNewVersionEntity(eq(key), anyString(), anyString(), any(), any(), any()))
            .thenAnswer(inv -> {
                ProcessDefinitionEntity e = new ProcessDefinitionEntity();
                e.setId(inv.getArgument(3));
                e.setKey(inv.getArgument(0));
                e.setName(inv.getArgument(1));
                e.setVersion(version);
                e.setSha256(inv.getArgument(2));
                e.setCreatedAt(Instant.now());
                e.setStartFormKey(inv.getArgument(4));
                e.setVersionTag(inv.getArgument(5));
                return e;
            });
    }

    @Test
    void getProcessDefinitionById_returnsMappedDTOWhenFound() {
        UUID id = UUID.randomUUID();
        ProcessDefinitionEntity entity = entity(id, "key1", 1);
        when(processDefinitionRepository.findById(id)).thenReturn(Optional.of(entity));

        Optional<ProcessDefinition> result = service.getProcessDefinitionById(id);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(id);
        assertThat(result.get().getKey()).isEqualTo("key1");
        assertThat(result.get().getVersion()).isEqualTo(1);
    }

    @Test
    void getProcessDefinitionById_returnsEmptyWhenMissing() {
        UUID id = UUID.randomUUID();
        when(processDefinitionRepository.findById(id)).thenReturn(Optional.empty());

        assertThat(service.getProcessDefinitionById(id)).isEmpty();
    }

    @Test
    void addProcessDefinition_newSha_savesAndPublishes() throws IOException {
        String bpmn = Files.readString(Path.of("src/test/files/test1.bpmn"));

        when(processDefinitionRepository.findBySha256(anyString())).thenReturn(Optional.empty());
        stubVersioning("test1", 3);
        // Snapshot each save's arguments AT CALL TIME (Mockito keeps references, so a plain
        // captor/matcher would see the entity already flipped to ACTIVE).
        List<ProcessDefinitionEntity> saved = new ArrayList<>();
        List<String> saveStates = new ArrayList<>();
        when(processDefinitionRepository.save(any(ProcessDefinitionEntity.class))).thenAnswer(inv -> {
            ProcessDefinitionEntity e = inv.getArgument(0);
            saved.add(e);
            saveStates.add(e.getDeploymentState());
            return e;
        });

        ProcessDefinition result = service.addProcessDefinition(bpmn);

        // WO-REL-15: two writes inside the single deployment transaction — PENDING first, ACTIVE last
        assertThat(saveStates).containsExactly(
            ProcessDefinitionEntity.STATE_PENDING, ProcessDefinitionEntity.STATE_ACTIVE);
        assertThat(saved).hasSize(2);
        assertThat(saved.get(1).getKey()).isEqualTo("test1");
        assertThat(saved.get(1).getVersion()).isEqualTo(3);

        // Orchestration: version built by the delegate, file by fileService, everything else
        // by the two other delegates — in the WO-REL-15 order (version → file → starts →
        // carry-forward → ACTIVE-save → post-commit).
        verify(versioning).createNewVersionEntity(eq("test1"), anyString(), anyString(), any(), any(), any());
        verify(fileService).saveFile(eq(saved.get(1).getId()), eq(bpmn));
        verify(artifactRegistrar).registerMessageStartSubscriptions(eq("test1"), eq(saved.get(1).getId()), any());
        verify(artifactRegistrar).registerTimerStartJobs(eq("test1"), eq(saved.get(1).getId()), any());
        verify(artifactRegistrar).registerSignalStartSubscriptions(eq("test1"), eq(saved.get(1).getId()), any());
        verify(artifactRegistrar).carryForwardBindings(eq("test1"), eq(2), any());
        verify(postCommitActions).cacheModelAfterCommit(eq(saved.get(1).getId()), any());
        verify(postCommitActions).requestJobQueuesAfterCommit(any());

        assertThat(result.getKey()).isEqualTo("test1");
        assertThat(result.getVersion()).isEqualTo(3);
    }

    /**
     * WO-REL-16 criterion #2 at orchestration level: deploying a definition must hand its
     * model to the post-commit delegate so the queue can be announced without waiting for
     * a process instance to reach the service task. WHAT is announced (the job1 payload)
     * is owned by {@code DeploymentPostCommitActionsTest.announcedJobTypesMatchModel},
     * not duplicated here.
     */
    @Test
    void addProcessDefinition_announcesJobQueuesForDeployedDefinition() throws IOException {
        String bpmn = Files.readString(Path.of("src/test/files/process2.bpmn"));

        when(processDefinitionRepository.findBySha256(anyString())).thenReturn(Optional.empty());
        stubVersioning("process2", 1);
        when(processDefinitionRepository.save(any(ProcessDefinitionEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        ProcessDefinition result = service.addProcessDefinition(bpmn);

        verify(postCommitActions).requestJobQueuesAfterCommit(any());
        verify(postCommitActions).cacheModelAfterCommit(any(), any());

        assertThat(result.getKey()).isEqualTo("process2");
    }

    // NOTE (WO-DEBT-5c): two pre-5c tests were deleted here, not moved —
    // - addProcessDefinition_withoutServiceTasks_announcesNothing: the "empty jobTypes →
    //   no publish" rule now lives inside DeploymentPostCommitActions.requestJobQueuesAfterCommit
    //   (early return) and is owned by
    //   DeploymentPostCommitActionsTest.requestJobQueuesAfterCommit_emptyJobTypes_publishesNothing.
    //   At orchestration level the service ALWAYS delegates (even for empty models), so a
    //   never()-assert here would pin the wrong layer.
    // - addProcessDefinition_announcementFailure_doesNotFailDeployment: the best-effort swallow
    //   now lives inside DeploymentPostCommitActions.publishJobQueuesRequested (try/catch) and is
    //   owned by DeploymentPostCommitActionsTest.requestJobQueuesAfterCommit_publisherThrows_swallowed.
    //   Stubbing the mock to throw would misrepresent prod (the mock bypasses the bean's catch).

    @Test
    void addProcessDefinition_existingSha_doesNotResaveAndDoesNotPublish() throws IOException {
        String bpmn = Files.readString(Path.of("src/test/files/test1.bpmn"));
        UUID existingId = UUID.randomUUID();
        ProcessDefinitionEntity existing = entity(existingId, "test1", 5);
        existing.setCreatedAt(Instant.now());

        when(processDefinitionRepository.findBySha256(anyString())).thenReturn(Optional.of(existing));

        ProcessDefinition result = service.addProcessDefinition(bpmn);

        verify(processDefinitionRepository, never()).save(any());
        verify(fileService, never()).saveFile(any(), any());
        // ACTIVE fast path: none of the 3 collaborators is touched.
        verifyNoInteractions(versioning, artifactRegistrar, postCommitActions);

        assertThat(result.getId()).isEqualTo(existingId);
        assertThat(result.getVersion()).isEqualTo(5);
    }

    @Test
    void addProcessDefinition_existingShaPending_repairsAndActivates() throws IOException {
        String bpmn = Files.readString(Path.of("src/test/files/test1.bpmn"));
        UUID existingId = UUID.randomUUID();
        ProcessDefinitionEntity existing = entity(existingId, "test1", 5);
        existing.setCreatedAt(Instant.now());
        existing.setDeploymentState(ProcessDefinitionEntity.STATE_PENDING);

        when(processDefinitionRepository.findBySha256(anyString())).thenReturn(Optional.of(existing));
        when(bindingRepository.findByProcessDefinitionId(existingId)).thenReturn(List.of());
        List<String> saveStates = new ArrayList<>();
        when(processDefinitionRepository.save(any(ProcessDefinitionEntity.class))).thenAnswer(inv -> {
            ProcessDefinitionEntity e = inv.getArgument(0);
            saveStates.add(e.getDeploymentState());
            return e;
        });

        ProcessDefinition result = service.addProcessDefinition(bpmn);

        // WO-REL-15: redeploy of the same sha256 REPAIRS the incomplete deployment instead of
        // bailing out with "already exists": model re-saved, state flipped to ACTIVE, artifacts
        // re-registered via the delegates, cache/queues re-announced post-commit.
        verify(fileService).saveFile(eq(existingId), eq(bpmn));
        verify(artifactRegistrar).registerMessageStartSubscriptions(eq("test1"), eq(existingId), any());
        verify(artifactRegistrar).registerTimerStartJobs(eq("test1"), eq(existingId), any());
        verify(artifactRegistrar).registerSignalStartSubscriptions(eq("test1"), eq(existingId), any());
        verify(artifactRegistrar).carryForwardBindings(eq("test1"), eq(4), any());
        verify(postCommitActions).cacheModelAfterCommit(eq(existingId), any());
        verify(postCommitActions).requestJobQueuesAfterCommit(any());
        assertThat(saveStates).containsExactly(ProcessDefinitionEntity.STATE_ACTIVE);

        assertThat(result.getId()).isEqualTo(existingId);
        assertThat(result.getVersion()).isEqualTo(5);
    }

    @Test
    void getProcessDefinitions_queriesViaSpecification() {
        ProcessDefinitionsQueryParameters params = new ProcessDefinitionsQueryParameters();
        params.setPageIndex(0);
        params.setPageSize(10);

        Page<ProcessDefinitionEntity> page = new PageImpl<>(List.of(entity(UUID.randomUUID(), "k", 1)));
        when(processDefinitionRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

        PagedDataDTO<ProcessDefinition> result = service.getProcessDefinitions(params);

        assertThat(result.getData()).hasSize(1);
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    void getProcessDefinitions_withNameAndLatestFilters_queriesViaSpecification() {
        ProcessDefinitionsQueryParameters params = new ProcessDefinitionsQueryParameters();
        params.setPageIndex(0);
        params.setPageSize(10);
        params.setName("order");
        params.setLatestVersionOnly(true);

        Page<ProcessDefinitionEntity> page = new PageImpl<>(List.of(entity(UUID.randomUUID(), "k", 2)));
        when(processDefinitionRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

        PagedDataDTO<ProcessDefinition> result = service.getProcessDefinitions(params);

        assertThat(result.getData()).hasSize(1);
        verify(processDefinitionRepository, never()).findAllLatest(any());
    }

    /**
     * WO-ENG-17: Camunda-style historyTimeToLive через BPMN-атрибут — деплой
     * парсит значение с процесса в entity (реальный BpmnParseServiceImpl, мок
     * только на versioning-границе; значение 30 доходит из XML в entity).
     */
    @Test
    void addProcessDefinition_camundaHistoryTimeToLive_setsTtlDays() throws IOException {
        String bpmn = Files.readString(Path.of("src/test/files/test1.bpmn"))
            .replace("xmlns:modeler=\"http://camunda.org/schema/modeler/1.0\"",
                "xmlns:modeler=\"http://camunda.org/schema/modeler/1.0\" xmlns:camunda=\"http://camunda.org/schema/1.0/bpmn\"")
            .replace("<bpmn:process id=\"test1\"",
                "<bpmn:process id=\"test1\" camunda:historyTimeToLive=\"30\"");

        when(processDefinitionRepository.findBySha256(anyString())).thenReturn(Optional.empty());
        stubVersioning("test1", 3);
        List<ProcessDefinitionEntity> saved = new ArrayList<>();
        when(processDefinitionRepository.save(any(ProcessDefinitionEntity.class))).thenAnswer(inv -> {
            ProcessDefinitionEntity e = inv.getArgument(0);
            saved.add(e);
            return e;
        });

        service.addProcessDefinition(bpmn);

        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getHistoryTimeToLiveDays()).isEqualTo(30);
    }

    @Test
    void addProcessDefinition_withoutTtlAttribute_leavesNull() throws IOException {
        String bpmn = Files.readString(Path.of("src/test/files/test1.bpmn"));

        when(processDefinitionRepository.findBySha256(anyString())).thenReturn(Optional.empty());
        stubVersioning("test1", 3);
        List<ProcessDefinitionEntity> saved = new ArrayList<>();
        when(processDefinitionRepository.save(any(ProcessDefinitionEntity.class))).thenAnswer(inv -> {
            ProcessDefinitionEntity e = inv.getArgument(0);
            saved.add(e);
            return e;
        });

        service.addProcessDefinition(bpmn);

        // Обратная совместимость §4 WO: без атрибута — NULL (глобальный TTL как раньше).
        assertThat(saved.get(0).getHistoryTimeToLiveDays()).isNull();
    }

    @Test
    void addProcessDefinition_garbageTtl_rejects400() throws IOException {
        String bpmn = Files.readString(Path.of("src/test/files/test1.bpmn"))
            .replace("xmlns:modeler=\"http://camunda.org/schema/modeler/1.0\"",
                "xmlns:modeler=\"http://camunda.org/schema/modeler/1.0\" xmlns:camunda=\"http://camunda.org/schema/1.0/bpmn\"")
            .replace("<bpmn:process id=\"test1\"",
                "<bpmn:process id=\"test1\" camunda:historyTimeToLive=\"soon\"");

        when(processDefinitionRepository.findBySha256(anyString())).thenReturn(Optional.empty());
        stubVersioning("test1", 3);

        com.zorrodev.bpm.contract.exception.ApiException thrown =
            org.junit.jupiter.api.Assertions.assertThrows(
                com.zorrodev.bpm.contract.exception.ApiException.class,
                () -> service.addProcessDefinition(bpmn));
        assertThat(thrown.getStatus()).isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
        assertThat(thrown.getCode()).isEqualTo("INVALID_HISTORY_TTL");
    }

    @Test
    void addProcessDefinition_zeroTtl_rejects400() throws IOException {
        String bpmn = Files.readString(Path.of("src/test/files/test1.bpmn"))
            .replace("xmlns:modeler=\"http://camunda.org/schema/modeler/1.0\"",
                "xmlns:modeler=\"http://camunda.org/schema/modeler/1.0\" xmlns:camunda=\"http://camunda.org/schema/1.0/bpmn\"")
            .replace("<bpmn:process id=\"test1\"",
                "<bpmn:process id=\"test1\" camunda:historyTimeToLive=\"0\"");

        when(processDefinitionRepository.findBySha256(anyString())).thenReturn(Optional.empty());
        stubVersioning("test1", 3);

        try {
            service.addProcessDefinition(bpmn);
            org.junit.jupiter.api.Assertions.fail("expected INVALID_HISTORY_TTL");
        } catch (com.zorrodev.bpm.contract.exception.ApiException e) {
            assertThat(e.getStatus()).isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
            assertThat(e.getCode()).isEqualTo("INVALID_HISTORY_TTL");
        }
    }

    private static ProcessDefinitionEntity entity(UUID id, String key, int version) {
        ProcessDefinitionEntity e = new ProcessDefinitionEntity();
        e.setId(id);
        e.setKey(key);
        e.setName(key);
        e.setVersion(version);
        e.setSha256("sha-" + key);
        e.setCreatedAt(Instant.now());
        return e;
    }
}
