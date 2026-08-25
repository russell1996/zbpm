// @vitest-environment jsdom
/**
 * WO-ACL-15 criteria 17-18: EVERY detail card with crumbs shows a
 * human-readable leaf (the entity's name, not the static titleKey) and leaving
 * the card clears the breadcrumb store.
 *
 * P-54: these tests run on the REAL tree — MainLayout + <router-view> + the
 * real page component — NOT on a synthetically mounted BreadcrumbNav with a
 * hand-provided value. BreadcrumbNav sits ABOVE <router-view>; a test that
 * mounts it alone can never fail when the page stops filling the store (see
 * POF 8: removing the fill on the instance card reddens THIS file while
 * BreadcrumbNav.test.ts stays green).
 */
import { describe, it, expect, vi, beforeAll, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'
import MainLayout from './MainLayout.vue'
import ProcessInstanceDetail from '@/pages/processes/ProcessInstanceDetail.vue'
import TaskDetail from '@/pages/tasks/TaskDetail.vue'
import ServiceTaskDetail from '@/pages/tasks/ServiceTaskDetail.vue'
import DmnViewer from '@/pages/dmn/DmnViewer.vue'
import { useBreadcrumbStore } from '@/stores/breadcrumb'
import en from '@/locales/en.json'
import ru from '@/locales/ru.json'
import kz from '@/locales/kz.json'

// ── services the pages hit on mount (real stores, mocked services) ─────────
vi.mock('@/services/processService', () => ({
  getProcessDefinitionXml: vi.fn().mockResolvedValue('<definitions id="test" />'),
  getProcessDefinitionStructure: vi.fn().mockResolvedValue({ id: 'pd-1', key: 'test', version: 1, name: 'Test', documentation: null, nodes: [], flows: [] }),
  getProcessDefinition: vi.fn().mockResolvedValue(null),
}))
vi.mock('@/services/instanceService', () => ({
  getProcessInstance: vi.fn().mockResolvedValue({ id: 'pi-1234567890', parentActivityId: null, processDefinitionId: 'pd-1', startedAt: '2026-01-01', completedAt: null, processName: 'Vacation Request', processKey: 'vacation', processVersion: 1 }),
  getProcessInstanceActivities: vi.fn().mockResolvedValue([]),
  getProcessInstances: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  startProcessInstance: vi.fn(),
}))
vi.mock('@/services/taskService', () => ({
  getUserTask: vi.fn().mockResolvedValue({ id: 'task-abcdef1234', code: 'Approve Order', name: 'Approve Order', processInstanceId: 'pi-1', processDefinitionId: 'pd-1', formKey: null, status: 'CREATED', createdAt: '2026-01-01', completedAt: null }),
  getServiceTask: vi.fn().mockResolvedValue({ id: 'st-9876543210', code: 'Call ERP', name: 'Call ERP', processInstanceId: 'pi-1', processDefinitionId: 'pd-1', job: 'job-1', status: 'CREATED', createdAt: '2026-01-01', completedAt: null }),
  getUserTasks: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  getServiceTasks: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  completeUserTask: vi.fn().mockResolvedValue(undefined),
  completeServiceTask: vi.fn().mockResolvedValue(undefined),
}))
vi.mock('@/services/incidentService', () => ({
  resolveIncident: vi.fn().mockResolvedValue(undefined),
  getIncidents: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  getIncident: vi.fn().mockResolvedValue(null),
}))
vi.mock('@/services/variableService', () => ({
  getVariables: vi.fn().mockResolvedValue({ data: [] }),
}))
vi.mock('@/services/formService', () => ({
  getTaskForm: vi.fn().mockResolvedValue(null),
  getStartForm: vi.fn().mockResolvedValue(null),
  listForms: vi.fn().mockResolvedValue([]),
  getForm: vi.fn(),
  getSchemaMap: vi.fn().mockResolvedValue({ processDefinitionKey: 'test', version: 1, elements: [] }),
  saveElementSchema: vi.fn(),
  createElementBinding: vi.fn(),
}))
vi.mock('@/services/timerService', () => ({
  getTimerJobs: vi.fn().mockResolvedValue({ data: [] }),
}))
vi.mock('@/services/dmnService', () => ({
  getDecision: vi.fn().mockResolvedValue({ id: 'dmn-1', name: 'Loan Decision', version: 1, createdAt: '2026-01-01', hitPolicy: 'UNIQUE', inputs: [], outputs: [], rules: [] }),
  evaluateDecision: vi.fn().mockResolvedValue({}),
  inferVariable: vi.fn().mockResolvedValue(null),
}))

vi.mock('@/composables/useToast', () => ({ useToast: () => ({ success: vi.fn(), error: vi.fn() }) }))
vi.mock('@/composables/useDateFormat', () => ({
  useDateFormat: () => ({ formatDate: (v: string) => v, formatDateTime: (v: string) => v }),
}))
vi.mock('@/widgets/shared/SidebarNavShadcn.vue', () => ({ default: { template: '<div class="sidebar-stub" />' } }))
vi.mock('@/widgets/shared/HeaderBar.vue', () => ({ default: { template: '<div class="header-stub" />' } }))
vi.mock('@/widgets/bpmn/BpmnViewer.vue', () => ({ default: { template: '<div class="bpmn-stub" />' } }))
vi.mock('@/widgets/shared/SchemaEditorPanel.vue', () => ({ default: { template: '<div class="schema-stub" />' } }))

const i18n = createI18n({ legacy: false, locale: 'ru', fallbackLocale: 'en', messages: { en, ru, kz } })

function makeRouter() {
  return createRouter({
    history: createMemoryHistory('/ui/'),
    routes: [
      {
        path: '/',
        component: MainLayout,
        children: [
          { path: 'processes/instances', name: 'process-instances', component: { template: '<div>List</div>' }, meta: { titleKey: 'processInstances' } },
          { path: 'processes/instances/:id', name: 'process-instance-detail', component: ProcessInstanceDetail, meta: { titleKey: 'processInstance', parentTitleKey: 'processInstances', parentTo: { name: 'process-instances' } } },
          { path: 'tasks', name: 'my-tasks', component: { template: '<div>List</div>' }, meta: { titleKey: 'myTasks' } },
          { path: 'tasks/:id', name: 'task-detail', component: TaskDetail, meta: { titleKey: 'task', parentTitleKey: 'myTasks', parentTo: { name: 'my-tasks' } } },
          { path: 'service-tasks', name: 'service-tasks', component: { template: '<div>List</div>' }, meta: { titleKey: 'serviceTasks' } },
          { path: 'service-tasks/:id', name: 'service-task-detail', component: ServiceTaskDetail, meta: { titleKey: 'serviceTask', parentTitleKey: 'serviceTasks', parentTo: { name: 'service-tasks' } } },
          { path: 'dmn', name: 'dmn-list', component: { template: '<div>List</div>' }, meta: { titleKey: 'dmnDecisions' } },
          { path: 'dmn/:id', name: 'dmn-detail', component: DmnViewer, meta: { titleKey: 'dmnDecision', parentTitleKey: 'dmnDecisions', parentTo: { name: 'dmn-list' } } },
        ],
      },
    ],
  })
}

async function mountApp(path: string, router: ReturnType<typeof makeRouter>) {
  await router.push(path)
  await router.isReady()
  const pinia = createPinia()
  setActivePinia(pinia)
  const wrapper = mount(MainLayout, {
    global: { stubs: { teleport: true }, plugins: [router, pinia, i18n] },
  })
  await flushPromises()
  return { wrapper, pinia }
}

function lastCrumbText(wrapper: Awaited<ReturnType<typeof mountApp>>['wrapper']) {
  const items = wrapper.findAll('li')
  return items[items.length - 1].find('span').text()
}

describe('WO-ACL-15 criterion 17: every card shows a human-readable crumb leaf on the real tree', () => {
  beforeAll(() => {
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
  })

  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('instance card: leaf = process name + short instance id', async () => {
    const router = makeRouter()
    const { wrapper } = await mountApp('/processes/instances/pi-1234567890', router)

    const nav = wrapper.find('nav')
    expect(nav.exists()).toBe(true)
    // parent crumb = translated section, leaf = "Vacation Request · pi-12345"
    expect(wrapper.findAll('li')[0].find('a').text()).toBe('Экземпляры процессов')
    expect(lastCrumbText(wrapper)).toBe('Vacation Request · pi-12345')
  })

  it('user task card: leaf = task name', async () => {
    const router = makeRouter()
    const { wrapper } = await mountApp('/tasks/task-abcdef1234', router)

    const nav = wrapper.find('nav')
    expect(nav.exists()).toBe(true)
    expect(wrapper.findAll('li')[0].find('a').text()).toBe('Мои задачи')
    expect(lastCrumbText(wrapper)).toBe('Approve Order')
  })

  it('service task card: leaf = task name', async () => {
    const router = makeRouter()
    const { wrapper } = await mountApp('/service-tasks/st-9876543210', router)

    const nav = wrapper.find('nav')
    expect(nav.exists()).toBe(true)
    expect(wrapper.findAll('li')[0].find('a').text()).toBe('Сервисные задачи')
    expect(lastCrumbText(wrapper)).toBe('Call ERP')
  })

  it('DMN card: leaf = decision name', async () => {
    const router = makeRouter()
    const { wrapper } = await mountApp('/dmn/dmn-1', router)

    const nav = wrapper.find('nav')
    expect(nav.exists()).toBe(true)
    expect(wrapper.findAll('li')[0].find('a').text()).toBe('DMN-решения')
    expect(lastCrumbText(wrapper)).toBe('Loan Decision')
  })

  it('instance card: a task WITHOUT a name falls back to the type + short id', async () => {
    const { getUserTask } = await import('@/services/taskService')
    vi.mocked(getUserTask).mockResolvedValueOnce({
      id: 'task-abcdef1234', code: null, name: null,
      processInstanceId: 'pi-1', processDefinitionId: 'pd-1', formKey: null,
      status: 'CREATED', createdAt: '2026-01-01', completedAt: null,
    })
    const router = makeRouter()
    const { wrapper } = await mountApp('/tasks/task-abcdef1234', router)

    expect(lastCrumbText(wrapper)).toBe('Задача task-abc')
  })
})

describe('WO-ACL-15 criterion 18: leaving the card clears the store — no stale name on the next page', () => {
  beforeAll(() => {
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
  })

  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('leaving the instance card clears the store (user task card does not show the instance name)', async () => {
    const router = makeRouter()
    const { wrapper, pinia } = await mountApp('/processes/instances/pi-1234567890', router)
    expect(useBreadcrumbStore().crumbLabel).toBe('Vacation Request · pi-12345')

    await router.push('/tasks/task-abcdef1234')
    await flushPromises()

    // the store holds the NEW card's label, not the previous card's stale one
    expect(useBreadcrumbStore().crumbLabel).toBe('Approve Order')
    expect(lastCrumbText(wrapper)).toBe('Approve Order')
    void pinia
  })

  it('leaving a card for its LIST page hides the crumb panel and nulls the store', async () => {
    const router = makeRouter()
    const { wrapper } = await mountApp('/dmn/dmn-1', router)
    expect(useBreadcrumbStore().crumbLabel).toBe('Loan Decision')

    await router.push('/dmn')
    await flushPromises()

    expect(useBreadcrumbStore().crumbLabel).toBeNull()
    // single crumb (no parent) → the panel is hidden
    expect(wrapper.find('nav').exists()).toBe(false)
  })
})