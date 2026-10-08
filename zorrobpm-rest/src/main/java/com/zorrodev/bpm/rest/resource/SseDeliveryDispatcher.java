package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.security.UiUserLookupService;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * WO-AUDIT-9 (шаг 5a): диспетчер доставки — фильтры подписки, группировка
 * liveness-проверок по subject, per-client authz-гейт и enqueue в writer.
 * Бывшие {@code dispatchToClientsTraced} / {@code closeDeadInGroup} /
 * {@code deliverToClient} {@code SseEventStreamService} (WO-REL-52 NEW-03
 * part A, WO-SEC-67 F13), перенесённые построчно, без смены семантики.
 *
 * <p>Зависимости — constructor-injected: реестр (итерации + точечные закрытия),
 * гейт (вердикты живости/прав), lookup пользователей (групповой row-read).
 * Закрытия идут через {@link CloseAction} (фасад: removeClientState +
 * emitter-complete + evict).
 */
@Slf4j
public final class SseDeliveryDispatcher {

    /** Точечное закрытие сессии (фасад). */
    public interface CloseAction {
        void close(String clientId, String reason);
    }

    private final SseSessionRegistry sessionRegistry;
    private final SseAuthzGate authzGate;
    private final UiUserLookupService uiUserLookupService;
    private final CloseAction closeAction;
    // WO-AUDIT-7: транзитный пин живых курсоров для events-retention
    // (nullable — unit-scope shape: без трекера advance молчит, поведение —
    // как раньше).
    private final com.zorrodev.bpm.engine.event.SseLiveCursorTracker cursorTracker;

    public SseDeliveryDispatcher(SseSessionRegistry sessionRegistry,
            SseAuthzGate authzGate,
            UiUserLookupService uiUserLookupService,
            CloseAction closeAction) {
        this(sessionRegistry, authzGate, uiUserLookupService, closeAction, null);
    }

    public SseDeliveryDispatcher(SseSessionRegistry sessionRegistry,
            SseAuthzGate authzGate,
            UiUserLookupService uiUserLookupService,
            CloseAction closeAction,
            com.zorrodev.bpm.engine.event.SseLiveCursorTracker cursorTracker) {
        this.sessionRegistry = sessionRegistry;
        this.authzGate = authzGate;
        this.uiUserLookupService = uiUserLookupService;
        this.closeAction = closeAction;
        this.cursorTracker = cursorTracker;
    }

    /**
     * Рассылка одного разрешённого события всем подходящим сессиям.
     * Вызывается под сиквенсор-локом сиквенсора (порядок выпуска = порядок
     * рассылки) — сама блокировок не берёт.
     */
    public void dispatch(Map<String, Object> envelope, String eventType,
            String processInstanceId, UUID pdUuid, long cursor) {

        // WO-REL-52 (NEW-03, part A): liveness — РАЗ на пользователя за
        // событие, а не раз на клиента. Клиенты одного principal (10 вкладок
        // одного юзера = 10 findById на каждое событие) делят один lookup:
        // первый проход группирует подходящих под фильтры клиентов по
        // subject, второй — один row-read на группу, мёртвые клиенты
        // закрываются точечно. Число SQL liveness на событие = числу
        // РАЗЛИЧНЫХ пользователей (обычно единицы), а не числу клиентов
        // (до max-clients=1000). Отзыв между событиями по-прежнему закрывает
        // поток на следующем событии (проверка на каждое событие, не кэш —
        // окно валидности отозванных прав не расширено ни на секунду сверх
        // принятого; отдельный TTL-кэш не заводился осознанно — см. отчёт).
        java.util.Map<String, java.util.List<SseClientSession>> bySubject = null;
        if (uiUserLookupService != null) {
            bySubject = new java.util.LinkedHashMap<>();
        }

        for (SseClientSession client : sessionRegistry.snapshot()) {
            // Check type filter
            if (client.typeFilter() != null && !client.typeFilter().isBlank()
                && !client.typeFilter().equals(eventType)) {
                continue;
            }

            // Check processInstanceId filter
            if (client.processInstanceIdFilter() != null && !client.processInstanceIdFilter().isBlank()
                && !client.processInstanceIdFilter().equals(processInstanceId)) {
                continue;
            }

            if (bySubject != null) {
                bySubject.computeIfAbsent(SseAuthzGate.subjectKey(client.principal()),
                    k -> new java.util.ArrayList<>()).add(client);
                continue;
            }

            // Unit-scope harness (null lookup — семантика та же, делить нечего):
            // живость проверяется гейтом напрямую (null-коллабораторы гейта =
            // missing harness, не dead credential — см. SseAuthzGate).
            if (!authzGate.isCredentialLive(client)) {
                closeAction.close(client.clientId(), "credential dead");
                continue;
            }
            deliverToClient(client, envelope, eventType, cursor, pdUuid);
        }

        if (bySubject != null) {
            for (java.util.List<SseClientSession> group : bySubject.values()) {
                // Один row-read на группу + per-client вердикты без SQL.
                if (!closeDeadInGroup(group)) {
                    continue;
                }
                for (SseClientSession client : group) {
                    if (sessionRegistry.contains(client.clientId())) {
                        deliverToClient(client, envelope, eventType, cursor, pdUuid);
                    }
                }
            }
        }
    }

    /**
     * WO-REL-52: живость ГРУППЫ клиентов одного subject за один row-read.
     * JWT: строка читается ОДИН раз, затем сверяется с tokenVersion/role
     * КАЖДОГО клиента группы (версии у вкладок одного юзера могут
     * различаться — login/logout между регистрациями; общий row-read не
     * смешивает вердикты: клиент со stale-версией закрывается точечно, даже
     * если сосед свеж — один stale-token не роняет все вкладки юзера).
     * API-key: один isKeyLive на группу (ключ один — subject и есть key id;
     * версии нет). Ошибка lookup → вся группа закрывается (fail-closed,
     * как раньше каждый клиент по отдельности).
     *
     * @return true — есть кому доставлять (группа не вся мертва)
     */
    private boolean closeDeadInGroup(java.util.List<SseClientSession> group) {
        SseClientSession first = group.get(0);
        Principal principal = first.principal();
        try {
            if (principal instanceof Principal.UserPrincipal up) {
                var state = uiUserLookupService.securityState(up.userId()).orElse(null);
                if (state == null || !state.active()) {
                    for (SseClientSession client : group) {
                        closeAction.close(client.clientId(), "credential dead");
                    }
                    return false;
                }
                boolean anyLive = false;
                for (SseClientSession client : group) {
                    if (authzGate.matchesLiveUserState(client, state)) {
                        anyLive = true;
                    } else {
                        closeAction.close(client.clientId(), "credential dead");
                    }
                }
                return anyLive;
            }
            if (principal instanceof Principal.ServicePrincipal) {
                if (!authzGate.isPrincipalLive(principal, first.tokenVersion())) {
                    for (SseClientSession client : group) {
                        closeAction.close(client.clientId(), "credential dead");
                    }
                    return false;
                }
                return true;
            }
            for (SseClientSession client : group) {
                closeAction.close(client.clientId(), "credential dead");
            }
            return false;
        } catch (RuntimeException e) {
            log.warn("SSE group liveness check failed for subject {} — failing closed",
                SseAuthzGate.subjectKey(principal), e);
            for (SseClientSession client : group) {
                try {
                    closeAction.close(client.clientId(), "credential dead");
                } catch (RuntimeException ce) {
                    log.warn("SSE close of revoked client {} failed", client.clientId(), ce);
                }
            }
            return false;
        }
    }

    /**
     * WO-REL-52: доставка одному клиенту (выделено из
     * {@code dispatchToClientsTraced} без смены семантики — rights
     * re-resolution, narrowing-check, authz-гейт и enqueue те же,
     * построчно).
     */
    private void deliverToClient(SseClientSession client, Map<String, Object> envelope,
            String eventType, long cursor, UUID pdUuid) {
            // WO-SEC-67 (F13), step 2 — rights re-resolution (periodic): the
            // registration-time snapshot goes stale on membership removal /
            // role change / grant narrowing. Re-resolve (30s-TTL cached) and
            // compare against the snapshot; on ANY narrowing close the stale
            // snapshot's stream now — it must re-register for the new, smaller
            // view. Fail-closed on resolver error (see method).
            Collection<UUID> fresh = authzGate.reevaluateRights(client);
            if (fresh == null && client.allowedPdIds() != null) {
                // Resolver error (not SUPER_ADMIN — that returns null by
                // contract and stays null): fail closed, do not deliver.
                closeAction.close(client.clientId(), "rights re-check failed");
                return;
            }
            if (SseAuthzGate.isNarrowed(client.allowedPdIds(), fresh)) {
                closeAction.close(client.clientId(), "rights narrowed");
                return;
            }

            // Check AuthZ: processDefinitionId must be in allowed set (fail-closed: G-L).
            // Uses the FRESH set when non-null, else the client snapshot
            // (SUPER_ADMIN null, or a still-valid cached view).
            Collection<UUID> effective = fresh != null ? fresh : client.allowedPdIds();
            if (effective != null) {
                if (pdUuid == null || !effective.contains(pdUuid)) {
                    return;
                }
            }

            // WO-REL-37 (F14) + WO-REL-47: событие — в writer клиента (в
            // BUFFERING — в очередь, в LIVE — в очередь pump'а на отправку;
            // решение под локом клиента, одним шагом — окно потери закрыто).
            // AuthZ-гейты выше (credential/rights) уже пройдены.
            client.enqueueLive(SseWireProtocol.buildLiveEvent(cursor, eventType, envelope), envelope, cursor);
            // WO-AUDIT-7: клиент увидел позицию (очередь pump'а/BUFFERING —
            // drain решит дубль/новое по границе catchup'а): пин двигается
            // вперёд max-merge'ем. Строка свежая (только прибыла live), cutoff
            // её всё равно держит — раннее продвижение безопасно.
            if (cursorTracker != null && cursor > 0) {
                cursorTracker.advance(client.clientId(), cursor);
            }
    }
}
