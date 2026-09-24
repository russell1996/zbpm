import { ref, onUnmounted } from 'vue'
import type { EventEnvelope } from '@/types/api'
import { useProcessStore } from '@/stores/process'
import { useTaskStore } from '@/stores/task'
import { useIncidentStore } from '@/stores/incident'

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
 * Задача клиента — не мешать: не закрывать EventSource на `onerror` и хранить
 * последний id для видимости состояния.
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

export function useRealtimeEvents() {
  const isConnected = ref(false)
  const lastEventId = ref<string | null>(null)
  const error = ref<string | null>(null)

  let eventSource: EventSource | null = null

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

  function connect() {
    if (eventSource) return
    if (typeof EventSource === 'undefined') {
      // jsdom и подобные среды без SSE: сторам просто не прилетают
      // live-события, страница работает на явных fetch как раньше.
      return
    }
    const source = new EventSource(buildStreamUrl(), { withCredentials: true })
    for (const type of REALTIME_EVENT_TYPES) {
      source.addEventListener(type, onNamedEvent)
    }
    source.onopen = () => {
      isConnected.value = true
      error.value = null
    }
    // Намеренно НЕ закрываем source здесь: нативный SSE-автореконнект
    // (reconnectTime(3000) от сервера) сам поднимет соединение и докачает
    // пропущенное по Last-Event-ID. Закрытие убило бы критерий 3.
    source.onerror = () => {
      isConnected.value = false
      error.value = 'Realtime connection lost, retrying…'
    }
    eventSource = source
  }

  function disconnect() {
    if (eventSource) {
      eventSource.close()
      eventSource = null
    }
    isConnected.value = false
  }

  onUnmounted(() => {
    disconnect()
  })

  return { isConnected, lastEventId, error, connect, disconnect }
}
