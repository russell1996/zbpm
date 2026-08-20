// @vitest-environment jsdom
/**
 * WO-ACL-16 criterion 6 — the incident card shows a human-readable breadcrumb
 * leaf (process name · element) and leaving the card clears the store.
 *
 * P-54: runs on the REAL tree — MainLayout + <router-view> + IncidentDetail —
 * not on a synthetically mounted BreadcrumbNav with a hand-provided value.
 * POF 3 (removing the useBreadcrumbLabel fill on IncidentDetail) reddens THIS
 * file while BreadcrumbNav.test.ts stays green.
 */
import { describe, it, expect, vi, beforeAll, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'
import MainLayout from './MainLayout.vue'
import IncidentDetail from '@/pages/incidents/IncidentDetail.vue'
import { useBreadcrumbStore } from '@/stores/breadcrumb'
import en from '@/locales/en.json'
import ru from '@/locales/ru.json'
import kz from '@/locales/kz.json'

const mocks = vi.hoisted(() => ({
  incident: {
    id: 'inc-1',
    activityId: 'act-1',
    message: 'Task failed',
    createdAt: '2026-01-01',
    completedAt: null,
    processName: 'Vacation Request',
    processInstanceId: 'pi-1234567890',
    bpmnElementId: 'Activity_1abc',
    elementName: 'Approve Order',
  } as {
    id: string
    activityId: string
    message: string
    createdAt: string
    completedAt: string | null
    processName: string | null
    processInstanceId: string | null
    bpmnElementId: string | null
    elementName: string | null
  },
}))

vi.mock('@/services/incidentService', () => ({
  resolveIncident: vi.fn().mockResolvedValue(undefined),
  getIncidents: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  getIncident: vi.fn().mockImplementation(() => mocks.incident),
}))
vi.mock('@/services/processService', () => ({
  getProcessDefinitionXml: vi.fn().mockResolvedValue('<definitions id="test" />'),
  getProcessDefinitionStructure: vi.fn().mockResolvedValue({ id: 'pd-1', key: 'test', version: 1, name: 'Test', documentation: null, nodes: [], flows: [] }),
  getProcessDefinition: vi.fn().mockResolvedValue(null),
}))
vi.mock('@/services/instanceService', () => ({
  getProcessInstance: vi.fn().mockResolvedValue(null),
  getProcessInstanceActivities: vi.fn().mockResolvedValue([]),
  getProcessInstances: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  startProcessInstance: vi.fn(),
}))
vi.mock('@/services/taskService', () => ({
  getUserTask: vi.fn().mockResolvedValue(null),
  getServiceTask: vi.fn().mockResolvedValue(null),
  getUserTasks: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  getServiceTasks: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  completeUserTask: vi.fn().mockResolvedValue(undefined),
  completeServiceTask: vi.fn().mockResolvedValue(undefined),
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
  getDecision: vi.fn().mockResolvedValue(null),
  evaluateDecision: vi.fn().mockResolvedValue({}),
  inferVariable: vi.fn().mockResolvedValue(null),
}))
vi.mock('@/composables/useToast', () => ({ useToast: () => ({ success: vi.fn(), error: vi.fn() }) }))
vi.mock('@/composables/useDateFormat', () => ({
  useDateFormat: () => ({ formatDate: (v: string) => v, formatDateTime: (v: string) => v }),
}))
vi.mock('@/widgets/shared/SidebarNav.vue', () => ({ default: { template: '<div class="sidebar-stub" />' } }))
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
          { path: 'incidents', name: 'incidents', component: { template: '<div>List</div>' }, meta: { titleKey: 'incidents' } },
          { path: 'incidents/:id', name: 'incident-detail', component: IncidentDetail, meta: { titleKey: 'incident', parentTitleKey: 'incidents', parentTo: { name: 'incidents' } } },
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

describe('WO-ACL-16 criterion 6: incident card crumb leaf = process name · element', () => {
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
    mocks.incident = {
      id: 'inc-1',
      activityId: 'act-1',
      message: 'Task failed',
      createdAt: '2026-01-01',
      completedAt: null,
      processName: 'Vacation Request',
      processInstanceId: 'pi-1234567890',
      bpmnElementId: 'Activity_1abc',
      elementName: 'Approve Order',
    }
  })

  it('leaf = process name · element name, parent crumb = translated section', async () => {
    const router = makeRouter()
    const { wrapper } = await mountApp('/incidents/inc-1', router)

    const nav = wrapper.find('nav')
    expect(nav.exists()).toBe(true)
    expect(wrapper.findAll('li')[0].find('a').text()).toBe('Инциденты')
    expect(lastCrumbText(wrapper)).toBe('Vacation Request · Approve Order')
  })

  it('falls back to the bpmn element id when the element has no name', async () => {
    mocks.incident = { ...mocks.incident, elementName: null }
    const router = makeRouter()
    const { wrapper } = await mountApp('/incidents/inc-1', router)

    expect(lastCrumbText(wrapper)).toBe('Vacation Request · Activity_1abc')
  })

  it('leaving the incident card clears the store — the crumb panel hides on the list page', async () => {
    const router = makeRouter()
    const { wrapper } = await mountApp('/incidents/inc-1', router)
    expect(useBreadcrumbStore().crumbLabel).toBe('Vacation Request · Approve Order')

    await router.push('/incidents')
    await flushPromises()

    expect(useBreadcrumbStore().crumbLabel).toBeNull()
    expect(wrapper.find('nav').exists()).toBe(false)
  })
})
