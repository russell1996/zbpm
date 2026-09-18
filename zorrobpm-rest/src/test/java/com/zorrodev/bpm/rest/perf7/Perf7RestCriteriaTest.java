package com.zorrodev.bpm.rest.perf7;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-PERF-7 (rest-часть): JwtAuthFilter debounce bounded (F38), OutboxAdmin
 * projection+limit (F30), SSE eviction через maxClients WO-PERF-6 (п.4).
 *
 * <p>Только reflection — компилируется и на pre-fix master {@code ac297d9d}
 * (там RED), и на ветке (там GREEN), кроме SSE-координации: она уже в обоих
 * деревьях (WO-PERF-6 смёржен в базу), тест фиксирует отсутствие дубля.
 */
class Perf7RestCriteriaTest {

    @Test
    void jwtAuthFilter_debounceMap_isBoundedCaffeine() throws Exception {
        Class<?> clazz = Class.forName(
            "com.zorrodev.bpm.rest.security.JwtAuthFilter");
        Field f = clazz.getDeclaredField("lastWriteTimestamps");
        assertThat(f.getType().getName().toLowerCase()).contains("caffeine");
    }

    @Test
    void outboxAdminResource_getOutbox_usesProjection() throws Exception {
        Class<?> clazz = Class.forName(
            "com.zorrodev.bpm.rest.resource.OutboxAdminResource");
        Method getOutbox = clazz.getMethod("getOutbox", String.class);
        assertThat(getOutbox).isNotNull();
        // Ресурс обязан маппить projection (toDTOView), а не только полный toDTO:
        // иначе payload тянется зря (F30).
        boolean hasViewMapper = false;
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals("toDTOView")) {
                hasViewMapper = true;
            }
        }
        assertThat(hasViewMapper).isTrue();
    }

    @Test
    void sse_evictionGoesThroughMaxClients_noSecondMechanism() throws Exception {
        Class<?> clazz = Class.forName(
            "com.zorrodev.bpm.rest.resource.SseEventStreamService");
        // WO-PERF-6: maxClients guard + onCompletion/onTimeout/onError removal.
        // WO-PERF-7 п.4 требует координации, а не дубля — проверяем, что лимит
        // есть и клиентская карта ровно одна (clients), второго кэша нет.
        // WO-REL-37: + bufferedEvents (буфер пересечения catchup→live, clientId →
        // события до drain) — НЕ второй реестр клиентов: записи живут только до
        // drain/remove/disconnect (removeClientState чистит все три структуры),
        // eviction идёт через maxClients/clients как раньше.
        // WO-SEC-67: + clientsPerSubject (счётчики слотов per-subject cap —
        // НЕ реестр: запись без живого клиента не существует, decrement+remove
        // при 0 в том же removeClientState) и rightsCache (Caffeine, TTL
        // переоценки прав — НЕ клиенты вовсе). Eviction по-прежнему идёт через
        // clients/removeClientState — per-subject cap лишь отказывает в НОВОЙ
        // регистрации (429), никого не выселяет.
        boolean hasMaxClients = false;
        java.util.Set<String> mapFields = new java.util.TreeSet<>();
        java.util.Set<String> caffeineFields = new java.util.TreeSet<>();
        for (Field f : clazz.getDeclaredFields()) {
            if (f.getName().equals("maxClients")) {
                hasMaxClients = true;
            }
            if (java.util.Map.class.isAssignableFrom(f.getType())) {
                mapFields.add(f.getName());
            }
            // Caffeine Cache's runtime type name is lowercase ("caffeine") —
            // match case-insensitively, not on the capital-C import idiom.
            if (f.getType().getName().toLowerCase(java.util.Locale.ROOT).contains("caffeine")) {
                caffeineFields.add(f.getName());
            }
        }
        assertThat(hasMaxClients).isTrue();
        assertThat(mapFields).containsExactly("bufferedEvents", "clients", "clientsPerSubject");
        assertThat(caffeineFields).containsExactly("rightsCache");
    }

    @Test
    void outboxEntryDTO_hasNoPayloadField() throws Exception {
        // DTO админ-списка не несёт payload — projection имеет смысл только тогда.
        List<String> names = new java.util.ArrayList<>();
        for (Field f : Class.forName("com.zorrodev.bpm.contract.dto.OutboxEntryDTO")
                .getDeclaredFields()) {
            names.add(f.getName().toLowerCase());
        }
        assertThat(names).doesNotContain("payload");
    }
}
