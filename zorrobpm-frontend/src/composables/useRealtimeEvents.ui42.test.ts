// @vitest-environment jsdom
/**
 * WO-UI-42 п.3 — «retryConnection — висящий таймер».
 *
 * Находка: ручной retry (кнопка в панели диагностики канала) НЕ отменял уже
 * запланированный backoff-таймер. Висящий таймер срабатывал на живом канале:
 * refreshAndReconnect() закрывал только что открытый источник и открывал новый
 * (лишний /auth/refresh + рёв EventSource на живом канале).
 * Вторая половина: чистка таймеров висела на onUnmounted — вне компонента
 * (effect-scope/тест) она не срабатывала вовсе.
 *
 * Проверка — на фейковых таймерах: без них backoff (1 с) не наступит.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { effectScope } from 'vue'
import { setActivePinia, createPinia } from 'pinia'
import axios from 'axios'
import api from '@/services/api'
import { useRealtimeEvents } from './useRealtimeEvents'

type Listener = (e: Event) => void

class FakeEventSource {
  static instances: FakeEventSource[] = []
  static CONNECTING = 0
  static OPEN = 1
  static CLOSED = 2
  listeners = new Map<string, Listener[]>()
  onopen: ((e: Event) => void) | null = null
  onerror: ((e: Event) => void) | null = null
  readyState = FakeEventSource.CONNECTING
  closed = false
  url: string
  constructor(url: string) {
    this.url = url
    FakeEventSource.instances.push(this)
  }
  addEventListener(type: string, fn: Listener) {
    const arr = this.listeners.get(type) || []
    arr.push(fn)
    this.listeners.set(type, arr)
  }
  close() {
    this.closed = true
    this.readyState = FakeEventSource.CLOSED
  }
}

/** Первый connect — сразу лидер (в jsdom Web Locks нет). */
function stubLeaderLocks() {
  const locks = {
    request: (_name: string, _opts: unknown, cb: () => Promise<void>) => cb(),
  }
  Object.defineProperty(navigator, 'locks', { value: locks, configurable: true })
}

function mockRefreshOk() {
  const adapter = vi.fn(async (cfg: { url?: string }) => {
    if (cfg.url === '/auth/refresh') return { data: {}, status: 200 }
    return { data: {}, status: 200 }
  })
  ;(api.defaults as Record<string, unknown>).adapter = adapter
  return adapter
}

function refreshCalls(adapter: ReturnType<typeof mockRefreshOk>): number {
  return adapter.mock.calls.filter(
    ([cfg]) => (cfg as { url?: string }).url === '/auth/refresh',
  ).length
}

describe('WO-UI-42 п.3: retryConnection отменяет висящий backoff-таймер', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    FakeEventSource.instances = []
    vi.stubGlobal('EventSource', FakeEventSource)
    vi.stubGlobal('fetch', vi.fn())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.useRealTimers()
  })

  it('после ручного retry висящего таймера нет: живой канал не рвётся фантомным refresh', async () => {
    vi.useFakeTimers()
    const adapter = mockRefreshOk()
    stubLeaderLocks()
    const rt = useRealtimeEvents()
    rt.connect()
    await vi.waitFor(() => expect(FakeEventSource.instances).toHaveLength(1))

    // Обрыв: CLOSED → scheduleReconnect() вооружает backoff-таймер (1 с).
    const first = FakeEventSource.instances[0]
    first.readyState = FakeEventSource.CLOSED
    first.onerror?.({} as Event)

    // Пользователь жмёт «Повторить» ДО срабатывания backoff.
    rt.retryConnection()
    expect(FakeEventSource.instances, 'ручной retry открыл новый источник').toHaveLength(2)
    const live = FakeEventSource.instances[1]
    expect(live.closed).toBe(false)

    // Время идёт — висящего таймера быть не должно.
    await vi.runAllTimersAsync()
    await Promise.resolve()
    await Promise.resolve()

    expect(FakeEventSource.instances, 'фантомного переподключения нет').toHaveLength(2)
    expect(live.closed, 'живой источник не закрыт').toBe(false)
    expect(refreshCalls(adapter), 'лишнего /auth/refresh нет').toBe(0)
    rt.disconnect()
  })

  it('ручной retry отменяет ВСЕ висящие backoff-таймеры (не только последний)', async () => {
    vi.useFakeTimers()
    const adapter = mockRefreshOk()
    stubLeaderLocks()
    const rt = useRealtimeEvents()
    rt.connect()
    await vi.waitFor(() => expect(FakeEventSource.instances).toHaveLength(1))

    // Два обрыва подряд → ДВА висящих backoff-таймера (handle'ы терялись:
    // хранился только последний, предыдущий отменить было нечем).
    const first = FakeEventSource.instances[0]
    first.readyState = FakeEventSource.CLOSED
    first.onerror?.({} as Event)
    first.onerror?.({} as Event)

    // Пользователь жмёт «Повторить» ДО срабатывания backoff'ов.
    rt.retryConnection()
    expect(FakeEventSource.instances, 'ручной retry открыл новый источник').toHaveLength(2)
    const live = FakeEventSource.instances[1]
    expect(live.closed).toBe(false)

    // Время идёт — висящих таймеров быть не должно (ни одного).
    await vi.runAllTimersAsync()
    await Promise.resolve()
    await Promise.resolve()

    expect(FakeEventSource.instances, 'фантомного переподключения нет').toHaveLength(2)
    expect(live.closed, 'живой источник не закрыт').toBe(false)
    expect(refreshCalls(adapter), 'лишнего /auth/refresh нет').toBe(0)
    rt.disconnect()
  })

  it('остановка scope гасит висящие таймеры (чистка по scope, а не только по unmount)', async () => {
    vi.useFakeTimers()
    const adapter = mockRefreshOk()
    stubLeaderLocks()
    const scope = effectScope()
    const rt = scope.run(() => useRealtimeEvents())!
    rt.connect()
    await vi.waitFor(() => expect(FakeEventSource.instances).toHaveLength(1))

    // Обрыв → backoff-таймер вооружён.
    const first = FakeEventSource.instances[0]
    first.readyState = FakeEventSource.CLOSED
    first.onerror?.({} as Event)

    // Scope остановлен (эффект-скоуп БЕЗ компонента: onUnmounted бы не сработал).
    scope.stop()

    await vi.runAllTimersAsync()
    await Promise.resolve()
    await Promise.resolve()

    expect(refreshCalls(adapter), 'после остановки scope refresh не пошёл').toBe(0)
    expect(FakeEventSource.instances, 'новый источник после остановки scope не открыт').toHaveLength(1)
  })

  it('контроль: при отказе refresh цепочка ЧЕСТНО продолжается (ассерты выше не пустые)', async () => {
    // Контрольный кейс: тесты выше не «зелёные всегда» — при отказе refresh
    // цепочка продолжается до капа и кончается sessionExpired (поведение не
    // менялось). Тем самым ассерт «refreshCalls === 0» выше — не пустой.
    vi.useFakeTimers()
    const adapter = vi.fn(async () => {
      const error = new axios.AxiosError('Request failed with status code 401')
      error.config = { url: '/auth/refresh', method: 'GET', headers: new axios.AxiosHeaders() } as never
      error.response = {
        data: null, status: 401, statusText: 'Unauthorized',
        headers: new axios.AxiosHeaders(), config: error.config,
      }
      throw error
    })
    ;(api.defaults as Record<string, unknown>).adapter = adapter
    stubLeaderLocks()
    const rt = useRealtimeEvents()
    rt.connect()
    await vi.waitFor(() => expect(FakeEventSource.instances).toHaveLength(1))
    const first = FakeEventSource.instances[0]
    first.readyState = FakeEventSource.CLOSED
    first.onerror?.({} as Event)
    await vi.runAllTimersAsync()
    await Promise.resolve()
    expect(refreshCalls(adapter as unknown as ReturnType<typeof mockRefreshOk>)).toBeGreaterThanOrEqual(1)
    expect(rt.sessionExpired.value, 'цепочка дошла до честного sessionExpired').toBe(true)
    rt.disconnect()
  })
})
