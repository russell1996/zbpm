// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import FormsAdmin from './FormsAdmin.vue'

const mockListForms = vi.fn()
const mockDeployForm = vi.fn()

vi.mock('@/services/formService', () => ({
  listForms: (...args: any[]) => mockListForms(...args),
  deployForm: (...args: any[]) => mockDeployForm(...args),
}))

vi.mock('@bpmn-io/form-js', () => ({
  FormEditor: class MockFormEditor {
    constructor() {}
    importSchema = vi.fn().mockResolvedValue(undefined)
    saveSchema = vi.fn().mockReturnValue({ type: 'default', components: [] })
    destroy = vi.fn()
  },
}))

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k }),
}))

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({
    success: vi.fn(),
    error: vi.fn(),
  }),
}))

describe('FormsAdmin', () => {
  beforeEach(() => {
    mockListForms.mockResolvedValue([])
    mockDeployForm.mockResolvedValue(undefined)
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
})
