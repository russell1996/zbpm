// @vitest-environment jsdom
/**
 * WO-UI-26 Доп.8 — «нет первого байта за N секунд» (признак буферизации
 * прокси, REL-70): соединение открыто, onopen был, но ни одного события/
 * heartbeat за 10 с → диагноз `no-first-byte` + подсказка про прокси.
 * Мутация M4 (убрать armFirstByteTimer) → первый тест красный.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import api from '@/services/api'
import { resetSseFanoutForTest } from '@/services/sseFanout'
import { useRealtimeEvents } from './useRealtimeEvents'

class FakeEventSource {
  static instances: FakeEventSource[] = []
  static CONNECTING = 0
  static OPEN = 1
  static CLOSED = 2
  listeners = new Map<string, Array<(e: Event) => void>>()
  onopen: ((e: Event) => void) | null = null
  onerror: ((e: Event) => void) | null = null
  onmessage: ((e: Event) => void) | null = null
  readyState = FakeEventSource.OPEN
  closed = false
  constructor(
    public url: string,
    public opts?: { withCredentials?: boolean },
  ) {
    FakeEventSource.instances.push(this)
  }
  addEventListener(type: string, fn: (e: Event) => void) {
    const arr = this.listeners.get(type) || []
    arr.push(fn)
    this.listeners.set(type, arr)
  }
  close() {
    this.closed = true
    this.readyState = FakeEventSource.CLOSED
  }
}

const originalAdapter = api.defaults.adapter

beforeEach(() => {
  vi.useFakeTimers()
  FakeEventSource.instances = []
  vi.stubGlobal('EventSource', FakeEventSource)
  resetSseFanoutForTest()
  Object.defineProperty(navigator, 'locks', {
    value: { request: (_n: string, _o: unknown, cb: () => Promise<void>) => cb() },
    configurable: true,
  })
  ;(api.defaults as Record<string, unknown>).adapter = vi.fn(async () => ({ data: {}, status: 200 }))
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
  ;(api.defaults as Record<string, unknown>).adapter = originalAdapter
  resetSseFanoutForTest()
})

describe('useRealtimeEvents no-first-byte (WO-UI-26 Доп.8)', () => {
  it('соединение без байтов 10 с → диагноз no-first-byte', async () => {
    const rt = useRealtimeEvents()
    rt.connect()
    await vi.advanceTimersByTimeAsync(0)
    expect(FakeEventSource.instances).toHaveLength(1)
    const src = FakeEventSource.instances[0]
    // Сервер ответил (onopen), но поток молчит — как за буферизующим прокси.
    src.onopen?.({} as Event)
    await vi.advanceTimersByTimeAsync(5000)
    expect(rt.diagnostics.value.lastError).toBe('none')
    await vi.advanceTimersByTimeAsync(6000)
    expect(rt.diagnostics.value.lastError).toBe('no-first-byte')
    rt.disconnect()
  })

  it('первое событие гасит таймер — диагноз остаётся none', async () => {
    const rt = useRealtimeEvents()
    rt.connect()
    await vi.advanceTimersByTimeAsync(0)
    const src = FakeEventSource.instances[0]
    src.onopen?.({} as Event)
    // heartbeat/событие на 3-й секунде — поток живой.
    await vi.advanceTimersByTimeAsync(3000)
    src.onmessage?.({} as Event)
    await vi.advanceTimersByTimeAsync(20000)
    expect(rt.diagnostics.value.lastError).toBe('none')
    rt.disconnect()
  })
})
