package com.zorrodev.bpm.engine.service.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.service.BpmnParseService;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.FileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

@Slf4j
@Service
public class BpmnServiceImpl implements BpmnService {

    private final Cache<UUID, BpmnProcessDefinitionModel> cache;
    private final FileService fileService;
    private final BpmnParseService bpmnParseService;

    public BpmnServiceImpl(
        FileService fileService,
        BpmnParseService bpmnParseService,
        int maxSize,
        int ttlMinutes) {
        this(fileService, bpmnParseService, maxSize, ttlMinutes, 100);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public BpmnServiceImpl(
        FileService fileService,
        BpmnParseService bpmnParseService,
        @Value("${zorrobpm.engine.bpmn-cache-max-size:500}") int maxSize,
        @Value("${zorrobpm.engine.bpmn-cache-ttl-minutes:60}") int ttlMinutes,
        @Value("${zorrobpm.engine.bpmn-cache-max-weight-mb:100}") int maxWeightMb) {
        this.fileService = fileService;
        this.bpmnParseService = bpmnParseService;
        long maxWeightBytes = (long) maxWeightMb * 1024 * 1024;
        this.cache = Caffeine.newBuilder()
            .maximumWeight(maxWeightBytes)
            .weigher((UUID k, BpmnProcessDefinitionModel v) -> 5 * 1024 * 1024)
            .expireAfterWrite(Duration.ofMinutes(ttlMinutes))
            .build();
        log.info("BPMN cache initialized: maxSize={}, maxWeightMb={}, ttlMinutes={}", maxSize, maxWeightMb, ttlMinutes);
    }

    @Override
    public void addProcessDefinition(UUID id, BpmnProcessDefinitionModel model) {
        cache.put(id, model);
    }

    @Override
    public BpmnProcessDefinitionModel getProcessDefinitionModelById(UUID id) {
        return cache.get(id, uuid -> {
            String bpmn;
            try {
                bpmn = fileService.getFileBytes(uuid)
                    .orElseThrow(() -> new EngineException("BPMN file not found for definition " + uuid));
            } catch (IOException e) {
                throw new EngineException("Failed to read BPMN file for definition " + uuid, e);
            }
            return bpmnParseService.parse(bpmn);
        });
    }
}
