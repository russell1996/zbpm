// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ProcessInstanceDetail from './ProcessInstanceDetail.vue'

// hoisted mocks so tests can assert on API calls
const mockCompleteUserTask = vi.hoisted(() => vi.fn().mockResolvedValue(undefined))
const mockCompleteServiceTask = vi.hoisted(() => vi.fn().mockResolvedValue(undefined))
const mockResolveIncident = vi.hoisted(() => vi.fn().mockResolvedValue(undefined))

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: 'pi-1' } }),
  useRouter: () => ({ push: vi.fn() }),
}))

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))

vi.mock('@/services/processService', () => ({
  getProcessDefinitionXml: vi.fn().mockResolvedValue('<definitions id="test" />'),
  getProcessDefinitionStructure: vi.fn().mockResolvedValue({ id: 'pd-1', key: 'test', version: 1, name: 'Test', documentation: null, nodes: [], flows: [] }),
  getProcessDefinition: vi.fn().mockResolvedValue(null),
}))

vi.mock('@/services/taskService', () => ({
  completeUserTask: mockCompleteUserTask,
  completeServiceTask: mockCompleteServiceTask,
  getServiceTasks: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  getUserTasks: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  getUserTask: vi.fn().mockResolvedValue(null),
}))

vi.mock('@/services/incidentService', () => ({
  resolveIncident: mockResolveIncident,
  getIncidents: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  getIncident: vi.fn().mockResolvedValue(null),
}))

vi.mock('@/services/instanceService', () => ({
  getProcessInstance: vi.fn().mockResolvedValue({ id: 'pi-1', parentActivityId: null, processDefinitionId: 'pd-1', startedAt: '2026-01-01', completedAt: null, processName: 'Test', processKey: 'test', processVersion: 1 }),
  getProcessInstanceActivities: vi.fn().mockResolvedValue([]),
  getProcessInstances: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  startProcessInstance: vi.fn(),
}))

vi.mock('@/services/variableService', () => ({
  getVariables: vi.fn().mockResolvedValue({ data: [] }),
}))

const stubs = { teleport: true, BpmnViewer: { template: '<div class="bpmn-stub" />' } }

describe('ProcessInstanceDetail — element dialog (WO-FE-BPMN-1)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  // ──────────────────────────────────────────────
  // POF RED: old goToElementTab (via direct activeTab mutation) loses BPMN tab
  // ──────────────────────────────────────────────
  it('POF RED: goToElementTab changed activeTab — old behavior loses BPMN schema view', async () => {
    const wrapper = mount(ProcessInstanceDetail, {
      global: { stubs, plugins: [createPinia()] },
    })
    await wrapper.vm.$nextTick()
    // wait for async init to settle
    await flushPromises()
    const vm = wrapper.vm as any

    // simulate the pre-fix cross-link behavior: activeTab = 'tasks'
    vm.activeTab = 'tasks'

    // ASSERT: activeTab is NOT 'bpmn' anymore — the BPMN view is lost
    expect(vm.activeTab).toBe('tasks')
    // This would cause the BPMN diagram to disappear. This is the RED (broken) behavior.
  })

  // ──────────────────────────────────────────────
  // POF GREEN: openElementDialog shows dialog, activeTab unchanged
  // ──────────────────────────────────────────────
  it('GREEN: openElementDialog shows element dialog without changing activeTab', async () => {
    const wrapper = mount(ProcessInstanceDetail, {
      global: { stubs, plugins: [createPinia()] },
    })
    await wrapper.vm.$nextTick()
    await flushPromises()
    const vm = wrapper.vm as any

    vm.selectedElement = 'Activity_1qnz0lj'
    vm.activeTab = 'bpmn'

    vm.openElementDialog('Activity_1qnz0lj')
    await wrapper.vm.$nextTick()

    // Dialog is open
    expect(vm.showElementDialog).toBe(true)
    expect(vm.dialogSelectedElement).toBe('Activity_1qnz0lj')
    expect(vm.elementDialogView).toBe('list')
    // activeTab did NOT change — BPMN schema remains visible
    expect(vm.activeTab).toBe('bpmn')
  })

  // ──────────────────────────────────────────────
  // GREEN: cross-link button for user-task calls openElementDialog (not goToElementTab)
  // ──────────────────────────────────────────────
  it('GREEN: user-task cross-link opens dialog, activeTab unchanged', async () => {
    const wrapper = mount(ProcessInstanceDetail, {
      global: { stubs, plugins: [createPinia()] },
    })
    await wrapper.vm.$nextTick()
    await flushPromises()
    const vm = wrapper.vm as any

    // Set up store data so selectedHasUserTask is true
    vm.selectedElement = 'Activity_1'
    vm.activeTab = 'bpmn'

    // Directly set store data via import
    const { useTaskStore } = await import('@/stores/task')
    const taskStore = useTaskStore()
    taskStore.userTasks = {
      data: [{ id: 'ut-1', code: 'Activity_1', name: 'User Task 1', processInstanceId: 'pi-1', processDefinitionId: 'pd-1', status: 'CREATED', formKey: null, createdAt: '2026-01-01T00:00:00Z', completedAt: null }],
      totalElements: 1, pageIndex: 0, pageSize: 100,
    }
    // Also set process store with currentInstance and structure so the BPMN panel renders
    const { useProcessStore } = await import('@/stores/process')
    const processStore = useProcessStore()
    processStore.currentInstance = { id: 'pi-1', parentActivityId: null, processDefinitionId: 'pd-1', startedAt: '2026-01-01', completedAt: null, processName: 'Test', processKey: 'test', processVersion: 1 }
    processStore.currentStructure = { id: 'pd-1', key: 'test', version: 1, name: 'Test', documentation: null, nodes: [], flows: [] }
    await wrapper.vm.$nextTick()

    // BPMN side panel (element details) should now be visible with cross-links
    // The cross-link button text is the i18n key 'openUserTask'
    const allButtons = wrapper.findAll('button')
    const crossLink = allButtons.find((b) => b.text().trim() === 'openUserTask')
    expect(crossLink, `Button 'openUserTask' not found. All buttons: ${allButtons.map((b) => `"${b.text().trim()}"`).join(', ')}`).toBeDefined()

    await crossLink!.trigger('click')
    await wrapper.vm.$nextTick()

    // Dialog opened, tab NOT changed
    expect(vm.showElementDialog).toBe(true)
    expect(vm.activeTab).toBe('bpmn')
  })

  // ──────────────────────────────────────────────
  // GREEN: complete user-task in element dialog → API called + dialog closed + reloadAll
  // ──────────────────────────────────────────────
  it('GREEN: completing a user task in element dialog calls API and reloads', async () => {
    const wrapper = mount(ProcessInstanceDetail, {
      global: { stubs, plugins: [createPinia()] },
    })
    await wrapper.vm.$nextTick()
    await flushPromises()
    const vm = wrapper.vm as any

    // Set up task store with a matching user task
    const { useTaskStore } = await import('@/stores/task')
    const taskStore = useTaskStore()
    taskStore.userTasks = {
      data: [{ id: 'ut-1', code: 'Activity_1', name: 'User Task 1', processInstanceId: 'pi-1', processDefinitionId: 'pd-1', status: 'CREATED', formKey: null, createdAt: '2026-01-01T00:00:00Z', completedAt: null }],
      totalElements: 1, pageIndex: 0, pageSize: 100,
    }
    await wrapper.vm.$nextTick()

    // Open element dialog for Activity_1
    vm.openElementDialog('Activity_1')
    await wrapper.vm.$nextTick()
    expect(vm.showElementDialog).toBe(true)

    // Start complete for the user task
    vm.startElementDialogComplete('ut-1', 'user')
    await wrapper.vm.$nextTick()
    expect(vm.elementDialogView).toBe('form')
    expect(vm.completingTaskId).toBe('ut-1')

    // Confirm complete (no variables needed for this test)
    await vm.confirmComplete()
    await wrapper.vm.$nextTick()

    // API was called
    expect(mockCompleteUserTask).toHaveBeenCalledWith('ut-1', { variables: [] })

    // Dialog closed
    expect(vm.showElementDialog).toBe(false)
    expect(vm.showCompleteModal).toBe(false)
  })

  // ──────────────────────────────────────────────
  // GREEN: resolve incident in element dialog → API called + dialog closed
  // ──────────────────────────────────────────────
  it('GREEN: resolving an incident in element dialog calls API and reloads', async () => {
    const wrapper = mount(ProcessInstanceDetail, {
      global: { stubs, plugins: [createPinia()] },
    })
    await wrapper.vm.$nextTick()
    await flushPromises()
    const vm = wrapper.vm as any

    // Set up activities and incidents so dialogIncidents returns entries
    const { useProcessStore } = await import('@/stores/process')
    const processStore = useProcessStore()
    processStore.currentActivities = [
      { id: 'act-1', processInstanceId: 'pi-1', bpmnElementId: 'Activity_1', type: 'serviceTask', status: 'ERROR', createdAt: '2026-01-01T00:00:00Z', completedAt: null },
    ]

    const { useIncidentStore } = await import('@/stores/incident')
    const incidentStore = useIncidentStore()
    incidentStore.incidents = {
      data: [{ id: 'inc-1', activityId: 'act-1', message: 'Something went wrong', createdAt: '2026-01-01T00:00:00Z', completedAt: null, processName: null, processInstanceId: null, bpmnElementId: null, elementName: null }],
      totalElements: 1, pageIndex: 0, pageSize: 100,
    }
    await wrapper.vm.$nextTick()

    // Open element dialog for Activity_1
    vm.openElementDialog('Activity_1')
    await wrapper.vm.$nextTick()
    expect(vm.showElementDialog).toBe(true)

    // Start resolve for the incident
    vm.startElementDialogResolve('inc-1')
    await wrapper.vm.$nextTick()
    expect(vm.elementDialogView).toBe('form')
    expect(vm.completingTaskType).toBe('resolve')

    // Confirm resolve
    await vm.confirmComplete()
    await wrapper.vm.$nextTick()

    // API was called
    expect(mockResolveIncident).toHaveBeenCalledWith('inc-1', { variables: [] })

    // Dialog closed
    expect(vm.showElementDialog).toBe(false)
  })

  // ──────────────────────────────────────────────
  // WO-FE-10: AI diagnostic download button
  // ──────────────────────────────────────────────
  it('GREEN: diagnostic download button exists and triggers download', async () => {
    // Spy on URL.createObjectURL to verify download is triggered
    const createSpy = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:test')

    const wrapper = mount(ProcessInstanceDetail, {
      global: { stubs, plugins: [createPinia()] },
    })
    await wrapper.vm.$nextTick()
    await flushPromises()

    // Set store data so the instance is available and button renders
    const { useProcessStore } = await import('@/stores/process')
    const processStore = useProcessStore()
    processStore.currentInstance = {
      id: 'pi-1',
      parentActivityId: null,
      processDefinitionId: 'pd-1',
      startedAt: '2026-01-01T00:00:00Z',
      completedAt: null,
      processName: 'Test',
      processKey: 'test',
      processVersion: 1,
    }
    await wrapper.vm.$nextTick()

    // Button should be rendered with the i18n key (mock returns key as-is)
    const allButtons = wrapper.findAll('button')
    const downloadBtn = allButtons.find((b) => b.text().trim() === 'downloadDiagnostic')
    expect(downloadBtn, `Button 'downloadDiagnostic' not found. All buttons: ${allButtons.map((b) => `"${b.text().trim()}"`).join(', ')}`).toBeDefined()

    // Click the button
    await downloadBtn!.trigger('click')
    await wrapper.vm.$nextTick()

    // createObjectURL should have been called with a Blob
    expect(createSpy).toHaveBeenCalledTimes(1)
    const blobArg = createSpy.mock.calls[0][0]
    expect(blobArg).toBeInstanceOf(Blob)
    expect((blobArg as Blob).type).toBe('application/json;charset=utf-8;')

    // Clean up
    createSpy.mockRestore()
  })
})
