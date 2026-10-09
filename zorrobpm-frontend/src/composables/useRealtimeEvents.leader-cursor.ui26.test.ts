// @vitest-environment jsdom
/**
 * WO-UI-26 Н-3 — Last-Event-ID при смене лидера + гонка двойного connect.
 *
 * - курсор переживает смену лидера: событие у старого лидера пишет id в
 *   sessionStorage (общий на вкладки), новый лидер подхватывает его в
 *   ref/диагностику при openSource (нативный catchup на новом соединении
 *   невозможен — разрыв закрывает запасной путь Доп.4);
 * - двойной connect в одном тике (remount) уходит в locks.request РОВНО
 *   один раз (синхронный guard leadershipInflight);
 * - disconnect между запросом лидерства и его резолвом НЕ открывает
 *   EventSource (поколение connectGeneration инвалидирует протухший исход,
 *   лок отпускается, зомби-соединения нет).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import api from '@/services/api'
import { resetSseFanoutForTest } from '@/services/sseFanout'
import { useRealtimeEvents } from './useRealtimeEvents'
import type { EventEnvelope } from '@/types/api'
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
  listeners = new Map<string, Listener[]>()
  onopen: ((e: Event) => void) | null = null
  onerror: ((e: Event) => void) | null = null
  readyState = 1
  closed = false
  constructor(public url: string) {
    FakeEventSource.instances.push(this)
  }
  addEventListener(type: string, fn: Listener) {
    const arr = this.listeners.get(type) || []
    arr.push(fn)
    this.listeners.set(type, arr)
  }
  emit(type: string, data: unknown, lastEventId: string) {
    const evt = { data: JSON.stringify(data), lastEventId } as MessageEvent
    for (const fn of this.listeners.get(type) || []) fn(evt as unknown as Event)
  }
  close() {
    this.closed = true
  }
}

const originalAdapter = api.defaults.adapter

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  FakeEventSource.instances = []
  vi.stubGlobal('EventSource', FakeEventSource)
  resetSseFanoutForTest()
  sessionStorage.clear()
  ;(api.defaults as Record<string, unknown>).adapter = vi.fn(async () => ({ data: {}, status: 200 }))
})

afterEach(() => {
  vi.unstubAllGlobals()
  sessionStorage.clear()
  ;(api.defaults as Record<string, unknown>).adapter = originalAdapter
  resetSseFanoutForTest()
})

function stubLocksImmediate() {
  const request = vi.fn((_n: string, _o: unknown, cb: () => Promise<void>) => cb())
  Object.defineProperty(navigator, 'locks', { value: { request }, configurable: true })
  return request
}

/** Висящий locks.request: колбэки копим, резолвим вручную. */
function stubLocksDeferred() {
  const pending: Array<() => Promise<void>> = []
  const request = vi.fn((_n: string, _o: unknown, cb: () => Promise<void>) => {
    pending.push(cb)
    return new Promise<void>(() => {})
  })
  Object.defineProperty(navigator, 'locks', { value: { request }, configurable: true })
  return { request, pending }
}

/**
 * Честный стаб Web Locks: эксклюзивный холд, FIFO-очередь, release будит
 * следующего. Единственная модель, на которой видно, отпускается ли лок
 * после disconnect повышенного лидера (баг self-review раунда 2: повышенный
 * получал no-op release — лок висел, цепочка переизбраний вставала).
 */
function stubLocksFaithful() {
  let held = false
  const queue: Array<() => void> = []
  const pump = () => {
    if (held) return
    const next = queue.shift()
    if (!next) return
    held = true
    next()
  }
  const request = vi.fn((_n: string, _o: unknown, cb: () => Promise<void>) => {
    queue.push(() => {
      // Семантика браузера: лок освобождается, когда сетится промис
      // колбэка холдера (releaseResolve из sseFanout.release).
      void cb().then(
        () => {
          held = false
          pump()
        },
        () => {
          held = false
          pump()
        },
      )
    })
    pump()
    return Promise.resolve()
  })
  Object.defineProperty(navigator, 'locks', { value: { request }, configurable: true })
  return {
    request,
    /** Лок свободен прямо сейчас (для ассертов освобождения). */
    isFree: () => !held,
  }
}

function envelope(type: string, sequence: number): EventEnvelope {
  return { sequence, id: `e-${sequence}`, type, version: 1, occurredAt: '2026-09-24T00:00:00Z', data: {} }
}

describe('useRealtimeEvents лидер-курсор и гонка connect (WO-UI-26 Н-3)', () => {
  it('новый лидер подхватывает Last-Event-ID умершего (sessionStorage)', async () => {
    stubLocksImmediate()
    vi.mocked(incidentService.getIncident).mockResolvedValue({
      id: 'inc77', activityId: 'a77', message: 'm', createdAt: '2026-09-24T00:00:00Z',
      completedAt: null, processName: null, processInstanceId: 'pi-1', bpmnElementId: 'el-77', elementName: null,
    })
    const old = useRealtimeEvents()
    old.connect()
    await vi.waitFor(() => expect(FakeEventSource.instances).toHaveLength(1))
    FakeEventSource.instances[0].emit('incident.raised',
      { ...envelope('incident.raised', 77), data: { incidentId: 'inc77' } }, '77')
    await vi.waitFor(() => expect(old.lastEventId.value).toBe('77'))
    expect(sessionStorage.getItem('zbpm.sse.lastEventId')).toBe('77')
    // Смерть лидера: вкладка закрыта.
    old.disconnect()
    expect(FakeEventSource.instances[0].closed).toBe(true)

    // Новый лидер в той же origin-сессии — курсор подхвачен до первого события.
    const next = useRealtimeEvents()
    expect(next.lastEventId.value).toBeNull()
    next.connect()
    await vi.waitFor(() => expect(FakeEventSource.instances).toHaveLength(2))
    expect(next.lastEventId.value).toBe('77')
    expect(next.diagnostics.value.lastEventId).toBe('77')
    next.disconnect()
  })

  it('двойной connect в одном тике → ровно один locks.request', async () => {
    const { request } = stubLocksDeferred()
    const rt = useRealtimeEvents()
    rt.connect()
    rt.connect()
    await Promise.resolve()
    await Promise.resolve()
    expect(request).toHaveBeenCalledTimes(1)
    rt.disconnect()
  })

  it('disconnect до резолва лидерства → EventSource не открывается (зомби нет)', async () => {
    const { request, pending } = stubLocksDeferred()
    const rt = useRealtimeEvents()
    rt.connect()
    await Promise.resolve()
    expect(request).toHaveBeenCalledTimes(1)
    expect(pending).toHaveLength(1)
    // Размонтирование до ответа лока.
    rt.disconnect()
    // Лок отвечает ПОСЛЕ disconnect: протухший исход — лок отпущен, ES нет.
    await pending[0]()
    await Promise.resolve()
    await Promise.resolve()
    expect(FakeEventSource.instances).toHaveLength(0)
    expect(rt.isLeaderTab()).toBe(false)
    rt.disconnect()
  })

  it('Н-3/self-review: повышенный лидер отпускает лок — цепочка переизбраний жива', async () => {
    const locks = stubLocksFaithful()
    // A — лидер (свободный лок → мгновенный грант).
    const leader = useRealtimeEvents()
    leader.connect()
    await vi.waitFor(() => expect(FakeEventSource.instances).toHaveLength(1))
    expect(leader.isLeaderTab()).toBe(true)

    // B — follower (лок занят → очередь; через 1500 мс — follower-резолв).
    const follower = useRealtimeEvents()
    follower.connect()
    await new Promise((r) => setTimeout(r, 1700))
    await Promise.resolve()
    expect(follower.isLeaderTab()).toBe(false)
    expect(FakeEventSource.instances).toHaveLength(1)

    // Смерть A → B повышен (ровно один новый ES).
    leader.disconnect()
    await vi.waitFor(() => expect(FakeEventSource.instances).toHaveLength(2))
    expect(follower.isLeaderTab()).toBe(true)

    // B уходит: лок ОБЯЗАН освободиться (без фикса — no-op release, лок висит).
    // Освобождение — через промис холдера (микротаски): ждём, не ассертим синхронно.
    follower.disconnect()
    expect(FakeEventSource.instances[1].closed).toBe(true)
    await vi.waitFor(() => expect(locks.isFree()).toBe(true))

    // C — новый кандидат: лок свободен → сразу лидер, без 1500 мс ожидания
    // и без вечного follower-стояния за призрачным холдом.
    const next = useRealtimeEvents()
    next.connect()
    await vi.waitFor(() => expect(next.isLeaderTab()).toBe(true))
    await vi.waitFor(() => expect(FakeEventSource.instances).toHaveLength(3))
    next.disconnect()
    leader.disconnect()
  }, 30000)
})
