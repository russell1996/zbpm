package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * WO-REG-3 criterion 4 (race half), PostgreSQL only: N REAL parallel
 * {@code POST /auth/register} calls with the same email in mixed cases — the partial
 * functional unique index guarantees exactly one row no matter the interleaving
 * (mirrors {@code Acl12SubmissionRacePgIT} shape: rounds × threads, winner + losers).
 * On H2 this is structurally unprovable (no partial functional index there) — see
 * {@code RegistrationEndpointIntegrationTest}, which covers the same path single-shot.
 */
@Tag("pg")
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RegistrationRacePgIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;

    private static final int RACE_THREADS = 4;
    private static final int RACE_ROUNDS = 3;

    @Test
    void parallelRegister_sameEmailCaseVariants_oneRowNever500() throws Exception {
        for (int round = 0; round < RACE_ROUNDS; round++) {
            String email = "regracy-" + UUID.randomUUID().toString().substring(0, 8) + "@x.com";
            List<MvcResult> results = runParallelRegisters(email);

            assertThat(results).hasSize(RACE_THREADS);
            long ok = results.stream().filter(r -> r.getResponse().getStatus() == 200).count();
            long serverError = results.stream().filter(r -> r.getResponse().getStatus() >= 500).count();
            assertThat(ok).as("PG race round #%d: exactly one register must win", round).isEqualTo(1);
            assertThat(serverError).as("PG race round #%d: never a 500", round).isZero();

            long rows = userRepository.findAll().stream()
                .filter(u -> u.getEmail() != null && u.getEmail().equalsIgnoreCase(email))
                .count();
            assertThat(rows).as("PG race round #%d: exactly one user row must survive", round).isEqualTo(1);

            for (UiUserEntity u : userRepository.findAll()) {
                if (u.getEmail() != null && u.getEmail().equalsIgnoreCase(email)) {
                    userRepository.deleteById(u.getId());
                }
            }
        }
    }

    private List<MvcResult> runParallelRegisters(String email) throws Exception {
        CountDownLatch ready = new CountDownLatch(RACE_THREADS);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(RACE_THREADS);
        List<MvcResult> results = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < RACE_THREADS; i++) {
            final int index = i;
            Thread thread = new Thread(() -> {
                ready.countDown();
                try {
                    if (!go.await(10, TimeUnit.SECONDS)) {
                        return;
                    }
                    String mixed = index % 2 == 0 ? email : email.toUpperCase();
                    String body = "{\"username\":\"regracy" + index + "-"
                        + UUID.randomUUID().toString().substring(0, 8)
                        + "\",\"password\":\"MyStr0ng!P@ssw0rd\",\"fullName\":\"Race\",\"email\":\""
                        + mixed + "\"}";
                    MvcResult result = mockMvc.perform(post("/auth/register")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body.getBytes(StandardCharsets.UTF_8)))
                            .andReturn();
                    results.add(result);
                } catch (Exception e) {
                    // transport-level failure counts as a failed request below
                } finally {
                    done.countDown();
                }
            });
            thread.setDaemon(true);
            thread.start();
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        return results;
    }
}
