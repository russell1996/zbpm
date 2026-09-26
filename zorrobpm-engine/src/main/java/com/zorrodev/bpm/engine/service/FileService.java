package com.zorrodev.bpm.engine.service;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

public interface FileService {

    void saveFile(UUID id, String bpmn);

    Optional<String> getFileBytes(UUID id) throws IOException;
}
