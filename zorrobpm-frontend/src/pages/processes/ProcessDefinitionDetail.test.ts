// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ProcessDefinitionDetail from './ProcessDefinitionDetail.vue'

// hoisted mock ref so tests can assert on startInstance calls
const mockStartInstance = vi.hoisted(() => vi.fn().mockResolvedValue({ id: 'inst-1' }))

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
  useI18n: () => ({ t: (k: string) => k }),
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

vi.mock('@/stores/process', () => ({
  useProcessStore: () => ({
    currentDefinition: { id: 'def1', key: 'test-proc', version: 1, name: 'Test', sha256: 'abc', createdAt: '2026-01-01', startFormKey: null },
    currentStructure: { id: 'def1', key: 'test-proc', version: 1, name: 'Test', documentation: null, nodes: [], flows: [] },
    currentVersions: [],
    loading: false,
    error: null,
    fetchDefinition: vi.fn(),
    fetchStructure: vi.fn(),
    fetchVersions: vi.fn(),
    startInstance: mockStartInstance,
  }),
}))

describe('ProcessDefinitionDetail render', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('mounts without ReferenceError (dead ref removed in MT-9)', async () => {
    const wrapper = mount(ProcessDefinitionDetail, {
      global: { stubs: { teleport: true }, plugins: [createPinia()] },
    })
    await wrapper.vm.$nextTick()
    // Should render without ReferenceError — previously 'authStore' was undefined
    expect(wrapper.text()).toContain('Test')
  })

  it('dropdown contains JSON and UUID options', async () => {
    const wrapper = mount(ProcessDefinitionDetail, {
      global: { stubs: { teleport: true }, plugins: [createPinia()] },
    })
    await wrapper.vm.$nextTick()
    const vm = wrapper.vm as any
    vm.showStartModal = true
    await wrapper.vm.$nextTick()
    const select = wrapper.find('select')
    const options = select.findAll('option').map((o: any) => o.text())
    expect(options).toContain('JSON')
    expect(options).toContain('UUID')
    expect(options).toContain('DOUBLE')
    expect(options).toContain('BOOLEAN')
    expect(options).toContain('LONG')
    expect(options).toContain('STRING')
  })

  it('shows textarea when JSON type is selected', async () => {
    const wrapper = mount(ProcessDefinitionDetail, {
      global: { stubs: { teleport: true }, plugins: [createPinia()] },
    })
    await wrapper.vm.$nextTick()
    const vm = wrapper.vm as any
    vm.showStartModal = true
    await wrapper.vm.$nextTick()
    vm.newVarType = 'JSON'
    await wrapper.vm.$nextTick()
    // Should show textarea instead of input for value
    const textarea = wrapper.find('textarea')
    expect(textarea.exists()).toBe(true)
  })

  it('rejects invalid JSON in addVariable', () => {
    const wrapper = mount(ProcessDefinitionDetail, {
      global: { stubs: { teleport: true }, plugins: [createPinia()] },
    })
    const vm = wrapper.vm as any
    vm.newVarType = 'JSON'
    vm.newVarValue = '{invalid}'
    vm.newVarName = 'badJson'
    vm.addVariable()
    expect(vm.startVars.length).toBe(0)
    expect(vm.jsonError).toBe('Invalid JSON')
  })

  it('POF: start process with type=JSON sends type JSON in API payload', async () => {
    const wrapper = mount(ProcessDefinitionDetail, {
      global: { stubs: { teleport: true }, plugins: [createPinia()] },
    })
    await wrapper.vm.$nextTick()
    const vm = wrapper.vm as any

    // Inject a JSON variable directly into startVars
    vm.startVars.push({ name: 'data', type: 'JSON', value: '["u1","u2"]' })
    await vm.startProcess()

    expect(mockStartInstance).toHaveBeenCalledTimes(1)
    const callArg = mockStartInstance.mock.calls[0][0]
    expect(callArg.variables).toContainEqual({ name: 'data', type: 'JSON', value: '["u1","u2"]' })
  })

  it('accepts valid JSON and adds the variable', () => {
    const wrapper = mount(ProcessDefinitionDetail, {
      global: { stubs: { teleport: true }, plugins: [createPinia()] },
    })
    const vm = wrapper.vm as any
    vm.newVarType = 'JSON'
    vm.newVarValue = '{"key":"val"}'
    vm.newVarName = 'cfg'
    vm.addVariable()
    expect(vm.startVars.length).toBe(1)
    expect(vm.startVars[0]).toEqual({ name: 'cfg', type: 'JSON', value: '{"key":"val"}' })
    expect(vm.jsonError).toBe('')
  })
})
