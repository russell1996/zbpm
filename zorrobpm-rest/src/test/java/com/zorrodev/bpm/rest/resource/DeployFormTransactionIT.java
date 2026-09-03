package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.DeployFormDTO;
import com.zorrodev.bpm.contract.dto.FormDTO;
import com.zorrodev.bpm.engine.entity.FormEntity;
import com.zorrodev.bpm.engine.repository.FormRepository;
import com.zorrodev.bpm.engine.security.Principal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * WO-DEBT-4e — transactional boundary proof for {@code deployForm}.
 * Real DB, real impl, real repository (as spy); only the authz boundary
 * ({@link FormAccessSupport}) is mocked.
 */
@SpringBootTest
@ActiveProfiles("test")
class DeployFormTransactionIT {

    @Autowired private FormOperations formOperations;
    @MockitoBean private FormAccessSupport formAccessSupport;
    @MockitoSpyBean private FormRepository formRepository;

    private String cleanupKey;

    @AfterEach
    void cleanup() {
        if (cleanupKey != null) {
            formRepository.findTopByFormKeyOrderByVersionDesc(cleanupKey)
                .ifPresent(f -> formRepository.deleteById(f.getId()));
            cleanupKey = null;
        }
    }

    private static DeployFormDTO dto(String key) {
        DeployFormDTO dto = new DeployFormDTO();
        dto.setKey(key);
        dto.setKind("FORM_JS");
        dto.setSchema("{\"components\":[]}");
        return dto;
    }

    @Test
    void deployForm_saveRollsBackWhenPostWriteFails() {
        String key = "df-" + UUID.randomUUID().toString().substring(0, 8);
        cleanupKey = key;
        Principal admin = new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN");
        when(formAccessSupport.getPrincipal()).thenReturn(admin);
        // Write really happens (via the unstubbed saveAndFlush on the same spy),
        // then a forced failure — the row must be rolled back
        doAnswer(inv -> {
            formRepository.saveAndFlush(inv.getArgument(0, FormEntity.class));
            throw new RuntimeException("forced post-write failure");
        }).when(formRepository).save(any(FormEntity.class));

        assertThatThrownBy(() -> formOperations.deployForm(dto(key)))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("forced post-write failure");

        // Rollback proof: without @Transactional the real save would have committed
        // (SimpleJpaRepository.save is transactional itself) and the row would exist
        Optional<FormEntity> after = formRepository.findTopByFormKeyOrderByVersionDesc(key);
        assertThat(after).isEmpty();
    }

    @Test
    void deployForm_commitsWithIncrementingVersions() {
        String key = "df-" + UUID.randomUUID().toString().substring(0, 8);
        cleanupKey = key;
        Principal admin = new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN");
        when(formAccessSupport.getPrincipal()).thenReturn(admin);

        FormDTO first = formOperations.deployForm(dto(key));
        FormDTO second = formOperations.deployForm(dto(key));

        // Real versioning on a real DB: max+1 across two deploys
        assertThat(first.getVersion()).isEqualTo(1);
        assertThat(second.getVersion()).isEqualTo(2);
    }
}
