package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.ProcessDefinitionsQueryParameters;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.handler.ElementSupport;
import com.zorrodev.bpm.engine.service.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProcessDefinitionServiceImplTest {

    @Mock
    private ProcessDefinitionRepository processDefinitionRepository;

    @Mock
    private BpmnService bpmnService;

    @Mock
    private FileService fileService;

    @Mock
    private com.zorrodev.bpm.engine.service.DBService dbService;

    @Mock
    private com.zorrodev.bpm.engine.repository.ElementArtifactBindingRepository bindingRepository;

    @Mock
    private com.zorrodev.bpm.engine.repository.FormRepository formRepository;

    @Mock
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Mock
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    @Mock
    private javax.sql.DataSource dataSource;

    @Mock
    private ElementSupport elementSupport;

    private final BpmnParseServiceImpl bpmnParseService = new BpmnParseServiceImpl();

    private ProcessDefinitionServiceImpl service;

    @BeforeEach
    void setUp() {
        // TransactionTemplate.execute runs the callback directly in unit tests
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            org.springframework.transaction.support.TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(null);
        });
        service = new ProcessDefinitionServiceImpl(
            processDefinitionRepository,
            bpmnService,
            bpmnParseService,
            fileService,
            dbService,
            bindingRepository,
            formRepository,
            jdbcTemplate,
            transactionTemplate,
            dataSource,
            elementSupport
        );
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
        when(processDefinitionRepository.findMaxByKey("test1")).thenReturn(Optional.of(2));
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

        verify(bpmnService).addProcessDefinition(eq(saved.get(1).getId()), any());
        verify(fileService).saveFile(eq(saved.get(1).getId()), eq(bpmn));

        assertThat(result.getKey()).isEqualTo("test1");
        assertThat(result.getVersion()).isEqualTo(3);
    }

    @Test
    void addProcessDefinition_existingSha_doesNotResaveAndDoesNotPublish() throws IOException {
        String bpmn = Files.readString(Path.of("src/test/files/test1.bpmn"));
        UUID existingId = UUID.randomUUID();
        ProcessDefinitionEntity existing = entity(existingId, "test1", 5);
        existing.setCreatedAt(Instant.now());

        when(processDefinitionRepository.findBySha256(anyString())).thenReturn(Optional.of(existing));

        ProcessDefinition result = service.addProcessDefinition(bpmn);

        verify(processDefinitionRepository, never()).save(any());
        verify(bpmnService, never()).addProcessDefinition(any(), any());
        verify(fileService, never()).saveFile(any(), any());

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
        // bailing out with "already exists": model re-saved, state flipped to ACTIVE.
        verify(fileService).saveFile(eq(existingId), eq(bpmn));
        verify(bpmnService).addProcessDefinition(eq(existingId), any());
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

    // ==================== WO-A-03: advisory lock fail-closed ====================

    /**
     * POF (G-P/G-N): On PostgreSQL, if advisory lock fails (e.g. SQLException),
     * the transaction must ROLLBACK — version is NOT created.
     * RED (before fix): broad catch swallows exception, version created without lock.
     * GREEN (after fix): exception propagates, save() never called.
     */
    @Test
    void advisoryLockFailure_propagatesAndNoVersionCreated() throws Exception {
        // Simulate PostgreSQL: dataSource returns a connection whose meta says "PostgreSQL"
        java.sql.Connection mockConn = org.mockito.Mockito.mock(java.sql.Connection.class);
        java.sql.DatabaseMetaData mockMeta = org.mockito.Mockito.mock(java.sql.DatabaseMetaData.class);
        org.mockito.Mockito.when(mockMeta.getDatabaseProductName()).thenReturn("PostgreSQL");
        org.mockito.Mockito.when(mockConn.getMetaData()).thenReturn(mockMeta);
        when(dataSource.getConnection()).thenReturn(mockConn);

        // Advisory lock will fail — simulate by making execute throw
        when(jdbcTemplate.execute(any(org.springframework.jdbc.core.ConnectionCallback.class)))
            .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("lock failed", new java.sql.SQLException("lock timeout")));

        String key = "test-key";
        UUID id = UUID.randomUUID();

        try {
            service.createNewVersionWithAdvisoryLock(key, "Test", "sha", id, null);
            org.assertj.core.api.Assertions.fail("Should have thrown due to lock failure");
        } catch (Exception e) {
            // Expected: exception propagates, version NOT created
            verify(processDefinitionRepository, never()).save(any(ProcessDefinitionEntity.class));
        }
    }
}
