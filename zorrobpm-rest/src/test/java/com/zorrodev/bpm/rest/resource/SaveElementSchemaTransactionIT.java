package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.SaveElementSchemaDTO;
import com.zorrodev.bpm.contract.dto.SchemaMapElementDTO;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.entity.ElementArtifactBindingEntity;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.repository.ElementArtifactBindingRepository;
import com.zorrodev.bpm.engine.repository.FormRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.BpmnService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * WO-DEBT-4e — transactional boundary proof for {@code saveElementSchema}.
 * Real DB, real impl, real repositories (binding repo as spy), real BpmnService
 * (model registered directly); only the authz boundary ({@link FormAccessSupport})
 * is mocked. Full XML parsing is covered by the pre-existing
 * {@code ElementSchemaMapIntegrationTest}.
 */
@SpringBootTest
@ActiveProfiles("test")
class SaveElementSchemaTransactionIT {

    @Autowired private SchemaMapOperations schemaMapOperations;
    @Autowired private BpmnService bpmnService;
    @Autowired private ProcessDefinitionRepository processDefinitionRepository;
    @Autowired private FormRepository formRepository;
    @MockitoBean private FormAccessSupport formAccessSupport;
    @MockitoSpyBean private ElementArtifactBindingRepository bindingRepository;

    private UUID cleanupPd;
    private String cleanupArtifactKey;
    private UUID cleanupBinding;

    @AfterEach
    void cleanup() {
        if (cleanupBinding != null) bindingRepository.deleteById(cleanupBinding);
        if (cleanupArtifactKey != null) {
            formRepository.findTopByFormKeyOrderByVersionDesc(cleanupArtifactKey)
                .ifPresent(f -> formRepository.deleteById(f.getId()));
        }
        if (cleanupPd != null) processDefinitionRepository.deleteById(cleanupPd);
        cleanupPd = null;
        cleanupArtifactKey = null;
        cleanupBinding = null;
    }

    private UUID seedPd(String key) {
        UUID pdId = UUID.randomUUID();
        ProcessDefinitionEntity pd = new ProcessDefinitionEntity();
        pd.setId(pdId);
        pd.setKey(key);
        pd.setVersion(1);
        pd.setCreatedAt(Instant.now());
        pd.setSha256(UUID.randomUUID().toString());
        pd.setName("test");
        processDefinitionRepository.saveAndFlush(pd);
        cleanupPd = pdId;
        return pdId;
    }

    private void seedModel(UUID pdId) {
        BpmnProcessDefinitionModel model = new BpmnProcessDefinitionModel();
        BpmnElementModel start = new BpmnElementModel();
        start.setId("start1");
        start.setName("Start");
        start.setType(BpmnElementType.START_EVENT);
        model.addElement(start);
        bpmnService.addProcessDefinition(pdId, model);
    }

    private static SaveElementSchemaDTO dto() {
        SaveElementSchemaDTO dto = new SaveElementSchemaDTO();
        dto.setKind("FORM_JS");
        dto.setSchema("{\"components\":[]}");
        return dto;
    }

    @Test
    void saveElementSchema_writesRollBackWhenBindingSaveFails() {
        String key = "sm-" + UUID.randomUUID().toString().substring(0, 8);
        UUID pdId = seedPd(key);
        seedModel(pdId);
        String artifactKey = key + ":start1";
        cleanupArtifactKey = artifactKey;
        // Pre-existing binding → the impl will delete it, then save artifact + new binding
        UUID oldId = UUID.randomUUID();
        ElementArtifactBindingEntity old = new ElementArtifactBindingEntity();
        old.setId(oldId);
        old.setProcessDefinitionId(pdId);
        old.setProcessDefinitionVersion(1);
        old.setElementId("start1");
        old.setArtifactKey(artifactKey);
        old.setArtifactVersion(1);
        old.setCreatedAt(Instant.now());
        bindingRepository.saveAndFlush(old);
        cleanupBinding = oldId;

        Principal admin = new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN");
        when(formAccessSupport.getPrincipal()).thenReturn(admin);
        // Fail at the LAST write (binding save) — the artifact save + upsert-delete
        // before it are real and must roll back too (proven by 4c precedent)
        doThrow(new RuntimeException("forced post-write failure"))
            .when(bindingRepository).save(any(ElementArtifactBindingEntity.class));

        assertThatThrownBy(() -> schemaMapOperations.saveElementSchema(key, "start1", dto()))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("forced post-write failure");

        // Rollback proof: artifact row absent AND the upsert-deleted old binding is back
        assertThat(formRepository.findTopByFormKeyOrderByVersionDesc(artifactKey)).isEmpty();
        Optional<ElementArtifactBindingEntity> oldAfter =
            bindingRepository.findByProcessDefinitionIdAndElementId(pdId, "start1");
        assertThat(oldAfter).isPresent();
        assertThat(oldAfter.get().getId()).isEqualTo(oldId);
        assertThat(oldAfter.get().getArtifactVersion()).isEqualTo(1);
    }

    @Test
    void saveElementSchema_commitsArtifactAndPinnedBinding() {
        String key = "sm-" + UUID.randomUUID().toString().substring(0, 8);
        UUID pdId = seedPd(key);
        seedModel(pdId);
        cleanupArtifactKey = key + ":start1";
        Principal admin = new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN");
        when(formAccessSupport.getPrincipal()).thenReturn(admin);

        SchemaMapElementDTO result = schemaMapOperations.saveElementSchema(key, "start1", dto());

        assertThat(result.getArtifactKey()).isEqualTo(key + ":start1");
        assertThat(result.getArtifactVersion()).isEqualTo(1);
        Optional<ElementArtifactBindingEntity> saved =
            bindingRepository.findByProcessDefinitionIdAndElementId(pdId, "start1");
        assertThat(saved).isPresent();
        // Pin: binding nails the just-created version
        assertThat(saved.get().getArtifactVersion()).isEqualTo(1);
        cleanupBinding = saved.get().getId();
    }
}
