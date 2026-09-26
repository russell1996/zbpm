// @vitest-environment jsdom
/**
 * WO-UI-22 (NEW-09) — SPA-realtime умирал навсегда после истечения JWT:
 * нативный EventSource переподключается только после сетевых ошибок, а 401
 * (истёкшая __Host-zbpm_token) переводит его в CLOSED навсегда; onerror
 * говорил «retrying…», хотя никакого повтора не было.
 *
 * Проверяет на FakeEventSource с readyState (CLOSED=2, как у настоящего):
 *  - CLOSED → POST /auth/refresh → успех → новый EventSource создан;
 *  - refresh провал → правдивое сообщение + sessionExpired, без пересоздания;
 *  - постоянный 401 → backoff: число refresh-вызовов ограничено;
 *  - не-CLOSED ошибка → старое поведение (без refresh, канал жив).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import axios from 'axios'
import api from '@/services/api'
import { useRealtimeEvents } from './useRealtimeEvents'
import * as processService from '@/services/processService'
import * as instanceService from '@/services/instanceService'
import * as variableService from '@/services/variableService'
import * as taskService from '@/services/taskService'
import * as incidentService from '@/services/incidentService'

vi.mock('@/services/processService', () => ({
  getProcessDefinitions: vi.fn().mockResolvedValue({ data: [], totalElements: 0 }),
  getProcessDefinition: vi.fn().mockResolvedValue(null),
  getProcessDefinitionStructure: vi.fn().mockResolvedValue(null),
  getProcessDefinitionVersions: vi.fn().mockResolvedValue([]),
}))
vi.mock('@/services/instanceService', () => ({
  getProcessInstances: vi.fn().mockResolvedValue({ data: [], totalElements: 0 }),
  getProcessInstance: vi.fn().mockResolvedValue(null),
  getProcessInstanceActivities: vi.fn().mockResolvedValue([]),
  getProcessInstanceActivitiesPaged: vi.fn().mockResolvedValue({ data: [], totalElements: 0 }),
  startProcessInstance: vi.fn(),
}))
vi.mock('@/services/variableService', () => ({
  getVariables: vi.fn().mockResolvedValue({ data: [] }),
}))
vi.mock('@/services/taskService', () => ({
  getUserTasks: vi.fn().mockResolvedValue({ data: [], totalElements: 0 }),
  getUserTask: vi.fn().mockResolvedValue(null),
  completeUserTask: vi.fn(),
  getServiceTasks: vi.fn().mockResolvedValue({ data: [], totalElements: 0 }),
  getServiceTask: vi.fn().mockResolvedValue(null),
  completeServiceTask: vi.fn(),
}))
vi.mock('@/services/incidentService', () => ({
  getIncidents: vi.fn().mockResolvedValue({ data: [], totalElements: 0 }),
  getIncident: vi.fn().mockResolvedValue(null),
  resolveIncident: vi.fn(),
}))

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

describe('useRealtimeEvents reconnect after JWT expiry (WO-UI-22)', () => {
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

  function mockRefreshOk() {
    // WO-QW-5: refresh идёт через axios sharedRefresh на api-инстансе
    // (single-flight с axios-интерсептором), не прямым fetch — мокаем
    // адаптер api-инстанса (дефолтный axios здесь ни при чём).
    const adapter = vi.fn(async (cfg: { url?: string }) => {
      if (cfg.url === '/auth/refresh') return { data: {}, status: 200 }
      return { data: {}, status: 200 }
    })
    ;(api.defaults as Record<string, unknown>).adapter = adapter
    return adapter
  }

  function mockRefreshFail() {
    const adapter = vi.fn(async (cfg: { url?: string }) => {
      const error = new axios.AxiosError('Request failed with status code 401')
      error.config = { url: cfg.url || '/x', method: 'GET', headers: new axios.AxiosHeaders() } as never
      error.response = {
        data: null, status: 401, statusText: 'Unauthorized',
        headers: new axios.AxiosHeaders(), config: error.config,
      }
      throw error
    })
    ;(api.defaults as Record<string, unknown>).adapter = adapter
    return adapter
  }

  it('criterion 1: CLOSED → refresh → new EventSource with expected params', async () => {
    vi.useFakeTimers()
    const adapter = mockRefreshOk()
    const rt = useRealtimeEvents()
    rt.connect()
    expect(FakeEventSource.instances).toHaveLength(1)
    const first = FakeEventSource.instances[0]
    const url = first.url
    first.readyState = FakeEventSource.CLOSED
    first.onerror?.({} as Event)
    // backoff-таймер первого повтора: прокручиваем
    await vi.runAllTimersAsync()
    await Promise.resolve()
    expect(adapter.mock.calls.filter(([cfg]) => (cfg as { url?: string }).url === '/auth/refresh'))
      .toHaveLength(1)
    expect(FakeEventSource.instances).toHaveLength(2)
    expect(FakeEventSource.instances[1].url).toBe(url)
    expect(first.closed).toBe(true)
    expect(rt.error.value).toBeNull()
    rt.disconnect()
  })

  it('criterion 3: refresh failure → truthful message, no endless retrying', async () => {
    vi.useFakeTimers()
    mockRefreshFail()
    const rt = useRealtimeEvents()
    rt.connect()
    const first = FakeEventSource.instances[0]
    first.readyState = FakeEventSource.CLOSED
    first.onerror?.({} as Event)
    await vi.runAllTimersAsync()
    await Promise.resolve()
    // refresh не удался: сессия мертва целиком — честный флаг + сообщение,
    // нового источника нет (не врём «retrying»).
    expect(rt.sessionExpired.value).toBe(true)
    expect(rt.error.value).toMatch(/session|expired|sign in|login|войдите|сессия/i)
    expect(FakeEventSource.instances).toHaveLength(1)
    rt.disconnect()
  })

  it('criterion 4: persistent 401 does not hammer /auth/refresh (bounded attempts)', async () => {
    vi.useFakeTimers()
    const adapter = mockRefreshFail()
    const rt = useRealtimeEvents()
    rt.connect()
    // Каждая неудача переводит новый source в CLOSED и снова стреляет onerror:
    // эмулируем 10 подряд идущих разрывов.
    for (let i = 0; i < 10; i++) {
      const cur = FakeEventSource.instances[FakeEventSource.instances.length - 1]
      cur.readyState = FakeEventSource.CLOSED
      cur.onerror?.({} as Event)
      await vi.runAllTimersAsync()
      await Promise.resolve()
    }
    // Попыток refresh — не больше капа, а не 10 (по одной на разрыв).
    const refreshCalls = adapter.mock.calls.filter(
      ([cfg]) => (cfg as { url?: string }).url === '/auth/refresh',
    ).length
    expect(refreshCalls).toBeLessThanOrEqual(5)
    expect(rt.sessionExpired.value).toBe(true)
    rt.disconnect()
  })

  it('non-CLOSED error keeps legacy behaviour (no refresh, channel alive)', async () => {
    const adapter = mockRefreshOk()
    const rt = useRealtimeEvents()
    rt.connect()
    const src = FakeEventSource.instances[0]
    src.readyState = FakeEventSource.OPEN
    src.onerror?.({} as Event)
    await Promise.resolve()
    expect(adapter).not.toHaveBeenCalled()
    expect(src.closed).toBe(false)
    expect(rt.error.value).toBeTruthy()
    rt.disconnect()
  })
})
