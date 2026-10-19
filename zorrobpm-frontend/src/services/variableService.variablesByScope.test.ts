// @vitest-environment node
/**
 * WO-UI-37 (часть B): клиент точки GET /process-instances/{id}/variables-by-scope
 * (бэкенд — WO-C8-51 часть A, смержен: QueryContract.java:66,
 * VariableQueryOperationsImpl.variablesByScope).
 *
 * Контракт прибит напрямую: компонентные тесты страницы мокают весь
 * variableService, поэтому смена URL или формы ответа здесь прошла бы молча
 * (тот же приём, что instanceService.cancel.test.ts для POST /cancel).
 *
 * Форма ответа — МАССИВ групп (не PagedData): первая группа всегда root
 * (scope == null), дальше по группе на каждый непустой scopeId.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import api from './api'
import { getVariablesByScope } from './variableService'

vi.mock('./api', () => ({
  default: { get: vi.fn() },
}))

describe('WO-UI-37 part B: variableService.getVariablesByScope', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('GETs /process-instances/{id}/variables-by-scope and returns the group array as-is', async () => {
    const groups = [
      { scope: null, variables: [{ name: 'items', value: '["a","b"]', type: 'JSON' }] },
      {
        scope: {
          activityId: 'act-1',
          bpmnElementId: 'miSub',
          elementType: 'SUB_PROCESS',
          status: 'COMPLETED',
          parentScopeActivityId: null,
        },
        variables: [{ name: 'item', value: 'a', type: 'STRING', activityId: 'act-1' }],
      },
    ]
    vi.mocked(api.get).mockResolvedValueOnce({ data: groups })

    const result = await getVariablesByScope('pi-1')

    expect(api.get).toHaveBeenCalledTimes(1)
    expect(api.get).toHaveBeenCalledWith('/process-instances/pi-1/variables-by-scope')
    // без перепаковки: root-группа первой, scope-info и переменные побайто от бэкенда
    expect(result).toEqual(groups)
  })
})
