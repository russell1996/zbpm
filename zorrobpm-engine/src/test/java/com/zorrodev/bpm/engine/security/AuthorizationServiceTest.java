package com.zorrodev.bpm.engine.security;

import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberId;
import com.zorrodev.bpm.engine.repository.ProcessMemberRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthorizationServiceTest {

    private ProcessRepository processRepository;
    private ProcessMemberRepository processMemberRepository;
    private AuthorizationService auth;

    @BeforeEach
    void setUp() {
        processRepository = mock(ProcessRepository.class);
        processMemberRepository = mock(ProcessMemberRepository.class);
        auth = new AuthorizationService(processRepository, processMemberRepository);
    }

    // --- SuperAdmin bypass ---

    @Test
    void superAdmin_canOperateAnything() {
        Principal sa = new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN");
        assertThat(auth.canOperate(sa, "any-process", AuthorizationService.Action.DEPLOY)).isTrue();
        assertThat(auth.canOperate(sa, "any-process", AuthorizationService.Action.MANAGE_MEMBERS)).isTrue();
        assertThat(auth.canOperate(sa, "any-process", AuthorizationService.Action.DELETE_PROCESS)).isTrue();
    }

    @Test
    void superAdmin_canCompleteUserTask() {
        Principal sa = new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN");
        assertThat(auth.canCompleteUserTask(sa, UUID.randomUUID())).isTrue();
    }

    // --- ADR-2: Owner can only do runtime actions, not management ---

    @Test
    void owner_canOperateRuntime() {
        UUID userId = UUID.randomUUID();
        UUID processId = UUID.randomUUID();
        ProcessEntity process = new ProcessEntity();
        process.setId(processId);
        process.setDefinitionKey("test-proc");

        when(processRepository.findByDefinitionKey("test-proc")).thenReturn(Optional.of(process));
        when(processMemberRepository.findById(new ProcessMemberId(processId, userId)))
            .thenReturn(Optional.of(createMember(processId, userId, "OWNER")));

        Principal owner = new Principal.UserPrincipal(userId, "owner", "USER");
        assertThat(auth.canOperate(owner, "test-proc", AuthorizationService.Action.START)).isTrue();
        assertThat(auth.canOperate(owner, "test-proc", AuthorizationService.Action.COMPLETE_SERVICE_TASK)).isTrue();
    }

    @Test
    void owner_cannotDeploy_ADR2_superAdminOnly() {
        UUID userId = UUID.randomUUID();
        UUID processId = UUID.randomUUID();
        ProcessEntity process = new ProcessEntity();
        process.setId(processId);
        process.setDefinitionKey("test-proc");

        when(processRepository.findByDefinitionKey("test-proc")).thenReturn(Optional.of(process));
        when(processMemberRepository.findById(new ProcessMemberId(processId, userId)))
            .thenReturn(Optional.of(createMember(processId, userId, "OWNER")));

        Principal owner = new Principal.UserPrincipal(userId, "owner", "USER");
        assertThat(auth.canOperate(owner, "test-proc", AuthorizationService.Action.DEPLOY)).isFalse();
    }

    @Test
    void owner_cannotManageMembers_ADR2_superAdminOnly() {
        UUID userId = UUID.randomUUID();
        UUID processId = UUID.randomUUID();
        ProcessEntity process = new ProcessEntity();
        process.setId(processId);
        process.setDefinitionKey("test-proc");

        when(processRepository.findByDefinitionKey("test-proc")).thenReturn(Optional.of(process));
        when(processMemberRepository.findById(new ProcessMemberId(processId, userId)))
            .thenReturn(Optional.of(createMember(processId, userId, "OWNER")));

        Principal owner = new Principal.UserPrincipal(userId, "owner", "USER");
        assertThat(auth.canOperate(owner, "test-proc", AuthorizationService.Action.MANAGE_MEMBERS)).isFalse();
    }

    // --- ADR-2 SA with grants (scoped keys) ---

    @Test
    void sa_withGrant_canCompleteUserTask() {
        UUID procId = UUID.randomUUID();
        Principal.Grant grant = new Principal.Grant(Set.of("COMPLETE_USER_TASK", "START"), false);
        Principal.ServicePrincipal sa = new Principal.ServicePrincipal(UUID.randomUUID(), UUID.randomUUID(),
            Map.of(procId, grant));
        assertThat(auth.canCompleteUserTask(sa, procId)).isTrue();
    }

    @Test
    void sa_withoutCompleteUserTask_cannotCompleteUserTask() {
        UUID procId = UUID.randomUUID();
        Principal.Grant grant = new Principal.Grant(Set.of("START"), false);
        Principal.ServicePrincipal sa = new Principal.ServicePrincipal(UUID.randomUUID(), UUID.randomUUID(),
            Map.of(procId, grant));
        assertThat(auth.canCompleteUserTask(sa, procId)).isFalse();
    }

    @Test
    void sa_fullGrant_canCompleteUserTask() {
        UUID procId = UUID.randomUUID();
        Principal.Grant grant = new Principal.Grant(Set.of(), true);
        Principal.ServicePrincipal sa = new Principal.ServicePrincipal(UUID.randomUUID(), UUID.randomUUID(),
            Map.of(procId, grant));
        assertThat(auth.canCompleteUserTask(sa, procId)).isTrue();
    }

    @Test
    void sa_wrongProcess_cannotCompleteUserTask() {
        UUID procId = UUID.randomUUID();
        Principal.Grant grant = new Principal.Grant(Set.of("COMPLETE_USER_TASK"), false);
        Principal.ServicePrincipal sa = new Principal.ServicePrincipal(UUID.randomUUID(), UUID.randomUUID(),
            Map.of(UUID.randomUUID(), grant)); // different process ID
        assertThat(auth.canCompleteUserTask(sa, procId)).isFalse();
    }

    @Test
    void sa_noGrant_cannotOperateRuntime() {
        UUID procId = UUID.randomUUID();
        Principal.ServicePrincipal sa = new Principal.ServicePrincipal(UUID.randomUUID(), UUID.randomUUID(),
            Map.of()); // empty grants
        assertThat(auth.canOperate(sa, "test-proc", AuthorizationService.Action.START)).isFalse();
    }

    @Test
    void sa_withGrant_canOperateRuntime() {
        UUID procId = UUID.randomUUID();
        ProcessEntity process = createProcess("test-proc");
        process.setId(procId);
        when(processRepository.findByDefinitionKey("test-proc")).thenReturn(Optional.of(process));

        Principal.Grant grant = new Principal.Grant(Set.of("START"), false);
        Principal.ServicePrincipal sa = new Principal.ServicePrincipal(UUID.randomUUID(), UUID.randomUUID(),
            Map.of(procId, grant));
        assertThat(auth.canOperate(sa, "test-proc", AuthorizationService.Action.START)).isTrue();
    }

    @Test
    void sa_fullGrant_canOperateAnyRuntime() {
        UUID procId = UUID.randomUUID();
        ProcessEntity process = createProcess("test-proc");
        process.setId(procId);
        when(processRepository.findByDefinitionKey("test-proc")).thenReturn(Optional.of(process));

        Principal.Grant grant = new Principal.Grant(Set.of(), true);
        Principal.ServicePrincipal sa = new Principal.ServicePrincipal(UUID.randomUUID(), UUID.randomUUID(),
            Map.of(procId, grant));
        assertThat(auth.canOperate(sa, "test-proc", AuthorizationService.Action.START)).isTrue();
        assertThat(auth.canOperate(sa, "test-proc", AuthorizationService.Action.COMPLETE_SERVICE_TASK)).isTrue();
    }

    @Test
    void sa_cannotGrantWrongProcess() {
        UUID procId = UUID.randomUUID();
        ProcessEntity process = createProcess("test-proc");
        process.setId(UUID.randomUUID()); // different ID
        when(processRepository.findByDefinitionKey("test-proc")).thenReturn(Optional.of(process));

        Principal.Grant grant = new Principal.Grant(Set.of("START"), false);
        Principal.ServicePrincipal sa = new Principal.ServicePrincipal(UUID.randomUUID(), UUID.randomUUID(),
            Map.of(procId, grant)); // grant for wrong process
        assertThat(auth.canOperate(sa, "test-proc", AuthorizationService.Action.START)).isFalse();
    }

    // --- SA management actions: should be false ---

    @Test
    void sa_cannotManageKeys() {
        Principal.Grant grant = new Principal.Grant(Set.of("START", "COMPLETE_SERVICE_TASK"), false);
        Principal.ServicePrincipal sa = new Principal.ServicePrincipal(UUID.randomUUID(), UUID.randomUUID(),
            Map.of(UUID.randomUUID(), grant));
        assertThat(auth.canOperate(sa, "any-process", AuthorizationService.Action.MANAGE_KEYS)).isFalse();
    }

    @Test
    void sa_cannotManageMembers() {
        Principal.Grant grant = new Principal.Grant(Set.of("START"), false);
        Principal.ServicePrincipal sa = new Principal.ServicePrincipal(UUID.randomUUID(), UUID.randomUUID(),
            Map.of(UUID.randomUUID(), grant));
        assertThat(auth.canOperate(sa, "any-process", AuthorizationService.Action.MANAGE_MEMBERS)).isFalse();
    }

    @Test
    void sa_cannotDeploy() {
        Principal.Grant grant = new Principal.Grant(Set.of("START"), false);
        Principal.ServicePrincipal sa = new Principal.ServicePrincipal(UUID.randomUUID(), UUID.randomUUID(),
            Map.of(UUID.randomUUID(), grant));
        assertThat(auth.canOperate(sa, "any-process", AuthorizationService.Action.DEPLOY)).isFalse();
    }

    @Test
    void sa_cannotDeleteProcess() {
        Principal.Grant grant = new Principal.Grant(Set.of("START"), false);
        Principal.ServicePrincipal sa = new Principal.ServicePrincipal(UUID.randomUUID(), UUID.randomUUID(),
            Map.of(UUID.randomUUID(), grant));
        assertThat(auth.canOperate(sa, "any-process", AuthorizationService.Action.DELETE_PROCESS)).isFalse();
    }

    // --- SA scope guard: wrong process → false ---

    @Test
    void sa_wrongProcess_cannotOperateRuntime() {
        UUID saProcessId = UUID.randomUUID();
        Principal.Grant grant = new Principal.Grant(Set.of("START"), false);
        Principal.ServicePrincipal sa = new Principal.ServicePrincipal(UUID.randomUUID(), saProcessId,
            Map.of(saProcessId, grant)); // grant for different process

        // Different process key → different process ID
        ProcessEntity otherProcess = createProcess("other-process");
        when(processRepository.findByDefinitionKey("other-process")).thenReturn(Optional.of(otherProcess));

        assertThat(auth.canOperate(sa, "other-process", AuthorizationService.Action.START)).isFalse();
    }

    // --- Designer ---

    @Test
    void designer_canDeployButNotManageMembers() {
        UUID userId = UUID.randomUUID();
        UUID processId = UUID.randomUUID();
        ProcessEntity process = new ProcessEntity();
        process.setId(processId);
        process.setDefinitionKey("test-proc");

        when(processRepository.findByDefinitionKey("test-proc")).thenReturn(Optional.of(process));
        when(processMemberRepository.findById(new ProcessMemberId(processId, userId)))
            .thenReturn(Optional.of(createMember(processId, userId, "DESIGNER")));

        Principal designer = new Principal.UserPrincipal(userId, "designer", "USER");
        assertThat(auth.canOperate(designer, "test-proc", AuthorizationService.Action.DEPLOY)).isFalse(); // DEPLOY = OWNER only
        assertThat(auth.canOperate(designer, "test-proc", AuthorizationService.Action.MANAGE_MEMBERS)).isFalse();
    }

    // --- No membership ---

    @Test
    void noMember_cannotOperate() {
        UUID userId = UUID.randomUUID();
        when(processRepository.findByDefinitionKey("test-proc")).thenReturn(
            Optional.of(createProcess("test-proc")));
        when(processMemberRepository.findById(any())).thenReturn(Optional.empty());

        Principal user = new Principal.UserPrincipal(userId, "user", "USER");
        assertThat(auth.canOperate(user, "test-proc", AuthorizationService.Action.DEPLOY)).isFalse();
    }

    // --- Non-existent process ---

    @Test
    void nonExistentProcess_cannotOperate() {
        when(processRepository.findByDefinitionKey("unknown")).thenReturn(Optional.empty());
        Principal user = new Principal.UserPrincipal(UUID.randomUUID(), "user", "USER");
        assertThat(auth.canOperate(user, "unknown", AuthorizationService.Action.DEPLOY)).isFalse();
    }

    // --- USER role (not ADMIN) ---

    @Test
    void userRole_canCompleteUserTask() {
        Principal user = new Principal.UserPrincipal(UUID.randomUUID(), "user", "USER");
        assertThat(auth.canCompleteUserTask(user, UUID.randomUUID())).isTrue();
    }

    private ProcessEntity createProcess(String key) {
        ProcessEntity p = new ProcessEntity();
        p.setId(UUID.randomUUID());
        p.setDefinitionKey(key);
        p.setCreatedAt(Instant.now());
        return p;
    }

    private ProcessMemberEntity createMember(UUID processId, UUID userId, String role) {
        ProcessMemberEntity m = new ProcessMemberEntity();
        m.setProcessId(processId);
        m.setUserId(userId);
        m.setRole(role);
        m.setAddedAt(Instant.now());
        return m;
    }
}
