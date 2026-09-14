package com.zorrodev.bpm.engine;

import com.zorrodev.bpm.engine.entity.ApiKeyEntity;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.ApiKeyRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.KeyHasher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-INT-4: migration 075 (ui_users.user_type), 076 (drop service_account tables),
 * 077 (drop unique(api_key.owner_user_id) — multiple keys per SYSTEM account).
 * Runs on REAL PostgreSQL: the Spring context applies every Liquibase changeset
 * against postgres:16, then this class asserts the resulting schema state (V11,
 * H2-green is not prod-green — the FK/index interplay of 077 is PG-specific).
 */
class SchemaMigrationPgIT extends PostgresIT {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ApiKeyRepository apiKeyRepository;
    @Autowired private UiUserRepository uiUserRepository;

    // --- 075: ui_users.user_type present, SYSTEM values accepted ---

    @Test
    void migration075_addsUserType() {
        Integer cols = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.columns "
                + "WHERE table_name = 'ui_users' AND column_name = 'user_type'",
            Integer.class);
        assertThat(cols).isEqualTo(1);

        UiUserEntity sys = new UiUserEntity();
        sys.setId(UUID.randomUUID());
        sys.setUsername("mig075sys_" + UUID.randomUUID().toString().substring(0, 8));
        sys.setPasswordHash("x");
        sys.setUserType("SYSTEM");
        sys.setRole("SUPER_ADMIN");
        sys.setActive(true);
        sys.setCreatedAt(Instant.now());
        uiUserRepository.save(sys);
        assertThat(uiUserRepository.findById(sys.getId()).orElseThrow().getUserType()).isEqualTo("SYSTEM");
    }

    // --- WO-SEC-63 (107): ui_users.token_version present with NOT NULL + DEFAULT 0,
    // and the entity round-trips it. The default matters: existing accounts upgraded in
    // place must start at 0, and a legacy JWT without a 'ver' claim matches that 0.

    @Test
    void migration107_addsTokenVersionColumnWithDefaultZero() {
        Integer cols = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.columns "
                + "WHERE table_name = 'ui_users' AND column_name = 'token_version'",
            Integer.class);
        assertThat(cols).isEqualTo(1);

        String isNullable = jdbcTemplate.queryForObject(
            "SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_name = 'ui_users' AND column_name = 'token_version'",
            String.class);
        assertThat(isNullable).isEqualTo("NO");

        // Insert WITHOUT setting tokenVersion → DB default 0 must apply (not explicit NULL)
        UiUserEntity u = new UiUserEntity();
        u.setId(UUID.randomUUID());
        u.setUsername("mig107_0_" + UUID.randomUUID().toString().substring(0, 8));
        u.setPasswordHash("x");
        u.setUserType("HUMAN");
        u.setRole("USER");
        u.setActive(true);
        u.setCreatedAt(Instant.now());
        uiUserRepository.save(u);
        assertThat(uiUserRepository.findById(u.getId()).orElseThrow().getTokenVersion()).isZero();

        // Bump and read back — the column actually persists the value
        uiUserRepository.incrementTokenVersion(u.getId());
        assertThat(uiUserRepository.findById(u.getId()).orElseThrow().getTokenVersion()).isEqualTo(1);
    }

    // --- 076 (criterion 11): service_account tables dropped ---

    @Test
    void migration076_dropsServiceAccountTables() {
        Integer serviceAccounts = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_name = 'service_account'",
            Integer.class);
        Integer serviceAccountPermissions = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_name = 'service_account_permission'",
            Integer.class);
        assertThat(serviceAccounts).isZero();
        assertThat(serviceAccountPermissions).isZero();
    }

    // --- 077: multiple active keys per SYSTEM owner, plain index + FK survive ---

    @Test
    void migration077_allowsMultipleKeysPerOwner() {
        UiUserEntity sys = new UiUserEntity();
        sys.setId(UUID.randomUUID());
        sys.setUsername("mig077sys_" + UUID.randomUUID().toString().substring(0, 8));
        sys.setPasswordHash("x");
        sys.setUserType("SYSTEM");
        sys.setRole("SUPER_ADMIN");
        sys.setActive(true);
        sys.setCreatedAt(Instant.now());
        uiUserRepository.save(sys);

        ApiKeyEntity key1 = new ApiKeyEntity();
        key1.setId(UUID.randomUUID());
        key1.setOwnerUserId(sys.getId());
        key1.setKeyHash(KeyHasher.sha256("zbpm_sk_mig_1_" + UUID.randomUUID()));
        key1.setPrefix("mig1");
        key1.setCreatedAt(Instant.now());
        apiKeyRepository.save(key1);

        // The second key for the same owner must NOT violate any unique constraint
        ApiKeyEntity key2 = new ApiKeyEntity();
        key2.setId(UUID.randomUUID());
        key2.setOwnerUserId(sys.getId());
        key2.setKeyHash(KeyHasher.sha256("zbpm_sk_mig_2_" + UUID.randomUUID()));
        key2.setPrefix("mig2");
        key2.setCreatedAt(Instant.now());
        apiKeyRepository.save(key2);

        assertThat(apiKeyRepository.findAllByOwnerUserId(sys.getId())).hasSize(2);

        // The helper that used to rely on one-key-per-owner no longer throws NonUnique
        assertThatThrownBy(() -> apiKeyRepository.findByOwnerUserId(sys.getId()))
            .hasMessageContaining("unique");
    }

    // --- 077: the supporting index and FK still exist (H2 orphan-index trap) ---

    @Test
    void migration077_keepsOwnerIndexAndForeignKey() {
        Integer index = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pg_indexes "
                + "WHERE tablename = 'api_key' AND indexname = 'idx_api_key_owner_user_id'",
            Integer.class);
        assertThat(index).isEqualTo(1);

        Integer fk = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.table_constraints "
                + "WHERE constraint_type = 'FOREIGN KEY' AND table_name = 'api_key' "
                + "AND constraint_name = 'fk_api_key_user'",
            Integer.class);
        assertThat(fk).isEqualTo(1);

        Integer uniqueOwner = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pg_indexes "
                + "WHERE tablename = 'api_key' AND indexdef ILIKE '%UNIQUE%owner_user_id%'",
            Integer.class);
        assertThat(uniqueOwner).isZero();
    }
}