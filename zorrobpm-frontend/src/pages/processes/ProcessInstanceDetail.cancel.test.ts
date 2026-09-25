// @vitest-environment jsdom
/**
 * WO-UI-21 Раунд 2: ручная отмена process instance.
 *
 * - CRIT-1: cancelled=true → бейдж statusCancelled в шапке деталей.
 * - CRIT-2: кнопка «Отменить» только для активного instance.
 * - CRIT-3: клик → confirm → POST .../cancel → статус CANCELLED без
 *   ручного refresh (компонент сам перечитывает instance через reloadAll;
 *   второй ответ мока — cancelled=true, бейдж переключается).
 * - CRIT-4: 409 → понятный тост, без молчаливого падения.
 * - CRIT-5: 403 → понятный тост; кнопка заранее НЕ скрывается по правам
 *   (паттерн проекта как archive в ProcessDefinitionList).
 * - CRIT-6: существующие тесты не тронуты (гоняются полным прогоном).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ProcessInstanceDetail from './ProcessInstanceDetail.vue'

const mockGetInstance = vi.hoisted(() => vi.fn())
const mockCancel = vi.hoisted(() => vi.fn())
const mockToastSuccess = vi.hoisted(() => vi.fn())
const mockToastError = vi.hoisted(() => vi.fn())

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: 'pi-1' } }),
  useRouter: () => ({ push: vi.fn() }),
}))

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({
    success: mockToastSuccess,
    error: mockToastError,
    info: vi.fn(),
    warning: vi.fn(),
    loading: vi.fn(),
    dismiss: vi.fn(),
  }),
}))

vi.mock('@/services/processService', () => ({
  getProcessDefinitionXml: vi.fn().mockResolvedValue('<definitions id="test" />'),
  getProcessDefinitionStructure: vi.fn().mockResolvedValue({ id: 'pd-1', key: 'test', version: 1, name: 'Test', documentation: null, nodes: [], flows: [] }),
  getProcessDefinition: vi.fn().mockResolvedValue(null),
}))

vi.mock('@/services/taskService', () => ({
  completeUserTask: vi.fn().mockResolvedValue(undefined),
  completeServiceTask: vi.fn().mockResolvedValue(undefined),
  getServiceTasks: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  getUserTasks: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  getUserTask: vi.fn().mockResolvedValue(null),
}))

vi.mock('@/services/incidentService', () => ({
  resolveIncident: vi.fn().mockResolvedValue(undefined),
  getIncidents: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  getIncident: vi.fn().mockResolvedValue(null),
}))

vi.mock('@/services/instanceService', () => ({
  getProcessInstance: mockGetInstance,
  getProcessInstanceActivities: vi.fn().mockResolvedValue([]),
  getProcessInstanceActivitiesPaged: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  getProcessInstances: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  startProcessInstance: vi.fn(),
  cancelProcessInstance: mockCancel,
}))

vi.mock('@/services/variableService', () => ({
  getVariables: vi.fn().mockResolvedValue({ data: [] }),
}))

const stubs = { teleport: true, BpmnViewer: { template: '<div class="bpmn-stub" />' } }

function activeInstance() {
  return { id: 'pi-1', parentActivityId: null, processDefinitionId: 'pd-1', startedAt: '2026-01-01', completedAt: null, cancelled: false, processName: 'Test', processKey: 'test', processVersion: 1 }
}

function completedInstance() {
  return { ...activeInstance(), completedAt: '2026-02-01' }
}

function cancelledInstance() {
  return { ...activeInstance(), completedAt: '2026-02-01', cancelled: true }
}

describe('WO-UI-21 Round 2: ProcessInstanceDetail manual cancel', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    mockGetInstance.mockResolvedValue(activeInstance())
    mockCancel.mockResolvedValue({ id: 'pi-1' })
  })

  async function mountDetail() {
    const wrapper = mount(ProcessInstanceDetail, {
      global: { stubs, plugins: [createPinia()] },
    })
    await flushPromises()
    return wrapper
  }

  function badgeText(wrapper: ReturnType<typeof mount>) {
    return wrapper.find('[data-testid="instance-status-badge"]').text()
  }

  it('CRIT-1(detail): cancelled instance shows statusCancelled, completed shows completed', async () => {
    mockGetInstance.mockResolvedValue(cancelledInstance())
    const wrapper = await mountDetail()
    expect(badgeText(wrapper)).toBe('statusCancelled')

    mockGetInstance.mockResolvedValue(completedInstance())
    const wrapper2 = await mountDetail()
    expect(badgeText(wrapper2)).toBe('completed')
  })

  it('CRIT-2: cancel button visible only for the active instance', async () => {
    const active = await mountDetail()
    expect(active.find('[data-testid="cancel-instance-btn"]').exists()).toBe(true)

    mockGetInstance.mockResolvedValue(completedInstance())
    const completed = await mountDetail()
    expect(completed.find('[data-testid="cancel-instance-btn"]').exists()).toBe(false)

    mockGetInstance.mockResolvedValue(cancelledInstance())
    const cancelled = await mountDetail()
    expect(cancelled.find('[data-testid="cancel-instance-btn"]').exists()).toBe(false)
  })

  it('CRIT-3: click → confirm → POST cancel → badge flips to CANCELLED without manual refresh', async () => {
    // Первый ответ — активный (mount), все следующие — уже отменённый
    // (reloadAll внутри confirmCancel перечитывает именно их).
    mockGetInstance.mockResolvedValueOnce(activeInstance()).mockResolvedValue(cancelledInstance())
    const wrapper = await mountDetail()
    expect(badgeText(wrapper)).toBe('running')

    await wrapper.find('[data-testid="cancel-instance-btn"]').trigger('click')
    expect(wrapper.find('[data-testid="cancel-dialog"]').exists()).toBe(true)

    await wrapper.find('[data-testid="cancel-dialog-confirm"]').trigger('click')
    await flushPromises()

    // Вызван реальный cancel-эндпоинт через сервис с id инстанса…
    expect(mockCancel).toHaveBeenCalledTimes(1)
    expect(mockCancel).toHaveBeenCalledWith('pi-1')
    // …успех — понятный тост, диалог закрыт…
    expect(mockToastSuccess).toHaveBeenCalledWith('processCancelled')
    expect(wrapper.find('[data-testid="cancel-dialog"]').exists()).toBe(false)
    // …и статус обновлён перечитыванием, не ручным refresh: бейдж — CANCELLED.
    expect(badgeText(wrapper)).toBe('statusCancelled')
  })

  it('CRIT-4: 409 (someone else finished/cancelled) shows a friendly error, does not fail silently', async () => {
    mockCancel.mockRejectedValueOnce({ response: { status: 409, data: { message: 'Process instance already completed or cancelled' } } })
    const wrapper = await mountDetail()

    await wrapper.find('[data-testid="cancel-instance-btn"]').trigger('click')
    await wrapper.find('[data-testid="cancel-dialog-confirm"]').trigger('click')
    await flushPromises()

    expect(mockToastError).toHaveBeenCalledWith('instanceAlreadyFinished')
    expect(mockToastSuccess).not.toHaveBeenCalled()
    // 409-ветка перезагружает instance, чтобы показать актуальный статус —
    // перезагрузка реально произошла (больше одного чтения).
    expect(mockGetInstance.mock.calls.length).toBeGreaterThan(1)
  })

  it('CRIT-5: 403 (no DELETE_PROCESS) shows an explicit error; button is NOT pre-hidden by rights', async () => {
    mockCancel.mockRejectedValueOnce({ response: { status: 403, data: { message: 'Forbidden' } } })
    const wrapper = await mountDetail()

    // Паттерн проекта (как archive в ProcessDefinitionList): кнопка видна,
    // права проверяет бэкенд, 403 превращается в понятную ошибку.
    expect(wrapper.find('[data-testid="cancel-instance-btn"]').exists()).toBe(true)

    await wrapper.find('[data-testid="cancel-instance-btn"]').trigger('click')
    await wrapper.find('[data-testid="cancel-dialog-confirm"]').trigger('click')
    await flushPromises()

    expect(mockToastError).toHaveBeenCalledWith('cancelNotAllowed')
    expect(mockToastSuccess).not.toHaveBeenCalled()
  })

  it('other backend errors surface the backend message, not a silent failure', async () => {
    mockCancel.mockRejectedValueOnce({ response: { status: 500, data: { message: 'boom' } } })
    const wrapper = await mountDetail()

    await wrapper.find('[data-testid="cancel-instance-btn"]').trigger('click')
    await wrapper.find('[data-testid="cancel-dialog-confirm"]').trigger('click')
    await flushPromises()

    expect(mockToastError).toHaveBeenCalledWith('boom')
  })
})
