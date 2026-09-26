// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import SchemaEditorPanel from './SchemaEditorPanel.vue'

const mockGetSchemaMap = vi.fn()
const mockSaveElementSchema = vi.fn()
const mockGetForm = vi.fn()
const mockGenerateSchema = vi.fn()

vi.mock('@/services/formService', () => ({
  getSchemaMap: (...args: any[]) => mockGetSchemaMap(...args),
  saveElementSchema: (...args: any[]) => mockSaveElementSchema(...args),
  getForm: (...args: any[]) => mockGetForm(...args),
  generateSchema: (...args: any[]) => mockGenerateSchema(...args),
}))

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn() }),
}))

vi.mock('@/widgets/forms/FormEditor.vue', () => ({
  default: { name: 'FormEditor', template: '<div />', methods: { saveSchema: () => '{}', importSchema: () => {} } },
}))

vi.mock('@/widgets/forms/JsonSchemaEditor.vue', () => ({
  default: { name: 'JsonSchemaEditor', template: '<div />', methods: { saveSchema: () => '{}' } },
}))

vi.mock('@/widgets/shared/SchemaFieldBuilder.vue', () => ({
  default: {
    name: 'SchemaFieldBuilder',
    template: '<div data-testid="field-builder" />',
    props: ['modelValue'],
    emits: ['update:modelValue'],
  },
}))

function vsElement(overrides = {}) {
  return {
    elementId: 'startEvent', name: 'Start', type: 'START_EVENT',
    artifactKey: 'vs-key', kind: 'VARIABLE_SCHEMA', artifactVersion: 1,
    hasExternalReference: false, shared: false, ...overrides,
  }
}

describe('SchemaEditorPanel', () => {
  beforeEach(() => {
    mockGetSchemaMap.mockResolvedValue({
      processDefinitionKey: 'proc1', version: 1,
      elements: [vsElement()],
    })
    mockGetForm.mockResolvedValue({ key: 'f', version: 1, kind: 'VARIABLE_SCHEMA', schema: '{}' })
    mockSaveElementSchema.mockResolvedValue({ elementId: 'startEvent' })
    mockGenerateSchema.mockResolvedValue('{"type":"object","properties":{}}')
  })

  // criterion1: save in constructor mode calls generate then save
  it('criterion1: save calls generate then saveElementSchema', async () => {
    mockGetForm.mockResolvedValue({
      key: 'f', version: 1, kind: 'VARIABLE_SCHEMA',
      schema: JSON.stringify({
        '$schema': 'https://json-schema.org/draft/2020-12/schema',
        type: 'object', properties: {},
        'x-builder': { fields: [{ key: 'name', label: 'N', type: 'string', required: true }] },
      }),
    })

    const wrapper = mount(SchemaEditorPanel, { props: { processKey: 'proc1' } })
    await flushPromises()
    await wrapper.find('.cursor-pointer').trigger('click')
    await flushPromises()

    // Constructor mode should be active (x-builder present)
    expect(wrapper.find('[data-testid="field-builder"]').exists()).toBe(true)

    // Click save
    const saveBtn = wrapper.findAll('button').find(b => b.text().includes('saveSchema'))
    expect(saveBtn).toBeTruthy()
    await saveBtn!.trigger('click')
    await flushPromises()

    // generateSchema should have been called with the fields
    expect(mockGenerateSchema).toHaveBeenCalled()
    const calledFields = mockGenerateSchema.mock.calls[0][0]
    expect(calledFields).toHaveLength(1)
    expect(calledFields[0].key).toBe('name')

    // saveElementSchema should have been called with the generated schema
    expect(mockSaveElementSchema).toHaveBeenCalled()
  })

  // criterion2: schema with x-builder restores constructor fields
  it('criterion2: x-builder restores constructor', async () => {
    mockGetForm.mockResolvedValue({
      key: 'f', version: 1, kind: 'VARIABLE_SCHEMA',
      schema: JSON.stringify({
        '$schema': 'https://json-schema.org/draft/2020-12/schema',
        type: 'object', properties: { name: { type: 'string' } },
        'x-builder': { fields: [{ key: 'name', label: 'Name', type: 'string', required: true }] },
      }),
    })

    const wrapper = mount(SchemaEditorPanel, { props: { processKey: 'proc1' } })
    await flushPromises()
    await wrapper.find('.cursor-pointer').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="field-builder"]').exists()).toBe(true)
  })

  // criterion3: schema without x-builder shows JSON mode + hint when user switches to constructor
  it('criterion3: no x-builder → JSON mode + hint on constructor switch', async () => {
    mockGetForm.mockResolvedValue({
      key: 'f', version: 1, kind: 'VARIABLE_SCHEMA',
      schema: JSON.stringify({
        '$schema': 'https://json-schema.org/draft/2020-12/schema',
        type: 'object', properties: { name: { type: 'string' } },
      }),
    })

    const wrapper = mount(SchemaEditorPanel, { props: { processKey: 'proc1' } })
    await flushPromises()
    await wrapper.find('.cursor-pointer').trigger('click')
    await flushPromises()

    // Should be in JSON mode (no x-builder)
    expect(wrapper.find('[data-testid="field-builder"]').exists()).toBe(false)

    // Switch to constructor mode
    const ctorBtn = wrapper.findAll('button').find(b => b.text().includes('constructor'))
    await ctorBtn!.trigger('click')
    await flushPromises()

    // Now the hint should appear
    expect(wrapper.text()).toContain('constructorUnavailable')
  })

  // criterion4: switching constructor/JSON preserves data
  it('criterion4: toggle preserves data', async () => {
    mockGetForm.mockResolvedValue({
      key: 'f', version: 1, kind: 'VARIABLE_SCHEMA',
      schema: JSON.stringify({
        '$schema': 'https://json-schema.org/draft/2020-12/schema',
        type: 'object', properties: {},
        'x-builder': { fields: [{ key: 'name', label: 'N', type: 'string' }] },
      }),
    })

    const wrapper = mount(SchemaEditorPanel, { props: { processKey: 'proc1' } })
    await flushPromises()
    await wrapper.find('.cursor-pointer').trigger('click')
    await flushPromises()

    // Start in constructor mode
    expect(wrapper.find('[data-testid="field-builder"]').exists()).toBe(true)

    // Switch to JSON mode
    const jsonBtn = wrapper.findAll('button').find(b => b.text().includes('jsonMode'))
    await jsonBtn!.trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="field-builder"]').exists()).toBe(false)

    // Switch back to constructor
    const ctorBtn = wrapper.findAll('button').find(b => b.text().includes('constructor'))
    await ctorBtn!.trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="field-builder"]').exists()).toBe(true)
  })
})
