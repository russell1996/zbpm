// @vitest-environment jsdom
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import JsonSchemaEditor from './JsonSchemaEditor.vue'

describe('JsonSchemaEditor', () => {
  it('renders textarea with JSON schema', () => {
    const wrapper = mount(JsonSchemaEditor, { props: { modelValue: '' } })
    const textarea = wrapper.find('textarea')
    expect(textarea.exists()).toBe(true)
    expect(textarea.element.value).toContain('json-schema.org')
  })

  it('shows no error for valid JSON', async () => {
    const wrapper = mount(JsonSchemaEditor, {
      props: { modelValue: '{"type":"object"}' },
    })
    await wrapper.find('textarea').trigger('input')
    expect(wrapper.find('.text-destructive').exists()).toBe(false)
  })

  it('shows error for invalid JSON', async () => {
    const wrapper = mount(JsonSchemaEditor, {
      props: { modelValue: '{not valid}' },
    })
    await wrapper.find('textarea').trigger('input')
    expect(wrapper.find('.text-destructive').exists()).toBe(true)
  })

  it('saveSchema returns current value', () => {
    const wrapper = mount(JsonSchemaEditor, {
      props: { modelValue: '{"type":"string"}' },
    })
    const result = (wrapper.vm as any).saveSchema()
    expect(result).toBe('{"type":"string"}')
  })
})
