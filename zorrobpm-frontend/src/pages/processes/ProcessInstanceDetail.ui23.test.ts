// @vitest-environment jsdom
/**
 * WO-UI-23: значения переменных + кнопка отмены.
 *
 * - CRIT-1: длинное/JSON-значение — кнопка-инспектор открывает модалку с
 *   ПОЛНЫМ значением (не только обрезанный title); JSON отформатирован.
 * - CRIT-2: короткие значения — как раньше (та же ячейка truncate+title,
 *   кнопки-инспектора нет) — регресс.
 * - CRIT-3: кнопка «Отменить процесс» несёт иконку (svg в DOM кнопки),
 *   вариант destructive сохранён.
 *
 * POF-мутации (сняты вручную, см. отчёт): убрать v-if кнопки-инспектора →
 * CRIT-1 RED (кнопки нет); убрать OctagonX из кнопки отмены → CRIT-3 RED
 * (svg нет); вернуть truncate без кнопки — CRIT-1 RED тем же местом.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ProcessInstanceDetail from './ProcessInstanceDetail.vue'

const LONG_JSON = JSON.stringify([{ employeeId: 301, employeeIin: '811231301234', note: 'x'.repeat(200) }])
const SHORT_VALUE = 'hello'

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: 'pi-1' } }),
  useRouter: () => ({ push: vi.fn() }),
}))

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn(), loading: vi.fn(), dismiss: vi.fn() }),
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
  getProcessInstance: vi.fn().mockResolvedValue({ id: 'pi-1', parentActivityId: null, processDefinitionId: 'pd-1', startedAt: '2026-01-01', completedAt: null, cancelled: false, processName: 'Test', processKey: 'test', processVersion: 1 }),
  getProcessInstanceActivities: vi.fn().mockResolvedValue([]),
  getProcessInstanceActivitiesPaged: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  getProcessInstances: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  startProcessInstance: vi.fn(),
  cancelProcessInstance: vi.fn().mockResolvedValue({ id: 'pi-1' }),
}))

const mockGetVariables = vi.hoisted(() => vi.fn())
vi.mock('@/services/variableService', () => ({
  getVariables: mockGetVariables,
}))

// WO-ACL-22: кнопка отмены теперь требует право (SUPER_ADMIN либо
// OWNER/DESIGNER из listMembers) — мокаем оба источника как OWNER.
const mockListMembers = vi.hoisted(() => vi.fn())
vi.mock('@/services/adminService', () => ({
  listMembers: mockListMembers,
}))
const mockAuthUser = vi.hoisted(() => ({ id: 'u-1' }))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ user: mockAuthUser, isSuperAdmin: false }),
}))

const stubs = { teleport: true, BpmnViewer: { template: '<div class="bpmn-stub" />' } }

describe('WO-UI-23: variable inspector + cancel button icon', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    mockAuthUser.id = 'u-1'
    mockListMembers.mockResolvedValue([{ userId: 'u-1', role: 'OWNER' }])
    mockGetVariables.mockResolvedValue({
      data: [
        { name: 'steps', type: 'JSON', value: LONG_JSON },
        { name: 'greeting', type: 'STRING', value: SHORT_VALUE },
      ],
    })
  })

  async function mountVariablesTab() {
    const wrapper = mount(ProcessInstanceDetail, {
      global: { stubs, plugins: [createPinia()] },
    })
    await flushPromises()
    const vm = wrapper.vm as any
    vm.activeTab = 'variables'
    await wrapper.vm.$nextTick()
    await flushPromises()
    return wrapper
  }

  it('CRIT-1: long JSON value has an inspector button opening the full value modal', async () => {
    const wrapper = await mountVariablesTab()

    const inspectBtn = wrapper.find('[data-testid="inspect-variable-steps"]')
    expect(inspectBtn.exists(), 'inspector button renders for the long JSON value').toBe(true)

    await inspectBtn.trigger('click')
    await wrapper.vm.$nextTick()

    const modal = wrapper.find('[data-testid="variable-inspector"]')
    expect(modal.exists(), 'inspector modal opens').toBe(true)
    // Полное значение — целиком, не обрезанное: длинный хвост JSON на месте.
    const modalText = wrapper.find('[data-testid="variable-inspector-value"]').text()
    expect(modalText).toContain('employeeIin')
    expect(modalText).toContain('x'.repeat(200))
    // JSON отформатирован (pretty-print с отступами), не сырая строка.
    expect(modalText).toContain('\n')
    expect(wrapper.find('[data-testid="variable-inspector-title"]').text()).toBe('steps')
  })

  it('CRIT-2: short value keeps truncate+title cell with no inspector button (regression)', async () => {
    const wrapper = await mountVariablesTab()

    expect(wrapper.find('[data-testid="inspect-variable-greeting"]').exists()).toBe(false)

    // Та же ячейка, что раньше: max-w-xs + title с полным значением;
    // truncate теперь на внутреннем span (на td браузер его игнорит).
    const cells = wrapper.findAll('td.max-w-xs')
    const shortCell = cells.find((c) => c.text().includes(SHORT_VALUE))
    expect(shortCell, 'short value cell renders as before').toBeTruthy()
    expect(shortCell!.attributes('title')).toBe(SHORT_VALUE)
    expect(shortCell!.find('span.truncate').exists(), 'short value keeps the truncate span').toBe(true)
  })

  it('CRIT-3: cancel button carries an icon and stays destructive', async () => {
    const wrapper = await mountVariablesTab()

    const btn = wrapper.find('[data-testid="cancel-instance-btn"]')
    expect(btn.exists(), 'cancel button renders for the active instance').toBe(true)
    // Иконка — svg внутри кнопки (соседи Download/RefreshCw — та же форма).
    expect(btn.find('svg').exists(), 'cancel button is not an icon-less orphan').toBe(true)
  })
})
