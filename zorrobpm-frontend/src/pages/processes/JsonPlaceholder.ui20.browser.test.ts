/**
 * WO-UI-20 criterion 3 — choosing variable type "JSON" in the start-process
 * modal of the REAL ProcessDefinitionDetail page renders the textarea with
 * the placeholder and logs no console errors, in a real Chromium.
 *
 * Mount pattern (real page + real vue-i18n + mocked services) is copied from
 * visual-geometry.browser.test.ts (mountPdd): the page itself, its template
 * binding `:placeholder="t('jsonPlaceholder')"` and the vue-i18n compiler
 * are all real — only the backend services are stubbed. vue-i18n is
 * deliberately NOT mocked: a t()-stub could never throw, so the check would
 * pass on any locale data (the P-54 class of false-green tests).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import ProcessDefinitionDetail from '@/pages/processes/ProcessDefinitionDetail.vue'
import ru from '@/locales/ru.json'
import en from '@/locales/en.json'
import kz from '@/locales/kz.json'

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: 'def1' }, path: '/' }),
  useRouter: () => ({ push: vi.fn() }),
}))

vi.mock('@/stores/auth', () => ({
  // Super-admin: canStart is true, so the start-process button renders.
  useAuthStore: () => ({ user: { id: 'u-owner', username: 'alice' }, isSuperAdmin: true }),
}))

vi.mock('@/stores/process', () => ({
  useProcessStore: () => ({
    currentDefinition: { id: 'def1', key: 'order', version: 1, name: 'Order', sha256: 'abc', createdAt: '2026-01-01', startFormKey: null },
    currentStructure: { id: 'def1', key: 'order', version: 1, name: 'Order', documentation: null, nodes: [], flows: [] },
    currentVersions: [],
    currentInstance: null,
    currentActivities: [],
    currentSubprocesses: [],
    currentVariables: [],
    instances: [],
    loading: false,
    error: null,
    fetchDefinition: vi.fn(),
    fetchStructure: vi.fn(),
    fetchVersions: vi.fn(),
    fetchInstance: vi.fn(),
    fetchActivities: vi.fn(),
    fetchInstances: vi.fn(),
    fetchDefinitions: vi.fn(),
    fetchSubprocesses: vi.fn(),
    fetchVariables: vi.fn(),
    startInstance: vi.fn(),
    clearCurrent: vi.fn(),
    handleEvent: vi.fn(),
  }),
}))
vi.mock('@/stores/breadcrumb', () => ({
  useBreadcrumbStore: () => ({ setCrumbLabel: vi.fn() }),
}))

const xmlHolder = vi.hoisted(() => ({
  xml: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" xmlns:di="http://www.omg.org/spec/DD/20100524/DI" id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
  <bpmn:process id="Process_1" isExecutable="false">
    <bpmn:startEvent id="StartEvent_1" />
    <bpmn:sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="Task_1" />
    <bpmn:userTask id="Task_1" name="Task" />
    <bpmn:sequenceFlow id="Flow_2" sourceRef="Task_1" targetRef="EndEvent_1" />
    <bpmn:endEvent id="EndEvent_1" />
  </bpmn:process>
  <bpmndi:BPMNDiagram id="BPMNDiagram_1">
    <bpmndi:BPMNPlane id="BPMNPlane_1" bpmnElement="Process_1">
      <bpmndi:BPMNShape id="StartEvent_1_di" bpmnElement="StartEvent_1"><dc:Bounds x="152" y="102" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Task_1_di" bpmnElement="Task_1"><dc:Bounds x="240" y="80" width="100" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="EndEvent_1_di" bpmnElement="EndEvent_1"><dc:Bounds x="392" y="102" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="Flow_1_di" bpmnElement="Flow_1"><di:waypoint x="188" y="120" /><di:waypoint x="240" y="120" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="Flow_2_di" bpmnElement="Flow_2"><di:waypoint x="340" y="120" /><di:waypoint x="392" y="120" /></bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>`,
}))

vi.mock('@/services/processService', () => ({
  getProcessDefinitionXml: vi.fn().mockResolvedValue(xmlHolder.xml),
  getProcessDefinitionStructure: vi.fn().mockResolvedValue({ id: 'pd-1', key: 'test', version: 1, name: 'Test', documentation: null, nodes: [], flows: [] }),
  getProcessDefinition: vi.fn().mockResolvedValue({ id: 'def1', key: 'order', version: 1, name: 'Order', sha256: 'abc', createdAt: '2026-01-01', startFormKey: null }),
  getProcessDefinitionVersions: vi.fn().mockResolvedValue([]),
  getProcessDefinitions: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  deployProcessDefinition: vi.fn().mockResolvedValue({}),
  addProcessDefinitionVersion: vi.fn().mockResolvedValue({}),
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
  getProcessInstance: vi.fn().mockResolvedValue(null),
  getProcessInstanceActivities: vi.fn().mockResolvedValue([]),
  getProcessInstanceActivitiesPaged: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  getProcessInstances: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  startProcessInstance: vi.fn(),
}))
vi.mock('@/services/variableService', () => ({
  getVariables: vi.fn().mockResolvedValue({ data: [] }),
}))
vi.mock('@/services/formService', () => ({
  getSchemaMap: vi.fn().mockResolvedValue({ processDefinitionKey: 'order', version: 1, elements: [] }),
  saveElementSchema: vi.fn().mockResolvedValue(undefined),
  getForm: vi.fn().mockResolvedValue(null),
  listForms: vi.fn().mockResolvedValue([]),
  createElementBinding: vi.fn().mockResolvedValue({}),
  deployForm: vi.fn().mockResolvedValue(undefined),
  getTaskForm: vi.fn().mockResolvedValue(null),
  getStartForm: vi.fn().mockResolvedValue(null),
  generateSchema: vi.fn().mockResolvedValue('{}'),
}))
vi.mock('@/services/adminService', () => ({
  listMembers: vi.fn().mockResolvedValue([]),
  changeMemberRole: vi.fn().mockResolvedValue({}),
  removeMember: vi.fn().mockResolvedValue({}),
  searchMemberCandidates: vi.fn().mockResolvedValue([]),
  addMember: vi.fn().mockResolvedValue({}),
}))
vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn() }),
}))
vi.mock('@/composables/useDateFormat', () => ({
  useDateFormat: () => ({ formatDate: (v: string) => v, formatDateTime: (v: string) => v }),
}))

function makeI18n(locale: string) {
  return createI18n({
    legacy: false,
    locale,
    fallbackLocale: 'en',
    messages: { ru, en, kz },
  })
}

let mounted: VueWrapper[] = []
let hosts: HTMLElement[] = []

beforeEach(() => {
  setActivePinia(createPinia())
  document.body.innerHTML = ''
})

afterEach(() => {
  for (const w of mounted) w.unmount()
  mounted = []
  for (const h of hosts) h.remove()
  hosts = []
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})

function until(cond: () => boolean, timeout = 8000): Promise<void> {
  return new Promise((resolve, reject) => {
    const start = Date.now()
    const tick = () => {
      if (cond()) return resolve()
      if (Date.now() - start > timeout) return reject(new Error('timeout waiting for condition'))
      requestAnimationFrame(tick)
    }
    tick()
  })
}

const expectedPlaceholder: Record<string, string> = {
  en: 'e.g. ["u1","u2"] or {"key":"val"}',
  ru: 'напр. ["u1","u2"] или {"key":"val"}',
  kz: 'мыс. ["u1","u2"] немесе {"key":"val"}',
}

describe('WO-UI-20 criterion 3: JSON variable type renders in a real browser', () => {
  for (const locale of ['ru', 'en', 'kz']) {
    it(`[${locale}] selecting JSON type shows the textarea with the placeholder and no console errors`, async () => {
      const errSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
      const i18n = makeI18n(locale)
      const host = document.createElement('div')
      host.style.position = 'absolute'
      host.style.top = '0'
      host.style.left = '0'
      host.style.display = 'flex'
      host.style.width = '1200px'
      host.style.height = '700px'
      document.body.appendChild(host)
      hosts.push(host)

      const wrapper = mount(ProcessDefinitionDetail, {
        attachTo: host,
        global: { stubs: { teleport: true }, plugins: [createPinia(), i18n] },
      })
      mounted.push(wrapper)
      await flushPromises()

      // Open the start-process modal (the exact user path from the report).
      const startLabel = i18n.global.t('startProcess') as unknown as string
      const startBtn = wrapper
        .findAll('button')
        .find((b) => b.text().trim() === startLabel)
      expect(startBtn, 'start-process button renders').toBeTruthy()
      await startBtn!.trigger('click')
      await flushPromises()

      // Choose the JSON variable type — this is the step that crashed.
      const typeSelect = wrapper.find('select')
      expect(typeSelect.exists(), 'variable type <select> renders').toBe(true)
      await typeSelect.setValue('JSON')
      await flushPromises()
      await until(() => wrapper.find('textarea').exists())

      const area = wrapper.find('textarea')
      expect(area.exists()).toBe(true)
      expect(area.attributes('placeholder')).toBe(expectedPlaceholder[locale])

      // The live symptom: a cascade of console errors from the vue-i18n
      // compiler (trigger/notify/runIfDirty reactive re-evaluation).
      const compileErrors = errSpy.mock.calls.filter((c) =>
        c.map(String).join(' ').includes('Message compilation error'),
      )
      expect(compileErrors, 'no vue-i18n compilation errors in console').toEqual([])
      expect(errSpy, 'no console errors at all on the JSON path').not.toHaveBeenCalled()
    })
  }
})
