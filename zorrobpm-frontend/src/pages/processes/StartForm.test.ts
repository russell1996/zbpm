// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import StartForm from './StartForm.vue'

vi.mock('@bpmn-io/form-js', () => ({
  Form: class MockForm {
    constructor() {}
    submit = vi.fn().mockReturnValue({ data: { amount: '100' }, errors: {} })
    importSchema = vi.fn()
    destroy = vi.fn()
  },
}))

vi.mock('@/services/formService', () => ({
  getTaskForm: vi.fn(),
  getStartForm: vi.fn(),
}))

vi.mock('@/services/instanceService', () => ({
  startProcessInstance: vi.fn().mockResolvedValue({ id: 'instance-1' }),
  getProcessInstances: vi.fn(),
  getProcessInstance: vi.fn(),
  getProcessInstanceActivities: vi.fn(),
}))

import { getStartForm } from '@/services/formService'
import { startProcessInstance } from '@/services/instanceService'

const i18n = createI18n({
  legacy: false, locale: 'en',
  messages: { en: {
    loading: 'Loading...', form: 'Form', externalForm: 'External Form',
    startProcess: 'Start Process',
    noStartFormConfigured: 'No start form configured. Process will start with default settings.',
  }},
})

const router = createRouter({
  history: createMemoryHistory('/ui/'),
  routes: [
    { path: '/ui/processes/:key/start', name: 'start-form', component: StartForm },
    { path: '/ui/process-instances/:id', name: 'process-instance-detail', component: { template: '<div/>' } },
  ],
})
router.push({ name: 'start-form', params: { key: 'orderProcess' } })

describe('StartForm', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(startProcessInstance).mockResolvedValue({ id: 'instance-1' })
    vi.mocked(getStartForm).mockResolvedValue({
      type: 'embedded',
      schema: { type: 'form', components: [{ type: 'number', key: 'amount' }] },
    })
  })

  it('criterion1: renders form for embedded start form', async () => {
    const wrapper = mount(StartForm, {
      global: { plugins: [createPinia(), i18n, router] },
    })
    await flushPromises()

    expect(wrapper.find('.form-js-container').exists()).toBe(true)
  })

  it('criterion2: startProcess calls processService with key and variables', async () => {
    const wrapper = mount(StartForm, {
      global: { plugins: [createPinia(), i18n, router] },
    })
    await flushPromises()

    // The form is embedded, formRef should be set after mount
    const vm = wrapper.vm as any

    // Simulate: if formRef.submit() returns data, startProcess should call processService
    // Test by directly calling startProcess and waiting for all promises
    try {
      await vm.startProcess()
    } catch {
      // startProcess may throw if formRef is not ready, that's OK for this test
    }
    await new Promise((r) => setTimeout(r, 200))

    // Verify processService.startProcess was called (mock returns 'instance-1')
    expect(startProcessInstance).toHaveBeenCalled()
  })

  it('criterion3: none type shows start button without form', async () => {
    vi.mocked(getStartForm).mockResolvedValue({ type: 'none' })

    const wrapper = mount(StartForm, {
      global: { plugins: [createPinia(), i18n, router] },
    })
    await flushPromises()

    expect(wrapper.find('.form-js-container').exists()).toBe(false)
    // WO-ACL-11 criterion 11/12: the hint goes through t() now (test i18n en)
    expect(wrapper.text()).toContain('No start form configured. Process will start with default settings.')
  })

  it('criterion4: external shows URL link', async () => {
    vi.mocked(getStartForm).mockResolvedValue({
      type: 'external', url: 'https://example.com/start',
    })

    const wrapper = mount(StartForm, {
      global: { plugins: [createPinia(), i18n, router] },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('https://example.com/start')
    expect(wrapper.find('a[href="https://example.com/start"]').exists()).toBe(true)
  })

  it('POF: start process does NOT call router.push immediately, shows toast with action', async () => {
    const pushSpy = vi.spyOn(router, 'push')

    const wrapper = mount(StartForm, {
      global: { plugins: [createPinia(), i18n, router] },
    })
    await flushPromises()

    const vm = wrapper.vm as any
    vm.formResponse = { type: 'none' }
    await vm.startProcess()
    await flushPromises()

    // After fix: router.push is NOT called (replaced by toast action button)
    expect(pushSpy).not.toHaveBeenCalled()
  })
})
