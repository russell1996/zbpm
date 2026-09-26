package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.PasswordHasher;
import com.zorrodev.bpm.engine.service.impl.UiUserServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-SEC-76: PBKDF2 cost at the OWASP target (600_000) + silent rehash-on-login.
 *
 * <ul>
 *   <li>Criterion 1: fresh {@link PasswordHasher#hash} stamps CURRENT_ITERATIONS.</li>
 *   <li>Criterion 2: a user whose stored hash is legacy (120_000) logs in fine AND
 *       the stored hash is re-stamped at the current cost in the same login.</li>
 * </ul>
 *
 * Full Spring context with the REAL {@link PasswordHasher} (no mocks — the cost
 * lives in real KDF runs) and the REAL {@link UiUserServiceImpl} login path.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class Sec76Pbkdf2IterationsIT {

    @Autowired private PasswordHasher passwordHasher;
    @Autowired private UiUserServiceImpl service;
    @Autowired private UiUserRepository repository;
    @Autowired private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private static String legacyHash(String password) {
        // Pre-SEC-76 format, byte-identical to the old hash() output: same salt
        // length, same key length, only the stamped count differs.
        byte[] salt = new byte[16];
        new java.security.SecureRandom().nextBytes(salt);
        try {
            javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
                password.toCharArray(), salt, 120_000, 256);
            byte[] hash = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).getEncoded();
            return "pbkdf2$120000$"
                + java.util.Base64.getEncoder().encodeToString(salt) + "$"
                + java.util.Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void newHashes_stampCurrentIterations() {
        String stored = passwordHasher.hash("fresh-password");
        // Literal, not PasswordHasher.CURRENT_ITERATIONS: the constant is a
        // compile-time inline candidate — referencing it here would assert the
        // test's own copy, not the production value (P-43 class, proven by
        // javap: test bytecode carried its own "pbkdf2$120000$" under mutation).
        assertThat(stored).startsWith("pbkdf2$600000$");
        assertThat(passwordHasher.matches("fresh-password", stored)).isTrue();
        assertThat(passwordHasher.needsRehash(stored)).isFalse();
    }

    @Test
    void legacyUser_loginSucceeds_andHashUpgraded() throws Exception {
        String username = "sec76legacy" + UUID.randomUUID().toString().substring(0, 4);
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash(legacyHash("old-pass"));
        user.setFullName(username);
        user.setRole("USER");
        user.setActive(true);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        repository.saveAndFlush(user);
        UUID userId = user.getId();

        assertThat(passwordHasher.needsRehash(user.getPasswordHash())).isTrue();

        // Red-team H1: the live login runs @Transactional(readOnly=true) — a
        // direct service call inside a test @Transactional would join the TEST's
        // read-write tx and mask a dropped write (P-18 class). Go through HTTP
        // with no test transaction, then re-read the row in a FRESH read-write
        // transaction (REQUIRES_NEW via the repository call after the HTTP
        // request completed and its tx committed/rolled back): a write that
        // never hit the DB comes back stale-legacy here.
        //
        // POF-mutant H1 (save inside the readOnly login tx) was PROVEN
        // undetectable by this HTTP shape — Hibernate flushes the managed
        // entity on the readOnly commit path despite FlushMode.MANUAL, so the
        // row IS upgraded even by the mutant. The mutant is therefore rejected
        // by code reading (write in a readOnly tx is a contract violation with
        // provider-dependent outcome), not by this test — stated here so the
        // POF claim stays honest. The REQUIRES_NEW production path IS proven:
        // the green run persists through a real commit boundary.
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword("old-pass");
        mockMvc.perform(post("/auth/login").header("X-Auth-Transport", "bearer")
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        String upgraded = repository.findById(userId).orElseThrow().getPasswordHash();
        assertThat(upgraded)
            .as("stored hash must be re-stamped at the current cost by the login")
            .startsWith("pbkdf2$600000$");
        assertThat(passwordHasher.matches("old-pass", upgraded)).isTrue();
    }

    @Test
    void wrongPassword_doesNotRehash() {
        String username = "sec76wrong" + UUID.randomUUID().toString().substring(0, 4);
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash(legacyHash("right-pass"));
        user.setFullName(username);
        user.setRole("USER");
        user.setActive(true);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        repository.saveAndFlush(user);

        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword("wrong-pass");
        assertThat(service.login(dto)).as("wrong password must fail").isEmpty();

        String still = repository.findByUsername(username).orElseThrow().getPasswordHash();
        assertThat(still).as("failed login must not rewrite the stored hash")
            .startsWith("pbkdf2$120000$");
    }

    @Test
    void dummyHash_atCurrentCost() {
        // WO-SEC-63 constant-time branch must cost the same as a real check.
        // Literal for the same inline reason as above.
        assertThat(PasswordHasher.CONSTANT_TIME_DUMMY_HASH)
            .startsWith("pbkdf2$600000$");
        assertThat(passwordHasher.needsRehash(
            Optional.of(PasswordHasher.CONSTANT_TIME_DUMMY_HASH).orElseThrow())).isFalse();
    }
}
