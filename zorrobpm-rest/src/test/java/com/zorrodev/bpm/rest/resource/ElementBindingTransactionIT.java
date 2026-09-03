package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.CreateElementBindingDTO;
import com.zorrodev.bpm.contract.dto.ElementBindingDTO;
import com.zorrodev.bpm.engine.entity.ElementArtifactBindingEntity;
import com.zorrodev.bpm.engine.entity.FormArtifactKind;
import com.zorrodev.bpm.engine.entity.FormEntity;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.repository.ElementArtifactBindingRepository;
import com.zorrodev.bpm.engine.repository.FormRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.security.Principal;
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
 * WO-DEBT-4c — transactional boundary proof for {@code createElementBinding}.
 * Real DB, real impl, real repositories; only the authz boundary
 * ({@link FormAccessSupport}) is mocked. The binding repo is a spy so the
 * rollback test can force a failure AFTER the upsert-delete, AT save.
 */
@SpringBootTest
@ActiveProfiles("test")
class ElementBindingTransactionIT {

    @Autowired private ElementBindingOperations elementBindingOperations;
    @Autowired private ProcessDefinitionRepository processDefinitionRepository;
    @Autowired private FormRepository formRepository;
    @MockitoBean private FormAccessSupport formAccessSupport;
    @MockitoSpyBean private ElementArtifactBindingRepository bindingRepository;

    private UUID cleanupPd;
    private UUID cleanupForm;
    private UUID cleanupBinding;

    @AfterEach
    void cleanup() {
        if (cleanupBinding != null) bindingRepository.deleteById(cleanupBinding);
        if (cleanupForm != null) formRepository.deleteById(cleanupForm);
        if (cleanupPd != null) processDefinitionRepository.deleteById(cleanupPd);
        cleanupPd = cleanupForm = cleanupBinding = null;
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

    private void seedArtifact(String key, int version) {
        UUID id = UUID.randomUUID();
        FormEntity f = new FormEntity();
        f.setId(id);
        f.setFormKey(key);
        f.setVersion(version);
        f.setKind(FormArtifactKind.FORM_JS);
        f.setSchemaJson("{\"components\":[]}");
        f.setCreatedAt(Instant.now());
        formRepository.saveAndFlush(f);
        cleanupForm = id;
    }

    private UUID seedBinding(UUID pdId, String elementId, String artifactKey, int artifactVersion) {
        UUID id = UUID.randomUUID();
        ElementArtifactBindingEntity b = new ElementArtifactBindingEntity();
        b.setId(id);
        b.setProcessDefinitionId(pdId);
        b.setProcessDefinitionVersion(1);
        b.setElementId(elementId);
        b.setArtifactKey(artifactKey);
        b.setArtifactVersion(artifactVersion);
        b.setCreatedAt(Instant.now());
        bindingRepository.saveAndFlush(b);
        return id;
    }

    private static CreateElementBindingDTO dto(String elementId, String artifactKey) {
        CreateElementBindingDTO dto = new CreateElementBindingDTO();
        dto.setElementId(elementId);
        dto.setArtifactKey(artifactKey);
        return dto;
    }

    @Test
    void createElementBinding_upsertDeleteRollsBackWhenSaveFails() {
        String key = "eb-" + UUID.randomUUID().toString().substring(0, 8);
        String art = "art-" + UUID.randomUUID().toString().substring(0, 8);
        UUID pdId = seedPd(key);
        seedArtifact(art, 2);
        UUID oldId = seedBinding(pdId, "start1", art, 1);
        cleanupBinding = oldId;

        Principal admin = new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN");
        when(formAccessSupport.getPrincipal()).thenReturn(admin);
        // Force failure at save — AFTER the upsert-delete of the existing binding
        doThrow(new RuntimeException("forced post-write failure")).when(bindingRepository).save(any());

        assertThatThrownBy(() -> elementBindingOperations.createElementBinding(key, dto("start1", art)))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("forced post-write failure");

        // Rollback proof: without @Transactional the upsert-delete would have committed
        // in its own transaction and the old binding would be GONE
        Optional<ElementArtifactBindingEntity> old =
            bindingRepository.findByProcessDefinitionIdAndElementId(pdId, "start1");
        assertThat(old).isPresent();
        assertThat(old.get().getId()).isEqualTo(oldId);
        assertThat(old.get().getArtifactVersion()).isEqualTo(1);
    }

    @Test
    void createElementBinding_commitsWithPinnedVersion() {
        String key = "eb-" + UUID.randomUUID().toString().substring(0, 8);
        String art = "art-" + UUID.randomUUID().toString().substring(0, 8);
        UUID pdId = seedPd(key);
        seedArtifact(art, 2);

        Principal admin = new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN");
        when(formAccessSupport.getPrincipal()).thenReturn(admin);

        ElementBindingDTO result = elementBindingOperations.createElementBinding(key, dto("start1", art));

        assertThat(result.getArtifactVersion()).isEqualTo(2);
        Optional<ElementArtifactBindingEntity> saved =
            bindingRepository.findByProcessDefinitionIdAndElementId(pdId, "start1");
        assertThat(saved).isPresent();
        assertThat(saved.get().getArtifactVersion()).isEqualTo(2);
        assertThat(saved.get().getArtifactKey()).isEqualTo(art);
        cleanupBinding = saved.get().getId();
    }
}
