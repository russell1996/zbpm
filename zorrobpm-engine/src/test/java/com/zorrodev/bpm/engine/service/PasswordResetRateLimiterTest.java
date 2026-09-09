package com.zorrodev.bpm.engine.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** WO-ACL-18 criterion 13: per-email + per-IP throttle on reset REQUESTS.
 * WO-SCALE-2: backed by {@code PgRateLimiter} instead of Caffeine caches. */
@ExtendWith(MockitoExtension.class)
class PasswordResetRateLimiterTest {

    @Mock PgRateLimiter pgRateLimiter;

    private PasswordResetRateLimiter limiter(int emailCap, int emailWin, int ipCap, int ipWin) {
        PasswordResetRateLimiter r = new PasswordResetRateLimiter(pgRateLimiter);
        r.setEmailCapacity(emailCap);
        r.setEmailWindowSeconds(emailWin);
        r.setIpCapacity(ipCap);
        r.setIpWindowSeconds(ipWin);
        return r;
    }


    void allowsUpToCapacityThenRejects_perEmail() {
        PasswordResetRateLimiter limiter = limiter(2, 3600, 20, 3600);
        when(pgRateLimiter.tryConsume(eq("a@b.c"), eq(2), eq(3600)))
            .thenReturn(0L).thenReturn(0L).thenReturn(3600L);
        when(pgRateLimiter.tryConsume(eq("d@e.f"), eq(2), eq(3600)))
            .thenReturn(0L);

        assertThat(limiter.tryAcquireForEmail("a@b.c")).isTrue();
        assertThat(limiter.tryAcquireForEmail("a@b.c")).isTrue();
        assertThat(limiter.tryAcquireForEmail("a@b.c")).isFalse();
        assertThat(limiter.tryAcquireForEmail("d@e.f")).isTrue();
    }

    @Test
    void allowsUpToCapacityThenRejects_perIp() {
        PasswordResetRateLimiter limiter = limiter(2, 3600, 3, 3600);
        when(pgRateLimiter.tryConsume(eq("1.1.1.1"), eq(3), eq(3600)))
                .thenReturn(0L).thenReturn(0L).thenReturn(0L).thenReturn(3600L);

        assertThat(limiter.tryAcquireForIp("1.1.1.1")).isTrue();
        assertThat(limiter.tryAcquireForIp("1.1.1.1")).isTrue();
        assertThat(limiter.tryAcquireForIp("1.1.1.1")).isTrue();
        assertThat(limiter.tryAcquireForIp("1.1.1.1")).isFalse();
    }

    @Test
    void nullIdentityAlwaysAllowed() {
        PasswordResetRateLimiter limiter = limiter(2, 3600, 20, 3600);
        assertThat(limiter.tryAcquireForEmail(null)).isTrue();
        assertThat(limiter.tryAcquireForIp(null)).isTrue();
        verifyNoInteractions(pgRateLimiter);
    }

    @Test
    void resetDelegatesToPgRateLimiter() {
        PasswordResetRateLimiter limiter = limiter(2, 3600, 20, 3600);
        limiter.reset();
        verify(pgRateLimiter).reset();
    }

    @Test
    void tryAcquireForEmailLowercasesKey() {
        PasswordResetRateLimiter limiter = limiter(1, 3600, 20, 3600);
        when(pgRateLimiter.tryConsume(eq("a@b.c"), eq(1), eq(3600))).thenReturn(0L);
        limiter.tryAcquireForEmail("A@B.C");
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(pgRateLimiter).tryConsume(captor.capture(), eq(1), eq(3600));
        assertThat(captor.getValue()).isEqualTo("a@b.c");
    }
}
