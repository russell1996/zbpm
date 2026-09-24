// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ProcessInstanceDetail from './ProcessInstanceDetail.vue'
import { useProcessStore } from '@/stores/process'
import * as variableService from '@/services/variableService'

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: 'pi-1' } }),
  useRouter: () => ({ push: vi.fn() }),
}))

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
  resolveIncident: vi.fn(),
  getIncidents: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  getIncident: vi.fn().mockResolvedValue(null),
}))

vi.mock('@/services/instanceService', () => ({
  getProcessInstance: vi.fn().mockResolvedValue({ id: 'pi-1', parentActivityId: null, processDefinitionId: 'pd-1', startedAt: '2026-01-01', completedAt: null, processName: 'Test', processKey: 'test', processVersion: 1 }),
  getProcessInstanceActivities: vi.fn().mockResolvedValue([]),
  getProcessInstanceActivitiesPaged: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  getProcessInstances: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  startProcessInstance: vi.fn(),
}))

vi.mock('@/services/variableService', () => ({
  getVariables: vi.fn().mockResolvedValue({ data: [] }),
}))

const stubs = { teleport: true, BpmnViewer: { template: '<div class="bpmn-stub" />' } }

const mappedNode: any = {
  id: 'task1',
  name: 'Mapped task',
  type: 'serviceTask',
  eventDefinition: null,
  documentation: null,
  incoming: [],
  outgoing: [],
  properties: {
    job: 'srvMapped',
    inputMappings: [{ source: '=orderId', target: 'orderId' }],
    outputMappings: [{ source: '=result', target: 'orderResult' }],
  },
  boundaryEvents: [],
  children: null,
}

function mountPage() {
  return mount(ProcessInstanceDetail, {
    global: { stubs, plugins: [createPinia()] },
  })
}

describe('ProcessInstanceDetail — ioMapping visibility (WO-ENG-14)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(variableService.getVariables).mockResolvedValue({ data: [] } as any)
  })

  it('renders static ioMapping declaration as source → target rows, not raw JSON', async () => {
    const wrapper = mountPage()
    await flushPromises()
    const store = useProcessStore()
    store.currentStructure = { id: 'pd-1', key: 'test', version: 1, name: 'Test', documentation: null, nodes: [mappedNode], flows: [] } as any
    const vm = wrapper.vm as any
    vm.selectedElement = 'task1'
    await flushPromises()

    const html = wrapper.html()
    // readable table cells, not a JSON blob
    expect(html).toContain('=orderId')
    expect(html).toContain('>orderId</td>')
    expect(html).toContain('=result')
    expect(html).toContain('>orderResult</td>')
    // generic properties block still shows job but not the raw mapping arrays
    expect(html).toContain('job')
    expect(html).not.toContain('"inputMappings"')
    expect(html).not.toContain('"outputMappings"')
  })

  it('loads and renders runtime activity-local variables separately', async () => {
    vi.mocked(variableService.getVariables).mockResolvedValue({
      data: [{ name: 'orderId', value: '42', type: 'STRING', activityId: 'act-9' }],
    } as any)
    const wrapper = mountPage()
    await flushPromises()
    const store = useProcessStore()
    store.currentStructure = { id: 'pd-1', key: 'test', version: 1, name: 'Test', documentation: null, nodes: [mappedNode], flows: [] } as any
    store.currentActivities = [{ id: 'act-9', processInstanceId: 'pi-1', bpmnElementId: 'task1', type: 'serviceTask', status: 'IN_PROGRESS', createdAt: '2026-01-01', completedAt: null }] as any
    const vm = wrapper.vm as any
    vm.selectedElement = 'task1'
    await flushPromises()

    // scoped fetch went out with the runtime activity id (not mixed into the store list)
    expect(variableService.getVariables).toHaveBeenCalledWith(
      expect.objectContaining({ processInstanceId: 'pi-1', activityId: 'act-9' }))
    const html = wrapper.html()
    expect(html).toContain('orderId')
    expect(html).toContain('42')
  })

  it('node without ioMapping renders no mapping blocks', async () => {
    const wrapper = mountPage()
    await flushPromises()
    const store = useProcessStore()
    const plainNode = { ...mappedNode, id: 'task2', properties: { job: 'srvPlain' } }
    store.currentStructure = { id: 'pd-1', key: 'test', version: 1, name: 'Test', documentation: null, nodes: [plainNode], flows: [] } as any
    const vm = wrapper.vm as any
    vm.selectedElement = 'task2'
    await flushPromises()

    const html = wrapper.html()
    expect(html).not.toContain('inputMappings')
    expect(html).not.toContain('outputMappings')
  })
})
