// @vitest-environment jsdom
/**
 * WO-REL-57 (критерий 4): гипотеза "плашка не исчезает" ПОДТВЕРЖДЕНА.
 *
 * Живой прод-репорт 2026-09-27: `GET /events/stream → 429`, JWT-сессия при
 * этом жива. Цепочка по коду (не гипотеза):
 *  1. 429 → EventSource CLOSED навсегда → onerror → scheduleReconnect →
 *     refreshAndReconnect → sharedRefresh проходит (сессия жива!) →
 *     openSource → снова 429 → … цикл;
 *  2. после 5 попыток старый код звал markSessionExpired() с текстом
 *     "Session expired" — враньё: вход не нужен;
 *  3. клик "Войти" → router-guard (`to.name === 'login' &&
 *     auth.isAuthenticated → dashboard`) отбивает обратно — тот же
 *     MainLayout, тот же composable-инстанс, sessionExpired не сброшен
 *     (сбрасывался только в onopen/disconnect) → плашка не исчезает.
 *
 * Фикс: refresh жив, а канал не поднялся → отдельный флаг realtimeDown +
 * честный текст + ручной retry вместо ложного sessionExpired.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
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

describe('WO-REL-57: live session + dead realtime channel', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    FakeEventSource.instances = []
    vi.stubGlobal('EventSource', FakeEventSource)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.useRealTimers()
  })

  function mockRefreshOk() {
    const adapter = vi.fn(async (cfg: { url?: string }) => {
      if (cfg.url === '/auth/refresh') return { data: {}, status: 200 }
      return { data: {}, status: 200 }
    })
    ;(api.defaults as Record<string, unknown>).adapter = adapter
    return adapter
  }

  /**
   * Прод-сценарий дословно: refresh ЖИВ (сессия валидна), но КАЖДЫЙ новый
   * EventSource сервер режет (429) → CLOSED. После исчерпания попыток —
   * честный realtimeDown, а НЕ ложный sessionExpired (POF: на старом коде
   * здесь стоял бы sessionExpired=true — тест ловит именно разводку).
   */
  it('live refresh + always-CLOSED channel → realtimeDown, not sessionExpired', async () => {
    vi.useFakeTimers()
    mockRefreshOk()
    const rt = useRealtimeEvents()
    rt.connect()
    expect(FakeEventSource.instances).toHaveLength(1)

    // Каждый новый источник сервер режет сразу: эмулируем 6 разрывов
    // (кап 5 попыток + финальный маркер).
    for (let i = 0; i < 6; i++) {
      const cur = FakeEventSource.instances[FakeEventSource.instances.length - 1]
      cur.readyState = FakeEventSource.CLOSED
      cur.onerror?.({} as Event)
      await vi.runAllTimersAsync()
      await Promise.resolve()
    }

    expect(rt.realtimeDown.value).toBe(true)
    expect(rt.sessionExpired.value).toBe(false)
    expect(rt.error.value).toMatch(/realtime|reconnect|подключ/i)
    expect(rt.error.value).not.toMatch(/session expired|sign in again/i)
    rt.disconnect()
  })

  /**
   * Регрессия WO-UI-22: мёртвый refresh по-прежнему даёт sessionExpired
   * (а не новый realtimeDown) — разводка в обе стороны.
   */
  it('dead refresh still yields sessionExpired, not realtimeDown', async () => {
    vi.useFakeTimers()
    const { default: axios } = await import('axios')
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
    const rt = useRealtimeEvents()
    rt.connect()
    for (let i = 0; i < 6; i++) {
      const cur = FakeEventSource.instances[FakeEventSource.instances.length - 1]
      cur.readyState = FakeEventSource.CLOSED
      cur.onerror?.({} as Event)
      await vi.runAllTimersAsync()
      await Promise.resolve()
    }
    expect(rt.sessionExpired.value).toBe(true)
    expect(rt.realtimeDown.value).toBe(false)
    rt.disconnect()
  })

  /**
   * Ручной retry: сбрасывает realtimeDown и пересоздаёт источник
   * (пользователь не обязан идти через ложный login).
   */
  it('retryConnection resets realtimeDown and reopens the source', async () => {
    vi.useFakeTimers()
    mockRefreshOk()
    const rt = useRealtimeEvents()
    rt.connect()
    for (let i = 0; i < 6; i++) {
      const cur = FakeEventSource.instances[FakeEventSource.instances.length - 1]
      cur.readyState = FakeEventSource.CLOSED
      cur.onerror?.({} as Event)
      await vi.runAllTimersAsync()
      await Promise.resolve()
    }
    expect(rt.realtimeDown.value).toBe(true)

    const before = FakeEventSource.instances.length
    rt.retryConnection()
    expect(rt.realtimeDown.value).toBe(false)
    expect(FakeEventSource.instances).toHaveLength(before + 1)
    rt.disconnect()
  })
})
