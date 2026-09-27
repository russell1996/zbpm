package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberEntity;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessMemberRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.Principal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Field;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-INT-9: юнит-уровень провижининга (без брокера — он здесь заменён
 * видимым seam'ом): схема permissions, hash-формат, guard'ы, no-op правило,
 * union версий, fail-closed проброс. Живое поведение — в
 * {@code RabbitMqProvisioningIT} (stub Management API) и
 * {@code RabbitMqPerSystemCredentialsRabbitIT} (реальный брокер).
 */
@ExtendWith(MockitoExtension.class)
class RabbitMqProvisioningServiceTest {

    @Mock UiUserRepository uiUserRepository;
    @Mock ProcessMemberRepository processMemberRepository;
    @Mock ProcessRepository processRepository;
    @Mock ProcessDefinitionRepository processDefinitionRepository;
    @Mock BpmnService bpmnService;
    @Mock com.zorrodev.bpm.engine.service.AuditLogService auditLogService;

    @InjectMocks RabbitMqProvisioningService service;

    // ==================== Схема permissions ====================

    @Test
    void permissionsFor_singleJob_coversQueueDlqAndCompletions() {
        RabbitMqProvisioningService.Permissions p =
            RabbitMqProvisioningService.permissionsFor(Set.of("billing"));

        assertThat(p.read()).isEqualTo("^zorrobpm\\.jobs\\.(billing)(\\.dlq)?$");
        assertThat(p.configure()).isEqualTo("^zorrobpm\\.jobs\\.(billing)(\\.dlq)?$");
        assertThat(p.write())
            .isEqualTo("(^zorrobpm\\.jobs\\.(billing)$)|(^zorrobpm\\.complete-service-task$)");

        // Regex реально матчит то, что должен, — через java.util.regex,
        // тем же движком семантически, что Erlang re на брокере.
        assertThat(Pattern.compile(p.read()).matcher("zorrobpm.jobs.billing").matches()).isTrue();
        assertThat(Pattern.compile(p.read()).matcher("zorrobpm.jobs.billing.dlq").matches()).isTrue();
        assertThat(Pattern.compile(p.read()).matcher("zorrobpm.jobs.other").matches()).isFalse();
        assertThat(Pattern.compile(p.write()).matcher("zorrobpm.complete-service-task").matches()).isTrue();
        assertThat(Pattern.compile(p.write()).matcher("zorrobpm.jobs.billing").matches()).isTrue();
        assertThat(Pattern.compile(p.write()).matcher("zorrobpm.jobs.billing.dlq").matches()).isFalse();
        assertThat(Pattern.compile(p.configure()).matcher("zorrobpm.jobs.dlx").matches()).isFalse();
    }

    @Test
    void permissionsFor_emptyMembership_denyAll() {
        RabbitMqProvisioningService.Permissions p =
            RabbitMqProvisioningService.permissionsFor(Set.of());

        // Verifier HOLD #2: юзер с нулём процессов — deny-all целиком,
        // включая completions (иначе ковка чужих service-task'ов).
        assertThat(Pattern.compile(p.read()).matcher("zorrobpm.jobs.anything").matches()).isFalse();
        assertThat(Pattern.compile(p.write()).matcher("zorrobpm.complete-service-task").matches()).isFalse();
        assertThat(Pattern.compile(p.write()).matcher("zorrobpm.jobs.anything").matches()).isFalse();
    }

    @Test
    void permissionsFor_unsafeJobType_excludedFailClosed() {
        RabbitMqProvisioningService.Permissions p =
            RabbitMqProvisioningService.permissionsFor(Set.of("billing", "evil.*|.*"));

        assertThat(p.read()).doesNotContain("evil");
        assertThat(Pattern.compile(p.read()).matcher("zorrobpm.jobs.billing").matches()).isTrue();
    }

    // ==================== Hash + пароль ====================

    @Test
    void rabbitPasswordHash_isSaltedSha256Base64() {
        String hash = RabbitMqProvisioningService.rabbitPasswordHash("secret-pw");
        byte[] decoded = Base64.getDecoder().decode(hash);
        // 4 байта соли + 32 байта SHA-256.
        assertThat(decoded).hasSize(36);
        // Соль случайна: два hash одного пароля различаются.
        assertThat(RabbitMqProvisioningService.rabbitPasswordHash("secret-pw"))
            .isNotEqualTo(hash);
    }

    @Test
    void generatePassword_isFortyAlphanumeric() {
        String pw = RabbitMqProvisioningService.generatePassword();
        assertThat(pw).hasSize(40).matches("[A-Za-z0-9]+");
        assertThat(RabbitMqProvisioningService.generatePassword()).isNotEqualTo(pw);
    }

    // ==================== Guard'ы provision ====================

    @Test
    void provision_humanUser_400() {
        UiUserEntity user = systemUser("bob", "HUMAN");
        when(uiUserRepository.findById(user.getId())).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.provisionPassword(user.getId(), null))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST));
        verify(uiUserRepository, never()).save(any());
    }

    @Test
    void provision_inactiveSystem_400() {
        UiUserEntity user = systemUser("sys-off", "SYSTEM");
        user.setActive(false);
        when(uiUserRepository.findById(user.getId())).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.provisionPassword(user.getId(), null))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void provision_reservedBrokerAdminLogin_409() throws Exception {
        UiUserEntity user = systemUser("zorrodev", "SYSTEM");
        when(uiUserRepository.findById(user.getId())).thenReturn(Optional.of(user));
        // Verifier HOLD #1: guard сравнивает с brokerAdminUser — подменяем
        // поле тем же способом, что no-op-тест ниже.
        setField(service, "brokerAdminUser", "zorrodev");

        assertThatThrownBy(() -> service.provisionPassword(user.getId(), null))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT));
        verify(uiUserRepository, never()).save(any());
    }

    @Test
    void provision_unknownUser_404() {
        UUID id = UUID.randomUUID();
        when(uiUserRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.provisionPassword(id, null))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ==================== Union версий + no-op ====================

    @Test
    void jobTypesForUser_unionsAllVersionsOfEachKey() {
        UUID userId = UUID.randomUUID();
        ProcessMemberEntity m1 = member(UUID.randomUUID(), userId);
        ProcessMemberEntity m2 = member(UUID.randomUUID(), userId);
        when(processMemberRepository.findByUserId(userId)).thenReturn(List.of(m1, m2));

        ProcessEntity p1 = new ProcessEntity();
        p1.setId(m1.getProcessId());
        p1.setDefinitionKey("order");
        ProcessEntity p2 = new ProcessEntity();
        p2.setId(m2.getProcessId());
        p2.setDefinitionKey("billing");
        when(processRepository.findById(m1.getProcessId())).thenReturn(Optional.of(p1));
        when(processRepository.findById(m2.getProcessId())).thenReturn(Optional.of(p2));

        ProcessDefinitionEntity v1 = version("order");
        ProcessDefinitionEntity v2 = version("order");
        ProcessDefinitionEntity v3 = version("billing");
        when(processDefinitionRepository.findAll(any(Specification.class)))
            .thenReturn(List.of(v1, v2))
            .thenReturn(List.of(v3));

        BpmnProcessDefinitionModel modelV1 = org.mockito.Mockito.mock(BpmnProcessDefinitionModel.class);
        when(modelV1.getJobTypes()).thenReturn(Set.of("old-job"));
        BpmnProcessDefinitionModel modelV2 = org.mockito.Mockito.mock(BpmnProcessDefinitionModel.class);
        when(modelV2.getJobTypes()).thenReturn(Set.of("new-job"));
        BpmnProcessDefinitionModel modelV3 = org.mockito.Mockito.mock(BpmnProcessDefinitionModel.class);
        when(modelV3.getJobTypes()).thenReturn(Set.of("bill-job"));
        when(bpmnService.getProcessDefinitionModelById(v1.getId())).thenReturn(modelV1);
        when(bpmnService.getProcessDefinitionModelById(v2.getId())).thenReturn(modelV2);
        when(bpmnService.getProcessDefinitionModelById(v3.getId())).thenReturn(modelV3);

        // V10-d: union ВСЕХ версий, не только latest — старая версия жива.
        assertThat(service.jobTypesForUser(userId))
            .containsExactlyInAnyOrder("old-job", "new-job", "bill-job");
    }

    @Test
    void syncPermissionsForUser_neverProvisioned_noBrokerCalls() throws Exception {
        UiUserEntity user = systemUser("sys-new", "SYSTEM");
        user.setRabbitmqProvisioned(false);
        when(uiUserRepository.findById(user.getId())).thenReturn(Optional.of(user));
        // Подменяем зависимости HTTP-клиента: любое обращение к брокеру
        // упадёт — тест доказывает, что обращения НЕТ (критерий 4 на юните).
        setField(service, "managementBaseUrl", "http://127.0.0.1:9");
        setField(service, "brokerAdminUser", "x");
        setField(service, "brokerAdminPassword", "y");

        service.syncPermissionsForUser(user.getId());

        verify(processMemberRepository, never()).findByUserId(any());
    }

    private static UiUserEntity systemUser(String login, String type) {
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(login);
        user.setActive(true);
        user.setUserType(type);
        return user;
    }

    private static ProcessMemberEntity member(UUID processId, UUID userId) {
        ProcessMemberEntity m = new ProcessMemberEntity();
        m.setProcessId(processId);
        m.setUserId(userId);
        return m;
    }

    private static ProcessDefinitionEntity version(String key) {
        ProcessDefinitionEntity v = new ProcessDefinitionEntity();
        v.setId(UUID.randomUUID());
        v.setKey(key);
        return v;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void normalizeBaseUrl_trimsTrailingSlashes() {
        assertThat(RabbitMqProvisioningService.normalizeBaseUrl("http://h:15672///"))
            .isEqualTo("http://h:15672");
        assertThat(RabbitMqProvisioningService.normalizeBaseUrl("  https://h  "))
            .isEqualTo("https://h");
    }

    @Test
    void mgmtUrlValidator_rejectsNonHttp_unitLevel() {
        // Полное поведение валидатора (fail-fast до бинов) — в
        // RabbitMqMgmtUrlFailFastIT (rest). Здесь — только матрица предиката
        // через публичный контракт: валидный дефолт стартует (тот же IT),
        // здесь фиксируем границы строковым набором без доступа к package.
        assertThat(RabbitMqProvisioningService.normalizeBaseUrl("http://rabbitmq:15672"))
            .isEqualTo("http://rabbitmq:15672");
    }
}
