package com.zorrodev.bpm.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.ApiKeyRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.ScrapeTokenBootstrap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-OBS-6: the app provisions its own Prometheus scrape credential on startup.
 *
 * <p>Full APP context — {@code ScrapeTokenBootstrap} runs for real during context
 * startup (no manual invocation for the primary path), against a temp-dir token
 * file (never the repo checkout). Every test is order-independent: none leaves
 * the token file or the scraper key in a state another test cannot handle.
 */
@ActiveProfiles("test")
@SpringBootTest(classes = APP.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:test",
    "spring.rabbitmq.host=localhost",
    "spring.rabbitmq.port=11002",
    "spring.rabbitmq.username=zorrodev",
    "spring.rabbitmq.password=zorrodev",
    "spring.rabbitmq.listener.simple.auto-startup=false",
    "app.filesDir=target/files",
    "zorrobpm.security.rate-limit.enabled=false",
    "server.forward-headers-strategy=framework"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScrapeTokenBootstrapTest {

    // Static init (not @TempDir): @DynamicPropertySource callbacks need the path
    // before any extension ordering can be relied upon — class-load order is certain.
    static final Path TOKEN_DIR;
    static final Path TOKEN_FILE;
    static {
        try {
            TOKEN_DIR = Files.createTempDirectory("scrape-token-it");
            TOKEN_FILE = TOKEN_DIR.resolve("scrape-token");
            // Deploy placeholder shape: empty regular file (Docker must never
            // create a directory here — the runner skips non-regular targets).
            Files.writeString(TOKEN_FILE, "");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void tokenPath(DynamicPropertyRegistry registry) {
        registry.add("SCRAPE_TOKEN_FILE", () -> TOKEN_FILE.toString());
    }

    @Autowired MockMvc mockMvc;
    @Autowired UiUserRepository userRepository;
    @Autowired ApiKeyRepository apiKeyRepository;
    @Autowired ScrapeTokenBootstrap bootstrap;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    // ==================== Criterion 1+3+4: startup provisions everything ====================

    @Test
    void startup_provisionsSystemAccountKeyAndFile_scrapeWorksLoginFails() throws Exception {
        UiUserEntity scraper = userRepository.findByUsername(ScrapeTokenBootstrap.SCRAPER_USERNAME).orElseThrow();
        assertEquals("SYSTEM", scraper.getUserType(), "scraper must be a SYSTEM account");
        assertTrue(scraper.isActive(), "scraper must be active (resolveApiKey rejects inactive owners)");

        assertTrue(Files.isRegularFile(TOKEN_FILE), "startup must have written the token file");
        String token = Files.readString(TOKEN_FILE);
        assertTrue(token.startsWith("zbpm_sk_"), "token must be a real API key, not a placeholder");
        assertFalse(token.endsWith("\n"), "no trailing newline (echo -n convention)");

        // The provisioned key actually scrapes (concrete metric, not just 200).
        String body = mockMvc.perform(get("/actuator/prometheus")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("zbpm_process_started_total"), "scrape must expose zbpm metrics");

        // SYSTEM account cannot log in (no usable password by design, WO-INT-4).
        LoginDTO login = new LoginDTO();
        login.setUsername(ScrapeTokenBootstrap.SCRAPER_USERNAME);
        login.setPassword("anything-at-all");
        mockMvc.perform(post("/auth/login")
                .content(mapper.writeValueAsString(login))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized());
    }

    // ==================== Criterion 2: restart never rewrites a valid token ====================

    @Test
    void restartWithExistingKey_writesNothing() throws Exception {
        String realKey = Files.readString(TOKEN_FILE);
        Files.writeString(TOKEN_FILE, "SENTINEL");
        FileTime mtime = Files.getLastModifiedTime(TOKEN_FILE);
        try {
            bootstrap.run(null);
            // Content AND mtime prove the file was not even touched, let alone rotated.
            assertEquals("SENTINEL", Files.readString(TOKEN_FILE), "existing token must not be rewritten");
            assertEquals(mtime, Files.getLastModifiedTime(TOKEN_FILE), "file must not be touched at all");
        } finally {
            Files.writeString(TOKEN_FILE, realKey);
        }
    }

    // ==================== Recovery: lost key is re-issued and the file rewritten ====================

    @Test
    void missingKey_reissuesFreshKeyAndRewritesFile() throws Exception {
        String before = Files.readString(TOKEN_FILE);
        UUID scraperId = userRepository.findByUsername(ScrapeTokenBootstrap.SCRAPER_USERNAME).orElseThrow().getId();
        apiKeyRepository.findAllByOwnerUserId(scraperId).forEach(apiKeyRepository::delete);

        bootstrap.run(null);

        String after = Files.readString(TOKEN_FILE);
        assertNotEquals(before, after, "a fresh key must be issued when none is active");
        assertTrue(after.startsWith("zbpm_sk_"), "rewritten token must be a real API key");
    }
}
