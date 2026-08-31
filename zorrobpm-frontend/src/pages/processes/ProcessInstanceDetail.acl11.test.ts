// @vitest-environment jsdom
/**
 * WO-ACL-11 criteria 6/7/9 — ProcessInstanceDetail subprocess table:
 *  6 — subprocess row opens the subprocess card on click;
 *  7 — the "view" button is gone;
 *  9 — focus + Enter opens it.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import ProcessInstanceDetail from './ProcessInstanceDetail.vue'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
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
  getProcessInstance: vi.fn().mockResolvedValue({ id: 'pi-1', parentActivityId: null, processDefinitionId: 'pd-1', startedAt: '2026-01-01', completedAt: null, processName: 'Test', processKey: 'test', processVersion: 1 }),
  getProcessInstanceActivities: vi.fn().mockResolvedValue([]),
  getProcessInstances: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  startProcessInstance: vi.fn(),
}))

vi.mock('@/services/variableService', () => ({
  getVariables: vi.fn().mockResolvedValue({ data: [] }),
}))

const stubs = { teleport: true, BpmnViewer: { template: '<div class="bpmn-stub" />' } }

function makeRouter() {
  return createRouter({
    history: createMemoryHistory('/ui/'),
    routes: [
      {
        path: '/',
        component: { template: '<router-view />' },
        children: [
          { path: 'processes/instances', name: 'process-instances', component: { template: '<div />' } },
          { path: 'processes/instances/:id', name: 'process-instance-detail', component: { template: '<div />' } },
        ],
      },
    ],
  })
}

async function mountWithSubprocess(router: ReturnType<typeof makeRouter>) {
  const wrapper = mount(ProcessInstanceDetail, {
    global: { stubs, plugins: [router, createPinia()] },
  })
  await wrapper.vm.$nextTick()
  await flushPromises()
  // switch to the subprocesses tab
  ;(wrapper.vm as any).activeTab = 'subprocesses'
  const { useProcessStore } = await import('@/stores/process')
  const processStore = useProcessStore()
  processStore.currentSubprocesses = [
    { id: 'sub-1', parentActivityId: null, processDefinitionId: 'pd-2', processName: 'Child process', processKey: 'child', processVersion: 1, startedAt: '2026-01-01', completedAt: null },
    { id: 'sub-2', parentActivityId: null, processDefinitionId: 'pd-3', processName: 'Done child', processKey: 'done', processVersion: 2, startedAt: '2026-01-02', completedAt: '2026-01-03' },
  ]
  await wrapper.vm.$nextTick()
  await flushPromises()
  return wrapper
}

describe('WO-ACL-11 criteria 6/7/9: subprocess table rows', () => {
  beforeEach(() => { setActivePinia(createPinia()); vi.clearAllMocks() })

  it('criterion 6: clicking the subprocess row opens its card', async () => {
    const router = makeRouter()
    await router.push('/processes/instances/pi-1')
    await router.isReady()
    const wrapper = await mountWithSubprocess(router)

    await wrapper.findAll('tbody tr')[0].trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/processes/instances/sub-1')
  })

  it('criterion 9: Enter on the focused subprocess row opens its card', async () => {
    const router = makeRouter()
    await router.push('/processes/instances/pi-1')
    await router.isReady()
    const wrapper = await mountWithSubprocess(router)

    const row = wrapper.findAll('tbody tr')[0]
    expect(row.attributes('tabindex')).toBe('0')
    await row.trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/processes/instances/sub-1')
  })

  it('criterion 7: the "view" button is gone from the subprocess table', async () => {
    const router = makeRouter()
    await router.push('/processes/instances/pi-1')
    await router.isReady()
    const wrapper = await mountWithSubprocess(router)
    const firstRow = wrapper.findAll('tbody tr')[0]
    // the only button inside the row is the CopyableId copy button — no "view" action
    const rowButtons = firstRow.findAll('button')
    expect(rowButtons.length).toBe(1)
    expect(rowButtons[0].text()).not.toContain('view')
    expect(firstRow.text()).not.toContain('view')
  })
})