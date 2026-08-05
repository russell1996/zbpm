// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import StartBindingAdmin from './StartBindingAdmin.vue'

const mockGetProcessDefinitions = vi.fn()
const mockGetProcessDefinitionStructure = vi.fn()
const mockListForms = vi.fn()
const mockCreateElementBinding = vi.fn()

vi.mock('@/services/processService', () => ({
  getProcessDefinitions: (...args: any[]) => mockGetProcessDefinitions(...args),
  getProcessDefinitionStructure: (...args: any[]) => mockGetProcessDefinitionStructure(...args),
}))

vi.mock('@/services/formService', () => ({
  listForms: (...args: any[]) => mockListForms(...args),
  createElementBinding: (...args: any[]) => mockCreateElementBinding(...args),
}))

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({
    success: vi.fn(),
    error: vi.fn(),
  }),
}))

describe('StartBindingAdmin', () => {
  beforeEach(() => {
    mockGetProcessDefinitions.mockResolvedValue({ data: [
      { id: 'pd-1', key: 'proc1', version: 1, name: 'Process 1' },
    ]})
    mockGetProcessDefinitionStructure.mockResolvedValue({
      id: 'pd-1', key: 'proc1', version: 1, name: 'Process 1',
      nodes: [
        { id: 'startEvent', name: 'Start', type: 'START_EVENT', properties: {} },
        { id: 'userTask1', name: 'Task', type: 'USER_TASK', properties: {} },
      ],
      flows: [],
    })
    mockListForms.mockResolvedValue([
      { key: 'form1', version: 1, kind: 'FORM_JS', schema: '{}' },
    ])
    mockCreateElementBinding.mockResolvedValue({ id: 'b1', elementId: 'startEvent', artifactKey: 'form1' })
  })

  it('renders process definitions list', async () => {
    const wrapper = mount(StartBindingAdmin)
    await flushPromises()
    expect(wrapper.text()).toContain('Process 1')
  })

  it('shows start events after selecting definition', async () => {
    const wrapper = mount(StartBindingAdmin)
    await flushPromises()
    await wrapper.find('.cursor-pointer').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('startEvent')
    // userTask should NOT appear (filtered out)
    expect(wrapper.text()).not.toContain('userTask1')
  })

  it('bind button sends correct payload', async () => {
    const wrapper = mount(StartBindingAdmin)
    await flushPromises()
    await wrapper.find('.cursor-pointer').trigger('click')
    await flushPromises()

    const selects = wrapper.findAll('select')
    // First select = element, second = artifact
    await selects[0].setValue('startEvent')
    await selects[1].setValue('form1')
    await wrapper.find('button:not(.cursor-pointer)').trigger('click')
    await flushPromises()

    expect(mockCreateElementBinding).toHaveBeenCalledWith('proc1', 'startEvent', 'form1')
  })
})
