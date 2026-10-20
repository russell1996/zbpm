// @vitest-environment jsdom
/**
 * WO-UI-17 F25 — живая замена удалённым `MainLayout.breadcrumb*.test.ts`
 * (4 × `describe.skip`).
 *
 * Почему skip'ы удалены, а не включены: WO-UI-14 убрал BreadcrumbNav из
 * MainLayout (критерий 1: «Breadcrumbs удалены из MainLayout») и заменил
 * крошки компактными шапками с back-arrow на 5 страницах детализации.
 * Старые сьюты assert'или `nav`/`li` внутри MainLayout — их там больше нет
 * физически, включать их нечего. Замена проверяет актуальное поведение:
 * каждая детальная страница рендерит back-arrow ССЫЛКОЙ на родительский
 * список (WO-UI-41: ссылка с сохранёнными фильтрами списка), и клик реально
 * ведёт туда (настоящий router, настоящие страницы,
 * настоящие Pinia-сторы с замоканными сервисами — стиль P-54, как в
 * `ProcessInstanceDetail.test.ts` / `TaskDetail.test.ts`).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import MainLayout from './MainLayout.vue'
import ProcessDefinitionDetail from '@/pages/processes/ProcessDefinitionDetail.vue'
import ProcessInstanceDetail from '@/pages/processes/ProcessInstanceDetail.vue'
import TaskDetail from '@/pages/tasks/TaskDetail.vue'
import ServiceTaskDetail from '@/pages/tasks/ServiceTaskDetail.vue'
import IncidentDetail from '@/pages/incidents/IncidentDetail.vue'
import BreadcrumbNav from '@/widgets/shared/BreadcrumbNav.vue'

vi.mock('@bpmn-io/form-js', () => ({
  Form: class MockForm {
    constructor() {}
    submit = vi.fn().mockReturnValue({ data: {}, errors: {} })
    importSchema = vi.fn()
    destroy = vi.fn()
  },
}))

vi.mock('@/services/processService', () => ({
  getProcessDefinitions: vi.fn().mockResolvedValue({ data: [], totalElements: 0 }),
  getProcessDefinition: vi.fn().mockResolvedValue({
    id: 'def1', key: 'test-proc', version: 1, name: 'Test Proc',
    sha256: 'abc', createdAt: '2026-01-01', startFormKey: null,
  }),
  getProcessDefinitionXml: vi.fn().mockResolvedValue('<definitions id="test" />'),
  getProcessDefinitionStructure: vi.fn().mockResolvedValue({
    id: 'def1', key: 'test-proc', version: 1, name: 'Test Proc',
    documentation: null, nodes: [], flows: [],
  }),
  getProcessDefinitionVersions: vi.fn().mockResolvedValue([]),
}))

vi.mock('@/services/adminService', () => ({
  listMembers: vi.fn().mockResolvedValue([]),
  changeMemberRole: vi.fn(),
  removeMember: vi.fn(),
}))

vi.mock('@/services/formService', () => ({
  getTaskForm: vi.fn().mockResolvedValue({ type: 'none' }),
  getSchemaMap: vi.fn().mockResolvedValue({ processDefinitionKey: 'test-proc', version: 1, elements: [] }),
  saveElementSchema: vi.fn(),
  getForm: vi.fn(),
  listForms: vi.fn().mockResolvedValue([]),
  createElementBinding: vi.fn(),
}))

vi.mock('@/services/instanceService', () => ({
  getProcessInstance: vi.fn().mockResolvedValue({
    id: 'pi-1', parentActivityId: null, processDefinitionId: 'def1',
    startedAt: '2026-01-01', completedAt: null,
    processName: 'Test Proc', processKey: 'test-proc', processVersion: 1,
  }),
  getProcessInstanceActivities: vi.fn().mockResolvedValue([]),
  getProcessInstanceActivitiesPaged: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  getProcessInstances: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  startProcessInstance: vi.fn(),
}))

vi.mock('@/services/taskService', () => ({
  getUserTask: vi.fn().mockResolvedValue({
    id: 'task-1', code: 'Approve Order', name: 'Approve Order',
    processInstanceId: 'pi-1', processDefinitionId: 'def1', formKey: null,
    status: 'CREATED', createdAt: '2026-01-01', completedAt: null,
  }),
  getServiceTask: vi.fn().mockResolvedValue({
    id: 'st-1', code: 'Call ERP', name: 'Call ERP',
    processInstanceId: 'pi-1', processDefinitionId: 'def1', job: 'job-1',
    status: 'CREATED', createdAt: '2026-01-01', completedAt: null,
  }),
  getUserTasks: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  getServiceTasks: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  completeUserTask: vi.fn().mockResolvedValue({ id: 'done' }),
  completeServiceTask: vi.fn().mockResolvedValue({ id: 'done' }),
}))

vi.mock('@/services/incidentService', () => ({
  getIncident: vi.fn().mockResolvedValue({
    id: 'inc-1', activityId: 'act-1', message: 'Task failed',
    createdAt: '2026-01-01', completedAt: null,
    processName: 'Test Proc', processInstanceId: 'pi-1',
    bpmnElementId: 'Activity_1', elementName: 'Approve Order',
  }),
  getIncidents: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  resolveIncident: vi.fn().mockResolvedValue(undefined),
}))

vi.mock('@/services/variableService', () => ({
  getVariables: vi.fn().mockResolvedValue({ data: [] }),
}))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ user: { id: 'u-1' }, isSuperAdmin: true }),
}))

vi.mock('vue-i18n', async (importOriginal) => {
  // Partial mock: pages use useI18n (stubbed), but the app/i18n module chain
  // (via HeaderBar → LanguageSwitcher) needs the real createI18n.
  const actual = await importOriginal<typeof import('vue-i18n')>()
  return {
    ...actual,
    useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
  }
})
vi.mock('@/composables/useToast', () => ({ useToast: () => ({ success: vi.fn(), error: vi.fn() }) }))
vi.mock('@/composables/useDateFormat', () => ({
  useDateFormat: () => ({ formatDate: (v: string) => v, formatDateTime: (v: string) => v }),
}))

function makeRouter() {
  return createRouter({
    history: createMemoryHistory('/ui/'),
    routes: [
      // Пути — как в продовом router.ts (base '/ui/' уже задан историей):
      // иначе href получался бы '/ui/ui/tasks' и проверка адреса была бы
      // проверкой артефакта тестового роутера, а не поведения.
      { path: '/processes/definitions', name: 'process-definitions', component: { template: '<div/>' } },
      { path: '/processes/definitions/:id', name: 'process-definition-detail', component: ProcessDefinitionDetail },
      { path: '/processes/instances', name: 'process-instances', component: { template: '<div/>' } },
      { path: '/processes/instances/:id', name: 'process-instance-detail', component: ProcessInstanceDetail },
      { path: '/tasks', name: 'my-tasks', component: { template: '<div/>' } },
      { path: '/tasks/:id', name: 'task-detail', component: TaskDetail },
      { path: '/service-tasks', name: 'service-tasks', component: { template: '<div/>' } },
      { path: '/service-tasks/:id', name: 'service-task-detail', component: ServiceTaskDetail },
      { path: '/incidents', name: 'incidents', component: { template: '<div/>' } },
      { path: '/incidents/:id', name: 'incident-detail', component: IncidentDetail },
    ],
  })
}

const pageStubs = {
  teleport: true,
  BpmnViewer: { template: '<div class="bpmn-stub" />' },
  SchemaEditorPanel: { template: '<div class="schema-stub" />' },
}

/**
 * Mounts a real detail page on its real route and returns the back-arrow link.
 */
async function backArrowTarget(
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  page: any,
  detailRoute: string,
) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const router = makeRouter()
  await router.push(detailRoute)
  await router.isReady()
  const wrapper = mount(page, { global: { stubs: pageStubs, plugins: [pinia, router] } })
  await flushPromises()
  const back = wrapper.find('[data-testid="back-to-list"]')
  return { wrapper, router, back }
}

describe('WO-UI-17 F25: compact headers carry a working back-arrow (replaces removed breadcrumbs)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    sessionStorage.clear()
  })

  it.each([
    ['definition card', ProcessDefinitionDetail, '/processes/definitions/def1', '/ui/processes/definitions'],
    ['instance card', ProcessInstanceDetail, '/processes/instances/pi-1', '/ui/processes/instances'],
    ['user task card', TaskDetail, '/tasks/task-1', '/ui/tasks'],
    ['service task card', ServiceTaskDetail, '/service-tasks/st-1', '/ui/service-tasks'],
    ['incident card', IncidentDetail, '/incidents/inc-1', '/ui/incidents'],
  ] as const)('%s: back-arrow points at the parent list', async (_label, page, detailRoute, parentPath) => {
    const { back } = await backArrowTarget(page, detailRoute)
    expect(back.exists(), `back-arrow must exist on ${detailRoute}`).toBe(true)
    expect(back.attributes('href')).toBe(parentPath)
  })

  it('clicking the back-arrow really navigates to the parent list', async () => {
    const { router, back } = await backArrowTarget(TaskDetail, '/tasks/task-1')
    expect(back.exists()).toBe(true)
    await back.trigger('click')
    await flushPromises()
    // The click lands on the parent list route (the wrapper itself stays the
    // mounted page — it is not rendered through <router-view> here — so the
    // route name, not unmounting, is the navigation proof).
    expect(router.currentRoute.value.name).toBe('my-tasks')
  })

  /**
   * WO-UI-41: стрелка «назад» ведёт на список С СОХРАНЁННЫМИ ФИЛЬТРАМИ —
   * список помнит свой последний query (sessionStorage, `useListState`).
   */
  it('back-arrow carries the saved list filters (WO-UI-41)', async () => {
    sessionStorage.setItem('zbpm.listState.tasks', JSON.stringify({ 'tasks.completed': '1' }))
    const { back } = await backArrowTarget(TaskDetail, '/tasks/task-1')
    expect(back.exists()).toBe(true)
    expect(back.attributes('href')).toBe('/ui/tasks?tasks.completed=1')
  })

  it('MainLayout renders no BreadcrumbNav (WO-UI-14 criterion 1 still holds)', async () => {
    window.matchMedia = vi.fn().mockImplementation((query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    }))
    const pinia = createPinia()
    setActivePinia(pinia)
    const router = makeRouter()
    await router.push('/tasks/task-1')
    await router.isReady()
    const wrapper = mount(MainLayout, {
      global: {
        stubs: {
          teleport: true,
          SidebarNavShadcn: { template: '<div class="sidebar-stub" />' },
          HeaderBar: { template: '<div class="header-stub" />' },
        },
        plugins: [pinia, router],
      },
    })
    await flushPromises()
    expect(wrapper.findComponent(BreadcrumbNav).exists()).toBe(false)
  })
})
