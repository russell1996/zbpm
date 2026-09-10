// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ProcessDefinitionDetail from './ProcessDefinitionDetail.vue'
import IoMappingTable from '@/widgets/shared/IoMappingTable.vue'

vi.mock('vue-router', () => ({
  useRoute: () => ({
    params: { id: 'def1' },
    path: '/processes/definitions/def1',
    fullPath: '/processes/definitions/def1',
    name: 'process-definition-detail',
  }),
  useRouter: () => ({ push: vi.fn() }),
}))

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))

vi.mock('@/services/processService', () => ({
  getProcessDefinitionXml: vi.fn().mockResolvedValue(null),
}))

vi.mock('@/services/formService', () => ({
  getSchemaMap: vi.fn().mockResolvedValue({ processDefinitionKey: 'test-proc', version: 1, elements: [] }),
  saveElementSchema: vi.fn(),
  getForm: vi.fn(),
  listForms: vi.fn().mockResolvedValue([]),
  createElementBinding: vi.fn(),
}))

const childNode: any = {
  id: 'nestedCall',
  name: 'Nested',
  type: 'callActivity',
  eventDefinition: null,
  documentation: null,
  incoming: [],
  outgoing: [],
  properties: {
    calledProcessId: 'child',
    inputMappings: [{ source: '=x', target: 'y' }],
  },
  boundaryEvents: [],
  children: null,
}

const subNode: any = {
  id: 'sub1',
  name: 'Sub',
  type: 'subProcess',
  eventDefinition: null,
  documentation: null,
  incoming: [],
  outgoing: [],
  properties: {},
  boundaryEvents: [
    {
      id: 'b1',
      name: null,
      type: 'boundaryEvent',
      eventDefinition: 'timer',
      documentation: null,
      incoming: [],
      outgoing: [],
      properties: { attachedToRef: 'sub1', cancelActivity: true, timerType: 'duration', timerExpression: 'PT5M' },
      boundaryEvents: [],
      children: null,
    },
  ],
  children: { nodes: [childNode], flows: [] },
}

vi.mock('@/stores/process', () => ({
  useProcessStore: () => ({
    currentDefinition: { id: 'def1', key: 'test-proc', version: 1, name: 'Test', sha256: 'abc', createdAt: '2026-01-01', startFormKey: null },
    currentStructure: {
      id: 'def1', key: 'test-proc', version: 1, name: 'Test', documentation: null,
      nodes: [subNode],
      flows: [{ id: 'flow9', name: null, sourceRef: 'a', targetRef: 'b', conditionExpression: '=x > 1' }],
    },
    currentVersions: [],
    loading: false,
    error: null,
    fetchDefinition: vi.fn(),
    fetchStructure: vi.fn(),
    fetchVersions: vi.fn(),
    startInstance: vi.fn(),
  }),
}))

describe('ProcessDefinitionDetail — structure navigator (WO-ENG-15)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  function mountStructureTab() {
    const wrapper = mount(ProcessDefinitionDetail, {
      global: { stubs: { teleport: true }, plugins: [createPinia()] },
    })
    const vm = wrapper.vm as any
    vm.activeTab = 'structure'
    return { wrapper, vm }
  }

  it('renders nested tree rows (subprocess child + boundary indented)', async () => {
    const { wrapper } = mountStructureTab()
    await flushPromises()
    const html = wrapper.html()
    expect(html).toContain('sub1')
    expect(html).toContain('nestedCall')
    expect(html).toContain('b1')
    // boundary row carries extra indent vs its host
    expect(html).toMatch(/padding-left:\s*16px/)
  })

  it('clicking a tree node shows its properties incl. ioMapping table', async () => {
    const { wrapper } = mountStructureTab()
    await flushPromises()
    const buttons = wrapper.findAll('button')
    const nestedBtn = buttons.find((b) => b.text().includes('nestedCall'))
    expect(nestedBtn).toBeTruthy()
    await nestedBtn!.trigger('click')
    await flushPromises()
    const html = wrapper.html()
    expect(html).toContain('=x')
    expect(html).toContain('>y</td>')
    expect(html).toContain('calledProcessId')
  })

  it('clicking a flow shows its condition block', async () => {
    const { wrapper } = mountStructureTab()
    await flushPromises()
    const buttons = wrapper.findAll('button')
    const flowBtn = buttons.find((b) => b.text().includes('flow9'))
    expect(flowBtn).toBeTruthy()
    await flowBtn!.trigger('click')
    await flushPromises()
    expect(wrapper.html()).toContain('=x &gt; 1')
  })

  it('nine input mappings render collapsed to 5 rows with showMore', async () => {
    const nine = Array.from({ length: 9 }, (_, i) => ({ source: `=v${i}`, target: `t${i}` }))
    const table = mount(IoMappingTable, {
      props: { titleKey: 'inputMappings', mappings: nine },
    })
    expect(table.findAll('tbody tr')).toHaveLength(5)
    expect(table.text()).toContain('showMore')
    await table.find('button').trigger('click')
    expect(table.findAll('tbody tr')).toHaveLength(9)
    expect(table.text()).toContain('showLess')
  })
})
