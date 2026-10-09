import type { EventEnvelope } from '@/types/api'

/**
 * WO-UI-25 — внутрипроцессная шина realtime-событий.
 *
 * SSE-соединение одно (MainLayout → useRealtimeEvents), а потребителей после
 * WO-UI-25 двое: сторы (списки, через handleEvent) и детальная страница
 * инстанса (useInstanceLiveUpdates, фильтр по processInstanceId). Шина
 * развязывает транспорт и подписчиков: useRealtimeEvents публикует сюда каждый
 * разобранный envelope, подписчики фильтруют сами и отписываются функцией.
 *
 * Своя реализация вместо mitt (G17 — без новых npm-зависимостей, 20 строк).
 */
export type RealtimeListener = (envelope: EventEnvelope) => void

const listeners = new Set<RealtimeListener>()

export function publishRealtimeEvent(envelope: EventEnvelope): void {
  for (const fn of [...listeners]) {
    try {
      fn(envelope)
    } catch (e) {
      console.error('[RealtimeBus] listener failed:', e)
    }
  }
}

export function subscribeRealtimeEvents(fn: RealtimeListener): () => void {
  listeners.add(fn)
  return () => {
    listeners.delete(fn)
  }
}

/** Только для тестов: сброс подписчиков между кейсами (прод-код не зовёт). */
export function resetRealtimeBusForTest(): void {
  listeners.clear()
}
