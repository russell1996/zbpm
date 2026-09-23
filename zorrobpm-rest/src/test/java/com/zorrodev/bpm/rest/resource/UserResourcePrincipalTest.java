package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.security.Principal;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-SEC-64 (S-RBAC-2): {@code UserResource} читает {@code principal}, а не
 * {@code authClaims}.
 *
 * <p>WO-SEC-69: приватный метод переименован {@code principalFromRequest} →
 * {@code getPrincipal} (каноническое имя, как у соседних ресурсов:
 * {@code ProcessDefinitionResource.getPrincipal}, {@code MyProfileResource.getPrincipal}).
 * Тело — тот же one-liner {@code request.getAttribute("principal")}, поведение
 * не менялось. Старый код (до SEC-64) возвращал null для service-принципала
 * (claims-атрибут пуст для API-ключа) — путь ломался там, где должен работать
 * через API-ключ. Unit-уровень: HTTP-гварды (/users SUPER_ADMIN-only) здесь
 * ни при чём, проверяется именно резолвинг принципала.
 */
@ExtendWith(MockitoExtension.class)
class UserResourcePrincipalTest {

    @Mock HttpServletRequest request;
    @Mock com.zorrodev.bpm.engine.service.UiUserService userService;
    @Mock com.zorrodev.bpm.engine.service.UserInvitationService invitationService;
    @Mock com.zorrodev.bpm.engine.service.AuditLogService auditLogService;
    @InjectMocks UserResource resource;

    private Principal invoke() throws Exception {
        Method m = UserResource.class.getDeclaredMethod("getPrincipal");
        m.setAccessible(true);
        return (Principal) m.invoke(resource);
    }

    @Test
    void servicePrincipal_resolved() throws Exception {
        Principal.ServicePrincipal sp =
                new Principal.ServicePrincipal(UUID.randomUUID(), UUID.randomUUID(), Map.of());
        when(request.getAttribute("principal")).thenReturn(sp);
        // authClaims пуст — как для API-ключа в JwtAuthFilter; новый код
        // его вообще не читает (старый — читал и возвращал null).
        assertThat(invoke()).isSameAs(sp);
    }

    @Test
    void userPrincipal_resolved() throws Exception {
        Principal.UserPrincipal up =
                new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN");
        when(request.getAttribute("principal")).thenReturn(up);
        assertThat(invoke()).isSameAs(up);
    }

    @Test
    void getPrincipal_noAttribute_returnsNull() throws Exception {
        when(request.getAttribute("principal")).thenReturn(null);
        assertThat(invoke()).isNull();
    }

    @Test
    void createUser_invitePassesServicePrincipal() {
        // Сквозной якорь: createUser(INVITE) передаёт getPrincipal в
        // invitationService.createInvitation + auditLogService.record. Со старым
        // кодом туда уходил null для service-ключа; с фиксом — ServicePrincipal.
        Principal.ServicePrincipal sp =
                new Principal.ServicePrincipal(UUID.randomUUID(), UUID.randomUUID(), Map.of());
        when(request.getAttribute("principal")).thenReturn(sp);
        org.mockito.Mockito.lenient().when(request.getAttribute("authClaims")).thenReturn(null);

        com.zorrodev.bpm.contract.dto.CreateUiUserDTO dto =
                new com.zorrodev.bpm.contract.dto.CreateUiUserDTO();
        dto.setUsername("sec64-u");
        dto.setCreationMode("INVITE");
        UUID id = UUID.randomUUID();
        org.mockito.Mockito.when(userService.create(org.mockito.ArgumentMatchers.any())).thenReturn(id);

        resource.createUser(dto);

        // same() — identity-матчинг: именно ЭТОТ ServicePrincipal, не равный по значению
        verify(invitationService).createInvitation(eq(id),
                org.mockito.ArgumentMatchers.argThat(p -> p == sp));
    }

    /**
     * WO-SEC-69 criterion 1+4: единственный reader принципала в этом ресурсе —
     * канонический {@code getPrincipal} ({@code request.getAttribute}), старого
     * {@code principalFromRequest} нет ни как метода, ни как call-site'а
     * (проверено grep'ом по main-дереву). POF-мутация: вернуть старый вызов
     * под новым именем — этот тест краснеет (метод снова существует).
     */
    @Test
    void noPrivatePrincipalFromRequest() {
        assertThat(java.util.Arrays.stream(UserResource.class.getDeclaredMethods())
            .noneMatch(m -> m.getName().equals("principalFromRequest"))).isTrue();
        assertThat(java.util.Arrays.stream(UserResource.class.getDeclaredMethods())
            .filter(m -> m.getName().equals("getPrincipal")).count()).isEqualTo(1);
    }
}
