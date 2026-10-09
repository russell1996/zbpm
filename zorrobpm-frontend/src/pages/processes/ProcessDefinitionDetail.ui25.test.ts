// @vitest-environment jsdom
/**
 * WO-UI-25 критерий 1 — старт ведёт НА СТРАНИЦУ инстанса.
 *
 * RED на коде до WO-UI-25: startProcess делает router.push('/processes/instances')
 * (список), двойной клик не блокируется.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ProcessDefinitionDetail from './ProcessDefinitionDetail.vue'

const mockStartInstance = vi.hoisted(() => vi.fn().mockResolvedValue('inst-9'))
const mockPush = vi.hoisted(() => vi.fn())

vi.mock('vue-router', () => ({
  useRoute: () => ({
    params: { id: 'def1' },
    path: '/processes/definitions/def1',
    fullPath: '/processes/definitions/def1',
    name: 'process-definition-detail',
  }),
  useRouter: () => ({ push: mockPush }),
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

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn(), info: vi.fn() }),
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

describe('WO-UI-25 criterion 1: start lands on the instance page', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    mockStartInstance.mockResolvedValue('inst-9')
  })

  async function openStartModal() {
    const wrapper = mount(ProcessDefinitionDetail, {
      global: { stubs: { teleport: true }, plugins: [createPinia()] },
    })
    await wrapper.vm.$nextTick()
    const vm = wrapper.vm as any
    vm.showStartModal = true
    await wrapper.vm.$nextTick()
    return { wrapper, vm }
  }

  it('successful start navigates to /processes/instances/<id>, not the list', async () => {
    const { wrapper, vm } = await openStartModal()
    await vm.startProcess()
    await flushPromises()
    expect(mockStartInstance).toHaveBeenCalledTimes(1)
    expect(mockPush).toHaveBeenCalledWith('/processes/instances/inst-9')
    wrapper.unmount()
  })

  it('double invocation creates only ONE instance (in-flight guard)', async () => {
    const { wrapper, vm } = await openStartModal()
    await Promise.all([vm.startProcess(), vm.startProcess()])
    await flushPromises()
    expect(mockStartInstance).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('failed start stays in the dialog (no navigation)', async () => {
    mockStartInstance.mockResolvedValue(null)
    const { wrapper, vm } = await openStartModal()
    await vm.startProcess()
    await flushPromises()
    expect(mockPush).not.toHaveBeenCalled()
    expect(vm.showStartModal).toBe(true)
    wrapper.unmount()
  })
})
