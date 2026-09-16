package com.zorrodev.bpm.engine.perf7;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.query.UiUserQuery;
import com.zorrodev.bpm.contract.model.UiUser;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberEntity;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.handler.ExecutionCtx;
import com.zorrodev.bpm.engine.handler.ExclusiveGatewayHandler;
import com.zorrodev.bpm.engine.handler.FlowNavigator;
import com.zorrodev.bpm.engine.handler.TokenExecutor;
import com.zorrodev.bpm.engine.mapper.ActivityInstanceMapper;
import com.zorrodev.bpm.engine.mapper.UiUserMapper;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.PasswordTokenRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.ProcessMemberRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.repository.RefreshTokenRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.repository.UserGroupRepository;
import com.zorrodev.bpm.engine.security.AuthorizationService;
import com.zorrodev.bpm.engine.security.PasswordHasher;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.security.TokenService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.impl.UiUserServiceImpl;
import com.zorrodev.bpm.engine.service.query.ActivityQueryOperationsImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-PERF-7: честные критерии на 9 пунктов (SQL-count Mockito-verify,
 * pageSize-clamp boundary, FEEL 1 SELECT, batch effectiveGrants, bounded
 * кэши, projection/pagination наличие методов).
 *
 * <p>НАМЕРЕННО без ссылок на новые методы прод-кода (только Mockito-verify
 * старых + reflection-строки): класс компилируется и на pre-fix master
 * {@code ac297d9d} (там обязан дать RED), и на ветке (там GREEN).
 */
class Perf7CriteriaTest {

    // ---------- helpers ----------

    private UiUserServiceImpl uiUserService(UiUserRepository repository,
            UiUserMapper mapper, PasswordTokenRepository passwordTokens) {
        return new UiUserServiceImpl(repository, mapper, mock(PasswordHasher.class),
            mock(TokenService.class), mock(RefreshTokenRepository.class),
            passwordTokens, mock(PlatformTransactionManager.class));
    }

    private List<UiUserEntity> threeUsers() {
        List<UiUserEntity> users = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            UiUserEntity e = new UiUserEntity();
            e.setId(UUID.randomUUID());
            e.setUsername("perf7user" + i);
            users.add(e);
        }
        return users;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubFindAll(UiUserRepository repository, List<UiUserEntity> users) {
        when(repository.findAll(any(Specification.class), any(Pageable.class))).thenAnswer(inv -> {
            Pageable p = inv.getArgument(1);
            return new PageImpl<>(users, p, users.size());
        });
    }

    private void stubMapper(UiUserMapper mapper) {
        when(mapper.toDTO(any(UiUserEntity.class))).thenAnswer(inv -> new UiUser());
    }

    // ---------- 1. pageSize clamp (WO критерий 1) ----------

    @Test
    void pageSize_hugeRequest_clampedTo200() {
        List<UiUserEntity> users = threeUsers();
        UiUserRepository repository = mock(UiUserRepository.class);
        UiUserMapper mapper = mock(UiUserMapper.class);
        stubFindAll(repository, users);
        stubMapper(mapper);

        UiUserQuery q = new UiUserQuery();
        q.setPageIndex(0);
        q.setPageSize(100000);

        PagedDataDTO<UiUser> dto = uiUserService(repository, mapper,
            mock(PasswordTokenRepository.class)).find(q);

        assertThat(dto.getPageSize()).isEqualTo(200);
        assertThat(dto.getData()).hasSize(3);
    }

    @Test
    void pageSize_zeroOrNegative_clampedTo1() {
        List<UiUserEntity> users = threeUsers();
        UiUserRepository repository = mock(UiUserRepository.class);
        UiUserMapper mapper = mock(UiUserMapper.class);
        stubFindAll(repository, users);
        stubMapper(mapper);

        UiUserQuery q = new UiUserQuery();
        q.setPageIndex(0);
        q.setPageSize(0);

        PagedDataDTO<UiUser> dto = uiUserService(repository, mapper,
            mock(PasswordTokenRepository.class)).find(q);

        assertThat(dto.getPageSize()).isEqualTo(1);
    }

    @Test
    void pageIndex_negative_clampedTo0() {
        List<UiUserEntity> users = threeUsers();
        UiUserRepository repository = mock(UiUserRepository.class);
        UiUserMapper mapper = mock(UiUserMapper.class);
        stubFindAll(repository, users);
        stubMapper(mapper);

        UiUserQuery q = new UiUserQuery();
        q.setPageIndex(-5);
        q.setPageSize(10);

        PagedDataDTO<UiUser> dto = uiUserService(repository, mapper,
            mock(PasswordTokenRepository.class)).find(q);

        assertThat(dto.getPageIndex()).isZero();
    }

    // ---------- 2. UiUser N+1 → batch (WO п.1, P-5) ----------

    @Test
    void userList_doesNotCallPerUserExists() {
        List<UiUserEntity> users = threeUsers();
        UiUserRepository repository = mock(UiUserRepository.class);
        UiUserMapper mapper = mock(UiUserMapper.class);
        PasswordTokenRepository passwordTokens = mock(PasswordTokenRepository.class);
        stubFindAll(repository, users);
        stubMapper(mapper);

        UiUserQuery q = new UiUserQuery();
        q.setPageIndex(0);
        q.setPageSize(10);

        PagedDataDTO<UiUser> dto = uiUserService(repository, mapper, passwordTokens).find(q);

        // Старый код: exists×N (N=3). Новый: один batch IN, per-user exists — 0 вызовов.
        verify(passwordTokens, times(0))
            .existsByUserIdAndTypeAndUsedFalseAndExpiresAtAfter(any(), anyString(), any());
        assertThat(dto.getData()).hasSize(3);
        assertThat(dto.getData()).allMatch(u -> !u.isPendingInvitation());
    }

    // ---------- 3. FEEL: 1 getVariables на фазу (WO п.2, критерий 2) ----------

    @Test
    void exclusiveGateway_fetchesVariablesOnceForThreeOutgoings() {
        DBService dbService = mock(DBService.class);
        FlowNavigator flowNavigator = mock(FlowNavigator.class);
        when(dbService.createActivity(any(), any(), any(BpmnElementModel.class)))
            .thenReturn(UUID.randomUUID());
        when(dbService.getVariables(any())).thenReturn(List.of());

        BpmnElementModel gw = new BpmnElementModel();
        gw.setId("gw1");
        gw.setType(BpmnElementType.EXCLUSIVE_GATEWAY);
        gw.getOutgoing().addAll(List.of("f1", "f2", "f3"));
        gw.getIncoming().add("in1");

        UUID piId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        ExecutionCtx ctx = new ExecutionCtx(piId, tokenId, mock(TokenExecutor.class), null);

        ExclusiveGatewayHandler handler = new ExclusiveGatewayHandler(dbService, flowNavigator);

        // Оба дерева: FlowNavigator-mock возвращает null → совпадений нет,
        // defaultFlowId null → IllegalStateException. До броска хендлер уже
        // сделал свои getVariables/processFlow вызовы — их и считаем.
        assertThatThrownBy(() -> handler.handle(ctx, mock(BpmnProcessDefinitionModel.class), gw))
            .isInstanceOf(IllegalStateException.class);

        // Новый код: ровно 1 getVariables перед циклом. Старый: 0 здесь (+N внутри).
        verify(dbService, times(1)).getVariables(piId);
        // Новый код ходит в 6-arg overload; 5-arg (старый путь) — 0 вызовов.
        verify(flowNavigator, times(0))
            .processFlow(any(), any(), anyString(), any(), any());
    }

    // ---------- 4. effectiveGrants без per-grant findById (WO F31, критерий b) ----------

    @Test
    void effectiveGrants_doesNotCallPerGrantFindById() {
        ProcessRepository processRepository = mock(ProcessRepository.class);
        ProcessMemberRepository memberRepository = mock(ProcessMemberRepository.class);
        AuthorizationService auth = new AuthorizationService(processRepository, memberRepository,
            mock(ProcessInstanceRepository.class), mock(ProcessDefinitionRepository.class),
            mock(UserGroupRepository.class));

        UUID owner = UUID.randomUUID();
        Map<UUID, Principal.Grant> keyGrants = new HashMap<>();
        for (int i = 0; i < 5; i++) {
            keyGrants.put(UUID.randomUUID(), new Principal.Grant(Set.of(), true));
        }

        Map<UUID, Principal.Grant> effective = auth.effectiveGrants(owner, keyGrants);

        // Моки пустые → effective пусто в обоих деревьях; разница — число SQL:
        // старый код: 5×findById + 5×findById; новый: batch, per-grant lookup — 0.
        assertThat(effective).isEmpty();
        verify(processRepository, times(0)).findById(any());
        verify(memberRepository, times(0)).findById(any());
    }

    // ---------- 5. bounded кэши движка (WO п.3/п.5/F38, критерий 4/c) ----------

    @Test
    void domainEventEmitter_pdKeyCache_isBoundedCaffeine() throws Exception {
        Class<?> clazz = Class.forName(
            "com.zorrodev.bpm.engine.event.DomainEventEmitter");
        var field = clazz.getDeclaredField("pdKeyCache");
        assertThat(field.getType().getName().toLowerCase()).contains("caffeine");
    }

    @Test
    void bpmnService_supportsMaxWeightConstructor() throws Exception {
        Class<?> clazz = Class.forName(
            "com.zorrodev.bpm.engine.service.impl.BpmnServiceImpl");
        // Старый ctor: (FileService, BpmnParseService, int, int). Новый: +int maxWeightMb.
        boolean hasWeightCtor = false;
        for (var ctor : clazz.getDeclaredConstructors()) {
            if (ctor.getParameterCount() == 5) {
                hasWeightCtor = true;
            }
        }
        assertThat(hasWeightCtor).isTrue();
    }

    // ---------- 6. activities pagination + outbox projection (WO п.6/F30) ----------

    @Test
    void activityRepository_hasPageableOverload() throws Exception {
        Class<?> clazz = Class.forName(
            "com.zorrodev.bpm.engine.repository.ActivityRepository");
        boolean found = false;
        for (var m : clazz.getMethods()) {
            if (m.getName().equals("findByProcessInstanceIdOrderByCreatedAtAsc")
                && m.getParameterCount() == 2
                && m.getParameterTypes()[1].getName().contains("Pageable")) {
                found = true;
            }
        }
        assertThat(found).isTrue();
    }

    @Test
    void activityQueryOperations_hasPagedMethod() throws Exception {
        Class<?> clazz = Class.forName(
            "com.zorrodev.bpm.engine.service.query.ActivityQueryOperations");
        clazz.getMethod("getActivitiesPaged", UUID.class, Integer.class, Integer.class);
    }

    @Test
    void outboxRepository_hasProjectionQueriesWithoutPayload() throws Exception {
        Class<?> view = Class.forName(
            "com.zorrodev.bpm.engine.repository.OutboxEntryView");
        // Projection несёт 7 полей — payload среди них нет.
        assertThat(view.getMethods()).hasSize(7);
        for (var m : view.getMethods()) {
            assertThat(m.getName().toLowerCase()).doesNotContain("payload");
        }
        Class<?> repo = Class.forName(
            "com.zorrodev.bpm.engine.repository.OutboxRepository");
        repo.getMethod("findProjectedByStatusOrderByCreatedAtDesc",
            String.class, org.springframework.data.domain.Pageable.class);
        repo.getMethod("findProjectedAllOrderByCreatedAtDesc",
            org.springframework.data.domain.Pageable.class);
    }

    // ---------- 7. legacy activities path: жёсткий cap 2000 (G-C решение CTO) ----------

    @Test
    void legacyGetActivities_isCappedAt2000_notUnbounded() {
        ActivityRepository repository = mock(ActivityRepository.class);
        UUID piId = UUID.randomUUID();
        List<ActivityEntity> three = threeActivities(piId);
        when(repository.findByProcessInstanceIdOrderByCreatedAtAsc(eq(piId), any(Pageable.class)))
            .thenAnswer(inv -> {
                Pageable p = inv.getArgument(1);
                return new PageImpl<>(three, p, three.size());
            });

        var ops = new ActivityQueryOperationsImpl(repository, new ActivityInstanceMapper());
        List<com.zorrodev.bpm.contract.model.ActivityInstance> dtos = ops.getActivities(piId);

        // Тип возврата — по-прежнему List (контракт не менялся), но запрос bounded:
        // Pageable-вариант вызван ровно 1 раз с лимитом 2000, unbounded (1-arg) — 0.
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository, times(1))
            .findByProcessInstanceIdOrderByCreatedAtAsc(eq(piId), captor.capture());
        assertThat(captor.getValue().getPageSize())
            .isEqualTo(ActivityQueryOperationsImpl.LEGACY_ACTIVITIES_MAX);
        assertThat(ActivityQueryOperationsImpl.LEGACY_ACTIVITIES_MAX).isEqualTo(2000);
        verify(repository, times(0))
            .findByProcessInstanceIdOrderByCreatedAtAsc(piId);
        assertThat(dtos).hasSize(3);
    }

    // ---------- 8. поведенческая пагинация: 5 активностей, pageSize=2 → 3 страницы ----------

    @Test
    @SuppressWarnings("unchecked")
    void activitiesPaged_fiveActivities_pageSizeTwo_threePagesDisjoint() throws Exception {
        // getActivitiesPaged нет на pre-fix базе — вызываем рефлексией, чтобы класс
        // компилировался в обоих деревьях; ассерты при этом поведенческие
        // (содержимое страниц, totalElements), а не на существование метода.
        ActivityRepository repository = mock(ActivityRepository.class);
        UUID piId = UUID.randomUUID();
        List<ActivityEntity> five = fiveActivities(piId);
        when(repository.findByProcessInstanceIdOrderByCreatedAtAsc(eq(piId), any(Pageable.class)))
            .thenAnswer(inv -> {
                Pageable p = inv.getArgument(1);
                int from = (int) Math.min(p.getOffset(), five.size());
                int to = (int) Math.min(from + p.getPageSize(), five.size());
                return new PageImpl<>(five.subList(from, to), p, five.size());
            });

        var ops = new ActivityQueryOperationsImpl(repository, new ActivityInstanceMapper());
        var method = ops.getClass().getMethod("getActivitiesPaged",
            UUID.class, Integer.class, Integer.class);

        PagedDataDTO<com.zorrodev.bpm.contract.model.ActivityInstance> p0 =
            (PagedDataDTO<com.zorrodev.bpm.contract.model.ActivityInstance>)
                method.invoke(ops, piId, 0, 2);
        PagedDataDTO<com.zorrodev.bpm.contract.model.ActivityInstance> p1 =
            (PagedDataDTO<com.zorrodev.bpm.contract.model.ActivityInstance>)
                method.invoke(ops, piId, 1, 2);
        PagedDataDTO<com.zorrodev.bpm.contract.model.ActivityInstance> p2 =
            (PagedDataDTO<com.zorrodev.bpm.contract.model.ActivityInstance>)
                method.invoke(ops, piId, 2, 2);

        assertThat(p0.getTotalElements()).isEqualTo(5);
        assertThat(p0.getPageIndex()).isZero();
        assertThat(p0.getPageSize()).isEqualTo(2);
        assertThat(p0.getData()).hasSize(2);
        assertThat(p1.getData()).hasSize(2);
        assertThat(p2.getData()).hasSize(1);
        // Вторая страница не пересекается с первой, третья — с обеими.
        assertThat(p1.getData().stream().map(d -> d.getId()).toList())
            .doesNotContainAnyElementsOf(p0.getData().stream().map(d -> d.getId()).toList());
        assertThat(p2.getData().stream().map(d -> d.getId()).toList())
            .doesNotContainAnyElementsOf(p0.getData().stream().map(d -> d.getId()).toList())
            .doesNotContainAnyElementsOf(p1.getData().stream().map(d -> d.getId()).toList());
    }

    // ---------- 9. позитивный effectiveGrants: гранты дают реальные процессы ----------

    @Test
    void effectiveGrants_memberOwner_getsNarrowedGrants_foreignerDropped() {
        ProcessRepository processRepository = mock(ProcessRepository.class);
        ProcessMemberRepository memberRepository = mock(ProcessMemberRepository.class);
        AuthorizationService auth = new AuthorizationService(processRepository, memberRepository,
            mock(ProcessInstanceRepository.class), mock(ProcessDefinitionRepository.class),
            mock(UserGroupRepository.class));

        UUID owner = UUID.randomUUID();
        UUID ownProc = UUID.randomUUID();
        UUID foreignProc = UUID.randomUUID();
        ProcessEntity own = new ProcessEntity();
        own.setId(ownProc);
        own.setDefinitionKey("own-proc");
        ProcessEntity foreign = new ProcessEntity();
        foreign.setId(foreignProc);
        foreign.setDefinitionKey("foreign-proc");
        when(processRepository.findByIdIn(anyCollection()))
            .thenReturn(List.of(own, foreign));
        ProcessMemberEntity membership = new ProcessMemberEntity();
        membership.setProcessId(ownProc);
        membership.setUserId(owner);
        membership.setRole("OWNER");
        when(memberRepository.findByProcessIdInAndUserId(anyCollection(), eq(owner)))
            .thenReturn(List.of(membership));

        Map<UUID, Principal.Grant> keyGrants = Map.of(
            ownProc, new Principal.Grant(Set.of(), true),
            foreignProc, new Principal.Grant(Set.of(), true));
        Map<UUID, Principal.Grant> effective = auth.effectiveGrants(owner, keyGrants);

        // Batch вызван ровно по 1 разу (не 2×G), результат правильный:
        // свой процесс виден с реальными OWNER-правами, чужой отпал.
        verify(processRepository, times(1)).findByIdIn(anyCollection());
        verify(memberRepository, times(1)).findByProcessIdInAndUserId(anyCollection(), eq(owner));
        assertThat(effective.keySet()).containsExactly(ownProc);
        assertThat(effective.get(ownProc).permissions()).contains("START");
    }

    private List<ActivityEntity> threeActivities(UUID piId) {
        return fiveActivities(piId).subList(0, 3);
    }

    private List<ActivityEntity> fiveActivities(UUID piId) {
        List<ActivityEntity> list = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ActivityEntity e = new ActivityEntity();
            e.setId(UUID.randomUUID());
            e.setProcessInstanceId(piId);
            e.setBpmnElementId("node" + i);
            e.setCreatedAt(java.time.Instant.now().plusSeconds(i));
            list.add(e);
        }
        return list;
    }
}
