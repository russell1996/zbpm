// @vitest-environment jsdom
/**
 * WO-UI-21 Раунд 2: сервис отмены instance бьёт в реальный бэкенд-эндпоинт
 * POST /process-instances/{id}/cancel (RuntimeContract.java:68).
 * Компонентные тесты мокают этот модуль целиком, поэтому смена URL/метода
 * здесь прошла бы молча — этот юнит фиксирует контракт напрямую.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import api from './api'
import { cancelProcessInstance } from './instanceService'

vi.mock('./api', () => ({
  default: { post: vi.fn() },
}))

describe('WO-UI-21 Round 2: instanceService.cancelProcessInstance', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('CRIT-3(contract): POSTs to /process-instances/{id}/cancel and returns the id', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({ data: { id: 'pi-1' } })
    const result = await cancelProcessInstance('pi-1')
    expect(api.post).toHaveBeenCalledTimes(1)
    expect(api.post).toHaveBeenCalledWith('/process-instances/pi-1/cancel')
    expect(result).toEqual({ id: 'pi-1' })
  })
})
