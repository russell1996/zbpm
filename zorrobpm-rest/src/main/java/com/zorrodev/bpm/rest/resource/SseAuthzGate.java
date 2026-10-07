package com.zorrodev.bpm.rest.resource;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.security.UiUserLookupService;
import com.zorrodev.bpm.engine.service.ApiKeyService;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.UUID;

/**
 * WO-AUDIT-9 (шаг 4): авторизация/фильтрация событий по получателю —
 * liveness-гейт credential'а, re-resolution прав и event-driven revoke-sweep.
 * Бывшие приватные методы {@code SseEventStreamService} (WO-SEC-67 F13,
 * WO-REL-52 NEW-03 part A), перенесённые построчно, без смены семантики.
 *
 * <p>Все зависимости — constructor-injected; nullable-коллабораторы
 * ({@code uiUserLookupService}/{@code apiKeyService} null в unit-харнессах)
 * означают «проверять не против чего» (production wiring всегда inject'ит
 * реальные бины), а не мёртвый credential — как раньше.
 *
 * <p>Revocation-действия (закрытие потока, снятие состояния, инвалидация кэша)
 * идут через {@link CloseAction} — реализует фасад поверх реестра;
 * резолвинг прав — через инжектированный {@link EventAuthzResolver}.
 */
@Slf4j
public final class SseAuthzGate {

    /**
     * Revocation-действие над сессией (закрыть + снять состояние + выселить
     * кэш-запись). Реализует фасад (реестр + emitter-complete).
     */
    public interface CloseAction {
        void close(SseClientSession session, String reason);
    }

    private final EventAuthzResolver eventAuthzResolver;
    private final UiUserLookupService uiUserLookupService;
    private final ApiKeyService apiKeyService;
    private final CloseAction closeAction;

    public SseAuthzGate(EventAuthzResolver eventAuthzResolver,
            UiUserLookupService uiUserLookupService,
            ApiKeyService apiKeyService,
            CloseAction closeAction) {
        this.eventAuthzResolver = eventAuthzResolver;
        this.uiUserLookupService = uiUserLookupService;
        this.apiKeyService = apiKeyService;
        this.closeAction = closeAction;
    }

    /**
     * WO-SEC-67: freeze the JWT token_version at registration (the liveness
     * baseline). Fail-closed: an unreadable row → -1, which can only mismatch
     * a real version (versions start at 0) and close the stream — never grant.
     */
    public int currentTokenVersion(Principal principal) {
        if (principal instanceof Principal.UserPrincipal up) {
            try {
                return uiUserLookupService.securityState(up.userId())
                    .map(com.zorrodev.bpm.engine.security.UiUserLookupService.UserSecurityState::tokenVersion)
                    .orElse(-1);
            } catch (RuntimeException e) {
                log.warn("SSE registration: token_version unreadable — freezing -1 (fail closed)", e);
                return -1;
            }
        }
        return -1;
    }

    /**
     * WO-SEC-67: stable per-subject key for the connection cap and the rights
     * cache. JWT → userId; API key → key id (one row = one subject for cap
     * purposes; rotation keeps the row id — streams on it are closed
     * explicitly by invalidateStreamsForKey, not by re-slotting).
     */
    public static String subjectKey(Principal principal) {
        return SseEventStreamService.subjectKey(principal);
    }

    /**
     * WO-SEC-67 (F13): re-resolution cache for the per-event rights
     * re-check. {@code readableRuntimePdIds} is a multi-query JPA read
     * (membership → processes → definitions); re-running it on EVERY event
     * for EVERY client would multiply DB load by clients×events. A 30s TTL
     * bounds the revocation window (stale rights live at most 30s + delivery
     * lag) instead of the stream lifetime (was: infinite). Keyed by the
     * subject + definition-key filter — the two inputs of the resolution.
     * SUPER_ADMIN bypasses (always null = see all, no query to cache).
     * Caffeine is already on the classpath (JwtAuthFilter debounce precedent).
     */
    private final Cache<ReevalKey, Collection<UUID>> rightsCache = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterWrite(Duration.ofSeconds(30))
        .build();

    private record ReevalKey(String subjectKey, String processDefinitionKeyFilter) {}

    /**
     * WO-SEC-67 (F13): is the credential behind this stream still alive?
     * Mirrors the per-request checks of {@code JwtAuthFilter} (same lookups,
     * same fail-closed direction):
     * <ul>
     *   <li>JWT user → {@code UiUserLookupService.securityState}: user exists,
     *       active, and the claim version/role still match the row (logout
     *       bumps {@code token_version} — the only writer in prod; a role
     *       change flows through the same version on write — a stale role
     *       beyond its TTL fails closed);</li>
     *   <li>API key → row exists, not revoked, not expired, owner still
     *       active (WO-ACL-5 criterion #4, same as the filter).</li>
     * </ul>
     * Any lookup error → false (fail closed — the stream dies, it never
     * delivers into doubt).
     */
    public boolean isCredentialLive(SseClientSession session) {
        // WO-SEC-67: unit-scope harness (hand-built service with null
        // collaborators, e.g. SsePerf6IntegrationTest/SseBridgeStartupTest) —
        // there is nothing to check against. Production Spring wiring always
        // injects real beans; the full-context proof (SseRevocationIT) runs
        // with real rows. A null collaborator is a missing harness, never a
        // dead credential — failing closed here would only test the harness.
        if (uiUserLookupService == null || apiKeyService == null) {
            return true;
        }
        Principal principal = session.principal();
        // WO-REL-52 (NEW-03, part A): liveness РАЗ на пользователя за
        // событие, а не раз на клиента — клиенты одного principal делят один
        // lookup (группировка перед проверкой, см. dispatchToClientsTraced).
        // Однопользовательский путь (reconnect-порог без толпы) идёт сюда
        // напрямую — семантика та же, кэширования нет (событийно-точный
        // revoke: logout между двумя событиями закрывает поток на втором).
        return isPrincipalLive(principal, session.tokenVersion());
    }

    /**
     * WO-REL-52: проверка живости ОДНОГО principal (вынесено из
     * {@code isCredentialLive} без смены семантики — те же lookups, то же
     * fail-closed направление). Пакетный путь вызывает это один раз на
     * пользователя и раздаёт результат его клиентам.
     */
    public boolean isPrincipalLive(Principal principal, int tokenVersion) {
        try {
            if (principal instanceof Principal.UserPrincipal up) {
                // The JWT claims are frozen at registration; the row is live.
                // Same comparison as JwtAuthFilter: version + active + role.
                var state = uiUserLookupService.securityState(up.userId()).orElse(null);
                if (state == null || !state.active()
                    || state.tokenVersion() != tokenVersion
                    || !Objects.equals(state.role(), up.globalRole())) {
                    return false;
                }
                return true;
            }
            if (principal instanceof Principal.ServicePrincipal sp) {
                // Key liveness via the engine-side owner (WO-DEBT-7: no
                // engine.repository import in rest/resource).
                return apiKeyService.isKeyLive(sp.apiKeyId());
            }
            return false;
        } catch (RuntimeException e) {
            log.warn("SSE credential liveness check failed for principal {} — failing closed",
                subjectKey(principal), e);
            return false;
        }
    }

    /**
     * WO-REL-52 (NEW-03, part A): per-client вердикт группового пути против
     * ОДНОГО прочитанного состояния: версии у вкладок одного юзера могут
     * различаться (login/logout между регистрациями) — общий row-read не
     * смешивает вердикты: клиент со stale-версией закрывается точечно, даже
     * если сосед свеж. Та же тройная проверка, что однопользовательский путь
     * (version + role), построчно.
     */
    public boolean matchesLiveUserState(SseClientSession session,
            UiUserLookupService.UserSecurityState state) {
        Principal p = session.principal();
        return p instanceof Principal.UserPrincipal cpu
            && state.tokenVersion() == session.tokenVersion()
            && Objects.equals(state.role(), cpu.globalRole());
    }

    /**
     * WO-SEC-67 (F13): fresh rights for this session (30s-TTL cached).
     * SUPER_ADMIN bypasses (null = see all by contract — nothing to re-check).
     * ServicePrincipal streams resolve the LIVE key view (current grant rows,
     * not the frozen registration snapshot — setGrants must show up here).
     * Any resolver error → null WITH a non-null snapshot behind it, which the
     * caller treats as fail-closed (close, do not deliver). A null snapshot
     * (SUPER_ADMIN) + null fresh = still see-all.
     */
    public Collection<UUID> reevaluateRights(SseClientSession session) {
        if (session.principal().isSuperAdmin()) {
            return null;
        }
        try {
            return liveView(session);
        } catch (RuntimeException e) {
            log.warn("SSE rights re-resolution failed for client {} — failing closed",
                session.clientId(), e);
            return null;
        }
    }

    /**
     * WO-SEC-67 (F13): has the fresh view narrowed vs the snapshot? null fresh
     * = SUPER_ADMIN see-all = never narrowed. A non-null fresh that is missing
     * ANY snapshot id (removal) closes the stream — even when it also ADDS ids
     * (a changed key filter outcome is still a different view; the client
     * re-registers for exactly it). Pure widening without loss keeps the
     * stream (fail-open on MORE rights would leak nothing the fresh set does
     * not already grant — delivery itself is checked against fresh).
     */
    public static boolean isNarrowed(Collection<UUID> snapshot, Collection<UUID> fresh) {
        if (fresh == null) {
            return false;
        }
        if (snapshot == null) {
            // Was see-all (non-admin snapshot cannot be null by contract —
            // defensive): any finite fresh view is narrower.
            return true;
        }
        return !fresh.containsAll(snapshot);
    }

    /**
     * WO-SEC-67 (F13): event-driven invalidation. Called by the revoke/logout/
     * membership paths; closes every open stream whose credential is now dead
     * or whose rights narrowed — immediately, without waiting for the next
     * event or the 30s cache TTL. Best-effort and non-throwing (a revoke must
     * never fail because a stream misbehaves); failures are logged.
     */
    public void invalidateStreams(Collection<SseClientSession> sessions) {
        for (SseClientSession session : java.util.List.copyOf(sessions)) {
            try {
                if (!isCredentialLive(session)) {
                    closeAction.close(session, "credential dead (event)");
                    continue;
                }
                // Single truth via liveView (POF-proven: a divergent inline
                // copy here once hid revokes from this sweep — SseRevocationIT
                // caught it). bypassCache=true: the revoke JUST happened, so
                // any cached view predates it by up to 30s.
                Collection<UUID> fresh;
                try {
                    fresh = liveView(session, true);
                } catch (RuntimeException e) {
                    log.warn("SSE event-driven re-resolution failed for client {} — failing closed",
                        session.clientId(), e);
                    closeAction.close(session, "rights re-check failed (event)");
                    continue;
                }
                if (fresh == null && session.allowedPdIds() != null) {
                    closeAction.close(session, "rights re-check failed (event)");
                    continue;
                }
                if (isNarrowed(session.allowedPdIds(), fresh)) {
                    closeAction.close(session, "rights narrowed (event)");
                }
            } catch (RuntimeException e) {
                log.warn("SSE event-driven invalidation failed for client {}", session.clientId(), e);
            }
        }
    }

    /**
     * WO-SEC-67 red-team #2: key rotation replaces the key MATERIAL in place
     * (same row id — liveness stays green), so the generic sweep cannot see
     * it. Rotation kills the old credential explicitly: close every stream
     * standing on this key id NOW, deterministically, without depending on
     * string comparisons. Best-effort and non-throwing like the sweep.
     */
    public void invalidateStreamsForKey(Collection<SseClientSession> sessions, UUID apiKeyId) {
        for (SseClientSession session : java.util.List.copyOf(sessions)) {
            try {
                if (session.principal() instanceof Principal.ServicePrincipal sp
                    && apiKeyId.equals(sp.apiKeyId())) {
                    closeAction.close(session, "key rotated");
                }
            } catch (RuntimeException e) {
                log.warn("SSE key invalidation failed for client {}", session.clientId(), e);
            }
        }
    }

    /** Инвалидация кэш-записи сессии (revoke-close путь фасада). */
    public void evict(SseClientSession session) {
        rightsCache.invalidate(
            new ReevalKey(subjectKey(session.principal()), session.processDefinitionKeyFilter()));
    }

    /**
     * WO-SEC-67 red-team #1: the CURRENT view for a session — live key rows for
     * service keys (frozen registration grants would hide a setGrants
     * narrowing forever), 30s-cached membership resolution for JWT users.
     * Single truth for the per-event check and the event-driven invalidation
     * (no double logic to diverge).
     *
     * @param bypassCache true on the event-driven path (the revoke JUST
     *        happened — a cached view predates it) — resolves fresh AND
     *        refreshes the cache so a racing per-event check sees the same
     *        view; false on the per-event path (cache governs the 30s TTL).
     */
    private Collection<UUID> liveView(SseClientSession session) {
        return liveView(session, false);
    }

    private Collection<UUID> liveView(SseClientSession session, boolean bypassCache) {
        // WO-SEC-67 verifier HOLD: SUPER_ADMIN bypass — see-all неизменно
        // (зеркало per-event reevaluateRights). Без него readableRuntimePdIds
        // вернул бы null → rightsCache.put(key, null) → Caffeine-NPE → любой
        // sweep закрывал бы ВСЕ admin-потоки. Credential-liveness админа
        // проверяется отдельно выше (logout бампает его version).
        if (session.principal().isSuperAdmin()) {
            return null;
        }
        if (session.principal() instanceof Principal.ServicePrincipal sp) {
            // Uncached: grant changes are rare, correctness beats one query
            // per event here.
            return eventAuthzResolver.readableRuntimePdIdsForKey(
                sp.apiKeyId(), sp.ownerUserId(), session.processDefinitionKeyFilter());
        }
        ReevalKey key = new ReevalKey(subjectKey(session.principal()), session.processDefinitionKeyFilter());
        if (bypassCache) {
            Collection<UUID> fresh = eventAuthzResolver.readableRuntimePdIds(
                session.principal(), session.processDefinitionKeyFilter());
            rightsCache.put(key, fresh);
            return fresh;
        }
        return rightsCache.get(key,
            k -> eventAuthzResolver.readableRuntimePdIds(
                session.principal(), session.processDefinitionKeyFilter()));
    }
}
