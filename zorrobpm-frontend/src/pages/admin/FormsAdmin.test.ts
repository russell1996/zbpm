// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import FormsAdmin from './FormsAdmin.vue'

const mockListForms = vi.fn()
const mockDeployForm = vi.fn()
const mockSuccess = vi.fn()
const mockError = vi.fn()

vi.mock('@/services/formService', () => ({
  listForms: (...args: any[]) => mockListForms(...args),
  deployForm: (...args: any[]) => mockDeployForm(...args),
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
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({
    success: mockSuccess,
    error: mockError,
  }),
}))

describe('FormsAdmin', () => {
  beforeEach(() => {
    mockListForms.mockResolvedValue([])
    mockDeployForm.mockReset()
    mockDeployForm.mockResolvedValue(undefined)
    mockSuccess.mockClear()
    mockError.mockClear()
  })

  it('renders create button when not editing', async () => {
    const wrapper = mount(FormsAdmin)
    await flushPromises()
    expect(wrapper.find('button').text()).toContain('createForm')
  })

  it('shows kind column in form list', async () => {
    mockListForms.mockResolvedValue([
      { key: 'test', version: 1, kind: 'FORM_JS', schema: '{}' },
    ])
    const wrapper = mount(FormsAdmin)
    await flushPromises()
    expect(wrapper.find('th').text()).toContain('key')
    const ths = wrapper.findAll('th')
    const kindTh = ths.find(th => th.text().includes('kind'))
    expect(kindTh).toBeTruthy()
  })

  it('shows kind badge in form list', async () => {
    mockListForms.mockResolvedValue([
      { key: 'test', version: 1, kind: 'VARIABLE_SCHEMA', schema: '{}' },
    ])
    const wrapper = mount(FormsAdmin)
    await flushPromises()
    expect(wrapper.text()).toContain('VARIABLE_SCHEMA')
  })

  it('kind selector present when creating new form', async () => {
    const wrapper = mount(FormsAdmin)
    await flushPromises()
    await wrapper.find('button').trigger('click')
    await flushPromises()
    const select = wrapper.find('select')
    expect(select.exists()).toBe(true)
  })

  it('kind selector has FORM_JS and VARIABLE_SCHEMA options', async () => {
    const wrapper = mount(FormsAdmin)
    await flushPromises()
    await wrapper.find('button').trigger('click')
    await flushPromises()
    const options = wrapper.findAll('option')
    const values = options.map(o => o.element.value)
    expect(values).toContain('FORM_JS')
    expect(values).toContain('VARIABLE_SCHEMA')
  })

  // --- WO-VM-8 behavioral tests ---

  it('key input exists when creating new form', async () => {
    const wrapper = mount(FormsAdmin)
    await flushPromises()
    // Click "Create Form" button
    await wrapper.find('button').trigger('click')
    await flushPromises()
    // Should have an input for the key
    const keyInput = wrapper.find('input')
    expect(keyInput.exists()).toBe(true)
    expect((keyInput.element as HTMLInputElement).placeholder).toContain('formKeyPlaceholder')
  })

  it('POF: save with key + VARIABLE_SCHEMA calls deployForm (WO-VM-8 criterion 2)', async () => {
    const wrapper = mount(FormsAdmin)
    await flushPromises()
    // Click "Create Form"
    await wrapper.find('button').trigger('click')
    await flushPromises()

    // Enter a key
    const keyInput = wrapper.find('input')
    await keyInput.setValue('my-form')

    // Select VARIABLE_SCHEMA
    const select = wrapper.find('select')
    await select.setValue('VARIABLE_SCHEMA')
    await flushPromises()

    // Click save button (second button in the flex container)
    const buttons = wrapper.findAll('button')
    const saveBtn = buttons.find(b => b.text().includes('save'))
    expect(saveBtn).toBeTruthy()
    await saveBtn!.trigger('click')
    await flushPromises()

    // deployForm should be called with (key, schema, 'VARIABLE_SCHEMA')
    expect(mockDeployForm).toHaveBeenCalledTimes(1)
    const args = mockDeployForm.mock.calls[0]
    expect(args[0]).toBe('my-form')
    expect(args[2]).toBe('VARIABLE_SCHEMA')
  })

  it('save with key + FORM_JS calls deployForm (WO-VM-8 criterion 3)', async () => {
    const wrapper = mount(FormsAdmin)
    await flushPromises()
    // Click "Create Form"
    await wrapper.find('button').trigger('click')
    await flushPromises()

    // Enter a key
    const keyInput = wrapper.find('input')
    await keyInput.setValue('my-form-js')

    // Kind is FORM_JS by default, no need to change
    await flushPromises()

    // Click save
    const buttons = wrapper.findAll('button')
    const saveBtn = buttons.find(b => b.text().includes('save'))
    await saveBtn!.trigger('click')
    await flushPromises()

    // deployForm should be called with ('my-form-js', schema, 'FORM_JS')
    expect(mockDeployForm).toHaveBeenCalledTimes(1)
    const args = mockDeployForm.mock.calls[0]
    expect(args[0]).toBe('my-form-js')
    expect(args[2]).toBe('FORM_JS')
  })

  it('empty key → toast error, deployForm NOT called (WO-VM-8 criterion 4)', async () => {
    const wrapper = mount(FormsAdmin)
    await flushPromises()
    // Click "Create Form"
    await wrapper.find('button').trigger('click')
    await flushPromises()

    // Leave key empty, click save
    const buttons = wrapper.findAll('button')
    const saveBtn = buttons.find(b => b.text().includes('save'))
    await saveBtn!.trigger('click')
    await flushPromises()

    // deployForm should NOT be called
    expect(mockDeployForm).not.toHaveBeenCalled()
    // Error toast should be shown
    expect(mockError).toHaveBeenCalledWith('formKeyRequired')
  })
})
