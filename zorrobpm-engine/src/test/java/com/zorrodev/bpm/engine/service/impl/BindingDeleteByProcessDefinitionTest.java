package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.ElementArtifactBindingRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-QW-2 criterion 3: {@code deleteByProcessDefinitionId} removes all bindings
 * of one process definition in a single derived-query statement (replaces the
 * N-statement {@code findBy...+forEach(delete)} loop in
 * {@code ProcessDefinitionServiceImpl.repairDeployment}).
 */
@ActiveProfiles("test")
@SpringBootTest(classes = TestMain.class)
@Transactional
class BindingDeleteByProcessDefinitionTest {

    @Autowired ElementArtifactBindingRepository bindingRepository;
    @Autowired UiUserRepository userRepository;

    @Test
    void deleteByProcessDefinitionId_removesOnlyOwnRows() {
        UUID pdA = UUID.randomUUID();
        UUID pdB = UUID.randomUUID();
        var a1 = binding(pdA, "el1");
        var a2 = binding(pdA, "el2");
        var b1 = binding(pdB, "el1");
        bindingRepository.save(a1);
        bindingRepository.save(a2);
        bindingRepository.save(b1);

        bindingRepository.deleteByProcessDefinitionId(pdA);

        assertThat(bindingRepository.findByProcessDefinitionId(pdA)).isEmpty();
        assertThat(bindingRepository.findByProcessDefinitionId(pdB))
            .extracting(b -> b.getElementId()).containsExactly("el1");
    }

    private com.zorrodev.bpm.engine.entity.ElementArtifactBindingEntity binding(UUID pdId, String el) {
        var b = new com.zorrodev.bpm.engine.entity.ElementArtifactBindingEntity();
        b.setId(UUID.randomUUID());
        b.setProcessDefinitionId(pdId);
        b.setElementId(el);
        b.setArtifactKey("form-" + el);
        b.setCreatedAt(java.time.Instant.now());
        return b;
    }
}
