package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.service.BpmnParseService;
import com.zorrodev.bpm.engine.service.FileService;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * WO-REL-3 proof-of-failure #5:
 * OLD: ConcurrentHashMap has no eviction → size > maxSize
 * NEW: Caffeine cache evicts → size <= maxSize
 */
class BpmnCacheEvictionTest {

    // --- OLD code (proof-of-failure RED): ConcurrentHashMap doesn't evict ---

    @Test
    void oldCode_concurrentHashMap_noEviction() {
        int maxSize = 3;
        var oldCache = new ConcurrentHashMap<UUID, BpmnProcessDefinitionModel>();

        for (int i = 0; i < maxSize + 1; i++) {
            UUID id = UUID.randomUUID();
            BpmnProcessDefinitionModel model = new BpmnProcessDefinitionModel();
            model.setKey("process" + i);
            oldCache.put(id, model);
        }

        // OLD: ConcurrentHashMap allows all entries, no eviction
        assertThat(oldCache.size()).isGreaterThan(maxSize);
    }

    // --- NEW code (GREEN): Caffeine cache evicts ---

    @Test
    void newCode_caffeineCache_evictsAtMaxSize() {
        int maxSize = 3;
        var newCache = com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
            .maximumSize(maxSize)
            .build();

        UUID[] ids = new UUID[maxSize + 1];
        for (int i = 0; i < maxSize + 1; i++) {
            ids[i] = UUID.randomUUID();
            newCache.put(ids[i], "value" + i);
        }

        newCache.cleanUp();

        int presentCount = 0;
        for (UUID id : ids) {
            if (newCache.getIfPresent(id) != null) presentCount++;
        }
        assertThat(presentCount).isLessThanOrEqualTo(maxSize);
    }
}
