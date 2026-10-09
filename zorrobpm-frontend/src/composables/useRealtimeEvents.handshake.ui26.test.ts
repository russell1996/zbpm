// @vitest-environment jsdom
/**
 * WO-UI-26 Б-2 — реальные диагнозы handshake для панели (критерий 23).
 *
 * Было: noteError живьём вызывался только с 'network'/'no-first-byte',
 * веток http-401/403/429 не было (только setChannelErrorForTest).
 * Стало: при CLOSED-обрыве идёт handshake-probe (fetch того же URL с той же
 * кукой, читаем только статус): 401/403/429/http-other/timeout — живые пути;
 * markSessionExpired ставит http-401 (не затирая более специфичный).
 *
 * Живой тест на КАЖДЫЙ код — мок-сервер (fetch-стаб) отвечает 401/403/429/
 * 500/вешает таймаут/сеть падает; без setChannelErrorForTest.
 * Мутация «убрать ветку 429» → 429-кейс красный.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import api from '@/services/api'
import { resetSseFanoutForTest } from '@/services/sseFanout'
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
const originalFetch = globalThis.fetch

function stubLocksImmediate() {
  Object.defineProperty(navigator, 'locks', {
    value: { request: (_n: string, _o: unknown, cb: () => Promise<void>) => cb() },
    configurable: true,
  })
}

/** Мок-сервер handshake: отвечает статусом, тело не отдаёт (как SSE-handshake). */
function stubHandshake(status: number) {
  vi.stubGlobal('fetch', vi.fn(async () => ({
    status,
    body: { cancel: vi.fn().mockResolvedValue(undefined) },
  })))
}

/** Мок-сервер: сеть падает (TypeError, как при обрыве соединения). */
function stubHandshakeNetworkError() {
  vi.stubGlobal('fetch', vi.fn(async () => {
    throw new TypeError('Failed to fetch')
  }))
}

/** Мок-сервер: висит без байтов — probe рвёт по своему таймауту (AbortError). */
function stubHandshakeHang() {
  vi.stubGlobal('fetch', vi.fn(async (_url: unknown, init?: { signal?: AbortSignal }) => {
    await new Promise<void>((_resolve, reject) => {
      init?.signal?.addEventListener('abort', () => {
        reject(new DOMException('This operation was aborted', 'AbortError'))
      })
    })
    throw new Error('unreachable')
  }))
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.useFakeTimers()
  FakeEventSource.instances = []
  vi.stubGlobal('EventSource', FakeEventSource)
  resetSseFanoutForTest()
  stubLocksImmediate()
  ;(api.defaults as Record<string, unknown>).adapter = vi.fn(async () => ({ data: {}, status: 200 }))
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
  ;(api.defaults as Record<string, unknown>).adapter = originalAdapter
  globalThis.fetch = originalFetch
  resetSseFanoutForTest()
})

describe('useRealtimeEvents handshake-диагнозы (WO-UI-26 Б-2)', () => {
  it('probe 401 → диагноз http-401 (живой путь, без setChannelErrorForTest)', async () => {
    stubHandshake(401)
    const rt = useRealtimeEvents()
    rt.connect()
    await vi.advanceTimersByTimeAsync(0)
    const src = FakeEventSource.instances[0]
    src.readyState = FakeEventSource.CLOSED
    src.onerror?.({} as Event)
    await vi.advanceTimersByTimeAsync(0)
    await vi.advanceTimersByTimeAsync(0)
    expect(rt.diagnostics.value.lastError).toBe('http-401')
    expect(rt.diagnostics.value.httpStatus).toBe(401)
    rt.disconnect()
  })

  it('probe 403 → диагноз http-403', async () => {
    stubHandshake(403)
    const rt = useRealtimeEvents()
    rt.connect()
    await vi.advanceTimersByTimeAsync(0)
    const src = FakeEventSource.instances[FakeEventSource.instances.length - 1]
    src.readyState = FakeEventSource.CLOSED
    src.onerror?.({} as Event)
    await vi.advanceTimersByTimeAsync(0)
    await vi.advanceTimersByTimeAsync(0)
    expect(rt.diagnostics.value.lastError).toBe('http-403')
    expect(rt.diagnostics.value.httpStatus).toBe(403)
    rt.disconnect()
  })

  it('probe 429 (maxClients) → диагноз http-429', async () => {
    stubHandshake(429)
    const rt = useRealtimeEvents()
    rt.connect()
    await vi.advanceTimersByTimeAsync(0)
    const src = FakeEventSource.instances[FakeEventSource.instances.length - 1]
    src.readyState = FakeEventSource.CLOSED
    src.onerror?.({} as Event)
    await vi.advanceTimersByTimeAsync(0)
    await vi.advanceTimersByTimeAsync(0)
    expect(rt.diagnostics.value.lastError).toBe('http-429')
    expect(rt.diagnostics.value.httpStatus).toBe(429)
    rt.disconnect()
  })

  it('probe 500 → диагноз http-other со статусом', async () => {
    stubHandshake(500)
    const rt = useRealtimeEvents()
    rt.connect()
    await vi.advanceTimersByTimeAsync(0)
    const src = FakeEventSource.instances[FakeEventSource.instances.length - 1]
    src.readyState = FakeEventSource.CLOSED
    src.onerror?.({} as Event)
    await vi.advanceTimersByTimeAsync(0)
    await vi.advanceTimersByTimeAsync(0)
    expect(rt.diagnostics.value.lastError).toBe('http-other')
    expect(rt.diagnostics.value.httpStatus).toBe(500)
    rt.disconnect()
  })

  it('probe висит → таймаут 5 с → диагноз timeout', async () => {
    stubHandshakeHang()
    const rt = useRealtimeEvents()
    rt.connect()
    await vi.advanceTimersByTimeAsync(0)
    const src = FakeEventSource.instances[FakeEventSource.instances.length - 1]
    src.readyState = FakeEventSource.CLOSED
    src.onerror?.({} as Event)
    // Сразу после обрыва — ещё 'network' (probe в полёте).
    expect(rt.diagnostics.value.lastError).toBe('network')
    await vi.advanceTimersByTimeAsync(5000)
    await vi.advanceTimersByTimeAsync(0)
    expect(rt.diagnostics.value.lastError).toBe('timeout')
    rt.disconnect()
  })

  it('probe падает сетью → остаётся network', async () => {
    stubHandshakeNetworkError()
    const rt = useRealtimeEvents()
    rt.connect()
    await vi.advanceTimersByTimeAsync(0)
    const src = FakeEventSource.instances[FakeEventSource.instances.length - 1]
    src.readyState = FakeEventSource.CLOSED
    src.onerror?.({} as Event)
    await vi.advanceTimersByTimeAsync(0)
    await vi.advanceTimersByTimeAsync(0)
    expect(rt.diagnostics.value.lastError).toBe('network')
    rt.disconnect()
  })

  it('probe 200 (рукопожатие живо) → остаётся network, не затирает', async () => {
    stubHandshake(200)
    const rt = useRealtimeEvents()
    rt.connect()
    await vi.advanceTimersByTimeAsync(0)
    const src = FakeEventSource.instances[FakeEventSource.instances.length - 1]
    src.readyState = FakeEventSource.CLOSED
    src.onerror?.({} as Event)
    await vi.advanceTimersByTimeAsync(0)
    await vi.advanceTimersByTimeAsync(0)
    expect(rt.diagnostics.value.lastError).toBe('network')
    expect(rt.diagnostics.value.httpStatus).toBeNull()
    rt.disconnect()
  })

  it('исчерпание попыток при мёртвом refresh → sessionExpired + http-401', async () => {
    // Refresh мёртв (сессия истекла): каждая попытка reconnnect — refresh fail.
    ;(api.defaults as Record<string, unknown>).adapter = vi.fn(async () => {
      throw new Error('refresh failed')
    })
    stubHandshakeNetworkError()
    const rt = useRealtimeEvents()
    rt.connect()
    await vi.advanceTimersByTimeAsync(0)
    const src = FakeEventSource.instances[FakeEventSource.instances.length - 1]
    src.readyState = FakeEventSource.CLOSED
    src.onerror?.({} as Event)
    // 5 попыток: задержки 1с, 2с, 4с, 8с, 16с + шестое расписание → expired.
    await vi.advanceTimersByTimeAsync(1000)
    await vi.advanceTimersByTimeAsync(2000)
    await vi.advanceTimersByTimeAsync(4000)
    await vi.advanceTimersByTimeAsync(8000)
    await vi.advanceTimersByTimeAsync(16000)
    await vi.advanceTimersByTimeAsync(32000)
    await vi.advanceTimersByTimeAsync(0)
    expect(rt.sessionExpired.value).toBe(true)
    expect(rt.diagnostics.value.lastError).toBe('http-401')
    expect(rt.diagnostics.value.httpStatus).toBe(401)
    rt.disconnect()
  })
})
