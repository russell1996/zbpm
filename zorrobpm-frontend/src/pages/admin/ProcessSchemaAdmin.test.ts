// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ProcessSchemaAdmin from './ProcessSchemaAdmin.vue'

const mockGetProcessDefinitions = vi.fn()

vi.mock('@/services/processService', () => ({
  getProcessDefinitions: (...args: any[]) => mockGetProcessDefinitions(...args),
  getProcessDefinitionStructure: vi.fn().mockResolvedValue({ nodes: [], flows: [] }),
}))

vi.mock('@/services/formService', () => ({
  getSchemaMap: vi.fn().mockResolvedValue({
    processDefinitionKey: 'proc1', version: 1,
    elements: [
      { elementId: 'startEvent', name: 'Start', type: 'START_EVENT', artifactKey: null, kind: null, artifactVersion: null, hasExternalReference: false, shared: false },
    ],
  }),
  saveElementSchema: vi.fn().mockResolvedValue({ elementId: 'startEvent' }),
  getForm: vi.fn().mockResolvedValue({ key: 'f', version: 1, kind: 'FORM_JS', schema: '{}' }),
  listForms: vi.fn().mockResolvedValue([]),
  createElementBinding: vi.fn(),
}))

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn() }),
}))

describe('ProcessSchemaAdmin', () => {
  beforeEach(() => {
    mockGetProcessDefinitions.mockResolvedValue({ data: [
      { id: 'pd-1', key: 'proc1', version: 1, name: 'Process 1' },
    ]})
  })

  it('renders process definitions list', async () => {
    const wrapper = mount(ProcessSchemaAdmin)
    await flushPromises()
    expect(wrapper.text()).toContain('Process 1')
  })

  it('selecting process shows SchemaEditorPanel', async () => {
    const wrapper = mount(ProcessSchemaAdmin)
    await flushPromises()
    await wrapper.find('.cursor-pointer').trigger('click')
    await flushPromises()
    // SchemaEditorPanel should be rendered (has loading or elements)
    expect(wrapper.text()).toContain('startEvent')
  })
})
