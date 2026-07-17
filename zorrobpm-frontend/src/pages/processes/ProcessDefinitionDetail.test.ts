// @vitest-environment jsdom
import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ProcessDefinitionDetail from './ProcessDefinitionDetail.vue'

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
    startInstance: vi.fn(),
  }),
}))

describe('ProcessDefinitionDetail render', () => {
  it('mounts without ReferenceError (dead ref removed in MT-9)', async () => {
    setActivePinia(createPinia())
    const wrapper = mount(ProcessDefinitionDetail, {
      global: { stubs: { teleport: true }, plugins: [createPinia()] },
    })
    await wrapper.vm.$nextTick()
    // Should render without ReferenceError — previously 'authStore' was undefined
    expect(wrapper.text()).toContain('Test')
  })
})
