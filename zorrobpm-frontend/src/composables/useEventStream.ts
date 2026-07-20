import { ref, onUnmounted } from 'vue'
import type { EventEnvelope } from '@/types/api'

/**
 * Composable for SSE event stream (ADR-7, WO-EVT-5).
 * Provides auto-reconnect, Last-Event-ID tracking, and event callback.
 */
export function useEventStream(
  options: {
    type?: string
    processInstanceId?: string
    processDefinitionKey?: string
    onEvent?: (event: EventEnvelope) => void
    onError?: (error: Event) => void
  } = {}
) {
  const isConnected = ref(false)
  const lastEventId = ref<string | null>(null)
  const error = ref<string | null>(null)

  let eventSource: EventSource | null = null
  let reconnectTimeout: ReturnType<typeof setTimeout> | null = null
  let reconnectAttempts = 0
  const MAX_RECONNECT_DELAY = 30000

  function buildUrl(): string {
    const params = new URLSearchParams()
    if (options.type) params.set('type', options.type)
    if (options.processInstanceId) params.set('processInstanceId', options.processInstanceId)
    if (options.processDefinitionKey) params.set('processDefinitionKey', options.processDefinitionKey)
    if (lastEventId.value) params.set('since', lastEventId.value)

    const qs = params.toString()
    return `/api/events/stream${qs ? '?' + qs : ''}`
  }

  function connect() {
    if (eventSource) {
      eventSource.close()
    }

    const url = buildUrl()
    eventSource = new EventSource(url, { withCredentials: true })

    eventSource.onopen = () => {
      isConnected.value = true
      error.value = null
      reconnectAttempts = 0
    }

    eventSource.onmessage = (event: MessageEvent) => {
      try {
        const envelope: EventEnvelope = JSON.parse(event.data)
        if (envelope.sequence) {
          lastEventId.value = String(envelope.sequence)
        }
        if (options.onEvent) {
          options.onEvent(envelope)
        }
      } catch (e) {
        console.error('[EventStream] Failed to parse event:', e)
      }
    }

    eventSource.onerror = (event) => {
      isConnected.value = false
      error.value = 'Connection lost'
      if (options.onError) {
        options.onError(event)
      }
      scheduleReconnect()
    }
  }

  function scheduleReconnect() {
    if (reconnectTimeout) {
      clearTimeout(reconnectTimeout)
    }
    const delay = Math.min(1000 * Math.pow(2, reconnectAttempts), MAX_RECONNECT_DELAY)
    reconnectAttempts++
    reconnectTimeout = setTimeout(() => {
      connect()
    }, delay)
  }

  function disconnect() {
    if (reconnectTimeout) {
      clearTimeout(reconnectTimeout)
      reconnectTimeout = null
    }
    if (eventSource) {
      eventSource.close()
      eventSource = null
    }
    isConnected.value = false
  }

  onUnmounted(() => {
    disconnect()
  })

  return {
    isConnected,
    lastEventId,
    error,
    connect,
    disconnect
  }
}
