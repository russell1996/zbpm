// @vitest-environment node
/**
 * WO-UI-37 (часть B): экспорт «Диагностика JSON» несёт variablesByScope —
 * группы переменных по скоупам (root-группа первой), чтобы консьюмер видел
 * decision/taskUuid/item без доступа к БД (мотивация — отчёт потребителя
 * по MI-subprocess). schemaVersion поднимается ОДИН раз за пакет (часть A
 * уже ввела v2) — часть B только добавляет поле.
 *
 * Мутация: убрать поле variablesByScope из возврата buildDiagnosticJson
 * (или перестать читать input.variablesByScope) → тесты ниже RED.
 */
import { describe, it, expect } from 'vitest'
import { buildDiagnosticJson } from '../diagnostic'
import type {
  ProcessInstance,
  ProcessVariable,
  VariableScopeGroup,
} from '@/types/api'

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
    cancelled: false,
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

describe('buildDiagnosticJson: variablesByScope (WO-UI-37 part B)', () => {
  it('carries the scope groups into the export: root first, scope-info and variables mapped', () => {
    const groups: VariableScopeGroup[] = [
      {
        scope: null,
        variables: [makeVariable({ name: 'items', value: '["a","b"]', type: 'JSON' })],
      },
      {
        scope: {
          activityId: 'act-mi-2',
          bpmnElementId: 'miSub',
          elementType: 'SUB_PROCESS',
          status: 'IN_PROGRESS',
          parentScopeActivityId: null,
        },
        variables: [
          makeVariable({ name: 'item', value: 'b', type: 'STRING' }),
          makeVariable({ name: 'loopCounter', value: '2', type: 'LONG' }),
        ],
      },
    ]
    const json = buildDiagnosticJson({
      instance: makeInstance(),
      activities: [],
      variables: [],
      userTasks: [],
      serviceTasks: [],
      incidents: [],
      bpmnXml: '<definitions/>',
      variablesByScope: groups,
    })

    // часть B НЕ поднимает версию — v2 ввела часть A (один раз за пакет)
    expect(json.schemaVersion).toBe(2)
    expect(json.variablesByScope).toHaveLength(2)
    // root-группа первой, переменные перемапнуты в экспортную форму (name/type/value)
    expect(json.variablesByScope[0].scope).toBeNull()
    expect(json.variablesByScope[0].variables).toEqual([
      { name: 'items', type: 'JSON', value: '["a","b"]' },
    ])
    // scope-info перенесён целиком — по нему консьюмер узнаёт элемент/статус скоупа
    expect(json.variablesByScope[1].scope).toEqual({
      activityId: 'act-mi-2',
      bpmnElementId: 'miSub',
      elementType: 'SUB_PROCESS',
      status: 'IN_PROGRESS',
      parentScopeActivityId: null,
    })
    expect(json.variablesByScope[1].variables).toEqual([
      { name: 'item', type: 'STRING', value: 'b' },
      { name: 'loopCounter', type: 'LONG', value: '2' },
    ])
  })

  it('empty array when the caller did not pass groups (compat with part-A inputs)', () => {
    const json = buildDiagnosticJson({
      instance: makeInstance(),
      activities: [],
      variables: [],
      userTasks: [],
      serviceTasks: [],
      incidents: [],
      bpmnXml: '<definitions/>',
    })
    expect(json.variablesByScope).toEqual([])
  })
})
