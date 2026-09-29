// @vitest-environment jsdom
/**
 * WO-UI-18 часть A (критерии 2/3) — useRealtimeEvents.
 *
 * Проверяет на FakeEventSource (jsdom без SSE — POF-мутация ниже доказывает,
 * что тест реально идёт через именованные события, а не мимо):
 *  - событие с именованным типом долетает до handleEvent всех трёх сторов;
 *  - lastEventId = SSE id (серверный sequence — курсор Last-Event-ID реконнекта);
 *  - onerror НЕ закрывает соединение (нативный автореконнект + catchup WO-REL-37);
 *  - disconnect закрывает; неизвестный тип без слушателя никому не доходит.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { readFileSync } from 'fs'
import { resolve, dirname } from 'path'
import { fileURLToPath } from 'url'
import { setActivePinia, createPinia } from 'pinia'
import { useRealtimeEvents, REALTIME_EVENT_TYPES, buildStreamUrl } from './useRealtimeEvents'
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

describe('useRealtimeEvents (WO-UI-18 A)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    FakeEventSource.instances = []
    vi.stubGlobal('EventSource', FakeEventSource)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('named event reaches all three stores via their handleEvent', async () => {
    const rt = useRealtimeEvents()
    rt.connect()
    expect(FakeEventSource.instances).toHaveLength(1)
    FakeEventSource.instances[0].emit('user-task.created', envelope('user-task.created', 41), '41')
    await vi.dynamicImportSettled()
    // dispatch идёт через настоящие handleEvent сторов — каждый зовёт свой refresh:
    expect(taskService.getUserTasks).toHaveBeenCalled()
    expect(instanceService.getProcessInstances).not.toHaveBeenCalled()
    expect(incidentService.getIncidents).not.toHaveBeenCalled()
    expect(rt.lastEventId.value).toBe('41')
    rt.disconnect()
  })

  it('connect subscribes to every type routed by the stores', () => {
    const rt = useRealtimeEvents()
    rt.connect()
    const src = FakeEventSource.instances[0]
    for (const type of REALTIME_EVENT_TYPES) {
      expect(src.listeners.get(type)?.length).toBeGreaterThanOrEqual(1)
    }
    rt.disconnect()
  })

  it('stream targets the same api base as the REST services (cookie auth)', () => {
    // WO-QW-1 F34: VITE_API_URL resolves differently per build topology
    // (Dockerfile ARG defaults to "/" for the external-proxy prod build,
    // ".env" here is "/api" for local dev/standalone) — asserting one
    // computed value against buildStreamUrl() is fragile to whichever
    // happens to be active. Assert the MIRRORING instead (same source file
    // pattern as apiBaseUrl.f34.test.ts): buildStreamUrl() must resolve its
    // base from the exact same expression api.ts uses for baseURL, so the
    // two can never drift apart regardless of which topology is built.
    const source = readFileSync(
      resolve(dirname(fileURLToPath(import.meta.url)), 'useRealtimeEvents.ts'),
      'utf-8',
    )
    expect(source).toContain("import.meta.env.VITE_API_URL || '/api'")
    expect(buildStreamUrl().endsWith('/events/stream')).toBe(true)
  })

  it('onerror keeps the channel open for native reconnect (criterion 3)', () => {
    const rt = useRealtimeEvents()
    rt.connect()
    const src = FakeEventSource.instances[0]
    src.onerror?.({} as Event)
    expect(src.closed).toBe(false)
    expect(rt.isConnected.value).toBe(false)
    expect(rt.error.value).toBeTruthy()
    // после разрыва канал жив — следующее событие всё ещё обрабатывается:
    src.emit('incident.raised', envelope('incident.raised', 42), '42')
    expect(incidentService.getIncidents).toHaveBeenCalled()
    expect(rt.lastEventId.value).toBe('42')
    rt.disconnect()
  })

  it('disconnect closes the channel', () => {
    const rt = useRealtimeEvents()
    rt.connect()
    rt.disconnect()
    expect(FakeEventSource.instances[0].closed).toBe(true)
  })
})
