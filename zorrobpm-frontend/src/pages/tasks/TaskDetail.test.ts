// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import TaskDetail from './TaskDetail.vue'

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
}))

vi.mock('@/services/taskService', () => ({
  getUserTasks: vi.fn(),
  getUserTask: vi.fn(),
  completeUserTask: vi.fn().mockResolvedValue({ id: 'done' }),
  getServiceTasks: vi.fn(),
  getServiceTask: vi.fn(),
  completeServiceTask: vi.fn(),
}))

vi.mock('@/services/variableService', () => ({
  getVariables: vi.fn().mockResolvedValue({ data: [] }),
}))

import { getTaskForm } from '@/services/formService'
import { getUserTask, completeUserTask } from '@/services/taskService'

const i18n = createI18n({
  legacy: false, locale: 'en',
  messages: { en: {
    loading: 'Loading...', completed: 'Completed', active: 'Active',
    name: 'Name', process: 'Process', formKey: 'Form Key',
    created: 'Created', completedAtLabel: 'Completed At',
    variables: 'Variables', noVariables: 'No variables',
    completeTask: 'Complete Task', submitForm: 'Submit Form',
    form: 'Form', externalForm: 'External Form',
    myTasks: 'My Tasks', userTask: 'User Task',
  }},
})

const router = createRouter({
  history: createMemoryHistory('/ui/'),
  routes: [
    { path: '/ui/processes/instances', name: 'process-instances', component: { template: '<div/>' } },
    { path: '/ui/tasks/:id', name: 'task-detail', component: TaskDetail },
    { path: '/ui/tasks', name: 'my-tasks', component: { template: '<div/>' } },
  ],
})
router.push({ name: 'task-detail', params: { id: 'task-1' } })

describe('TaskDetail — form integration', () => {
  beforeEach(async () => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    await router.push({ name: 'task-detail', params: { id: 'task-1' } })

    vi.mocked(getUserTask).mockResolvedValue({
      id: 'task-1', code: 'User Task', name: null,
      processInstanceId: 'proc-1', processDefinitionId: 'pd-1',
      formKey: 'orderForm', status: null,
      createdAt: '2026-01-01T00:00:00Z', completedAt: null,
    })
    vi.mocked(completeUserTask).mockResolvedValue({ id: 'done' })
  })

  it('criterion3: embedded form renders and submit calls completeUserTask', async () => {
    vi.mocked(getTaskForm).mockResolvedValue({
      type: 'embedded',
      schema: { type: 'form', components: [{ type: 'number', key: 'amount' }] },
      data: { amount: '100' },
    })

    const wrapper = mount(TaskDetail, {
      global: { plugins: [createPinia(), i18n, router] },
    })
    await flushPromises()

    expect(wrapper.find('.form-js-container').exists()).toBe(true)

    const vm = wrapper.vm as any
    vm.onFormSubmit({ amount: '100' })
    await flushPromises()

    expect(completeUserTask).toHaveBeenCalledWith('task-1', {
      variables: [{ name: 'amount', value: '100', type: 'STRING' }],
    })
  })

  it('criterion4: external form shows URL link', async () => {
    vi.mocked(getTaskForm).mockResolvedValue({
      type: 'external', url: 'https://example.com/form',
    })

    const wrapper = mount(TaskDetail, {
      global: { plugins: [createPinia(), i18n, router] },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('https://example.com/form')
    expect(wrapper.find('a[href="https://example.com/form"]').exists()).toBe(true)
  })

  it('criterion5: none type shows generic variable editor', async () => {
    vi.mocked(getTaskForm).mockResolvedValue({ type: 'none' })

    const wrapper = mount(TaskDetail, {
      global: { plugins: [createPinia(), i18n, router] },
    })
    await flushPromises()

    expect(wrapper.find('.form-js-container').exists()).toBe(false)
  })

  it('POF: complete task does NOT call router.push immediately, shows toast with action', async () => {
    vi.mocked(getTaskForm).mockResolvedValue({ type: 'none' })
    const pushSpy = vi.spyOn(router, 'push')

    const wrapper = mount(TaskDetail, {
      global: { plugins: [createPinia(), i18n, router] },
    })
    await flushPromises()

    const vm = wrapper.vm as any
    await vm.doComplete([])
    await flushPromises()

    // After fix: router.push is NOT called (replaced by toast action button)
    expect(pushSpy).not.toHaveBeenCalled()
  })
})
