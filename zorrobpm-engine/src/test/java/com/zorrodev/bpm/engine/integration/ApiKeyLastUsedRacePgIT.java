package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ApiKeyEntity;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.ApiKeyRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.service.ApiKeyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-SEC-66 criterion 1 (F07), PostgreSQL only: the debounce {@code lastUsedAt}
 * write must never resurrect a concurrently revoked API key.
 *
 * <p>The race, faithfully replayed: thread A (the filter) loads the live key
 * row — the exact snapshot {@code JwtAuthFilter.resolveApiKey} holds when it
 * reaches the debounce block. Thread B revokes the key (the real
 * {@code ApiKeyService.revokeOwnKey} path). Thread A then performs the debounce
 * write. With the old {@code save(detachedSnapshot)} the merge writes the stale
 * {@code revokedAt=null} back over the revoke; with the conditional
 * single-column UPDATE the revoke survives (zero rows matched) and a follow-up
 * lookup still sees the key as revoked.
 *
 * <p>Criterion 2 is covered in the same class against the same statement: an
 * uncontended touch updates {@code lastUsedAt} (debounce behaviour unchanged).
 *
 * <p>POF (G-N): removing the {@code AND a.revokedAt IS NULL} condition from
 * {@code touchLastUsedAtIfLive} makes this RED — the unconditional UPDATE
 * matches the dead key ({@code touched==1}) even though {@code revokedAt}
 * stays set. (The consumer-guard half — {@code save} vs {@code touch} in
 * {@code JwtAuthFilter} — is proven separately by
 * {@code JwtAuthFilterTest.api_key_debounce_doesNotSaveTwice}; this PgIT never
 * calls the filter, so a filter-only revert would leave it green.)
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
@Tag("pg")
public class ApiKeyLastUsedRacePgIT extends PostgresIT {

    @Autowired private ApiKeyRepository apiKeyRepository;
    @Autowired private UiUserRepository userRepository;
    @Autowired private ApiKeyService apiKeyService;

    private final List<UUID> cleanupKeyIds = Collections.synchronizedList(new ArrayList<>());
    private final List<UUID> cleanupUserIds = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void cleanup() {
        for (UUID id : List.copyOf(cleanupKeyIds)) {
            try {
                apiKeyRepository.deleteById(id);
            } catch (Exception e) {
                // already gone — best effort
            }
        }
        cleanupKeyIds.clear();
        for (UUID id : List.copyOf(cleanupUserIds)) {
            try {
                userRepository.deleteById(id);
            } catch (Exception e) {
                // best effort
            }
        }
        cleanupUserIds.clear();
    }

    private UUID seedUser(String tag) {
        UiUserEntity e = new UiUserEntity();
        e.setId(UUID.randomUUID());
        e.setUsername("sec66-" + tag + "-" + UUID.randomUUID().toString().substring(0, 8));
        e.setPasswordHash("hashed");
        e.setFullName("Key Race");
        e.setEmail("sec66-" + tag + "-" + UUID.randomUUID().toString().substring(0, 8) + "@x.com");
        e.setRole("USER");
        e.setActive(true);
        e.setUserType("HUMAN");
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        e.setRegistrationStatus("ACTIVE");
        userRepository.save(e);
        cleanupUserIds.add(e.getId());
        return e.getId();
    }

    private UUID seedKey(UUID ownerId, String tag) {
        ApiKeyEntity key = new ApiKeyEntity();
        key.setId(UUID.randomUUID());
        key.setOwnerUserId(ownerId);
        key.setKeyHash("sec66-" + tag + "-" + UUID.randomUUID());
        key.setPrefix("zbpm_sk_test");
        key.setCreatedAt(Instant.now());
        key.setLastUsedAt(Instant.now().minusSeconds(3600L));
        apiKeyRepository.saveAndFlush(key);
        cleanupKeyIds.add(key.getId());
        return key.getId();
    }

    @Test
    void revokeDuringDebounceWindow_revokedAtSurvives() throws Exception {
        for (int round = 0; round < 5; round++) {
            UUID owner = seedUser("r" + round);
            UUID keyId = seedKey(owner, "r" + round);

            // Thread A = the filter: loads the row BEFORE the revoke (live snapshot,
            // revokedAt=null) — exactly what JwtAuthFilter.resolveApiKey holds when
            // it reaches the debounce block.
            ApiKeyEntity filterSnapshot =
                apiKeyRepository.findById(keyId).orElseThrow();
            assertThat(filterSnapshot.getRevokedAt()).isNull();

            // Thread B revokes concurrently, through the real service path. Sequenced
            // after the load and before the debounce write: the exact interleaving
            // that used to resurrect the key (stale snapshot merged back over the
            // revoke). Deterministic — no latches needed for this ordering.
            apiKeyService.revokeOwnKey(owner);

            // Thread A now performs the debounce write with its stale snapshot.
            // Exactly what JwtAuthFilter.resolveApiKey does in the debounce block.
            int touched = apiKeyRepository.touchLastUsedAtIfLive(
                filterSnapshot.getId(), Instant.now());

            ApiKeyEntity fin = apiKeyRepository.findById(keyId).orElseThrow();
            assertThat(fin.getRevokedAt())
                .as("round %d: revoke survives the debounce write", round)
                .isNotNull();
            // The conditional UPDATE matched zero rows on the dead key.
            assertThat(touched)
                .as("round %d: touch on a revoked key matches nothing", round)
                .isEqualTo(0);
            // And the key is rejected on next lookup, like the filter would.
            assertThat(apiKeyRepository.findById(keyId).orElseThrow().getRevokedAt())
                .isNotNull();
        }
    }

    @Test
    void uncontendedTouch_updatesLastUsedAt() {
        UUID owner = seedUser("plain");
        UUID keyId = seedKey(owner, "plain");
        Instant before = apiKeyRepository.findById(keyId).orElseThrow().getLastUsedAt();

        Instant touchAt = Instant.now();
        int rows = apiKeyRepository.touchLastUsedAtIfLive(keyId, touchAt);

        assertThat(rows).isEqualTo(1);
        ApiKeyEntity fin = apiKeyRepository.findById(keyId).orElseThrow();
        // PG timestamp(6): micros, not nanos — compare within 1ms, not exactly.
        assertThat(Math.abs(fin.getLastUsedAt().toEpochMilli() - touchAt.toEpochMilli()))
            .isLessThanOrEqualTo(1L);
        assertThat(fin.getLastUsedAt()).isAfter(before);
        assertThat(fin.getRevokedAt()).isNull();
    }
}
