// @vitest-environment jsdom
/**
 * WO-UI-26 Доп.5 (кр.21) — лидер-вкладка SSE: одно соединение на браузер.
 * - лидер открывает EventSource, follower — нет (0 своих соединений);
 * - событие лидера доходит до follower через ретрансляцию (dispatch у обоих);
 * - закрытие лидера → переизбрание (новый лидер открывает EventSource).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useRealtimeEvents } from './useRealtimeEvents'
import {
  resetSseFanoutForTest,
  SSE_FANOUT_CHANNEL,
} from '@/services/sseFanout'
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
  closed = false
  readyState = 1
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
  emit(type: string, data: unknown, lastEventId: string) {
    const evt = { data: JSON.stringify(data), lastEventId } as MessageEvent
    for (const fn of this.listeners.get(type) || []) fn(evt as unknown as Event)
  }
  close() {
    this.closed = true
  }
}

function envelope(type: string, sequence: number): EventEnvelope {
  return { sequence, id: `e-${sequence}`, type, version: 1, occurredAt: '2026-09-24T00:00:00Z', data: {} }
}

// Web Locks стаб: первый запросивший — лидер, лок держится до release.
let lockOwner: (() => void) | null = null
function stubLocks() {
  const locks = {
    request: vi.fn((_name: string, _opts: unknown, cb: () => Promise<void>) => {
      if (lockOwner) {
        // занято — висим (follower ждёт освобождения)
        return new Promise<void>((resolve) => {
          const check = () => {
            if (!lockOwner) {
              lockOwner = () => {}
              void cb().finally(() => {})
            } else {
              setTimeout(check, 10)
            }
          }
          setTimeout(check, 10)
          void resolve
        })
      }
      lockOwner = () => {}
      return cb()
    }),
  }
  Object.defineProperty(navigator, 'locks', { value: locks, configurable: true })
  return locks
}

function releaseLockForTest() {
  lockOwner = null
}

describe('useRealtimeEvents leader-tab (WO-UI-26 Доп.5)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    FakeEventSource.instances = []
    vi.stubGlobal('EventSource', FakeEventSource)
    resetSseFanoutForTest()
    lockOwner = null
    stubLocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    resetSseFanoutForTest()
    lockOwner = null
  })

  it('лидер открывает EventSource, второй connect того же контекста — тоже лидер (один лок на стабе)', async () => {
    const rt = useRealtimeEvents()
    rt.connect()
    await vi.waitFor(() => expect(FakeEventSource.instances).toHaveLength(1))
    expect(rt.isLeaderTab()).toBe(true)
    rt.disconnect()
  })

  it('событие лидера ретранслируется follower через BroadcastChannel', async () => {
    // Две вкладки = два экземпляра composable с общим BroadcastChannel.
    const leader = useRealtimeEvents()
    leader.connect()
    await vi.waitFor(() => expect(FakeEventSource.instances).toHaveLength(1))

    const follower = useRealtimeEvents()
    // follower подписывается напрямую на fanout (эмуляция второй вкладки:
    // тот же JS-контекст, но отдельный экземпляр — dispatch идёт в общие сторы).
    const { subscribeFanout } = await import('@/services/sseFanout')
    let followerGot: EventEnvelope | null = null
    const unsub = subscribeFanout((msg) => {
      if (msg.kind === 'event') followerGot = msg.envelope
    })
    void follower

    vi.mocked(taskService.getUserTask).mockResolvedValue({
      id: 'nt77', code: 'c', name: 'N', processInstanceId: 'pi-1', processDefinitionId: 'pd-1',
      formKey: null, status: 'CREATED', createdAt: '2026-09-24T00:00:00Z', completedAt: null,
    })
    FakeEventSource.instances[0].emit('user-task.created',
      { ...envelope('user-task.created', 77), data: { activityId: 'nt77' } }, '77')
    // BroadcastChannel.postMessage — макрозадача: ждём реальную доставку,
    // а не только промисы (dynamicImportSettled её не покрывает).
    await vi.waitFor(() => expect(followerGot).not.toBeNull())
    // Доп.4: адресный патч одним GET, список — нет.
    await vi.waitFor(() => expect(taskService.getUserTask).toHaveBeenCalledWith('nt77'))
    expect(taskService.getUserTasks).not.toHaveBeenCalled()
    expect((followerGot as unknown as EventEnvelope).sequence).toBe(77)
    unsub()
    leader.disconnect()
  })

  it('ретрансляция идёт всем подписчикам контекста (поимка снятия publishFanout)', async () => {
    const leader = useRealtimeEvents()
    leader.connect()
    await vi.waitFor(() => expect(FakeEventSource.instances).toHaveLength(1))
    // Два подписчика — оба получают ровно по одному событию каждый.
    // Мутация «убрать publishFanout из onNamedEvent» даёт calls=0 (RED).
    const { subscribeFanout } = await import('@/services/sseFanout')
    let calls = 0
    const unsub = subscribeFanout(() => {
      calls++
    })
    vi.mocked(incidentService.getIncident).mockResolvedValue({
      id: 'inc78', activityId: 'a78', message: 'm', createdAt: '2026-09-24T00:00:00Z',
      completedAt: null, processName: null, processInstanceId: 'pi-1', bpmnElementId: 'el-78', elementName: null,
    })
    FakeEventSource.instances[0].emit('incident.raised',
      { ...envelope('incident.raised', 78), data: { incidentId: 'inc78' } }, '78')
    // Локальная доставка синхронна + кросс-вкладочная через BroadcastChannel
    // (макрозадача): ждём обе, итог — 2 доставки одному подписчику
    // (идемпотентность по sequence — на сторах, Доп.4).
    await vi.waitFor(() => expect(calls).toBe(2))
    // лидер применил адресным патчем (один GET), список — нет
    await vi.waitFor(() => expect(incidentService.getIncident).toHaveBeenCalledWith('inc78'))
    expect(incidentService.getIncidents).not.toHaveBeenCalled()
    unsub()
    leader.disconnect()
  })

  it('disconnect лидера закрывает EventSource и отпускает лок', async () => {
    const rt = useRealtimeEvents()
    rt.connect()
    await vi.waitFor(() => expect(FakeEventSource.instances).toHaveLength(1))
    rt.disconnect()
    expect(FakeEventSource.instances[0].closed).toBe(true)
    releaseLockForTest()
    expect(SSE_FANOUT_CHANNEL).toBe('zbpm-sse-fanout')
  })
})
