// @vitest-environment jsdom
import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import FormEditor from './FormEditor.vue'
import { EMPTY_SCHEMA } from './formSchema'

const mockImportSchema = vi.fn().mockResolvedValue(undefined)
// form-js editor's real method is saveSchema() (not save())
const mockSaveSchema = vi.fn().mockReturnValue({ type: 'default', components: [] })
const mockDestroy = vi.fn()

vi.mock('@bpmn-io/form-js', () => ({
  FormEditor: class MockFormEditor {
    constructor() {}
    importSchema = mockImportSchema
    saveSchema = mockSaveSchema
    destroy = mockDestroy
  },
}))

describe('FormEditor', () => {
  it('renders the form editor container div', () => {
    const wrapper = mount(FormEditor)
    expect(wrapper.find('.form-editor-container').exists()).toBe(true)
  })

  it('imports a schema on mount (empty form for a new form)', async () => {
    mount(FormEditor)
    await flushPromises()
    expect(mockImportSchema).toHaveBeenCalled()
  })

  it('saveSchema calls editor.saveSchema() and emits JSON string', async () => {
    const wrapper = mount(FormEditor)
    await flushPromises()
    ;(wrapper.vm as unknown as { saveSchema: () => void }).saveSchema()
    expect(mockSaveSchema).toHaveBeenCalled()
    const emitted = wrapper.emitted('save')
    expect(emitted).toBeTruthy()
    expect(emitted![0][0]).toContain('"type":"default"')
  })
})

describe('EMPTY_SCHEMA (formSchema)', () => {
  it('root type is "default" (not "form") — form-js requires this', () => {
    expect(EMPTY_SCHEMA.type).toBe('default')
  })

  it('has required form-js properties', () => {
    expect(EMPTY_SCHEMA).toHaveProperty('components')
    expect(EMPTY_SCHEMA).toHaveProperty('schemaVersion')
  })
})
