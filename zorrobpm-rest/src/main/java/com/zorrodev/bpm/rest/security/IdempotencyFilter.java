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
import org.springframework.http.MediaType;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
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
    /**
     * Bodies beyond this get a direct 413 (house precedent: send413) instead of being
     * hashed — a truncated prefix could alias different bodies to one hash, and
     * unbounded buffering is a memory-DoS vector. Admin-scale batches fit easily.
     */
    static final int MAX_BODY_BYTES = 5 * 1024 * 1024;

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
            // Written directly (never thrown): exceptions from filters bypass the
            // DispatcherServlet advice and would surface as container 500s.
            writeError(response, HttpStatus.BAD_REQUEST.value(),
                "INVALID_IDEMPOTENCY_KEY", "Idempotency-Key too long");
            return;
        }
        String endpoint = PathNormalizer.normalize(request.getRequestURI());

        // Read the body ONCE here (for the hash) and serve it downstream from our own
        // byte-backed wrapper: pre-reading Spring's ContentCachingRequestWrapper leaves
        // the downstream read empty in this Spring version (proven by live RED), so the
        // framework wrapper is used ONLY on the response side. Bounded read — bodies
        // beyond the cap get a direct 413 (house precedent: send413), never truncated
        // hashes and never unbounded buffering.
        byte[] body = readBounded(request.getInputStream(), MAX_BODY_BYTES);
        if (body == null) {
            writeError(response, 413, "IDEMPOTENCY_BODY_TOO_LARGE",
                "Request body exceeds idempotency limit");
            return;
        }
        String hash = sha256Hex(body);
        HttpServletRequest replayableRequest = new CachedBodyRequest(request, body);

        AtomicReference<Replay> replay = new AtomicReference<>();
        AtomicReference<String> mismatchMessage = new AtomicReference<>();
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
                            // Written directly after the tx (below), never thrown:
                            // exceptions from filters bypass DispatcherServlet advice.
                            mismatchMessage.set("Idempotency-Key already used with a different request body");
                            return;
                        }
                        replay.set(new Replay(existing.get().getResponseStatus(),
                            existing.get().getResponseBody(), existing.get().getResponseContentType()));
                        return;
                    }
                    ContentCachingResponseWrapper cachedResponse =
                        new ContentCachingResponseWrapper(response);
                    try {
                        chain.doFilter(replayableRequest, cachedResponse);
                    } catch (IOException | RuntimeException | jakarta.servlet.ServletException e) {
                        // Effect (if any) rolls back with this tx — retry starts clean.
                        // Deliberately no copyBodyToResponse: the container owns error
                        // rendering from here (copying could commit a 200-empty first).
                        throw new FilterChainException(e);
                    }
                    int responseStatus = cachedResponse.getStatus();
                    if (responseStatus == 401 || responseStatus == 403 || responseStatus == 429) {
                        // Auth/rate-limit failures are never cached — the retry must
                        // re-authenticate and re-pass the limiter live. A saved 401/429
                        // would pin the client to failure after fixing its token/quota.
                        copyBodyOrThrow(cachedResponse, response);
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
                    copyBodyOrThrow(cachedResponse, response);
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
        if (mismatchMessage.get() != null) {
            // Written directly (never thrown): filter exceptions bypass DispatcherServlet
            // advice and would surface as container 500s. The tx above committed empty.
            writeError(response, HttpStatus.UNPROCESSABLE_ENTITY.value(),
                "IDEMPOTENCY_KEY_REUSED", mismatchMessage.get());
            return;
        }
        if (raceLost.get() != null) {
            // Fresh read outside the rolled-back transaction.
            try {
                replay.set(readFresh(key, endpoint, raceLost.get()));
            } catch (ErrorSpec e) {
                writeError(response, e.status(), e.code(), e.getMessage());
                return;
            }
        }
        if (replay.get() != null) {
            writeReplay(response, replay.get());
        }
    }

    /** Fresh (own-transaction) read for the lost-race fallback. */
    private Replay readFresh(String key, String endpoint, String hash) {
        Optional<IdempotencyRecord> existing = repository.findByIdemKeyAndEndpoint(key, endpoint);
        if (existing.isEmpty()) {
            throw new ErrorSpec(HttpStatus.CONFLICT.value(),
                "IDEMPOTENCY_KEY_CONFLICT", "Idempotency-Key conflict, retry with a new key");
        }
        if (!MessageDigest.isEqual(
                existing.get().getRequestHash().getBytes(StandardCharsets.UTF_8),
                hash.getBytes(StandardCharsets.UTF_8))) {
            throw new ErrorSpec(HttpStatus.UNPROCESSABLE_ENTITY.value(),
                "IDEMPOTENCY_KEY_REUSED", "Idempotency-Key already used with a different request body");
        }
        return new Replay(existing.get().getResponseStatus(),
            existing.get().getResponseBody(), existing.get().getResponseContentType());
    }

    /**
     * Error writer for this filter's own rejections (key too long / reuse / conflict).
     * Written directly, never thrown: exceptions from servlet filters bypass the
     * DispatcherServlet advice ({@code GlobalExceptionHandler} only sees controller
     * exceptions) and would surface as container 500s. Shape mirrors neighboring
     * filters ({@code send429}/{@code send413}).
     */
    private void writeError(HttpServletResponse response, int status,
                            String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }

    /** Unchecked error spec for filter-own rejections (written directly, see above). */
    private static final class ErrorSpec extends RuntimeException {
        private final int status;
        private final String code;

        ErrorSpec(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        int status() {
            return status;
        }

        String code() {
            return code;
        }
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

    /**
     * Copy with unchecked failures, for use inside {@code TransactionCallbackWithoutResult}
     * (which declares no checked throws): an I/O failure mid-copy rolls our tx back
     * via the carrier.
     */
    private static void copyBodyOrThrow(ContentCachingResponseWrapper cached,
                                        HttpServletResponse response) {
        try {
            cached.copyBodyToResponse();
        } catch (IOException e) {
            throw new FilterChainException(e);
        }
    }

    private record Replay(int status, String body, String contentType) {
    }

    /**
     * Re-readable request backed by already-read bytes. Passed downstream instead of
     * the consumed original.
     */
    private static final class CachedBodyRequest extends jakarta.servlet.http.HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public jakarta.servlet.ServletInputStream getInputStream() {
            java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(body);
            return new jakarta.servlet.ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(jakarta.servlet.ReadListener readListener) {
                    throw new UnsupportedOperationException("async not supported");
                }
            };
        }

        @Override
        public java.io.BufferedReader getReader() throws IOException {
            String enc = getCharacterEncoding();
            java.nio.charset.Charset charset;
            try {
                charset = enc != null ? java.nio.charset.Charset.forName(enc) : StandardCharsets.UTF_8;
            } catch (java.nio.charset.UnsupportedCharsetException e) {
                throw new java.io.UnsupportedEncodingException(enc);
            }
            return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(), charset));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }

    /**
     * Reads at most {@code cap + 1} bytes; returns {@code null} when the stream is
     * longer (caller rejects oversized bodies instead of hashing a truncated prefix,
     * which could alias different bodies to one hash).
     */
    static byte[] readBounded(java.io.InputStream in, int cap) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf, 0, Math.min(buf.length, cap + 1 - total))) > 0) {
            out.write(buf, 0, n);
            total += n;
            if (total > cap) {
                return null;
            }
        }
        return out.toByteArray();
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
