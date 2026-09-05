package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * WO-DEBT-1c: unit-тест на перенесённую логику `ProcessDefinitionDbOperationsImpl` — восстанавливает
 * покрытие, которое раньше держал `DBServiceImplTest` до среза (мапинг всех 7 полей + `orElseThrow`,
 * `orElse(0)` для отсутствующего ключа). После среза `DBServiceImplTest` мокает уже сам делегат, а не
 * репозиторий, поэтому эту логику больше никто не проверяет — вот этот файл её и покрывает.
 */
@ExtendWith(MockitoExtension.class)
class ProcessDefinitionDbOperationsImplTest {

    @Mock private ProcessDefinitionRepository processDefinitionRepository;

    @InjectMocks
    private ProcessDefinitionDbOperationsImpl service;

    @Test
    void getProcessDefinition_mapsAllFields() {
        ProcessDefinitionEntity entity = new ProcessDefinitionEntity();
        entity.setId(UUID.randomUUID());
        entity.setKey("k");
        entity.setName("n");
        entity.setVersion(2);
        entity.setSha256("s");
        entity.setCreatedAt(Instant.now());
        entity.setStartFormKey("form-1");
        when(processDefinitionRepository.findByKeyAndVersion("k", 2)).thenReturn(Optional.of(entity));

        ProcessDefinition result = service.getProcessDefinition("k", 2);

        assertThat(result.getId()).isEqualTo(entity.getId());
        assertThat(result.getName()).isEqualTo(entity.getName());
        assertThat(result.getKey()).isEqualTo(entity.getKey());
        assertThat(result.getSha256()).isEqualTo(entity.getSha256());
        assertThat(result.getCreatedAt()).isEqualTo(entity.getCreatedAt());
        assertThat(result.getStartFormKey()).isEqualTo(entity.getStartFormKey());
        assertThat(result.getVersion()).isEqualTo(entity.getVersion());
    }

    @Test
    void getProcessDefinition_throwsWhenMissing() {
        when(processDefinitionRepository.findByKeyAndVersion("k", 2)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getProcessDefinition("k", 2))
            .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void getMaxProcessDefinitionVersionByKey_returnsValue() {
        when(processDefinitionRepository.findMaxByKey("present")).thenReturn(Optional.of(7));

        assertThat(service.getMaxProcessDefinitionVersionByKey("present")).isEqualTo(7);
    }

    @Test
    void getMaxProcessDefinitionVersionByKey_returnsZeroWhenAbsent() {
        when(processDefinitionRepository.findMaxByKey("absent")).thenReturn(Optional.empty());

        assertThat(service.getMaxProcessDefinitionVersionByKey("absent")).isEqualTo(0);
    }

    @Test
    void getMaxProcessDefinitionVersionByKeyAndVersionTag_returnsValue() {
        when(processDefinitionRepository.findMaxByKeyAndVersionTag("present", "v1")).thenReturn(Optional.of(3));

        assertThat(service.getMaxProcessDefinitionVersionByKeyAndVersionTag("present", "v1")).isEqualTo(3);
    }

    @Test
    void getMaxProcessDefinitionVersionByKeyAndVersionTag_returnsZeroWhenAbsent() {
        when(processDefinitionRepository.findMaxByKeyAndVersionTag("absent", "v9")).thenReturn(Optional.empty());

        assertThat(service.getMaxProcessDefinitionVersionByKeyAndVersionTag("absent", "v9")).isEqualTo(0);
    }

    @Test
    void getMaxProcessDefinitionVersionByKeyAndDeploymentId_returnsValue() {
        UUID deploymentId = UUID.randomUUID();
        when(processDefinitionRepository.findMaxByKeyAndDeploymentId("present", deploymentId)).thenReturn(Optional.of(2));

        assertThat(service.getMaxProcessDefinitionVersionByKeyAndDeploymentId("present", deploymentId)).isEqualTo(2);
    }

    @Test
    void getMaxProcessDefinitionVersionByKeyAndDeploymentId_returnsZeroWhenAbsent() {
        UUID deploymentId = UUID.randomUUID();
        when(processDefinitionRepository.findMaxByKeyAndDeploymentId("absent", deploymentId)).thenReturn(Optional.empty());

        assertThat(service.getMaxProcessDefinitionVersionByKeyAndDeploymentId("absent", deploymentId)).isEqualTo(0);
    }

    @Test
    void getDeploymentIdByProcessDefinitionId_returnsValue() {
        UUID pdId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();
        ProcessDefinitionEntity entity = new ProcessDefinitionEntity();
        entity.setId(pdId);
        entity.setDeploymentId(deploymentId);
        when(processDefinitionRepository.findById(pdId)).thenReturn(Optional.of(entity));

        assertThat(service.getDeploymentIdByProcessDefinitionId(pdId)).isEqualTo(deploymentId);
    }

    @Test
    void getDeploymentIdByProcessDefinitionId_returnsNullWhenSingle() {
        UUID pdId = UUID.randomUUID();
        ProcessDefinitionEntity entity = new ProcessDefinitionEntity();
        entity.setId(pdId);
        entity.setDeploymentId(null);
        when(processDefinitionRepository.findById(pdId)).thenReturn(Optional.of(entity));

        assertThat(service.getDeploymentIdByProcessDefinitionId(pdId)).isNull();
    }
}
