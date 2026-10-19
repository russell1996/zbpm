// @vitest-environment jsdom
/**
 * WO-UI-37 (часть B) — страница инстанса: переменные по скоупам.
 *
 * Контракт (WO):
 *  (а) переключатель «Показать переменные скоупов» на вкладке «Переменные»,
 *      по умолчанию ВЫКЛЮЧЕН — root-таблица как сейчас, запрос
 *      variables-by-scope не летит;
 *  (б) включение → fetch /process-instances/{id}/variables-by-scope, группы
 *      отображаются и подписаны (root «Процесс (root)», скоуп «элемент ·
 *      статус»), режим сохраняется в localStorage (try/catch);
 *  (в) сохранённый режим переживает перезагрузку: localStorage=1 до mount →
 *      запрос уже на первичной загрузке;
 *  (г) завершённый инстанс — бэкенд отдаёт только root (движок сводит
 *      скоуп-переменные при завершении скоупа, C8-51:
 *      instanceFinished_onlyRootGroupRemains) → UI показывает только root;
 *  (д) экспорт «Диагностика JSON» несёт variablesByScope (свежий fetch при
 *      экспорте), schemaVersion остаётся 2;
 *  (е) UI-32: событие при активной вкладке и включённом режиме → тихий
 *      refetch групп (склеенный, счёт вызовов); выключенный режим → 0
 *      запросов (не грузить лишнего).
 *
 * Мутации (каждая обязана краснить, см. отчёт):
 *  M1 — убрать quietScopeVariables из onTabChange/schedule → (б)/(в)/(е);
 *  M2 — режим по умолчанию true (readScopeVariablesMode → true) → (а);
 *  M3 — downloadDiagnostic не передаёт variablesByScope в buildDiagnosticJson
 *       → (д);
 *  M4 — убрать fetch при включении тумблера → (б).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ProcessInstanceDetail from './ProcessInstanceDetail.vue'
import { publishRealtimeEvent, resetRealtimeBusForTest } from '@/services/realtimeBus'
import { resetRealtimeSchedulerForTest } from '@/services/realtimeScheduler'
import type { EventEnvelope, ProcessVariable, VariableScopeGroup } from '@/types/api'

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: 'pi-1' }, path: '/instances/pi-1', query: {} }),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn().mockResolvedValue(undefined) }),
}))

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string, p?: Record<string, unknown>) => k, locale: { value: 'en' } }),
}))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ user: { id: 'u1', username: 'op' }, isSuperAdmin: true, myMembership: null }),
}))

vi.mock('@/stores/breadcrumb', () => ({
  useBreadcrumbStore: () => ({ setCrumbLabel: vi.fn() }),
}))

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn(), info: vi.fn() }),
}))

vi.mock('@/composables/useDateFormat', () => ({
  useDateFormat: () => ({ formatDate: (v: string) => v, formatDateTime: (v: string) => v }),
}))

vi.mock('@/shared/lib/export', () => ({ exportToCsv: vi.fn() }))

vi.mock('@/services/adminService', () => ({
  listMembers: vi.fn().mockResolvedValue([]),
  changeMemberRole: vi.fn(),
  removeMember: vi.fn(),
}))

const mockGetProcessInstance = vi.hoisted(() => vi.fn())
const mockGetActivitiesPaged = vi.hoisted(() => vi.fn())
const mockGetProcessInstances = vi.hoisted(() => vi.fn())
const mockGetUserTasks = vi.hoisted(() => vi.fn())
const mockGetServiceTasks = vi.hoisted(() => vi.fn())
const mockGetIncidents = vi.hoisted(() => vi.fn())
const mockGetVariables = vi.hoisted(() => vi.fn())
const mockGetVariablesByScope = vi.hoisted(() => vi.fn())

vi.mock('@/services/processService', () => ({
  getProcessDefinitionXml: vi.fn().mockResolvedValue('<definitions id="test" />'),
  getProcessDefinitionStructure: vi.fn().mockResolvedValue({
    id: 'pd-1', key: 'test', version: 1, name: 'Test', documentation: null, nodes: [], flows: [],
  }),
  getProcessDefinition: vi.fn().mockResolvedValue(null),
  getProcessDefinitionVersions: vi.fn().mockResolvedValue([]),
  getProcessDefinitions: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 10 }),
}))

vi.mock('@/services/instanceService', () => ({
  getProcessInstance: mockGetProcessInstance,
  getProcessInstanceActivities: vi.fn().mockResolvedValue([]),
  getProcessInstanceActivitiesPaged: mockGetActivitiesPaged,
  getProcessInstances: mockGetProcessInstances,
  startProcessInstance: vi.fn(),
  cancelProcessInstance: vi.fn(),
}))

vi.mock('@/services/taskService', () => ({
  getUserTasks: mockGetUserTasks,
  getUserTask: vi.fn().mockResolvedValue(null),
  completeUserTask: vi.fn().mockResolvedValue(undefined),
  getServiceTasks: mockGetServiceTasks,
  getServiceTask: vi.fn().mockResolvedValue(null),
  completeServiceTask: vi.fn().mockResolvedValue(undefined),
  failServiceTask: vi.fn().mockResolvedValue(undefined),
  throwServiceTaskError: vi.fn().mockResolvedValue(undefined),
}))

vi.mock('@/services/incidentService', () => ({
  getIncidents: mockGetIncidents,
  getIncident: vi.fn().mockResolvedValue(null),
  resolveIncident: vi.fn().mockResolvedValue(undefined),
}))

vi.mock('@/services/variableService', () => ({
  getVariables: mockGetVariables,
  getVariablesByScope: mockGetVariablesByScope,
}))

vi.mock('@/services/formService', () => ({
  getTaskForm: vi.fn().mockResolvedValue(null),
  getStartForm: vi.fn().mockResolvedValue(null),
}))

const stubs = { teleport: true, BpmnViewer: { template: '<div class="bpmn-stub" />' } }

const STORAGE_KEY = 'zbpm.instance.variables-by-scope'

function envelope(partial: Partial<EventEnvelope> & { type: string }): EventEnvelope {
  return {
    sequence: 1,
    id: `e-${Math.random()}`,
    version: 1,
    occurredAt: '2026-10-14T00:00:00Z',
    processInstanceId: 'pi-1',
    data: {},
    ...partial,
  } as EventEnvelope
}

const piRow = {
  id: 'pi-1', parentActivityId: null, processDefinitionId: 'pd-1',
  processName: 'Test', processKey: 'test', processVersion: 1,
  startedAt: '2026-01-01', completedAt: null, cancelled: false,
}

function varRow(name: string, value: string, type: 'STRING' | 'JSON' = 'STRING'): ProcessVariable {
  return { name, type, value }
}

function rootGroup(vars: ProcessVariable[] = []): VariableScopeGroup {
  return { scope: null, variables: vars }
}

function scopeGroup(activityId: string, status: string, vars: ProcessVariable[] = []): VariableScopeGroup {
  return {
    scope: {
      activityId,
      bpmnElementId: 'miSub',
      elementType: 'SUB_PROCESS',
      status,
      parentScopeActivityId: null,
    },
    variables: vars,
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms))
}

describe('ProcessInstanceDetail: переменные по скоупам (WO-UI-37 part B)', () => {
  let activeWrapper: ReturnType<typeof mount> | null = null

  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    resetRealtimeBusForTest()
    resetRealtimeSchedulerForTest()
    window.localStorage.clear()
    mockGetProcessInstance.mockResolvedValue({ ...piRow })
    mockGetActivitiesPaged.mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 })
    mockGetProcessInstances.mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 })
    mockGetUserTasks.mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 })
    mockGetServiceTasks.mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 })
    mockGetIncidents.mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 })
    mockGetVariables.mockResolvedValue({ data: [varRow('rootVar', 'root-value')] })
    mockGetVariablesByScope.mockResolvedValue([
      rootGroup([varRow('rootVar', 'root-value')]),
      scopeGroup('act-scope-1', 'IN_PROGRESS', [varRow('item', 'a')]),
    ])
  })

  afterEach(() => {
    activeWrapper?.unmount()
    activeWrapper = null
    resetRealtimeBusForTest()
    resetRealtimeSchedulerForTest()
    window.localStorage.clear()
  })

  async function mountPage() {
    const wrapper = mount(ProcessInstanceDetail, {
      global: { stubs, plugins: [createPinia()] },
    })
    activeWrapper = wrapper
    await flushPromises()
    await sleep(100)
    await flushPromises()
    return wrapper
  }

  async function clickTab(wrapper: ReturnType<typeof mount>, id: string): Promise<void> {
    const tab = wrapper.findAll('button[role="tab"]').find((b) => b.text() === id)
    expect(tab, `tab button "${id}" exists`).toBeTruthy()
    await tab!.trigger('click')
    await flushPromises()
    await sleep(50)
    await flushPromises()
  }

  /** Реальный клик по тумблеру (reka v2: click → toggleCheck →
   *  update:modelValue → onScopeVariablesToggle). Не $emit: мутация
   *  «убрать обработчик» обязана краснить ровно через этот путь. */
  async function toggleScopeSwitch(wrapper: ReturnType<typeof mount>, on: boolean): Promise<void> {
    const btn = wrapper.find('button[role="switch"]')
    expect(btn, 'switch button in DOM').toBeTruthy()
    const state = btn.attributes('data-state')
    if ((state === 'checked') !== on) {
      await btn.trigger('click')
      await flushPromises()
      await sleep(50)
      await flushPromises()
    }
  }

  /** DOM-состояние тумблера: data-state на нативном role="switch". */
  function switchState(wrapper: ReturnType<typeof mount>): string {
    const btn = wrapper.find('button[role="switch"]')
    expect(btn, 'switch button in DOM').toBeTruthy()
    return btn.attributes('data-state') ?? ''
  }

  it('(а) по умолчанию выключен: root-таблица как сейчас, запрос variables-by-scope не летит', async () => {
    const wrapper = await mountPage()
    await clickTab(wrapper, 'variables')

    expect(mockGetVariablesByScope).not.toHaveBeenCalled()
    expect(switchState(wrapper)).toBe('unchecked')
    // панель групп не смонтирована, root-строка на месте
    expect(wrapper.find('[data-testid="scope-group-root"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('rootVar')
  })

  it('(б) включение → fetch + группы с подписями + localStorage=1', async () => {
    const wrapper = await mountPage()
    await clickTab(wrapper, 'variables')
    expect(mockGetVariablesByScope).not.toHaveBeenCalled()

    await toggleScopeSwitch(wrapper, true)
    expect(switchState(wrapper)).toBe('checked')

    expect(mockGetVariablesByScope).toHaveBeenCalledTimes(1)
    expect(mockGetVariablesByScope).toHaveBeenCalledWith('pi-1')
    // группы отображаются и подписаны
    const root = wrapper.find('[data-testid="scope-group-root"]')
    expect(root.exists()).toBe(true)
    expect(root.text()).toContain('processRootGroup')
    const scope = wrapper.find('[data-testid="scope-group-act-scope-1"]')
    expect(scope.exists()).toBe(true)
    expect(scope.text()).toContain('miSub')
    expect(scope.text()).toContain('statusInProgress')
    expect(scope.text()).toContain('item')
    // режим сохранён
    expect(window.localStorage.getItem(STORAGE_KEY)).toBe('1')
  })

  it('(в) сохранённый режим (localStorage=1 до mount) → fetch на первичной загрузке', async () => {
    window.localStorage.setItem(STORAGE_KEY, '1')
    const wrapper = await mountPage()

    expect(mockGetVariablesByScope).toHaveBeenCalledTimes(1)
    expect(mockGetVariablesByScope).toHaveBeenCalledWith('pi-1')
    // тумблер включён (режим пережил mount), группы на экране после открытия
    await clickTab(wrapper, 'variables')
    expect(switchState(wrapper)).toBe('checked')
    expect(wrapper.find('[data-testid="scope-group-act-scope-1"]').exists()).toBe(true)
  })

  it('(г) завершённый инстанс — бэкенд отдаёт только root → UI показывает только root', async () => {
    // C8-51: instanceFinished_onlyRootGroupRemains — скоуп-переменные движок
    // сводит в root при завершении, групп больше не существует.
    mockGetVariablesByScope.mockResolvedValue([rootGroup([varRow('items', '["a","b"]', 'JSON')])])
    const wrapper = await mountPage()
    await clickTab(wrapper, 'variables')
    await toggleScopeSwitch(wrapper, true)

    expect(wrapper.find('[data-testid="scope-group-root"]').exists()).toBe(true)
    const scopeSections = wrapper.findAll('[data-testid^="scope-group-act-"]')
    expect(scopeSections).toHaveLength(0)
    expect(wrapper.find('[data-testid="scope-group-root"]').text()).toContain('items')
  })

  it('(д) экспорт несёт variablesByScope (свежий fetch), schemaVersion 2', async () => {
    const createSpy = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:test')
    try {
      const wrapper = await mountPage()
      await clickTab(wrapper, 'variables')
      // переключатель выключен — экспорт всё равно тянет группы (консьюмер
      // должен видеть скоупы независимо от режима просмотра)
      expect(mockGetVariablesByScope).not.toHaveBeenCalled()

      const allButtons = wrapper.findAll('button')
      const downloadBtn = allButtons.find((b) => b.text().trim() === 'downloadDiagnostic')
      expect(downloadBtn, `Button 'downloadDiagnostic' not found. All buttons: ${allButtons.map((b) => `"${b.text().trim()}"`).join(', ')}`).toBeDefined()
      await downloadBtn!.trigger('click')
      await flushPromises()
      await sleep(50)
      await flushPromises()

      expect(mockGetVariablesByScope).toHaveBeenCalledTimes(1)
      expect(mockGetVariablesByScope).toHaveBeenCalledWith('pi-1')

      expect(createSpy).toHaveBeenCalledTimes(1)
      const blobArg = createSpy.mock.calls[0][0] as Blob
      const parsed = JSON.parse(await blobArg.text())
      expect(parsed.schemaVersion).toBe(2)
      expect(parsed.variablesByScope).toHaveLength(2)
      expect(parsed.variablesByScope[0].scope).toBeNull()
      expect(parsed.variablesByScope[1].scope.activityId).toBe('act-scope-1')
      expect(parsed.variablesByScope[1].variables).toEqual([{ name: 'item', type: 'STRING', value: 'a' }])
    } finally {
      createSpy.mockRestore()
    }
  })

  it('(ж) повторное открытие вкладки перечитывает группы (тихо)', async () => {
    const wrapper = await mountPage()
    await clickTab(wrapper, 'variables')
    await toggleScopeSwitch(wrapper, true)
    const afterOn = mockGetVariablesByScope.mock.calls.length
    expect(afterOn).toBe(1)

    // уход на другую вкладку и обратно — свежие группы (UI-32: клик =
    // перечитывание собственного источника), без изменения DOM-состава
    await clickTab(wrapper, 'tasks')
    await clickTab(wrapper, 'variables')
    expect(mockGetVariablesByScope.mock.calls.length).toBeGreaterThanOrEqual(afterOn + 1)
    expect(wrapper.find('[data-testid="scope-group-act-scope-1"]').exists()).toBe(true)
  })

  it('(е) UI-32: событие при активной вкладке и включённом режиме → 1 тихий refetch групп; выключенный режим → 0', async () => {
    const wrapper = await mountPage()
    await clickTab(wrapper, 'variables')
    await toggleScopeSwitch(wrapper, true)
    const afterToggle = mockGetVariablesByScope.mock.calls.length
    expect(afterToggle).toBe(1)

    // «сервер» вернул свежие скоуп-значения
    mockGetVariablesByScope.mockResolvedValue([
      rootGroup([varRow('rootVar', 'root-v2')]),
      scopeGroup('act-scope-1', 'COMPLETED', [varRow('item', 'b')]),
    ])
    publishRealtimeEvent(envelope({ type: 'activity.completed', elementId: 'act-x' }))
    await sleep(900)
    await flushPromises()

    expect(mockGetVariablesByScope.mock.calls.length).toBeGreaterThanOrEqual(afterToggle + 1)
    // новые значения в DOM
    const scope = wrapper.find('[data-testid="scope-group-act-scope-1"]')
    expect(scope.text()).toContain('b')
    expect(scope.text()).toContain('statusCompleted')

    // выключенный режим → события больше не тянут группы
    await toggleScopeSwitch(wrapper, false)
    const afterOff = mockGetVariablesByScope.mock.calls.length
    publishRealtimeEvent(envelope({ type: 'activity.completed', elementId: 'act-y' }))
    await sleep(900)
    await flushPromises()
    expect(mockGetVariablesByScope.mock.calls.length).toBe(afterOff)
  })
})
