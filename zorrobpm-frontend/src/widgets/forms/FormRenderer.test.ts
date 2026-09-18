// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import FormRenderer from './FormRenderer.vue'

const mockSubmit = vi.fn().mockReturnValue({ data: { name: 'test', age: '25' }, errors: {} })
const mockImportSchema = vi.fn()
const mockDestroy = vi.fn()

vi.mock('@bpmn-io/form-js', () => ({
  Form: class MockForm {
    constructor() {}
    submit = mockSubmit
    importSchema = mockImportSchema
    destroy = mockDestroy
  },
}))

describe('FormRenderer', () => {
  const schema = {
    type: 'form',
    components: [
      { type: 'textfield', key: 'name', label: 'Name' },
      { type: 'number', key: 'age', label: 'Age' },
    ],
  }

  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders the form container div (criterion #1)', () => {
    const wrapper = mount(FormRenderer, { props: { schema } })
    expect(wrapper.find('.form-js-container').exists()).toBe(true)
  })

  it('submit returns data without errors (criterion #2)', async () => {
    const wrapper = mount(FormRenderer, {
      props: { schema, data: { name: 'Alice', age: '30' } },
    })

    await wrapper.vm.$nextTick()

    // Call the exposed submit method
    const vm = wrapper.vm as any
    vm.submit()
    await wrapper.vm.$nextTick()

    // Check emitted events
    const emitted = (wrapper.emitted() as Record<string, unknown[][]>)
    expect(emitted.submit).toBeTruthy()
    expect(emitted.submit![0]).toEqual([{ name: 'test', age: '25' }])
  })
})
