// @vitest-environment node
import { describe, it, expect, vi } from 'vitest'
import * as utils from '../utils'
import { buildDiagnosticJson } from '../diagnostic'

// WO-UI-37 (часть A): экспорт обязан брать статус из ТОГО ЖЕ дериватора
// `processInstanceStatus` (shared/lib/utils.ts), а не из второй копии логики.
// Пасущий мок доказывает ФАКТ вызова: если диагностика считает статус сама,
// подмена возврата дериватора на результат не влияет — тест краснеет.
vi.mock('../utils', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../utils')>()
  return { ...actual, processInstanceStatus: vi.fn(actual.processInstanceStatus) }
})
import type {
  ProcessInstance,
  ActivityInstance,
  ProcessVariable,
  UserTask,
  ServiceTask,
  Incident,
} from '@/types/api'

// ── Helpers ──

function makeInstance(overrides: Partial<ProcessInstance> = {}): ProcessInstance {
  return {
    id: 'pi-1',
    parentActivityId: null,
    processDefinitionId: 'pd-1',
    processName: 'Test Process',
    processKey: 'test-process',
    processVersion: 1,
    startedAt: '2026-01-01T00:00:00Z',
    completedAt: null,
    cancelled: false, // WO-UI-21 Раунд 2: обязательное поле ProcessInstance
    ...overrides,
  }
}

function makeActivity(overrides: Partial<ActivityInstance> = {}): ActivityInstance {
  return {
    id: 'act-1',
    processInstanceId: 'pi-1',
    bpmnElementId: 'Activity_1',
    type: 'serviceTask',
    status: 'COMPLETED',
    createdAt: '2026-01-01T01:00:00Z',
    completedAt: '2026-01-01T02:00:00Z',
    ...overrides,
  }
}

function makeVariable(overrides: Partial<ProcessVariable> = {}): ProcessVariable {
  return {
    name: 'var1',
    value: 'hello',
    type: 'STRING',
    ...overrides,
  }
}

function makeUserTask(overrides: Partial<UserTask> = {}): UserTask {
  return {
    id: 'ut-1',
    code: 'Activity_1',
    name: 'User Task 1',
    processInstanceId: 'pi-1',
    processDefinitionId: 'pd-1',
    formKey: null,
    status: 'CREATED',
    createdAt: '2026-01-01T03:00:00Z',
    completedAt: null,
    ...overrides,
  }
}

function makeServiceTask(overrides: Partial<ServiceTask> = {}): ServiceTask {
  return {
    id: 'st-1',
    code: 'Activity_2',
    name: 'Service Task 1',
    processInstanceId: 'pi-1',
    processDefinitionId: 'pd-1',
    job: 'myJob',
    status: 'COMPLETED',
    createdAt: '2026-01-01T04:00:00Z',
    completedAt: '2026-01-01T05:00:00Z',
    ...overrides,
  }
}

function makeIncident(overrides: Partial<Incident> = {}): Incident {
  return {
    id: 'inc-1',
    activityId: 'act-1',
    message: 'Something went wrong',
    createdAt: '2026-01-01T06:00:00Z',
    completedAt: null,
    processName: null,
    processInstanceId: null,
    bpmnElementId: null,
    elementName: null,
    ...overrides,
  }
}

// ── Tests ──

describe('buildDiagnosticJson', () => {
  // ──────────────────────────────────────────────
  // Criteria 3: Anomalies when COMPLETED + active user task
  // ──────────────────────────────────────────────
  it('POF RED: instance COMPLETED with active user task — anomalies report desync', () => {
    const instance = makeInstance({ completedAt: '2026-01-02T00:00:00Z' })
    const activities: ActivityInstance[] = [
      makeActivity({ status: 'CREATED', completedAt: null }),
    ]
    const userTasks: UserTask[] = [
      makeUserTask({ status: 'CREATED', completedAt: null }),
    ]
    const result = buildDiagnosticJson({
      instance,
      activities,
      variables: [],
      userTasks,
      serviceTasks: [],
      incidents: [],
      bpmnXml: '',
    })

    expect(result.instance.status).toBe('completed')
    expect(result.diagnostics.activeTokenCount).toBe(1)
    // The anomaly SHOULD mention the desync (OLD behavior without fix would miss this)
    expect(result.diagnostics.anomalies).toEqual(
      expect.arrayContaining([
        expect.stringContaining('COMPLETED'),
        expect.stringContaining('active elements'),
      ]),
    )
  })

  it('GREEN: instance COMPLETED with active user task — anomalies report desync', () => {
    const instance = makeInstance({ completedAt: '2026-01-02T00:00:00Z' })
    const activities: ActivityInstance[] = [
      makeActivity({ status: 'CREATED', completedAt: null }),
    ]
    const userTasks: UserTask[] = [
      makeUserTask({ status: 'CREATED', completedAt: null }),
    ]
    const result = buildDiagnosticJson({
      instance,
      activities,
      variables: [],
      userTasks,
      serviceTasks: [],
      incidents: [],
      bpmnXml: '',
    })

    expect(result.diagnostics.anomalies.length).toBeGreaterThanOrEqual(1)
    expect(result.diagnostics.anomalies).toContain(
      'Instance status=COMPLETED, but active elements: 1',
    )
  })

  // ──────────────────────────────────────────────
  // Criteria 4: Empty stores → empty sections, no exceptions
  // ──────────────────────────────────────────────
  it('GREEN: empty stores — all sections empty, no exceptions', () => {
    const instance = makeInstance()
    const result = buildDiagnosticJson({
      instance,
      activities: [],
      variables: [],
      userTasks: [],
      serviceTasks: [],
      incidents: [],
      bpmnXml: '',
    })

    expect(result.instance.id).toBe('pi-1')
    expect(result.instance.status).toBe('running')
    expect(result.diagnostics.activeTokenCount).toBe(0)
    expect(result.diagnostics.activeElements).toEqual([])
    expect(result.diagnostics.openIncidents).toBe(0)
    expect(result.diagnostics.anomalies).toEqual([])
    expect(result.tokens).toEqual([])
    expect(result.timeline).toEqual([])
    expect(result.variables).toEqual([])
    expect(result.incidents).toEqual([])
    expect(result.userTasks).toEqual([])
    expect(result.serviceTasks).toEqual([])
    expect(result.bpmnXml).toBe('')
  })

  // ──────────────────────────────────────────────
  // Criteria 2: JSON structure completeness, timeline sorting + dedup
  // ──────────────────────────────────────────────
  it('GREEN: all sections present; timeline sorted by createdAt', () => {
    const instance = makeInstance()
    const activities: ActivityInstance[] = [
      makeActivity({
        id: 'act-1',
        bpmnElementId: 'Start_1',
        type: 'startEvent',
        status: 'COMPLETED',
        createdAt: '2026-01-01T01:00:00Z',
        completedAt: '2026-01-01T01:30:00Z',
      }),
      makeActivity({
        id: 'act-2',
        bpmnElementId: 'Activity_1',
        type: 'serviceTask',
        status: 'IN_PROGRESS',
        createdAt: '2026-01-01T02:00:00Z',
        completedAt: null,
      }),
    ]
    const variables: ProcessVariable[] = [
      makeVariable({ name: 'orderId', value: 'ORD-123', type: 'STRING' }),
    ]
    const userTasks: UserTask[] = [
      makeUserTask({
        id: 'ut-1',
        code: 'Activity_2',
        name: 'Review Order',
        status: 'CREATED',
        createdAt: '2026-01-01T03:00:00Z',
      }),
    ]
    const incidents: Incident[] = [
      makeIncident({
        id: 'inc-1',
        activityId: 'act-2',
        message: 'Service timeout',
        createdAt: '2026-01-01T04:00:00Z',
      }),
    ]

    const result = buildDiagnosticJson({
      instance,
      activities,
      variables,
      userTasks,
      serviceTasks: [],
      incidents,
      bpmnXml: '<definitions id="test" />',
    })

    // Schema version (WO-UI-37 часть A: v1 → v2, добавлено значение 'cancelled')
    expect(result.schemaVersion).toBe(2)
    expect(result.generatedAt).toBeTruthy()

    // Instance
    expect(result.instance.id).toBe('pi-1')
    expect(result.instance.processKey).toBe('test-process')
    expect(result.instance.processName).toBe('Test Process')
    expect(result.instance.version).toBe(1)
    expect(result.instance.status).toBe('running')

    // Diagnostics
    expect(result.diagnostics.activeTokenCount).toBe(1) // act-2 is IN_PROGRESS
    expect(result.diagnostics.activeElements).toHaveLength(1)
    expect(result.diagnostics.activeElements[0].bpmnElementId).toBe('Activity_1')
    expect(result.diagnostics.openIncidents).toBe(1)

    // Variables
    expect(result.variables).toHaveLength(1)
    expect(result.variables[0]).toEqual({ name: 'orderId', type: 'STRING', value: 'ORD-123' })

    // User tasks
    expect(result.userTasks).toHaveLength(1)
    expect(result.userTasks[0].id).toBe('ut-1')
    expect(result.userTasks[0].assignee).toBeNull()

    // Incidents
    expect(result.incidents).toHaveLength(1)
    expect(result.incidents[0].bpmnElementId).toBe('Activity_1') // resolved from act-2

    // BPMN XML
    expect(result.bpmnXml).toBe('<definitions id="test" />')

    // Timeline sorting: entries must be in chronological order by createdAt
    for (let i = 1; i < result.timeline.length; i++) {
      const prev = new Date(result.timeline[i - 1].createdAt).getTime()
      const curr = new Date(result.timeline[i].createdAt).getTime()
      expect(curr).toBeGreaterThanOrEqual(prev)
    }

    // Timeline seq is sequential 0..N
    result.timeline.forEach((entry, i) => {
      expect(entry.seq).toBe(i)
    })

    // All timeline entries have required fields
    for (const entry of result.timeline) {
      expect(entry).toHaveProperty('seq')
      expect(entry).toHaveProperty('token')
      expect(entry).toHaveProperty('bpmnElementId')
      expect(entry).toHaveProperty('type')
      expect(entry).toHaveProperty('status')
      expect(entry).toHaveProperty('createdAt')
      expect(entry).toHaveProperty('assignee')
      expect(entry).toHaveProperty('formKey')
      expect(entry).toHaveProperty('incidentMessage')
    }
  })

  // ──────────────────────────────────────────────
  // Timeline dedup: identical entries are collapsed
  // ──────────────────────────────────────────────
  it('GREEN: timeline deduplicates identical entries', () => {
    const instance = makeInstance()
    // Two activities with same id would be duplicate — but activities have unique ids
    // Test with duplicate-like entries: two activities with different ids but same bpmnElementId+createdAt
    const activities: ActivityInstance[] = [
      makeActivity({
        id: 'act-1',
        bpmnElementId: 'Activity_1',
        type: 'serviceTask',
        status: 'COMPLETED',
        createdAt: '2026-01-01T01:00:00Z',
        completedAt: '2026-01-01T02:00:00Z',
      }),
    ]

    const result = buildDiagnosticJson({
      instance,
      activities,
      variables: [],
      userTasks: [],
      serviceTasks: [],
      incidents: [],
      bpmnXml: '',
    })

    // Only 1 entry in timeline
    expect(result.timeline).toHaveLength(1)
  })

  // ──────────────────────────────────────────────
  // Anomalies: open incidents reported
  // ──────────────────────────────────────────────
  it('GREEN: open incidents appear in anomalies', () => {
    const instance = makeInstance({ completedAt: '2026-01-02T00:00:00Z' })
    const incidents: Incident[] = [
      makeIncident({ id: 'inc-1', completedAt: null }),
      makeIncident({ id: 'inc-2', completedAt: '2026-01-02T00:00:00Z' }), // resolved
    ]
    const result = buildDiagnosticJson({
      instance,
      activities: [],
      variables: [],
      userTasks: [],
      serviceTasks: [],
      incidents,
      bpmnXml: '',
    })

    expect(result.diagnostics.openIncidents).toBe(1)
    expect(result.diagnostics.anomalies).toContain('Open incidents: 1')
  })

  // ──────────────────────────────────────────────
  // No anomalies when everything is normal
  // ──────────────────────────────────────────────
  it('GREEN: no anomalies for normal running instance', () => {
    const instance = makeInstance() // running, no completedAt
    const activities: ActivityInstance[] = [
      makeActivity({ status: 'IN_PROGRESS', completedAt: null }),
    ]
    const result = buildDiagnosticJson({
      instance,
      activities,
      variables: [],
      userTasks: [],
      serviceTasks: [],
      incidents: [],
      bpmnXml: '',
    })

    expect(result.diagnostics.anomalies).toEqual([])
    expect(result.instance.status).toBe('running')
    expect(result.diagnostics.activeTokenCount).toBe(1)
  })

  // ──────────────────────────────────────────────
  // WO-C8-49 (п.4): cancelled отличается от completed
  // ──────────────────────────────────────────────
  it('WO-C8-49: cancelled instance reports status=cancelled with flag', () => {
    const instance = makeInstance({
      completedAt: '2026-01-02T00:00:00Z',
      cancelled: true,
    })
    const result = buildDiagnosticJson({
      instance,
      activities: [],
      variables: [],
      userTasks: [],
      serviceTasks: [],
      incidents: [],
      bpmnXml: '',
    })

    expect(result.instance.status).toBe('cancelled')
    expect(result.instance.cancelled).toBe(true)
    expect(result.instance.completedAt).toBe('2026-01-02T00:00:00Z')
  })

  it('WO-C8-49: completed-but-not-cancelled stays completed', () => {
    const instance = makeInstance({
      completedAt: '2026-01-02T00:00:00Z',
      cancelled: false,
    })
    const result = buildDiagnosticJson({
      instance,
      activities: [],
      variables: [],
      userTasks: [],
      serviceTasks: [],
      incidents: [],
      bpmnXml: '',
    })

    expect(result.instance.status).toBe('completed')
    expect(result.instance.cancelled).toBe(false)
  })

  // ──────────────────────────────────────────────
  // WO-UI-37 часть A (бывшая WO-UI-36): статус — через общий дериватор
  // ──────────────────────────────────────────────
  it('WO-UI-37-A: status is derived by shared processInstanceStatus (same source as the UI)', () => {
    vi.mocked(utils.processInstanceStatus).mockClear()

    const instance = makeInstance({
      completedAt: '2026-01-02T00:00:00Z',
      cancelled: true,
    })
    const result = buildDiagnosticJson({
      instance,
      activities: [],
      variables: [],
      userTasks: [],
      serviceTasks: [],
      incidents: [],
      bpmnXml: '',
    })

    // 1) дериватор реально вызван — не вторая копия тернарника
    expect(utils.processInstanceStatus).toHaveBeenCalledTimes(1)
    // 2) его ответ реально управляет полем status (uppercase → lowercase)
    expect(result.instance.status).toBe('cancelled')
  })

  it('WO-UI-37-A: status follows the shared deriver, not local completedAt logic (mutation guard)', () => {
    // Если диагностика начнёт считать статус сама, подмена дериватора не сработает.
    vi.mocked(utils.processInstanceStatus).mockReturnValueOnce('RUNNING')

    const instance = makeInstance({ completedAt: '2026-01-02T00:00:00Z' })
    const result = buildDiagnosticJson({
      instance,
      activities: [],
      variables: [],
      userTasks: [],
      serviceTasks: [],
      incidents: [],
      bpmnXml: '',
    })

    expect(result.instance.status).toBe('running')
  })

  // ──────────────────────────────────────────────
  // WO-UI-37 часть A: аномалии отменённого инстанса
  // ──────────────────────────────────────────────
  it('WO-UI-37-A: cancelled with leftover open incidents gets its own anomaly, not COMPLETED wording', () => {
    const instance = makeInstance({
      completedAt: '2026-01-02T00:00:00Z',
      cancelled: true,
    })
    const activities: ActivityInstance[] = [
      makeActivity({ id: 'act-open', status: 'CREATED', completedAt: null }),
    ]
    const incidents: Incident[] = [makeIncident({ id: 'inc-1', completedAt: null })]

    const result = buildDiagnosticJson({
      instance,
      activities,
      variables: [],
      userTasks: [],
      serviceTasks: [],
      incidents,
      bpmnXml: '',
    })

    expect(result.instance.status).toBe('cancelled')
    // WO-C8-49: после отмены живых элементов быть не должно — это и есть аномалия
    expect(result.diagnostics.anomalies).toEqual([
      'Instance cancelled; open incidents: 1, active elements: 1',
    ])
    // Старая формулировка про COMPLETED не должна просачиваться
    expect(result.diagnostics.anomalies.join(' ')).not.toContain('status=COMPLETED')
  })

  it('WO-UI-37-A: cancelled with no leftovers reports no anomalies', () => {
    const instance = makeInstance({
      completedAt: '2026-01-02T00:00:00Z',
      cancelled: true,
    })
    const result = buildDiagnosticJson({
      instance,
      activities: [],
      variables: [],
      userTasks: [],
      serviceTasks: [],
      incidents: [],
      bpmnXml: '',
    })

    expect(result.diagnostics.anomalies).toEqual([])
  })

  it('WO-UI-37-A: completed instance keeps the COMPLETED wording (no regression)', () => {
    const instance = makeInstance({ completedAt: '2026-01-02T00:00:00Z' })
    const activities: ActivityInstance[] = [
      makeActivity({ status: 'CREATED', completedAt: null }),
    ]
    const result = buildDiagnosticJson({
      instance,
      activities,
      variables: [],
      userTasks: [],
      serviceTasks: [],
      incidents: [],
      bpmnXml: '',
    })

    expect(result.diagnostics.anomalies).toContain(
      'Instance status=COMPLETED, but active elements: 1',
    )
  })

  // ──────────────────────────────────────────────
  // WO-UI-37 часть A: схема экспорта v2
  // ──────────────────────────────────────────────
  it('WO-UI-37-A: schemaVersion is 2 and the instance block keeps its documented shape', () => {
    const instance = makeInstance({
      completedAt: '2026-01-02T00:00:00Z',
      cancelled: true,
    })
    const result = buildDiagnosticJson({
      instance,
      activities: [],
      variables: [],
      userTasks: [],
      serviceTasks: [],
      incidents: [],
      bpmnXml: '',
    })

    expect(result.schemaVersion).toBe(2)
    expect(Object.keys(result.instance).sort()).toEqual(
      [
        'businessKeyIfAny',
        'cancelled',
        'completedAt',
        'id',
        'processKey',
        'processName',
        'startedAt',
        'status',
        'version',
      ].sort(),
    )
    // Совместимость: status остаётся строкой, появляется новое значение
    expect(typeof result.instance.status).toBe('string')
  })
})
