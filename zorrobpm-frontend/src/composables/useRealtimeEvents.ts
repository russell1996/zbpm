import { ref, onUnmounted } from 'vue'
import type { EventEnvelope } from '@/types/api'
import { useProcessStore } from '@/stores/process'
import { useTaskStore } from '@/stores/task'
import { useIncidentStore } from '@/stores/incident'
import api from '@/services/api'
import { sharedRefresh } from '@/services/refreshInterceptor'

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
export const REALTIME_EVENT_TYPES = [
  'process-instance.started',
  'process-instance.completed',
  'process-instance.cancelled',
  'user-task.created',
  'user-task.completed',
  'service-task.created',
  'incident.raised',
  'incident.resolved',
] as const

// Тот же baseURL-резолвинг, что в services/api.ts: EventSource обязан идти на
// тот же origin/base, иначе cookie-auth не приложится.
export function buildStreamUrl(): string {
  const base = import.meta.env.VITE_API_URL || '/api'
  return `${base}/events/stream`
}

// WO-UI-22: refresh шёл на тот же base напрямую fetch'ем; WO-QW-5 перевёл
// путь на sharedRefresh (тот же origin/кука через api-инстанс). Хелпер
// оставлен для совместимости тестов/вызывающих — URL тот же.
// origin, credentials:include обязателен — иначе браузер не приложит куки).
export function buildRefreshUrl(): string {
  const base = import.meta.env.VITE_API_URL || '/api'
  return `${base}/auth/refresh`
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

export function useRealtimeEvents() {
  const isConnected = ref(false)
  const lastEventId = ref<string | null>(null)
  const error = ref<string | null>(null)
  // WO-UI-22: сессия истекла целиком (refresh тоже не удался) — realtime
  // восстановить нельзя, нужен вход. Отличается от transient-обрыва, после
  // которого нативный автореконнект жив.
  const sessionExpired = ref(false)

  let eventSource: EventSource | null = null
  let reconnectAttempts = 0
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null

  function dispatch(envelope: EventEnvelope) {
    // Каждый стор сам фильтрует по типу в своём handleEvent — дублирования
    // switch здесь нет сознательно, маршрутизация живёт в одном месте.
    useProcessStore().handleEvent(envelope)
    useTaskStore().handleEvent(envelope)
    useIncidentStore().handleEvent(envelope)
  }

  function onNamedEvent(event: Event) {
    const msg = event as MessageEvent<string>
    // SSE id = серверный sequence-курсор (SseEventStreamService ставит
    // .id(sequence)); браузер перепошлёт его как Last-Event-ID при реконнекте.
    if (msg.lastEventId) {
      lastEventId.value = msg.lastEventId
    }
    try {
      const envelope = JSON.parse(msg.data) as EventEnvelope
      dispatch(envelope)
    } catch (e) {
      console.error('[RealtimeEvents] Failed to parse event data:', e)
    }
  }

  function openSource() {
    const source = new EventSource(buildStreamUrl(), { withCredentials: true })
    for (const type of REALTIME_EVENT_TYPES) {
      source.addEventListener(type, onNamedEvent)
    }
    source.onopen = () => {
      isConnected.value = true
      error.value = null
      sessionExpired.value = false
      reconnectAttempts = 0
    }
    // WO-UI-18: намеренно НЕ закрываем source на transient-ошибке — нативный
    // SSE-автореконнект (reconnectTime(3000) от сервера) сам поднимет
    // соединение и докачает пропущенное по Last-Event-ID.
    // WO-UI-22: но CLOSED — это НЕ transient: браузер сам больше не попробует
    // (401 с истёкшей кукой). Тогда пробуем refresh + пересоздание с backoff;
    // если refresh мёртв — честный sessionExpired вместо вечного «retrying».
    source.onerror = () => {
      isConnected.value = false
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
      markSessionExpired()
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
    error.value = 'Session expired, please sign in again'
  }

  function connect() {
    if (eventSource) return
    if (typeof EventSource === 'undefined') {
      // jsdom и подобные среды без SSE: сторам просто не прилетают
      // live-события, страница работает на явных fetch как раньше.
      return
    }
    openSource()
  }

  function disconnect() {
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    reconnectAttempts = 0
    sessionExpired.value = false
    if (eventSource) {
      eventSource.close()
      eventSource = null
    }
    isConnected.value = false
  }

  onUnmounted(() => {
    disconnect()
  })

  return { isConnected, lastEventId, error, sessionExpired, connect, disconnect }
}
