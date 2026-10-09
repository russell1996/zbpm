import { ref, onUnmounted } from 'vue'
import type { EventEnvelope } from '@/types/api'
import { useProcessStore } from '@/stores/process'
import { useTaskStore } from '@/stores/task'
import { useIncidentStore } from '@/stores/incident'
import api from '@/services/api'
import { sharedRefresh } from '@/services/refreshInterceptor'
import { publishRealtimeEvent } from '@/services/realtimeBus'
import {
  acquireSseLeadership,
  publishFanout,
  subscribeFanout,
  type SseFanoutMessage,
} from '@/services/sseFanout'

/**
 * WO-UI-18, часть A — живой realtime-канал для встроенного SPA.
 *
 * Backend SSE (`GET /events/stream`, контракт C из ADR-7) рабочий с WO-EVT-4,
 * но фронт его никогда не подключал: `handleEvent` трёх сторов вызывался только
 * из юнит-тестов (Finding #1 аудита 2026-09-22). Это осознанно НЕ восстанавливает
 * удалённый в WO-UI-17 `useEventStream.ts` as-is — у того было два расхождения
 * с реальным серверным протоколом (зафиксированы в отчёте WO-UI-17, F35):
 *  1. сервер шлёт курсор через `Last-Event-ID` **header**, а не `since` query;
 *  2. сервер шлёт ИМЕНОВАННЫЕ события (`.name(type)` в SseEventStreamService),
 *     которые `onmessage`-хендлер не получает вообще — нужны `addEventListener`
 *     на каждый тип.
 *
 * Реконнект (критерий 3): сервер прикладывает `reconnectTime(3000)` к каждому
 * событию, поэтому браузер переподключается сам; при переподключении браузер
 * автоматически шлёт `Last-Event-ID` = id последнего полученного события, а
 * сервер докачивает пропущенное (catchup WO-REL-37, курсор уже на бэкенде).
 * Задача клиента — не мешать: не закрывать EventSource на transient-`onerror`
 * и хранить последний id для видимости состояния.
 *
 * WO-UI-22 (NEW-09): исключение — CLOSED после 401 с истёкшей JWT-кукой.
 * Нативный EventSource переподключается только после сетевых ошибок; не-200
 * переводит его в CLOSED навсегда, и сервер (закрывающий поток через 30 мин =
 * JWT TTL) рассчитывал на несуществующий браузерный reconnect. Поэтому
 * `onerror` при `readyState === CLOSED` идёт по пути refresh+пересоздание
 * (с backoff, кап попыток), а при мёртвом refresh — в честный
 * `sessionExpired` вместо вечного «retrying…».
 */

// Источник истины — case-ветки handleEvent трёх сторов. Если в стор добавится
// новый тип, его нужно добавить и сюда, иначе событие молча не дойдёт:
// именованные SSE-события без слушателя никуда не падают (нет onmessage-фолбэка,
// сервер всегда ставит .name()).
// WO-UI-25 (критерий 2): подписано ВСЁ, что сервер реально шлёт UI
// (DomainEventType минус осознанный ignore-list контрактного теста).
// activity.completed — главный «двигательный» тип (~70% прод-потока),
// user-task.assigned/unassigned — смена исполнителя без смены статуса.
export const REALTIME_EVENT_TYPES = [
  'process-instance.started',
  'process-instance.completed',
  'process-instance.cancelled',
  'activity.completed',
  'user-task.created',
  'user-task.completed',
  'user-task.assigned',
  'user-task.unassigned',
  'service-task.created',
  'incident.raised',
  'incident.resolved',
] as const

/**
 * WO-UI-25 — общее здоровье realtime-канала для подписчиков шины.
 *
 * useRealtimeEvents() создаёт локальные ref на вызов (у MainLayout свой
 * экземпляр), а детальной странице инстанса нужен тот же сигнал для
 * фолбэк-опроса и индикатора — поэтому каждый экземпляр зеркалит своё
 * состояние сюда. В проде экземпляр один (MainLayout), last-writer-wins
 * в тестах с несколькими экземплярами — приемлемо и задокументировано.
 */
export const sharedRealtimeHealth = {
  isConnected: ref(false),
  /** Канал был жив хотя бы раз — отличает «ещё подключаемся» от «упал». */
  wasConnected: ref(false),
  realtimeDown: ref(false),
  sessionExpired: ref(false),
}

function mirrorHealth(patch: {
  isConnected?: boolean
  wasConnected?: boolean
  realtimeDown?: boolean
  sessionExpired?: boolean
}): void {
  if (patch.isConnected !== undefined) sharedRealtimeHealth.isConnected.value = patch.isConnected
  if (patch.wasConnected !== undefined) sharedRealtimeHealth.wasConnected.value = patch.wasConnected
  if (patch.realtimeDown !== undefined) sharedRealtimeHealth.realtimeDown.value = patch.realtimeDown
  if (patch.sessionExpired !== undefined) sharedRealtimeHealth.sessionExpired.value = patch.sessionExpired
}

// Тот же baseURL-резолвинг, что в services/api.ts: EventSource обязан идти на
// тот же origin/base, иначе cookie-auth не приложится.
// WO-URGENT-3: хвостовой '/' у base ОБЯЗАТЕЛЬНО срезается — при дефолте
// Dockerfile `VITE_API_URL=/` наивный `${base}/events/stream` давал
// `'//events/stream'` (protocol-relative URL с хостом `events`, CSP-блок,
// realtime мёртв на проде). axios в services/api.ts от этого защищён сам
// (combineURLs нормализует слэши), здесь нормализация явная.
function stripTrailingSlash(base: string): string {
  return base.replace(/\/+$/, '')
}

export function buildStreamUrl(): string {
  const base = import.meta.env.VITE_API_URL || '/api'
  return `${stripTrailingSlash(base)}/events/stream`
}

// WO-UI-22: refresh шёл на тот же base напрямую fetch'ем; WO-QW-5 перевёл
// путь на sharedRefresh (тот же origin/кука через api-инстанс). Хелпер
// оставлен для совместимости тестов/вызывающих — URL тот же.
// origin, credentials:include обязателен — иначе браузер не приложит куки).
export function buildRefreshUrl(): string {
  const base = import.meta.env.VITE_API_URL || '/api'
  return `${stripTrailingSlash(base)}/auth/refresh`
}

/**
 * WO-UI-22 (NEW-09): параметры восстановления после CLOSED. Нативный
 * EventSource переподключается только после сетевых ошибок; 401 с истёкшей
 * JWT-кукой переводит его в CLOSED навсегда. Экспортированы для тестов.
 */
export const SSE_RECONNECT_MAX_ATTEMPTS = 5
export const SSE_RECONNECT_BASE_DELAY_MS = 1000
export const SSE_RECONNECT_MAX_DELAY_MS = 30000

export function reconnectDelayMs(attempt: number): number {
  return Math.min(SSE_RECONNECT_BASE_DELAY_MS * 2 ** attempt, SSE_RECONNECT_MAX_DELAY_MS)
}

/**
 * WO-UI-26 Доп.6 п.2/Доп.8: диагноз последней неудачи канала для панели
 * диагностики (по клику на индикатор). `no-first-byte` — признак буферизации
 * прокси (REL-70): соединение открыто, но ни одного байта за N секунд.
 */
export type ChannelErrorKind =
  | 'none'
  | 'http-401'
  | 'http-403'
  | 'http-429'
  | 'http-other'
  | 'network'
  | 'timeout'
  | 'no-first-byte'

export interface ChannelDiagnostics {
  /** Текущий диагноз (none = канал здоров или ещё не пробовали). */
  lastError: ChannelErrorKind
  /** HTTP-статус, если диагноз http-*. */
  httpStatus: number | null
  /** Время последней попытки соединения (ISO). */
  lastAttemptAt: string | null
  /** Число попыток (пересозданий EventSource) в текущей сессии вкладки. */
  attempts: number
  /** Последний известный Last-Event-ID (курсор catchup). */
  lastEventId: string | null
  /** Эта вкладка — лидер SSE (держит соединение). */
  isLeader: boolean
}

export function useRealtimeEvents() {
  const isConnected = ref(false)
  const lastEventId = ref<string | null>(null)
  const error = ref<string | null>(null)
  // WO-UI-22: сессия истекла целиком (refresh тоже не удался) — realtime
  // восстановить нельзя, нужен вход. Отличается от transient-обрыва, после
  // которого нативный автореконнект жив.
  const sessionExpired = ref(false)
  // WO-REL-57: JWT-сессия ЖИВА (refresh проходит), а сам realtime-канал не
  // поднимается (429/обрывы исчерпали попытки). Прежний код схлопывал это в
  // sessionExpired с текстом "Session expired" — враньё: вход не нужен и
  // клик "Войти" отбивается router-guard'ом обратно на dashboard, а плашка
  // не исчезает (живой прод-репорт 2026-09-27). Честный сигнал — отдельно.
  const realtimeDown = ref(false)
  // WO-REL-57: результат последнего refresh — различаем "сессия мертва"
  // (refresh не проходит → sessionExpired) от "канал мёртв" (refresh жив,
  // а переподключения исчерпаны → realtimeDown). null — refresh ещё не было.
  let lastRefreshOk: boolean | null = null

  let eventSource: EventSource | null = null
  let reconnectAttempts = 0
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null
  // WO-UI-26 Доп.6 п.2/Доп.8: диагностика канала для панели по клику.
  const diagnostics = ref<ChannelDiagnostics>({
    lastError: 'none',
    httpStatus: null,
    lastAttemptAt: null,
    attempts: 0,
    lastEventId: null,
    isLeader: false,
  })
  // WO-UI-26 Доп.8: «нет первого байта» — соединение открыто, но onopen и
  // события не приходят N секунд (буферизация прокси, REL-70). EventSource
  // не отдаёт TTFB, поэтому меряем сами: таймер с момента construct.
  let firstByteTimer: ReturnType<typeof setTimeout> | null = null
  let firstByteSeen = false
  const FIRST_BYTE_TIMEOUT_MS = 10000

  function noteAttempt(): void {
    diagnostics.value = {
      ...diagnostics.value,
      lastAttemptAt: new Date().toISOString(),
      attempts: diagnostics.value.attempts + 1,
      isLeader: isLeaderTab,
    }
  }

  function noteError(kind: ChannelErrorKind, httpStatus: number | null = null): void {
    // Первое наблюдение сильнее последующих повторов той же природы —
    // но счётчик попыток и время всегда свежие (noteAttempt отдельно).
    diagnostics.value = { ...diagnostics.value, lastError: kind, httpStatus }
  }

  function noteHealthy(): void {
    diagnostics.value = { ...diagnostics.value, lastError: 'none', httpStatus: null }
  }

  function armFirstByteTimer(): void {
    if (firstByteTimer) clearTimeout(firstByteTimer)
    firstByteSeen = false
    firstByteTimer = setTimeout(() => {
      firstByteTimer = null
      // Ни одного БАЙТА (события/heartbeat) за N секунд — поток висит без
      // данных = признак буферизации прокси (REL-70). onopen — это заголовки
      // ответа, а не байты потока: его наличие таймер НЕ гасит (иначе
      // pending-случай Доп.8 — onopen есть, байтов нет — не диагностировался).
      // CLOSED-источник уже ушёл в scheduleReconnect — не дублируем.
      if (!firstByteSeen && eventSource && !isClosed(eventSource)) {
        noteError('no-first-byte')
      }
    }, FIRST_BYTE_TIMEOUT_MS)
  }

  function markFirstByte(): void {
    firstByteSeen = true
    if (firstByteTimer) {
      clearTimeout(firstByteTimer)
      firstByteTimer = null
    }
  }
  // WO-UI-26 Доп.5 (кр.21): роль вкладки. Лидер держит EventSource и
  // ретранслирует события остальным через BroadcastChannel; follower только
  // слушает ретрансляцию (своего EventSource не открывает — экономия слотов
  // HTTP/1.1: 1 соединение на браузер, а не на вкладку).
  let leadershipRelease: (() => void) | null = null
  let fanoutUnsubscribe: (() => void) | null = null
  let isLeaderTab = false

  function dispatch(envelope: EventEnvelope) {
    // Каждый стор сам фильтрует по типу в своём handleEvent — дублирования
    // switch здесь нет сознательно, маршрутизация живёт в одном месте.
    useProcessStore().handleEvent(envelope)
    useTaskStore().handleEvent(envelope)
    useIncidentStore().handleEvent(envelope)
    // WO-UI-25: шина для подписчиков с собственным фильтром (детальная
    // страница инстанса) — транспорт один, потребителей двое.
    publishRealtimeEvent(envelope)
  }

  /**
   * WO-UI-26 Доп.5: входящее событие от ЛИДЕРА (ретрансляция) либо от
   * собственного EventSource. Идемпотентность — по sequence на стороне
   * сторов/шины (Доп.4): дубль (лидер применил у себя + follower получил то
   * же) — no-op, порядок — по sequence.
   */
  function onFanoutMessage(msg: SseFanoutMessage) {
    if (msg.kind === 'event') {
      // Ретрансляция лидера: тот же dispatch, что у собственного EventSource.
      // Идемпотентность — по sequence/id на стороне сторов/шины (Доп.4):
      // лидер уже применил событие у себя, follower применяет у себя.
      if (msg.envelope.sequence !== undefined) {
        lastEventId.value = String(msg.envelope.sequence)
      }
      dispatch(msg.envelope)
    } else if (msg.kind === 'health') {
      if (msg.patch.isConnected !== undefined) isConnected.value = msg.patch.isConnected
      if (msg.patch.realtimeDown !== undefined) realtimeDown.value = msg.patch.realtimeDown
      if (msg.patch.sessionExpired !== undefined) sessionExpired.value = msg.patch.sessionExpired
      if (msg.patch.lastEventId !== undefined) lastEventId.value = msg.patch.lastEventId
      mirrorHealth({
        isConnected: isConnected.value,
        realtimeDown: realtimeDown.value,
        sessionExpired: sessionExpired.value,
      })
    }
    // 'leader' обрабатывается внутри sseFanout (переизбрание молча).
  }

  function onNamedEvent(event: Event) {
    const msg = event as MessageEvent<string>
    markFirstByte()
    // SSE id = серверный sequence-курсор (SseEventStreamService ставит
    // .id(sequence)); браузер перепошлёт его как Last-Event-ID при реконнекте.
    if (msg.lastEventId) {
      lastEventId.value = msg.lastEventId
      diagnostics.value = { ...diagnostics.value, lastEventId: msg.lastEventId }
    }
    try {
      const envelope = JSON.parse(msg.data) as EventEnvelope
      // WO-UI-26 Доп.5: лидер ретранслирует событие остальным вкладкам.
      publishFanout({ kind: 'event', envelope })
      if (lastEventId.value) {
        publishFanout({ kind: 'health', patch: { lastEventId: lastEventId.value } })
      }
      dispatch(envelope)
    } catch (e) {
      console.error('[RealtimeEvents] Failed to parse event data:', e)
    }
  }

  function openSource() {
    noteAttempt()
    const source = new EventSource(buildStreamUrl(), { withCredentials: true })
    for (const type of REALTIME_EVENT_TYPES) {
      source.addEventListener(type, onNamedEvent)
    }
    // Любой байт потока (событие ИЛИ heartbeat) гасит first-byte таймер.
    // Именованные типы покрыты выше; heartbeat без имени ловим через
    // message-фолбэк, если он есть у реализации.
    try {
      const anySource = source as EventSource & { onmessage?: ((e: Event) => void) | null }
      const prev = anySource.onmessage
      anySource.onmessage = (e: Event) => {
        markFirstByte()
        if (prev) prev(e)
      }
    } catch {
      // ignore — heartbeat без имени просто не гасит таймер раньше onopen
    }
    armFirstByteTimer()
    source.onopen = () => {
      // onopen = заголовки ответа, НЕ байты потока: first-byte таймер НЕ
      // гасим (Доп.8 — pending за буферизующим прокси: onopen есть, байтов
      // нет). Гасят его только события/heartbeat (markFirstByte выше).
      noteHealthy()
      isConnected.value = true
      error.value = null
      sessionExpired.value = false
      realtimeDown.value = false
      reconnectAttempts = 0
      // WO-UI-25: зеркало для подписчиков шины (индикатор + фолбэк-опрос).
      mirrorHealth({ isConnected: true, wasConnected: true, realtimeDown: false, sessionExpired: false })
      // WO-UI-26 Доп.5: здоровье — остальным вкладкам.
      publishFanout({
        kind: 'health',
        patch: { isConnected: true, wasConnected: true, realtimeDown: false, sessionExpired: false },
      })
    }
    // WO-UI-18: намеренно НЕ закрываем source на transient-ошибке — нативный
    // SSE-автореконнект (reconnectTime(3000) от сервера) сам поднимет
    // соединение и докачает пропущенное по Last-Event-ID.
    // WO-UI-22: но CLOSED — это НЕ transient: браузер сам больше не попробует
    // (401 с истёкшей кукой). Тогда пробуем refresh + пересоздание с backoff;
    // если refresh мёртв — честный sessionExpired вместо вечного «retrying».
    source.onerror = () => {
      isConnected.value = false
      mirrorHealth({ isConnected: false })
      // WO-UI-26 Доп.6 п.2: классифицируем обрыв для панели диагностики.
      // Точный HTTP-код браузер не отдаёт (CLOSED без деталей): различаем
      // «сеть» (transient, нативный автореконнект в полёте) и «закрыт»
      // (сервер/прокси закрыл или не-200 — идёт refresh+пересоздание).
      noteError('network')
      // WO-UI-26 Доп.5: обрыв видят и остальные вкладки.
      publishFanout({ kind: 'health', patch: { isConnected: false } })
      if (isClosed(source)) {
        scheduleReconnect()
      } else {
        error.value = 'Realtime connection lost, retrying…'
      }
    }
    eventSource = source
  }

  function isClosed(source: EventSource): boolean {
    // readyState может отсутствовать у моков — тогда это не CLOSED
    // (строгое сравнение типов: undefined === undefined дало бы true).
    return typeof source.readyState === 'number'
      && source.readyState === (EventSource as unknown as { CLOSED: number }).CLOSED
  }

  function scheduleReconnect() {
    if (reconnectAttempts >= SSE_RECONNECT_MAX_ATTEMPTS) {
      // WO-REL-57: refresh жив, а канал не поднялся — это НЕ expired-сессия
      // (вход не поможет и guard его отобьёт), а мёртвый realtime при живой
      // сессии. Честный сигнал вместо ложного "Session expired".
      if (lastRefreshOk === true) {
        markRealtimeDown()
      } else {
        markSessionExpired()
      }
      return
    }
    const delay = reconnectDelayMs(reconnectAttempts)
    reconnectAttempts += 1
    error.value = 'Realtime connection lost, retrying…'
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null
      void refreshAndReconnect()
    }, delay)
  }

  async function refreshAndReconnect() {
    // WO-QW-5 (NEW2-10): refresh через sharedRefresh — тот же single-flight
    // promise, что у axios-интерсептора. Раньше здесь был прямой `fetch`
    // мимо `isRefreshing`: одновременный 401-refresh от axios гонялся за ту
    // же ротируемую refresh-куку, проигравший получал «already rotated».
    // credentials те же (api-инстанс с withCredentials — кука приложится).
    const ok = await sharedRefresh(api)
    lastRefreshOk = ok
    if (ok) {
      error.value = null
      if (eventSource) {
        eventSource.close()
        eventSource = null
      }
      openSource()
    } else {
      scheduleReconnect()
    }
  }

  function markSessionExpired() {
    sessionExpired.value = true
    realtimeDown.value = false
    error.value = 'Session expired, please sign in again'
    mirrorHealth({ realtimeDown: false, sessionExpired: true })
  }

  // WO-REL-57: канал мёртв при живой сессии — честный сигнал + ручной retry
  // (счётчик попыток сброшен, следующий обрыв начнёт цикл заново).
  function markRealtimeDown() {
    realtimeDown.value = true
    sessionExpired.value = false
    error.value = 'Realtime unavailable, retrying…'
    mirrorHealth({ realtimeDown: true, sessionExpired: false })
  }

  function retryConnection() {
    reconnectAttempts = 0
    realtimeDown.value = false
    error.value = null
    mirrorHealth({ realtimeDown: false })
    if (eventSource) {
      eventSource.close()
      eventSource = null
    }
    openSource()
  }

  function connect() {
    if (eventSource || leadershipRelease || fanoutUnsubscribe) return
    if (typeof EventSource === 'undefined') {
      // jsdom и подобные среды без SSE: сторам просто не прилетают
      // live-события, страница работает на явных fetch как раньше.
      return
    }
    // WO-UI-26 Доп.5 (кр.21): сначала роль. Лидер открывает EventSource,
    // follower только подписывается на ретрансляцию (своего EventSource нет —
    // экономия слотов HTTP/1.1). Без Web Locks — каждая вкладка сама, как раньше.
    void acquireSseLeadership(() => {
      // Лок отобран (вкладка закрывается / dispose): чистимся как follower.
      isLeaderTab = false
    }).then(({ leader, release, onPromoted }) => {
      if (leader) {
        isLeaderTab = true
        leadershipRelease = release
        openSource()
      } else {
        isLeaderTab = false
        fanoutUnsubscribe = subscribeFanout(onFanoutMessage)
        // HOLD раунд 1 (Б-1): смерть лидера = браузер отдаёт лок следующему
        // в очереди = onPromoted: follower открывает СВОЙ EventSource
        // (ровно один новый; Last-Event-ID подхватит браузер сам) и
        // объявляет себя лидером. Без этого остальные вкладки протухали молча.
        onPromoted(() => {
          if (fanoutUnsubscribe) {
            fanoutUnsubscribe()
            fanoutUnsubscribe = null
          }
          isLeaderTab = true
          leadershipRelease = release
          openSource()
        })
      }
    })
  }

  function disconnect() {
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    if (firstByteTimer) {
      clearTimeout(firstByteTimer)
      firstByteTimer = null
    }
    reconnectAttempts = 0
    lastRefreshOk = null
    sessionExpired.value = false
    realtimeDown.value = false
    mirrorHealth({ isConnected: false, realtimeDown: false, sessionExpired: false })
    if (eventSource) {
      eventSource.close()
      eventSource = null
    }
    // WO-UI-26 Доп.5: снять лидерство/подписку вкладки.
    if (leadershipRelease) {
      try {
        leadershipRelease()
      } catch {
        // ignore
      }
      leadershipRelease = null
    }
    if (fanoutUnsubscribe) {
      fanoutUnsubscribe()
      fanoutUnsubscribe = null
    }
    isLeaderTab = false
    isConnected.value = false
  }

  onUnmounted(() => {
    disconnect()
  })

  return {
    isConnected,
    lastEventId,
    error,
    sessionExpired,
    realtimeDown,
    connect,
    disconnect,
    retryConnection,
    /** WO-UI-26 Доп.5: эта вкладка — лидер SSE (держит EventSource). */
    isLeaderTab: () => isLeaderTab,
    /** WO-UI-26 Доп.6 п.2: диагноз канала для панели по клику. */
    diagnostics,
    /** Только для тестов: форсировать диагноз (RED-контроль панели). */
    setChannelErrorForTest: (kind: ChannelErrorKind, httpStatus: number | null = null) =>
      noteError(kind, httpStatus),
  }
}
