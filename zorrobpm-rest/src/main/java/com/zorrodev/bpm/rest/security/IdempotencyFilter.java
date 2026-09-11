package com.zorrodev.bpm.rest.security;

import com.zorrodev.bpm.engine.entity.IdempotencyRecord;
import com.zorrodev.bpm.engine.repository.IdempotencyRecordRepository;
import com.zorrodev.bpm.engine.service.AdvisoryDeployLock;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * WO-REL-21: {@code Idempotency-Key} on create-mutations (replay, not re-execution).
 *
 * <p>Scope — exactly 5 endpoints, POST + exact path (never {@code /dmn/{id}/evaluate},
 * never GET): {@code /process-instances}, {@code /deployments}, {@code /dmn},
 * {@code /forms}, {@code /auth/register}. Without the header the request passes
 * through byte-for-byte untouched (no wrapping at all).
 *
 * <p>Semantics per (key, endpoint): same body hash → saved status+body replayed
 * WITHOUT invoking the controller; different hash → 422 (key reuse); absent →
 * execute once and save. Save happens in the SAME transaction the controller joins
 * ({@code REQUIRED}), so effect + record commit atomically: any exception rolls both
 * back and the retry starts clean. 401/403 responses are never saved (auth failures
 * must re-authenticate live on retry — a saved 401 would pin the client to failure
 * after fixing its token). No principal column by design (see WO-REL-21 report,
 * threat-model: replaying a foreign key+body buys nothing over a plain HTTP replay
 * of the same request; keys must be client UUIDs).
 *
 * <p>Race: two concurrent same-key requests serialize on
 * {@code AdvisoryDeployLock} (per-endpoint+key namespace) INSIDE the transaction —
 * the loser blocks on the lock, then sees the winner's committed record and replays
 * without executing. The {@code DataIntegrityViolationException} fallback covers
 * databases without advisory locks (H2 skips them): the loser re-reads fresh and
 * replays instead of 500ing.
 */
@RequiredArgsConstructor
public class IdempotencyFilter extends OncePerRequestFilter {

    static final String HEADER = "Idempotency-Key";
    private static final int MAX_KEY_LENGTH = 255;

    private static final Set<String> PATHS = Set.of(
        "/process-instances",
        "/deployments",
        "/dmn",
        "/forms",
        "/auth/register"
    );

    private final IdempotencyRecordRepository repository;
    private final AdvisoryDeployLock advisoryLock;
    private final TransactionTemplate transactionTemplate;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws IOException, jakarta.servlet.ServletException {
        if (!"POST".equalsIgnoreCase(request.getMethod())
            || !PATHS.contains(PathNormalizer.normalize(request.getRequestURI()))) {
            chain.doFilter(request, response);
            return;
        }
        String key = request.getHeader(HEADER);
        if (key == null || key.isBlank()) {
            chain.doFilter(request, response);
            return;
        }
        if (key.length() > MAX_KEY_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key too long");
        }
        String endpoint = PathNormalizer.normalize(request.getRequestURI());

        ContentCachingRequestWrapper cachedRequest = new ContentCachingRequestWrapper(request);
        byte[] body = cachedRequest.getInputStream().readAllBytes();
        String hash = sha256Hex(body);

        AtomicReference<Replay> replay = new AtomicReference<>();
        AtomicReference<ResponseStatusException> mismatch = new AtomicReference<>();
        AtomicReference<String> raceLost = new AtomicReference<>();
        try {
            transactionTemplate.execute(new TransactionCallbackWithoutResult() {
                @Override
                protected void doInTransactionWithoutResult(TransactionStatus status) {
                    // Serialize same-key concurrents; the loser waits here, then reads
                    // the winner's committed record below (lock is xact-scoped).
                    advisoryLock.acquireForKey("idempotency:" + endpoint + ":" + key);
                    Optional<IdempotencyRecord> existing =
                        repository.findByIdemKeyAndEndpoint(key, endpoint);
                    if (existing.isPresent()) {
                        if (!MessageDigest.isEqual(
                                existing.get().getRequestHash().getBytes(StandardCharsets.UTF_8),
                                hash.getBytes(StandardCharsets.UTF_8))) {
                            mismatch.set(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                                "Idempotency-Key already used with a different request body"));
                            return;
                        }
                        replay.set(new Replay(existing.get().getResponseStatus(),
                            existing.get().getResponseBody(), existing.get().getResponseContentType()));
                        return;
                    }
                    ContentCachingResponseWrapper cachedResponse =
                        new ContentCachingResponseWrapper(response);
                    try {
                        chain.doFilter(cachedRequest, cachedResponse);
                    } catch (IOException | RuntimeException | jakarta.servlet.ServletException e) {
                        // Effect (if any) rolls back with this tx — retry starts clean.
                        // Deliberately no copyBodyToResponse: the container owns error
                        // rendering from here (copying could commit a 200-empty first).
                        throw new FilterChainException(e);
                    }
                    int responseStatus = cachedResponse.getStatus();
                    if (responseStatus == 401 || responseStatus == 403) {
                        // Auth failures are never cached — the retry must re-authenticate live.
                        copyBody(cachedResponse, response);
                        return;
                    }
                    try {
                        IdempotencyRecord record = new IdempotencyRecord();
                        record.setIdemKey(key);
                        record.setEndpoint(endpoint);
                        record.setRequestHash(hash);
                        record.setResponseStatus(responseStatus);
                        byte[] responseBody = cachedResponse.getContentAsByteArray();
                        record.setResponseBody(new String(responseBody, StandardCharsets.UTF_8));
                        record.setResponseContentType(cachedResponse.getContentType());
                        record.setCreatedAt(Instant.now());
                        repository.save(record);
                    } catch (DataIntegrityViolationException dup) {
                        // Lost the race where advisory locks don't exist (H2): this
                        // transaction is now dead (PG aborts on error), so only mark
                        // rollback and re-read FRESH below, outside of it. On PG this
                        // branch is unreachable (the lock serializes).
                        status.setRollbackOnly();
                        raceLost.set(hash);
                        return;
                    }
                    copyBody(cachedResponse, response);
                }
            });
        } catch (FilterChainException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof jakarta.servlet.ServletException se) {
                throw se;
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new jakarta.servlet.ServletException(cause);
        }
        if (mismatch.get() != null) {
            throw mismatch.get();
        }
        if (raceLost.get() != null) {
            // Fresh read outside the rolled-back transaction.
            replay.set(readFresh(key, endpoint, raceLost.get()));
        }
        if (replay.get() != null) {
            writeReplay(response, replay.get());
        }
    }

    /** Fresh (own-transaction) read for the lost-race fallback. */
    private Replay readFresh(String key, String endpoint, String hash) {
        Optional<IdempotencyRecord> existing = repository.findByIdemKeyAndEndpoint(key, endpoint);
        if (existing.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Idempotency-Key conflict, retry with a new key");
        }
        if (!MessageDigest.isEqual(
                existing.get().getRequestHash().getBytes(StandardCharsets.UTF_8),
                hash.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Idempotency-Key already used with a different request body");
        }
        return new Replay(existing.get().getResponseStatus(),
            existing.get().getResponseBody(), existing.get().getResponseContentType());
    }

    private void writeReplay(HttpServletResponse response, Replay replay) throws IOException {
        response.setStatus(replay.status());
        if (replay.contentType() != null) {
            response.setContentType(replay.contentType());
        }
        byte[] body = replay.body() != null ? replay.body().getBytes(StandardCharsets.UTF_8) : new byte[0];
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    private static void copyBody(ContentCachingResponseWrapper cached,
                                 HttpServletResponse response) throws IOException {
        cached.copyBodyToResponse();
    }

    private record Replay(int status, String body, String contentType) {
    }

    /** Unchecked carrier: exceptions from the downstream chain must roll our tx back. */
    private static final class FilterChainException extends RuntimeException {
        FilterChainException(Throwable cause) {
            super(cause);
        }
    }

    static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
