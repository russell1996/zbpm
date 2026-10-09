package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.security.Principal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-REL-70 (критерий 2, P-46): контроллер ОБЯЗАН звать
 * {@code sendImmediateHello} после регистрации — иначе delete одной строки
 * оставляет все остальные тесты зелёными, а первый байт снова едет только
 * с 15с heartbeat (тихий откат за буферизующим прокси).
 *
 * <p>POF-мутация: убрать {@code sendImmediateHello} из
 * {@code SseEventStreamController.stream} — {@code stream_wiresImmediateHello}
 * КРАСНЫЙ ({@code Wanted but not invoked}).
 */
@ExtendWith(MockitoExtension.class)
class SseControllerHelloWiringTest {

    @Mock
    private SseEventStreamService sseEventStreamService;
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;

    @InjectMocks
    private SseEventStreamController controller;

    @Test
    void stream_wiresImmediateHello() {
        Principal principal = new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN");
        when(request.getAttribute("principal")).thenReturn(principal);
        when(sseEventStreamService.registerBufferedClient(any(), eq(principal),
            any(), any(), any())).thenReturn("cid-1");

        controller.stream(null, null, null, null, request, response);

        verify(sseEventStreamService).sendImmediateHello("cid-1");
    }

    @Test
    void stream_setsHeadersOnSuccessResponse() {
        Principal principal = new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN");
        when(request.getAttribute("principal")).thenReturn(principal);
        when(sseEventStreamService.registerBufferedClient(any(), eq(principal),
            any(), any(), any())).thenReturn("cid-2");

        controller.stream(null, null, null, null, request, response);

        verify(response).setHeader(
            SseEventStreamController.HDR_X_ACCEL_BUFFERING, "no");
        verify(response).setHeader(
            SseEventStreamController.HDR_CACHE_CONTROL,
            SseEventStreamController.CACHE_CONTROL_VALUE);
    }

    @Test
    void stream_mvcEntryPoint_setsHeadersOnCurrentResponse() {
        // MVC-вход (5-параметровый, с аннотациями) берёт response текущего
        // запроса: заголовки ставятся — живой путь за буферизующим прокси.
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
            new org.springframework.web.context.request.ServletRequestAttributes(request, response));
        try {
            Principal principal = new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN");
            when(request.getAttribute("principal")).thenReturn(principal);
            when(sseEventStreamService.registerBufferedClient(any(), eq(principal),
                any(), any(), any())).thenReturn("cid-4");

            controller.stream(null, null, null, null, request);

            verify(response).setHeader(
                SseEventStreamController.HDR_X_ACCEL_BUFFERING, "no");
            verify(response).setHeader(
                SseEventStreamController.HDR_CACHE_CONTROL,
                SseEventStreamController.CACHE_CONTROL_VALUE);
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void stream_legacyOverload_stillWiresHello_withoutHeaders() {
        Principal principal = new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN");
        when(request.getAttribute("principal")).thenReturn(principal);
        when(sseEventStreamService.registerBufferedClient(any(), eq(principal),
            any(), any(), any())).thenReturn("cid-3");

        controller.stream(null, null, null, null, request);

        // Поведение то же (hello уходит), заголовки ставить некуда —
        // контракт char9 держит именно эту сигнатуру.
        verify(sseEventStreamService).sendImmediateHello("cid-3");
    }
}
