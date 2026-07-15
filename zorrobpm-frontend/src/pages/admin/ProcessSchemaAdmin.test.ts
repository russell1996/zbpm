// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ProcessSchemaAdmin from './ProcessSchemaAdmin.vue'

const mockGetProcessDefinitions = vi.fn()
const mockGetSchemaMap = vi.fn()
const mockSaveElementSchema = vi.fn()
const mockSuccess = vi.fn()
const mockError = vi.fn()

vi.mock('@/services/processService', () => ({
  getProcessDefinitions: (...args: any[]) => mockGetProcessDefinitions(...args),
  getProcessDefinitionStructure: vi.fn().mockResolvedValue({ nodes: [], flows: [] }),
}))

vi.mock('@/services/formService', () => ({
  getSchemaMap: (...args: any[]) => mockGetSchemaMap(...args),
  saveElementSchema: (...args: any[]) => mockSaveElementSchema(...args),
  listForms: vi.fn().mockResolvedValue([]),
  createElementBinding: vi.fn(),
}))

vi.mock('@bpmn-io/form-js', () => ({
  FormEditor: class MockFormEditor {
    constructor() {}
    importSchema = vi.fn().mockResolvedValue(undefined)
    saveSchema = vi.fn().mockReturnValue(JSON.stringify({ type: 'default', components: [] }))
    destroy = vi.fn()
  },
}))

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k }),
}))

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({
    success: mockSuccess,
    error: mockError,
  }),
}))

describe('ProcessSchemaAdmin', () => {
  beforeEach(() => {
    mockGetProcessDefinitions.mockResolvedValue({ data: [
      { id: 'pd-1', key: 'proc1', version: 1, name: 'Process 1' },
    ]})
    mockGetSchemaMap.mockResolvedValue({
      processDefinitionKey: 'proc1',
      version: 1,
      elements: [
        { elementId: 'startEvent', name: 'Start', type: 'START_EVENT', artifactKey: null, kind: null, artifactVersion: null, hasExternalReference: false, shared: false },
        { elementId: 'userTask1', name: 'Task', type: 'USER_TASK', artifactKey: 'myForm', kind: 'FORM_JS', artifactVersion: 1, hasExternalReference: true, shared: false },
      ],
    })
    mockSaveElementSchema.mockResolvedValue({
      elementId: 'startEvent', name: 'Start', type: 'START_EVENT', artifactKey: 'proc1:startEvent',
      kind: 'VARIABLE_SCHEMA', artifactVersion: 1, hasExternalReference: false, shared: false,
    })
    mockSuccess.mockClear()
    mockError.mockClear()
  })

  it('renders process definitions list', async () => {
    const wrapper = mount(ProcessSchemaAdmin)
    await flushPromises()
    expect(wrapper.text()).toContain('Process 1')
  })

  it('shows elements from schema-map with status', async () => {
    const wrapper = mount(ProcessSchemaAdmin)
    await flushPromises()
    await wrapper.find('.cursor-pointer').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('startEvent')
    expect(wrapper.text()).toContain('userTask1')
    expect(wrapper.text()).toContain('FORM_JS')
  })

  it('POF: click element → save → POST schema (WO-VM-9b criterion 2)', async () => {
    const wrapper = mount(ProcessSchemaAdmin)
    await flushPromises()

    // Select process
    await wrapper.find('.cursor-pointer').trigger('click')
    await flushPromises()

    // Click first element (startEvent)
    const elements = wrapper.findAll('.cursor-pointer')
    // The second set of cursor-pointer divs are the elements
    const startEventEl = elements.find(el => el.text().includes('startEvent'))
    expect(startEventEl).toBeTruthy()
    await startEventEl!.trigger('click')
    await flushPromises()

    // Select VARIABLE_SCHEMA
    const select = wrapper.find('select')
    if (select.exists()) {
      await select.setValue('VARIABLE_SCHEMA')
    }
    await flushPromises()

    // Click save
    const saveBtn = wrapper.findAll('button').find(b => b.text().includes('saveSchema'))
    expect(saveBtn).toBeTruthy()
    await saveBtn!.trigger('click')
    await flushPromises()

    // saveElementSchema should be called
    expect(mockSaveElementSchema).toHaveBeenCalledTimes(1)
    const args = mockSaveElementSchema.mock.calls[0]
    expect(args[0]).toBe('proc1')  // processKey
    expect(args[1]).toBe('startEvent')  // elementId
  })

  it('user-task without externalReference → hint, save blocked (WO-VM-9b criterion 3)', async () => {
    // Override schema-map with user-task without externalReference
    mockGetSchemaMap.mockResolvedValue({
      processDefinitionKey: 'proc1',
      version: 1,
      elements: [
        { elementId: 'ut1', name: 'Task', type: 'USER_TASK', artifactKey: null, kind: null, artifactVersion: null, hasExternalReference: false, shared: false },
      ],
    })

    const wrapper = mount(ProcessSchemaAdmin)
    await flushPromises()

    // Select process
    await wrapper.find('.cursor-pointer').trigger('click')
    await flushPromises()

    // Click element — use findAll since process selector is also .cursor-pointer
    const clickables = wrapper.findAll('.cursor-pointer')
    // First is process selector, second is the element
    if (clickables.length > 1) {
      await clickables[1].trigger('click')
      await flushPromises()
    }

    // Should show hint
    expect(wrapper.text()).toContain('noExternalReferenceHint')

    // Save button should not exist
    const saveBtn = wrapper.findAll('button').find(b => b.text().includes('saveSchema'))
    expect(saveBtn).toBeFalsy()
  })
})
