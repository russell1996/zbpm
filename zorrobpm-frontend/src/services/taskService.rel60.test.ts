import { describe, it, expect, vi, beforeEach } from 'vitest'

/**
 * WO-REL-60 — регресс-тест на живую причину ночного красного E2E 27–28.09:
 * `toQueryString` в taskService отбрасывал `false` как "пустое" (`v !== ''`
 * пропускает `false`, но исходный код... — см. ниже), и запрос уходил БЕЗ
 * `completed=false`. Бэкенд возвращал задачи без фильтра completed, включая
 * только что завершённые — live-строка не исчезала после SSE
 * `user-task.completed` (строка 129 login-complete.e2e.ts).
 *
 * POF-мутация: вернуть в `toQueryString` условие, отбрасывающее `false`
 * (например `&& v !== false`) — оба теста ниже краснеют: URL без
 * `completed=false`, а completed-задача остаётся в списке.
 */
vi.mock('@/services/api', () => ({
  default: { get: vi.fn(), post: vi.fn() },
}))

import api from '@/services/api'
import { getUserTasks, getServiceTasks } from '@/services/taskService'

const mockedGet = api.get as unknown as ReturnType<typeof vi.fn>

describe('WO-REL-60 taskService query-string keeps false/0 filters', () => {
  beforeEach(() => {
    mockedGet.mockReset()
    mockedGet.mockResolvedValue({ data: { data: [], totalElements: 0 } })
  })

  it('getUserTasks({ completed: false }) шлёт completed=false, а не роняет фильтр', async () => {
    await getUserTasks({ completed: false })
    expect(mockedGet).toHaveBeenCalledOnce()
    const url = mockedGet.mock.calls[0][0] as string
    expect(url).toContain('completed=false')
  })

  it('pageIndex=0 не отбрасывается (0 — легитимное значение, не "пусто")', async () => {
    await getUserTasks({ pageIndex: 0, pageSize: 10, completed: false })
    const url = mockedGet.mock.calls[0][0] as string
    expect(url).toContain('pageIndex=0')
    expect(url).toContain('pageSize=10')
    expect(url).toContain('completed=false')
  })

  it('getServiceTasks({ completed: false }) — тот же контракт (общий toQueryString-паттерн)', async () => {
    await getServiceTasks({ completed: false })
    const url = mockedGet.mock.calls[0][0] as string
    expect(url).toContain('completed=false')
  })

  it('undefined/null/пустая строка по-прежнему отбрасываются (не шлём мусор)', async () => {
    await getUserTasks({ assignee: undefined, candidateGroup: null as unknown as undefined, processInstanceId: '' })
    const url = mockedGet.mock.calls[0][0] as string
    expect(url).not.toContain('assignee=')
    expect(url).not.toContain('candidateGroup=')
    expect(url).not.toContain('processInstanceId=')
  })
})
