package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.security.Principal;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WO-DEBT-4b — unit tests for the shared authz foundation. Real
 * {@link FormAccessSupport} bean logic, mocked {@code HttpServletRequest} /
 * {@code EventAuthzResolver} boundary only.
 */
class FormAccessSupportTest {

    private HttpServletRequest request;
    private EventAuthzResolver eventAuthzResolver;
    private FormAccessSupport support;
    private final Principal principal =
        new Principal.UserPrincipal(UUID.randomUUID(), "user", "USER");

    @BeforeEach
    void setup() {
        request = mock(HttpServletRequest.class);
        eventAuthzResolver = mock(EventAuthzResolver.class);
        support = new FormAccessSupport(request, eventAuthzResolver);
    }

    // ==================== getPrincipal ====================

    @Test
    void getPrincipal_withPrincipal_returnsIt() {
        when(request.getAttribute("principal")).thenReturn(principal);

        assertSame(principal, support.getPrincipal());
    }

    @Test
    void getPrincipal_withoutPrincipal_returnsNull() {
        when(request.getAttribute("principal")).thenReturn(null);

        assertNull(support.getPrincipal());
    }

    // ==================== resolveAllowedPdIds ====================

    @Test
    void resolveAllowedPdIds_withPrincipal_delegatesToResolver() {
        Set<UUID> allowed = Set.of(UUID.randomUUID());
        when(request.getAttribute("principal")).thenReturn(principal);
        when(eventAuthzResolver.visibleDefinitionIds(eq(principal), any())).thenReturn(allowed);

        assertEquals(allowed, support.resolveAllowedPdIds());
    }

    @Test
    void resolveAllowedPdIds_withPrincipal_seeAllNullPassesThrough() {
        when(request.getAttribute("principal")).thenReturn(principal);
        when(eventAuthzResolver.visibleDefinitionIds(eq(principal), any())).thenReturn(null);

        assertNull(support.resolveAllowedPdIds());
    }

    @Test
    void resolveAllowedPdIds_withoutPrincipal_emptySet() {
        when(request.getAttribute("principal")).thenReturn(null);

        Collection<UUID> result = support.resolveAllowedPdIds();

        assertThat(result).isNotNull().isEmpty();
    }

    // ==================== resolveRuntimePdIds ====================

    @Test
    void resolveRuntimePdIds_withPrincipal_delegatesToResolver() {
        Set<UUID> allowed = Set.of(UUID.randomUUID());
        when(request.getAttribute("principal")).thenReturn(principal);
        when(eventAuthzResolver.readableRuntimePdIds(eq(principal), any())).thenReturn(allowed);

        assertEquals(allowed, support.resolveRuntimePdIds());
    }

    @Test
    void resolveRuntimePdIds_withPrincipal_seeAllNullPassesThrough() {
        when(request.getAttribute("principal")).thenReturn(principal);
        when(eventAuthzResolver.readableRuntimePdIds(eq(principal), any())).thenReturn(null);

        assertNull(support.resolveRuntimePdIds());
    }

    @Test
    void resolveRuntimePdIds_withoutPrincipal_emptySet() {
        when(request.getAttribute("principal")).thenReturn(null);

        Collection<UUID> result = support.resolveRuntimePdIds();

        assertThat(result).isNotNull().isEmpty();
    }

    // ==================== requirePdAccess ====================

    @Test
    void requirePdAccess_allowed_noThrow() {
        UUID pdId = UUID.randomUUID();
        when(request.getAttribute("principal")).thenReturn(principal);
        when(eventAuthzResolver.visibleDefinitionIds(eq(principal), any())).thenReturn(Set.of(pdId));

        assertDoesNotThrow(() -> support.requirePdAccess(pdId));
    }

    @Test
    void requirePdAccess_denied_404() {
        when(request.getAttribute("principal")).thenReturn(principal);
        when(eventAuthzResolver.visibleDefinitionIds(eq(principal), any()))
            .thenReturn(Set.of(UUID.randomUUID()));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> support.requirePdAccess(UUID.randomUUID()));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    @Test
    void requirePdAccess_seeAllNull_noThrow() {
        when(request.getAttribute("principal")).thenReturn(principal);
        when(eventAuthzResolver.visibleDefinitionIds(eq(principal), any())).thenReturn(null);

        assertDoesNotThrow(() -> support.requirePdAccess(UUID.randomUUID()));
    }

    // ==================== requireRuntimePdAccess ====================

    @Test
    void requireRuntimePdAccess_allowed_noThrow() {
        UUID pdId = UUID.randomUUID();
        when(request.getAttribute("principal")).thenReturn(principal);
        when(eventAuthzResolver.readableRuntimePdIds(eq(principal), any())).thenReturn(Set.of(pdId));

        assertDoesNotThrow(() -> support.requireRuntimePdAccess(pdId));
    }

    @Test
    void requireRuntimePdAccess_denied_404() {
        when(request.getAttribute("principal")).thenReturn(principal);
        when(eventAuthzResolver.readableRuntimePdIds(eq(principal), any()))
            .thenReturn(Set.of(UUID.randomUUID()));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> support.requireRuntimePdAccess(UUID.randomUUID()));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    @Test
    void requireRuntimePdAccess_seeAllNull_noThrow() {
        when(request.getAttribute("principal")).thenReturn(principal);
        when(eventAuthzResolver.readableRuntimePdIds(eq(principal), any())).thenReturn(null);

        assertDoesNotThrow(() -> support.requireRuntimePdAccess(UUID.randomUUID()));
    }
}
