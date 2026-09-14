package com.zorrodev.bpm.engine.security;

import com.zorrodev.bpm.contract.dto.ApiKeyWithSecretDTO;
import com.zorrodev.bpm.contract.dto.CreateUiUserDTO;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.ApiKeyRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.service.ApiKeyService;
import com.zorrodev.bpm.engine.service.UiUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;

/**
 * WO-OBS-6: self-provisioning of the Prometheus scrape credential.
 *
 * <p>On startup, idempotently ensures a fixed SYSTEM account
 * ({@link #SCRAPER_USERNAME}, no password — login impossible, WO-INT-4) and, only if the account was just
 * created or holds no active API key, issues one raw key via
 * {@link ApiKeyService#issueKeyForUser} (the same path as the real UI flow) and
 * writes it — without trailing newline, the {@code echo -n} convention from
 * {@code ci/observability/prometheus.yml} — to the file Prometheus actually
 * reads ({@code SCRAPE_TOKEN_FILE}, bind-mounted by the observability overlay).
 *
 * <p>Idempotency is literal: an existing valid token on disk is never rewritten,
 * so restarting the app never desyncs a running Prometheus (WO criterion 2).
 * A missing/non-regular target (e.g. Docker created a directory because no
 * placeholder exists) is a loud ERROR, not a startup crash — availability of
 * the app outranks monitoring on a broken mount.
 *
 * <p>File ownership/permissions are NOT managed here: entrypoint.sh (root)
 * sets group + mode before dropping privileges, and a truncate-write preserves
 * them. No {@code @Transactional}: like {@code UiUserBootstrap}, each step
 * commits on its own and the existence checks reconcile partial progress on
 * the next start (two replicas racing the first start converge: at most one
 * extra key, the file always matches a valid one).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScrapeTokenBootstrap implements ApplicationRunner {

    /**
     * Fixed scraper login. WO names {@code prom-scraper} as an example, but that
     * exact string is already a test fixture ({@code JwtAuthFilterIntegrationTest}
     * saves its own HUMAN {@code prom-scraper} — adopting it would collide on the
     * username unique index), so the provisioned account uses this name instead.
     */
    public static final String SCRAPER_USERNAME = "prometheus-scraper";

    private final UiUserRepository uiUserRepository;
    private final UiUserService uiUserService;
    private final ApiKeyService apiKeyService;
    private final ApiKeyRepository apiKeyRepository;

    /**
     * Absolute path of the token file inside THIS container. Empty/absent =
     * bootstrap still provisions account + key, but skips the file write
     * (local runs and H2 tests have nowhere to write to).
     */
    @Value("${SCRAPE_TOKEN_FILE:}")
    private String scrapeTokenFile;

    @Override
    public void run(ApplicationArguments args) {
        // NOTE: this runner executes in EVERY Spring context, including sliced
        // test contexts that @MockitoBean-mock our collaborators (nulls instead
        // of rows). Every step below null-guards instead of orElseThrow — a
        // bootstrap must degrade to a loud log, never crash someone else's
        // context startup (caught live: two CharacterizationTest classes).
        UUID userId = ensureAccount();
        boolean accountCreated = false;
        if (userId == null) {
            userId = createAccount();
            if (userId == null) {
                log.warn("Scrape account provisioning unavailable (user service returned no id) — skipping");
                return;
            }
            accountCreated = true;
        }

        String rawKey = null;
        if (accountCreated || !hasActiveKey(userId)) {
            // Self-issuance, trailed in audit as such: the account provisions
            // its own first key (no human actor exists at bootstrap time).
            UiUserEntity account = uiUserRepository.findById(userId).orElse(null);
            if (account == null) {
                log.error("Scrape account {} vanished before key issuance — skipping", userId);
                return;
            }
            Principal actor = new Principal.UserPrincipal(userId, account.getUsername(), account.getRole());
            ApiKeyWithSecretDTO issued = apiKeyService.issueKeyForUser(userId, actor);
            if (issued == null || issued.getKey() == null) {
                log.warn("Scrape key issuance unavailable (key service returned nothing) — skipping");
                return;
            }
            rawKey = issued.getKey();
            log.info("Issued Prometheus scrape API key for user={}", SCRAPER_USERNAME);
        }

        if (rawKey != null && writeTokenFile(rawKey)) {
            log.info("Wrote Prometheus scrape token to {}", scrapeTokenFile);
        }
    }

    private UUID ensureAccount() {
        return uiUserRepository.findByUsername(SCRAPER_USERNAME).map(UiUserEntity::getId).orElse(null);
    }

    private UUID createAccount() {
        CreateUiUserDTO dto = new CreateUiUserDTO();
        dto.setUsername(SCRAPER_USERNAME);
        dto.setFullName("Prometheus scraper");
        dto.setUserType("SYSTEM");
        dto.setActive(true);
        try {
            return uiUserService.create(dto);
        } catch (RuntimeException e) {
            // First-start race (e.g. two replicas): the other instance won —
            // re-read instead of crashing startup.
            return uiUserRepository.findByUsername(SCRAPER_USERNAME)
                .map(UiUserEntity::getId)
                .orElseThrow(() -> e);
        }
    }

    /** Mirrors {@code JwtAuthFilter.resolveApiKey} validity: not revoked, not expired. */
    private boolean hasActiveKey(UUID userId) {
        Instant now = Instant.now();
        return apiKeyRepository.findAllByOwnerUserId(userId).stream()
            .anyMatch(k -> k.getRevokedAt() == null
                && (k.getExpiresAt() == null || k.getExpiresAt().isAfter(now)));
    }

    private boolean writeTokenFile(String rawKey) {
        if (scrapeTokenFile == null || scrapeTokenFile.isBlank()) {
            log.info("SCRAPE_TOKEN_FILE not set — scrape token provisioned in DB only");
            return false;
        }
        Path target = Path.of(scrapeTokenFile);
        if (!Files.isRegularFile(target)) {
            log.error("Scrape token target {} is not a regular file (missing, or Docker "
                + "created a directory because no placeholder exists) — token NOT written; "
                + "Prometheus will keep failing auth until the file is fixed", scrapeTokenFile);
            return false;
        }
        try {
            // No CREATE: the deploy placeholder must exist; truncate preserves the
            // entrypoint-managed owner/group/mode. No trailing newline (echo -n convention).
            Files.writeString(target, rawKey, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            return true;
        } catch (IOException e) {
            log.error("Failed to write scrape token to {} — token NOT written", scrapeTokenFile, e);
            return false;
        }
    }
}
