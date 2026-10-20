package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.entity.DomainEventEntity;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.repository.DomainEventRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-AUDIT-14 (F-18): {@code GET /events} must tell the client that the page
 * is NOT the whole feed.
 *
 * <p>Before the fix {@code EventResource} computed {@code hasMore}
 * (the query reads {@code maxResults + 1} rows precisely to detect "there is
 * more") and then THREW IT AWAY, while {@code totalElements} was set to the
 * size of the delivered page. The client had no way to know that events 101+
 * existed — with the default {@code limit=50} it silently lost the rest of the
 * feed on every call.
 *
 * <p>What this test pins:
 * <ul>
 *   <li>150 events, {@code limit=100} → exactly 100 delivered,
 *       {@code hasMore=true} and {@code nextCursor} = the feed position of the
 *       LAST delivered event (the cursor of the next page);</li>
 *   <li>{@code since=nextCursor&limit=100} → the remaining 50,
 *       {@code hasMore=false} and NO {@code nextCursor} field at all (the field
 *       is {@code @JsonInclude(NON_NULL)}, so "no next page" = "no cursor"
 *       instead of a serialized null);</li>
 *   <li>the two pages together are exactly the 150 events — no duplicate, no
 *       loss (the union is compared as a set of sequences);</li>
 *   <li>ADDITIVITY: the other endpoints that share {@code PagedDataDTO}
 *       ({@code /users}) must NOT grow the new fields — their answers stay
 *       byte-identical.</li>
 * </ul>
 *
 * <p>The events are filtered by their own definition key, so events left in
 * the shared H2 by other test classes cannot pollute the window (P-59).
 */
@SpringBootTest(classes = TestMain.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ActiveProfiles("test")
class EventPaginationCursorIT {

    private static final int EVENT_COUNT = 150;
    private static final int PAGE_SIZE = 100;

    @Autowired private MockMvc mockMvc;
    @Autowired private DomainEventRepository domainEventRepository;
    @Autowired private ProcessDefinitionRepository processDefinitionRepository;
    @Autowired private ProcessRepository processRepository;
    @Autowired private com.zorrodev.bpm.engine.scheduler.FeedPositionAssigner feedPositionAssigner;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final List<Long> createdEventIds = new ArrayList<>();

    private String adminToken;
    private String definitionKey;

    @BeforeAll
    void setup() throws Exception {
        adminToken = loginAndGetToken("admin", "admin");
        definitionKey = "audit14-events-" + UUID.randomUUID().toString().substring(0, 8);
        UUID defId = createDefinition(definitionKey);
        createProcessRow(definitionKey);
        for (int i = 0; i < EVENT_COUNT; i++) {
            emitEvent(defId, "audit14.event." + i);
        }
        // WO-REL-38: the feed cursor is feed_position, assigned by the job — not the
        // INSERT-time sequence. Without the tick the cursor sees nothing at all.
        feedPositionAssigner.assignPendingPositions();
    }

    @AfterAll
    void cleanupEvents() {
        // P-59: synthetic events must not leak into the shared H2 for later classes
        if (!createdEventIds.isEmpty()) {
            domainEventRepository.deleteAllById(createdEventIds);
        }
    }

    // ==================== F-18: cursor pagination ====================

    @Test
    void firstPage_reportsHasMoreAndNextCursor() throws Exception {
        JsonNode page = getEvents(Map.of("processDefinitionKey", definitionKey, "limit", String.valueOf(PAGE_SIZE)));

        assertThat(page.get("data").size())
            .as("the page itself must be exactly limit-sized")
            .isEqualTo(PAGE_SIZE);
        assertThat(page.get("hasMore"))
            .as("hasMore must be reported — 150 events exist, 100 delivered")
            .isNotNull();
        assertThat(page.get("hasMore").asBoolean()).isTrue();

        JsonNode last = page.get("data").get(PAGE_SIZE - 1);
        assertThat(page.get("nextCursor"))
            .as("nextCursor must be the feed position of the last delivered event")
            .isNotNull();
        assertThat(page.get("nextCursor").asLong())
            .as("nextCursor must equal the last delivered event's feedPosition")
            .isEqualTo(last.get("feedPosition").asLong());
    }

    @Test
    void secondPage_deliversTheRest_andReportsNoMore() throws Exception {
        JsonNode first = getEvents(Map.of("processDefinitionKey", definitionKey, "limit", String.valueOf(PAGE_SIZE)));
        long cursor = first.get("nextCursor").asLong();

        JsonNode second = getEvents(Map.of(
            "processDefinitionKey", definitionKey,
            "limit", String.valueOf(PAGE_SIZE),
            "since", String.valueOf(cursor)));

        assertThat(second.get("data").size())
            .as("the remainder must be delivered")
            .isEqualTo(EVENT_COUNT - PAGE_SIZE);
        assertThat(second.get("hasMore").asBoolean())
            .as("no events left after the last page")
            .isFalse();
        assertThat(second.has("nextCursor"))
            .as("no next page -> no cursor; the field is @JsonInclude(NON_NULL), " +
                "so a null cursor is not serialized at all")
            .isFalse();
    }

    @Test
    void twoPages_together_areExactlyAllEvents_noLossNoDuplicate() throws Exception {
        JsonNode first = getEvents(Map.of("processDefinitionKey", definitionKey, "limit", String.valueOf(PAGE_SIZE)));
        long cursor = first.get("nextCursor").asLong();
        JsonNode second = getEvents(Map.of(
            "processDefinitionKey", definitionKey,
            "limit", String.valueOf(PAGE_SIZE),
            "since", String.valueOf(cursor)));

        Set<String> sequences = new LinkedHashSet<>();
        List<JsonNode> all = new ArrayList<>();
        first.get("data").forEach(all::add);
        second.get("data").forEach(all::add);
        for (JsonNode e : all) {
            sequences.add(e.get("sequence").asText());
        }

        assertThat(all).as("both pages together").hasSize(EVENT_COUNT);
        assertThat(sequences)
            .as("no event may be delivered twice across the two pages")
            .hasSize(EVENT_COUNT);
    }

    // ==================== additivity: the other PagedDataDTO endpoints stay as they were ====================

    @Test
    void otherPagedEndpoints_growNoNewFields() throws Exception {
        MvcResult result = mockMvc.perform(get("/users").header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andReturn();

        JsonNode json = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.has("hasMore"))
            .as("/users answers must stay byte-identical — the new fields are optional and absent")
            .isFalse();
        assertThat(json.has("nextCursor"))
            .as("/users answers must stay byte-identical — the new fields are optional and absent")
            .isFalse();
        assertThat(json.has("data"))
            .as("the shared envelope itself is unchanged")
            .isTrue();
    }

    // ==================== helpers ====================

    private JsonNode getEvents(Map<String, String> params) throws Exception {
        var req = get("/events").header("Authorization", "Bearer " + adminToken);
        for (var e : params.entrySet()) req = req.queryParam(e.getKey(), e.getValue());
        MvcResult result = mockMvc.perform(req).andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private UUID createDefinition(String key) {
        ProcessDefinitionEntity def = new ProcessDefinitionEntity();
        def.setId(UUID.randomUUID());
        def.setKey(key);
        def.setName(key);
        def.setVersion(1);
        def.setSha256(UUID.randomUUID().toString());
        def.setCreatedAt(Instant.now());
        return processDefinitionRepository.save(def).getId();
    }

    private void createProcessRow(String definitionKey) {
        ProcessEntity p = new ProcessEntity();
        p.setId(UUID.randomUUID());
        p.setDefinitionKey(definitionKey);
        p.setName(definitionKey);
        p.setCreatedAt(Instant.now());
        processRepository.save(p);
    }

    private void emitEvent(UUID pdId, String type) {
        DomainEventEntity event = new DomainEventEntity();
        event.setId(UUID.randomUUID());
        event.setType(type);
        event.setVersion(1);
        event.setOccurredAt(Instant.now());
        event.setProcessDefinitionId(pdId);
        event.setProcessInstanceId(UUID.randomUUID());
        event.setOwnerScope(pdId.toString());
        event.setData(Map.of());
        domainEventRepository.save(event);
        if (event.getSequence() != null) createdEventIds.add(event.getSequence());
    }

    private String loginAndGetToken(String username, String password) throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        MvcResult result = mockMvc.perform(post("/auth/login").header("X-Auth-Transport", "bearer")
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class).getToken();
    }
}
