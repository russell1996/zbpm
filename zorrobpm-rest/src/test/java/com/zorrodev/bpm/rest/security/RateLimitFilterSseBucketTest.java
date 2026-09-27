package com.zorrodev.bpm.rest.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * WO-REL-57 (критерий 3): {@code GET /events/stream} больше не делит capacity
 * с обычными data-эндпоинтами — у долгоживущего стрима свой бакет подключений.
 *
 * <p>Живой прод-репорт 2026-09-27: серия обрывов+reconnect'ов (или просто
 * активный UI-шум с одного IP) съедала общий data-бюджет 300/60 и блокировала
 * сам realtime-канал 429'ми.
 *
 * <p>POF-мутация: убрать SSE-ветку из {@code RateLimitFilter} (стрим снова в
 * общем data-бакете) — {@code exhaustedDataBucket_stillOpensStream} КРАСНЫЙ.
 */
class RateLimitFilterSseBucketTest {

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter();
        filter.setPgRateLimiter(TestRateLimitBuckets.create());
        filter.setCapacity(5);
        filter.setWindowSeconds(3600);
        filter.setAccountCapacity(5);
        filter.setRefreshCapacity(30);
        filter.setRefreshWindowSeconds(60);
        filter.setDataCapacity(10);
        filter.setDataWindowSeconds(3600);
        filter.setSseCapacity(5);
        filter.setSseWindowSeconds(3600);
        filter.setRateLimitEnabled(true);
        filter.setTrustedProxies(Set.of());
        filter.reset();
    }

    private int doFilter(String method, String ip, String path) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        req.setRemoteAddr(ip);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilterInternal(req, resp, chain);
        return resp.getStatus();
    }

    /**
     * Критерий 3 дословно: исчерпать data-бакет обычными REST-вызовами —
     * SSE-подключение с того же IP всё равно проходит.
     */
    @Test
    void exhaustedDataBucket_stillOpensStream() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertThat(doFilter("GET", "10.0.0.1", "/process-instances")).isEqualTo(200);
        }
        assertThat(doFilter("GET", "10.0.0.1", "/process-instances"))
            .as("data bucket must be exhausted by now")
            .isEqualTo(429);

        assertThat(doFilter("GET", "10.0.0.1", "/events/stream"))
            .as("SSE stream must not share capacity with data endpoints")
            .isEqualTo(200);
    }

    /**
     * Зеркало: шторм SSE-подключений не съедает data-бюджет (изоляция в обе
     * стороны — иначе reconnect-шторм душил бы обычный REST).
     */
    @Test
    void exhaustedSseBucket_dataStillPasses() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(doFilter("GET", "10.0.0.1", "/events/stream")).isEqualTo(200);
        }
        assertThat(doFilter("GET", "10.0.0.1", "/events/stream"))
            .as("6th SSE connect must hit the sse bucket")
            .isEqualTo(429);

        assertThat(doFilter("GET", "10.0.0.1", "/process-instances"))
            .as("data budget must be untouched by the SSE storm")
            .isEqualTo(200);
    }

    /**
     * SSE-бакет жив: за пределом capacity — честный 429 (стрим не вынут из
     * лимита целиком: reconnect-шторм режется, per-subject cap — не его дело).
     */
    @Test
    void sseBucket_overflow_returns429() throws Exception {
        for (int i = 0; i < 5; i++) {
            doFilter("GET", "10.0.0.1", "/events/stream");
        }
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/events/stream");
        req.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilterInternal(req, resp, mock(FilterChain.class));
        assertThat(resp.getStatus()).isEqualTo(429);
        assertThat(resp.getContentAsString()).contains("RATE_LIMITED");
        assertThat(resp.getHeader("Retry-After")).isNotNull();
    }

    /**
     * {@code GET /events} (REST-лист) остаётся в data-бакете — делится только
     * стрим, лист по-прежнему тратит общий бюджет.
     */
    @Test
    void eventsList_staysInDataBucket() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertThat(doFilter("GET", "10.0.0.1", "/events")).isEqualTo(200);
        }
        assertThat(doFilter("GET", "10.0.0.1", "/events"))
            .as("REST event list must stay in the shared data bucket")
            .isEqualTo(429);
    }
}
