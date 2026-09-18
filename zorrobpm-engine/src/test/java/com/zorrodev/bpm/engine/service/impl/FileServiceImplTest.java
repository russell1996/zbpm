package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.engine.entity.BpmnEntity;
import com.zorrodev.bpm.engine.repository.BpmnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileServiceImplTest {

    @Mock
    private BpmnRepository bpmnRepository;

    @InjectMocks
    private FileServiceImpl fileService;

    @BeforeEach
    void setFilesDir() {
        ReflectionTestUtils.setField(fileService, "filesDir", "target/no-such-dir-for-tests");
    }

    @Test
    void saveFile_persistsEntity() {
        UUID id = UUID.randomUUID();
        String bpmn = "<bpmn/>";

        fileService.saveFile(id, bpmn);

        ArgumentCaptor<BpmnEntity> captor = ArgumentCaptor.forClass(BpmnEntity.class);
        verify(bpmnRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(id);
        assertThat(captor.getValue().getBpmn()).isEqualTo(bpmn);
    }

    @Test
    void getFileBytes_returnsBpmnFromRepository() throws IOException {
        UUID id = UUID.randomUUID();
        BpmnEntity entity = new BpmnEntity();
        entity.setId(id);
        entity.setBpmn("<bpmn/>");
        when(bpmnRepository.findById(id)).thenReturn(Optional.of(entity));

        Optional<String> result = fileService.getFileBytes(id);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo("<bpmn/>");
    }

    @Test
    void getFileBytes_returnsEmptyWhenMissing() throws IOException {
        UUID id = UUID.randomUUID();
        when(bpmnRepository.findById(id)).thenReturn(Optional.empty());

        Optional<String> result = fileService.getFileBytes(id);

        assertThat(result).isEmpty();
    }

    @Test
    void getFileBytes_refusesSymlink() throws IOException {
        // WO-QW-1 S-13: a symlinked legacy file must be refused, not followed.
        UUID id = UUID.randomUUID();
        when(bpmnRepository.findById(id)).thenReturn(Optional.empty());
        java.nio.file.Path base = java.nio.file.Files.createTempDirectory("qw1-symlink");
        try {
            java.nio.file.Path target = base.resolve("outside.txt");
            java.nio.file.Files.writeString(target, "<bpmn/>");
            String idStr = id.toString();
            java.nio.file.Path dir = base.resolve(java.nio.file.Paths.get(
                idStr.substring(0, 2), idStr.substring(2, 4), idStr.substring(4, 6), idStr.substring(6, 8)));
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.createSymbolicLink(dir.resolve(idStr), target);
            ReflectionTestUtils.setField(fileService, "filesDir", base.toString());

            assertThatThrownBy(() -> fileService.getFileBytes(id))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("symlink");
        } finally {
            ReflectionTestUtils.setField(fileService, "filesDir", "target/no-such-dir-for-tests");
            deleteRecursively(base);
        }
    }

    private static void deleteRecursively(java.nio.file.Path p) throws IOException {
        if (java.nio.file.Files.isDirectory(p) && !java.nio.file.Files.isSymbolicLink(p)) {
            try (var s = java.nio.file.Files.list(p)) {
                for (java.nio.file.Path c : s.toList()) deleteRecursively(c);
            }
        }
        java.nio.file.Files.deleteIfExists(p);
    }
}
