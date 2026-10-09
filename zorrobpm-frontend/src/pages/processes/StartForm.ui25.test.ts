// @vitest-environment jsdom
/**
 * WO-UI-25 критерий 1 — StartForm ведёт НА СТРАНИЦУ инстанса.
 *
 * RED на коде до WO-UI-25: после старта — только тост с кнопкой, перехода нет;
 * повторный вызов во время полёта не отбит.
 */
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
  startProcessInstance: vi.fn().mockResolvedValue({ id: 'instance-7' }),
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
    presetManualMode: 'Manual input', presetTemplateMode: 'From template',
    presetTableMode: 'Table', presetRawMode: 'Raw JSON',
    presetVarName: 'Name', presetVarType: 'Type', presetVarValue: 'Value',
    presetAddVariable: 'Add variable', presetNoVariables: 'No variables',
    presetFillAskFields: 'Fill: {fields}',
    instanceStarted: 'Instance started',
  }},
})

function makeRouter() {
  const router = createRouter({
    history: createMemoryHistory('/ui/'),
    routes: [
      { path: '/ui/processes/:key/start', name: 'start-form', component: StartForm },
      { path: '/ui/process-instances/:id', name: 'process-instance-detail', component: { template: '<div/>' } },
      { path: '/processes/instances/:id', name: 'instance', component: { template: '<div/>' } },
    ],
  })
  router.push({ name: 'start-form', params: { key: 'orderProcess' } })
  return router
}

describe('WO-UI-25 criterion 1: StartForm lands on the instance page', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(startProcessInstance).mockResolvedValue({ id: 'instance-7' })
    vi.mocked(getStartForm).mockResolvedValue({ type: 'none' })
  })

  it('successful start navigates to the instance page', async () => {
    const router = makeRouter()
    const pushSpy = vi.spyOn(router, 'push')
    const wrapper = mount(StartForm, {
      global: { plugins: [createPinia(), i18n, router] },
    })
    await flushPromises()
    const vm = wrapper.vm as any
    await vm.startProcess()
    await flushPromises()
    expect(pushSpy).toHaveBeenCalledWith('/processes/instances/instance-7')
    wrapper.unmount()
  })

  it('concurrent double start creates only ONE instance', async () => {
    const router = makeRouter()
    const wrapper = mount(StartForm, {
      global: { plugins: [createPinia(), i18n, router] },
    })
    await flushPromises()
    const vm = wrapper.vm as any
    await Promise.all([vm.startProcess(), vm.startProcess()])
    await flushPromises()
    expect(startProcessInstance).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('failed start stays on the form with a visible error', async () => {
    vi.mocked(startProcessInstance).mockRejectedValue(new Error('boom'))
    const router = makeRouter()
    const pushSpy = vi.spyOn(router, 'push')
    const wrapper = mount(StartForm, {
      global: { plugins: [createPinia(), i18n, router] },
    })
    await flushPromises()
    const vm = wrapper.vm as any
    await vm.startProcess()
    await flushPromises()
    expect(pushSpy).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('boom')
    wrapper.unmount()
  })
})
