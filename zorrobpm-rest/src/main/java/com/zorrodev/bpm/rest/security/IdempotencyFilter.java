package com.zorrodev.bpm.rest.security;

import com.zorrodev.bpm.engine.entity.IdempotencyRecord;
import com.zorrodev.bpm.engine.repository.IdempotencyRecordRepository;
import com.zorrodev.bpm.engine.security.Principal;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * WO-REL-21/32: {@code Idempotency-Key} replay on create + mutation endpoints.
 *
 * <p>WO-REL-21: 5 create paths. WO-REL-32: +5 mutation paths (complete/fail/resolve/claim/cancel)
 * — replay on same (key, endpoint, actor_id, request_hash), 422 on same key+actor but
 * different body (already handled with other data), isolated per actor (F05). Runs AFTER
 * {@code JwtAuthFilter} (HIGHEST+30 vs +20) so a cache-hit still re-validates the credential
 * (F04 — revoked/expired → 401, not replay). Response is buffered inside the transaction
 * and written ONLY after {@code execute()} returns successfully (F06 — commit failure →
 * no 200 to client). AdvisoryDeployLock serializes same-key concurrents.
 */
@RequiredArgsConstructor
public class IdempotencyFilter extends OncePerRequestFilter {

    static final String HEADER = "Idempotency-Key";
    private static final int MAX_KEY_LENGTH = 255;
    static final int MAX_BODY_BYTES = 5 * 1024 * 1024;

    private static final Set<String> EXACT_PATHS = Set.of(
        "/process-instances",
        "/deployments",
        "/dmn",
        "/forms",
        "/auth/register"
    );

    // Mutation endpoints — full normalized URI is the endpoint (includes resourceId)
    private static final Pattern MUTATION_COMPLETE_USER = Pattern.compile("^/user-tasks/[^/]+/complete$");
    private static final Pattern MUTATION_COMPLETE_SERVICE = Pattern.compile("^/service-tasks/[^/]+/complete$");
    private static final Pattern MUTATION_FAIL = Pattern.compile("^/service-tasks/[^/]+/fail$");
    private static final Pattern MUTATION_RESOLVE = Pattern.compile("^/incidents/[^/]+/resolve$");
    private static final Pattern MUTATION_CLAIM = Pattern.compile("^/user-tasks/[^/]+/claim$");
    private static final Pattern MUTATION_UNCLAIM = Pattern.compile("^/user-tasks/[^/]+/unclaim$");
    private static final Pattern MUTATION_ASSIGN = Pattern.compile("^/user-tasks/[^/]+/assign$");
    private static final Pattern MUTATION_CANCEL = Pattern.compile("^/process-instances/[^/]+/cancel$");

    private final IdempotencyRecordRepository repository;
    private final AdvisoryDeployLock advisoryLock;
    private final TransactionTemplate transactionTemplate;

    static boolean isIdempotentPath(String normalized) {
        if (EXACT_PATHS.contains(normalized)) return true;
        return MUTATION_COMPLETE_USER.matcher(normalized).matches()
            || MUTATION_COMPLETE_SERVICE.matcher(normalized).matches()
            || MUTATION_FAIL.matcher(normalized).matches()
            || MUTATION_RESOLVE.matcher(normalized).matches()
            || MUTATION_CLAIM.matcher(normalized).matches()
            || MUTATION_UNCLAIM.matcher(normalized).matches()
            || MUTATION_ASSIGN.matcher(normalized).matches()
            || MUTATION_CANCEL.matcher(normalized).matches();
    }

    static String actorIdOf(HttpServletRequest request) {
        Object p = request.getAttribute("principal");
        if (p instanceof Principal.UserPrincipal up) {
            return up.userId().toString();
        }
        if (p instanceof Principal.ServicePrincipal sp) {
            return sp.ownerUserId().toString();
        }
        return "";
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws IOException, jakarta.servlet.ServletException {
        String normalized = PathNormalizer.normalize(request.getRequestURI());
        if (!"POST".equalsIgnoreCase(request.getMethod()) || !isIdempotentPath(normalized)) {
            chain.doFilter(request, response);
            return;
        }
        String key = request.getHeader(HEADER);
        if (key == null || key.isBlank()) {
            chain.doFilter(request, response);
            return;
        }
        if (key.length() > MAX_KEY_LENGTH) {
            writeError(response, HttpStatus.BAD_REQUEST.value(),
                "INVALID_IDEMPOTENCY_KEY", "Idempotency-Key too long");
            return;
        }
        String endpoint = normalized;

        byte[] body = readBounded(request.getInputStream(), MAX_BODY_BYTES);
        if (body == null) {
            writeError(response, 413, "IDEMPOTENCY_BODY_TOO_LARGE",
                "Request body exceeds idempotency limit");
            return;
        }
        String hash = sha256Hex(body);
        HttpServletRequest replayableRequest = new CachedBodyRequest(request, body);

        String actorId = actorIdOf(request);
        String credentialHashLegacy = sha256Hex(actorId.getBytes(StandardCharsets.UTF_8));

        AtomicReference<Replay> replay = new AtomicReference<>();
        AtomicReference<String> mismatchMessage = new AtomicReference<>();
        AtomicReference<String> raceLost = new AtomicReference<>();
        AtomicReference<Buffered> buffered = new AtomicReference<>();

        try {
            transactionTemplate.execute(new TransactionCallbackWithoutResult() {
                @Override
                protected void doInTransactionWithoutResult(TransactionStatus status) {
                    advisoryLock.acquireForKey("idempotency:" + endpoint + ":" + key + ":" + actorId);
                    var existing = repository.findByIdemKeyAndEndpointAndActorId(key, endpoint, actorId);
                    if (existing.isPresent()) {
                        IdempotencyRecord rec = existing.get();
                        if (isEqualHex(rec.getRequestHash(), hash)) {
                            replay.set(new Replay(rec.getResponseStatus(),
                                rec.getResponseBody(), rec.getResponseContentType()));
                            return;
                        } else {
                            mismatchMessage.set("Idempotency-Key already used with a different request body");
                            return;
                        }
                    }
                    ContentCachingResponseWrapper cachedResponse =
                        new ContentCachingResponseWrapper(response);
                    try {
                        chain.doFilter(replayableRequest, cachedResponse);
                    } catch (IOException | RuntimeException | jakarta.servlet.ServletException e) {
                        throw new FilterChainException(e);
                    }
                    int responseStatus = cachedResponse.getStatus();
                    byte[] responseBody = cachedResponse.getContentAsByteArray();
                    String contentType = cachedResponse.getContentType();
                    String bodyStr = new String(responseBody, StandardCharsets.UTF_8);
                    if (responseStatus == 401 || responseStatus == 403 || responseStatus == 429) {
                        buffered.set(new Buffered(responseStatus, bodyStr, contentType));
                        return;
                    }
                    try {
                        IdempotencyRecord record = new IdempotencyRecord();
                        record.setIdemKey(key);
                        record.setEndpoint(endpoint);
                        record.setActorId(actorId);
                        record.setCredentialHash(credentialHashLegacy);
                        record.setRequestHash(hash);
                        record.setResponseStatus(responseStatus);
                        record.setResponseBody(bodyStr);
                        record.setResponseContentType(contentType);
                        record.setCreatedAt(Instant.now());
                        repository.save(record);
                    } catch (DataIntegrityViolationException dup) {
                        status.setRollbackOnly();
                        raceLost.set(hash);
                        return;
                    }
                    buffered.set(new Buffered(responseStatus, bodyStr, contentType));
                }
            });
        } catch (FilterChainException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof jakarta.servlet.ServletException se) throw se;
            if (cause instanceof RuntimeException re) throw re;
            throw new jakarta.servlet.ServletException(cause);
        }
        if (mismatchMessage.get() != null) {
            writeError(response, HttpStatus.UNPROCESSABLE_ENTITY.value(),
                "IDEMPOTENCY_KEY_REUSED", mismatchMessage.get());
            return;
        }
        if (raceLost.get() != null) {
            try {
                replay.set(readFresh(key, endpoint, actorId, hash));
            } catch (ErrorSpec e) {
                writeError(response, e.status(), e.code(), e.getMessage());
                return;
            }
        }
        if (replay.get() != null) {
            writeReplay(response, replay.get());
            return;
        }
        if (buffered.get() != null) {
            writeBuffered(response, buffered.get());
        }
    }

    private Replay readFresh(String key, String endpoint, String actorId, String hash) {
        var existing = repository.findByIdemKeyAndEndpointAndActorId(key, endpoint, actorId);
        if (existing.isPresent()) {
            IdempotencyRecord rec = existing.get();
            if (isEqualHex(rec.getRequestHash(), hash)) {
                return new Replay(rec.getResponseStatus(), rec.getResponseBody(), rec.getResponseContentType());
            } else {
                throw new ErrorSpec(HttpStatus.UNPROCESSABLE_ENTITY.value(),
                    "IDEMPOTENCY_KEY_REUSED", "Idempotency-Key already used with a different request body");
            }
        }
        throw new ErrorSpec(HttpStatus.CONFLICT.value(),
            "IDEMPOTENCY_KEY_CONFLICT", "Idempotency-Key conflict, retry with a new key");
    }

    private void writeError(HttpServletResponse response, int status,
                            String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }

    private static final class ErrorSpec extends RuntimeException {
        private final int status;
        private final String code;
        ErrorSpec(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
        int status() { return status; }
        String code() { return code; }
    }

    private void writeReplay(HttpServletResponse response, Replay replay) throws IOException {
        response.setStatus(replay.status());
        if (replay.contentType() != null) response.setContentType(replay.contentType());
        byte[] body = replay.body() != null ? replay.body().getBytes(StandardCharsets.UTF_8) : new byte[0];
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    private void writeBuffered(HttpServletResponse response, Buffered b) throws IOException {
        response.setStatus(b.status);
        if (b.contentType != null) response.setContentType(b.contentType);
        byte[] body = b.body != null ? b.body.getBytes(StandardCharsets.UTF_8) : new byte[0];
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    private static void copyBodyOrThrow(ContentCachingResponseWrapper cached,
                                        HttpServletResponse response, boolean throwOnError) {
        try {
            cached.copyBodyToResponse();
        } catch (IOException e) {
            throw new FilterChainException(e);
        }
    }

    private static void copyBodyOrThrow(ContentCachingResponseWrapper cached,
                                        HttpServletResponse response) {
        copyBodyOrThrow(cached, response, true);
    }

    private record Replay(int status, String body, String contentType) {}
    private record Buffered(int status, String body, String contentType) {}

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
                @Override public int read() { return in.read(); }
                @Override public int read(byte[] b, int off, int len) { return in.read(b, off, len); }
                @Override public boolean isFinished() { return in.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(jakarta.servlet.ReadListener l) { throw new UnsupportedOperationException("async not supported"); }
            };
        }
        @Override
        public java.io.BufferedReader getReader() throws IOException {
            String enc = getCharacterEncoding();
            java.nio.charset.Charset charset;
            try { charset = enc != null ? java.nio.charset.Charset.forName(enc) : StandardCharsets.UTF_8; }
            catch (java.nio.charset.UnsupportedCharsetException e) { throw new java.io.UnsupportedEncodingException(enc); }
            return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(), charset));
        }
        @Override public int getContentLength() { return body.length; }
        @Override public long getContentLengthLong() { return body.length; }
    }

    static byte[] readBounded(java.io.InputStream in, int cap) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf, 0, Math.min(buf.length, cap + 1 - total))) > 0) {
            out.write(buf, 0, n);
            total += n;
            if (total > cap) return null;
        }
        return out.toByteArray();
    }

    private static boolean isEqualHex(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static final class FilterChainException extends RuntimeException {
        FilterChainException(Throwable cause) { super(cause); }
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
