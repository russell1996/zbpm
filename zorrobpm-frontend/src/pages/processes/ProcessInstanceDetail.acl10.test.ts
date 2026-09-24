// @vitest-environment jsdom
/**
 * WO-ACL-10 criterion 10 → WO-ACL-14 criteria 1-3: the instance page now uses the
 * SAME TabsBar component as the definition page — the active tab carries
 * border-primary and the single -mb-px lives on the nav, NEVER on the buttons
 * (the old local strip put -mb-px on every button, which pushed the active
 * underline under the container border — P-55).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ProcessInstanceDetail from './ProcessInstanceDetail.vue'

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
  completeUserTask: vi.fn().mockResolvedValue(undefined),
  completeServiceTask: vi.fn().mockResolvedValue(undefined),
  getServiceTasks: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  getUserTasks: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  getUserTask: vi.fn().mockResolvedValue(null),
}))
vi.mock('@/services/incidentService', () => ({
  resolveIncident: vi.fn().mockResolvedValue(undefined),
  getIncidents: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  getIncident: vi.fn().mockResolvedValue(null),
}))
vi.mock('@/services/instanceService', () => ({
  getProcessInstance: vi.fn().mockResolvedValue({ id: 'pi-1', parentActivityId: null, processDefinitionId: 'pd-1', startedAt: '2026-01-01', completedAt: null, processName: 'Test', processKey: 'test', processVersion: 1 }),
  getProcessInstanceActivities: vi.fn().mockResolvedValue([]),
  getProcessInstanceActivitiesPaged: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  getProcessInstances: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  startProcessInstance: vi.fn(),
}))
vi.mock('@/services/variableService', () => ({
  getVariables: vi.fn().mockResolvedValue({ data: [] }),
}))
vi.mock('@/services/formService', () => ({
  getTaskForm: vi.fn().mockResolvedValue(null),
  getStartForm: vi.fn().mockResolvedValue(null),
  listForms: vi.fn().mockResolvedValue([]),
}))
vi.mock('@/services/timerService', () => ({
  getTimerJobs: vi.fn().mockResolvedValue({ data: [] }),
}))
vi.mock('@/composables/useDateFormat', () => ({
  useDateFormat: () => ({ formatDate: (v: string) => v, formatDateTime: (v: string) => v }),
}))

const stubs = { teleport: true, BpmnViewer: { template: '<div class="bpmn-stub" />' } }

describe('ProcessInstanceDetail — WO-ACL-10 criterion 10', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('instance tab strip (shared TabsBar): active tab has border-primary, single -mb-px on the nav', async () => {
    const wrapper = mount(ProcessInstanceDetail, {
      global: { stubs, plugins: [createPinia()] },
    })
    await wrapper.vm.$nextTick()
    await flushPromises()

    const nav = wrapper.find('nav[role="tablist"]')
    expect(nav.exists()).toBe(true)
    // the SINGLE -mb-px lives on the nav (WO-ACL-10 criteria 9-10)…
    expect(nav.classes()).toContain('-mb-px')
    // …and NEVER on the tab buttons — a button-level -mb-px pushed the active
    // underline under the container border on this very page (P-55)
    const tabs = nav.findAll('button')
    expect(tabs.length).toBeGreaterThanOrEqual(7)
    for (const b of tabs) {
      expect(b.classes()).not.toContain('-mb-px')
      expect(b.classes()).toContain('border-b-2')
    }
    // the active (first, bpmn) tab is highlighted
    const active = tabs.filter((b) => b.classes().includes('border-primary'))
    expect(active.length).toBe(1)
    expect(active[0].text()).toContain('bpmnFlow')
  })
})