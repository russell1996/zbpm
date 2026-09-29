/**
 * WO-TEST-6 (+ WO-ACL-14, merged in) — browser-geometry checks.
 *
 * jsdom cannot compute layout (`getBoundingClientRect()` is always 0, P-53), so
 * the visual criteria that bit us on the live stand are asserted HERE, in a
 * real headless Chromium (vitest browser mode + Playwright provider). Every
 * check asserts geometry — rectangles, computed styles, scroll sizes — not
 * screenshots, and none of them duplicates an existing jsdom test (the jsdom
 * suite stays GREEN under every POF mutation below; see the report table).
 *
 * The WO-ACL-14 checks were merged into this single file (the second browser
 * file `wo-acl-14.browser.test.ts` is gone): tab-underline checks use the
 * STRICT assertions (±0.5px position, exact 2px width) instead of the weaker
 * ±2px tolerance that could not distinguish the -mb-px mutation.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { page } from 'vitest/browser'
import ProcessInstanceDetail from '@/pages/processes/ProcessInstanceDetail.vue'
import ProcessDefinitionDetail from '@/pages/processes/ProcessDefinitionDetail.vue'
import SidebarNav from '@/widgets/shared/SidebarNav.vue'
import TimerList from '@/pages/timers/TimerList.vue'
import AppDrawer from '@/widgets/shared/AppDrawer.vue'
import MySubmissions from '@/pages/processes/MySubmissions.vue'
import MailSettings from '@/pages/admin/MailSettings.vue'
import ru from '@/locales/ru.json'
import en from '@/locales/en.json'
import kz from '@/locales/kz.json'

// The REAL app stylesheet (Tailwind) and fonts, exactly as main.ts loads them.
// Without Tailwind CSS the browser measures UA defaults (e.g. the native 2px
// <button> border), which proves nothing about the app's geometry.
import '@/style.css'
import '@fontsource/golos-text/400.css'
import '@fontsource/golos-text/500.css'
import '@fontsource/golos-text/600.css'
import '@fontsource/golos-text/700.css'

const SHOT_DIR = 'shots'

// ---- file-level mocks. vue-i18n is deliberately NOT mocked: check 4 needs the
// real Kazakh strings (a t()-stub can never wrap, so any layout would pass). ----

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: 'def1' }, path: '/' }),
  useRouter: () => ({ push: vi.fn() }),
}))

const mockAuth = vi.hoisted(() => ({ isSuperAdmin: true }))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ user: { id: 'u-owner', username: 'alice' }, isSuperAdmin: mockAuth.isSuperAdmin }),
}))

vi.mock('@/stores/process', () => ({
  useProcessStore: () => ({
    currentDefinition: { id: 'def1', key: 'order', version: 1, name: 'Order', sha256: 'abc', createdAt: '2026-01-01', startFormKey: null },
    currentStructure: { id: 'def1', key: 'order', version: 1, name: 'Order', documentation: null, nodes: [], flows: [] },
    currentVersions: [],
    currentInstance: { id: 'pi-1', parentActivityId: null, processDefinitionId: 'def1', startedAt: '2026-01-01', completedAt: null, processName: 'Order', processKey: 'order', processVersion: 1 },
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

const mockListMembers = vi.hoisted(() => vi.fn())
const mockGetMailHealth = vi.hoisted(() => vi.fn())
const mockGetMailSettings = vi.hoisted(() => vi.fn())
vi.mock('@/services/adminService', () => ({
  listMembers: mockListMembers,
  changeMemberRole: vi.fn().mockResolvedValue({}),
  removeMember: vi.fn().mockResolvedValue({}),
  searchMemberCandidates: vi.fn().mockResolvedValue([
    { userId: 'u-annette', username: 'annette', fullName: 'Анна Аннет', email: 'annette@t.com' },
    { userId: 'u-bob', username: 'bob', fullName: '', email: '' },
  ]),
  addMember: vi.fn().mockResolvedValue({}),
  getMailHealth: mockGetMailHealth,
  getMailSettings: mockGetMailSettings,
  saveMailSettings: vi.fn().mockResolvedValue({ passwordSet: true }),
  checkMailSettings: vi.fn().mockResolvedValue({ reachable: true, errorCode: null }),
  testMailSettingsToSelf: vi.fn().mockResolvedValue(undefined),
}))

const mockGetTimers = vi.hoisted(() => vi.fn())
vi.mock('@/services/timerService', () => ({
  getTimerJobs: mockGetTimers,
}))

const mockGetMySubmissions = vi.hoisted(() => vi.fn())
vi.mock('@/services/submissionService', () => ({
  getMySubmissions: mockGetMySubmissions,
  submitProcessSubmission: vi.fn().mockResolvedValue({}),
  getPendingSubmissions: vi.fn().mockResolvedValue([]),
  approveSubmission: vi.fn().mockResolvedValue({}),
  rejectSubmission: vi.fn().mockResolvedValue({}),
  getSubmissionBpmn: vi.fn().mockResolvedValue(''),
}))

// A real, valid BPMN diagram — the viewer (real bpmn-js) renders it in Chromium,
// so checks 5/6 measure the actual canvas geometry and a real element click.
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
  getProcessInstance: vi.fn().mockResolvedValue({ id: 'pi-1', parentActivityId: null, processDefinitionId: 'pd-1', startedAt: '2026-01-01', completedAt: null, processName: 'Test', processKey: 'test', processVersion: 1 }),
  getProcessInstanceActivities: vi.fn().mockResolvedValue([]),
  getProcessInstanceActivitiesPaged: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  getProcessInstances: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  startProcessInstance: vi.fn(),
  // WO-UI-21 Раунд 2: новый экспорт сервиса (browser = нативный ESM, без него — import error).
  cancelProcessInstance: vi.fn(),
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
vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn() }),
}))
vi.mock('@/composables/useDateFormat', () => ({
  useDateFormat: () => ({ formatDate: (v: string) => v, formatDateTime: (v: string) => v }),
}))
vi.mock('@/shared/lib/export', () => ({ exportToCsv: vi.fn() }))

// ---- helpers ----

function makeI18n(locale: string) {
  return createI18n({
    legacy: false,
    locale,
    fallbackLocale: 'en',
    messages: { ru, en, kz },
  })
}

/** Absolutely-positioned host with a definite size, so min-h-full/flex chains compute.
 *  display:flex makes the intermediate mount container (@vue/test-utils) stretch
 *  to the host's height instead of collapsing to its content. */
function mountHost(w: number, h: number, flex = true): HTMLElement {
  const host = document.createElement('div')
  host.style.position = 'absolute'
  host.style.top = '0'
  host.style.left = '0'
  if (flex) host.style.display = 'flex'
  host.style.width = w + 'px'
  host.style.height = h + 'px'
  document.body.appendChild(host)
  return host
}

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

let mounted: VueWrapper[] = []
let hosts: HTMLElement[] = []

beforeEach(() => {
  setActivePinia(createPinia())
  document.body.innerHTML = ''
  mockListMembers.mockResolvedValue([
    { userId: 'u-owner', username: 'alice', fullName: 'Alice A.', email: 'alice@test.com', role: 'OWNER', addedBy: null, addedAt: '2026-01-01', processKey: 'order' },
    { userId: 'u-bob', username: 'bob', fullName: 'Bob B.', email: 'bob@test.com', role: 'VIEWER', addedBy: 'u-owner', addedAt: '2026-01-02', processKey: 'order' },
  ])
  mockGetTimers.mockResolvedValue({
    data: [
      { id: '4394c7b1-aaaa-4000-8000-000000000001', processInstanceId: 'inst-11111111-aaaa-4000-8000-000000000001', activityId: 'Activity_1abc', dueAt: '2026-01-01', fired: true, boundaryElementId: null, eventSubprocessId: null, createdAt: '2026-01-01' },
      { id: 'timer-2', processInstanceId: null, activityId: 'act-only', dueAt: '2026-01-02', fired: false, boundaryElementId: null, eventSubprocessId: null, createdAt: '2026-01-02' },
    ],
    totalElements: 2,
  })
  mockGetMySubmissions.mockResolvedValue([
    { id: 'sub-1', processKey: 'vacation', name: 'Vacation', status: 'APPROVED', submittedBy: 'alice', submittedAt: '2026-08-01T10:00:00Z', rejectReason: null, previousSubmissionId: null },
    { id: 'sub-2', processKey: 'purchase', name: 'Purchase', status: 'REJECTED', submittedBy: 'alice', submittedAt: '2026-08-02T10:00:00Z', rejectReason: 'документы не приложены: счёт-фактура № 12345678901234567890 от 2026-08-01 отсутствует в системе и не может быть восстановлен автоматически, пожалуйста приложите оригинал', previousSubmissionId: null },
  ])
})

afterEach(() => {
  for (const w of mounted) w.unmount()
  mounted = []
  for (const h of hosts) h.remove()
  hosts = []
  document.body.innerHTML = ''
})

function mountPid(w = 1200, h = 700, flex = true) {
  const host = mountHost(w, h, flex)
  hosts.push(host)
  const wrapper = mount(ProcessInstanceDetail, {
    attachTo: host,
    global: { stubs: { teleport: true }, plugins: [createPinia(), makeI18n('en')] },
  })
  mounted.push(wrapper)
  return wrapper
}

function mountPdd() {
  const host = mountHost(1200, 700)
  hosts.push(host)
  const wrapper = mount(ProcessDefinitionDetail, {
    attachTo: host,
    global: { stubs: { teleport: true }, plugins: [createPinia(), makeI18n('ru')] },
  })
  mounted.push(wrapper)
  return wrapper
}

function mountNav(collapsed: boolean, locale: string, w: number, h: number) {
  const host = mountHost(w, h)
  hosts.push(host)
  const wrapper = mount(SidebarNav, {
    props: { collapsed },
    attachTo: host,
    global: { plugins: [makeI18n(locale)] },
  })
  mounted.push(wrapper)
  return wrapper
}

// ---- WO-TEST-6 check 1 (+ WO-ACL-14 criterion 2): the active tab underline is
// really visible on BOTH tab strips. TabsBar markup (role="tab", aria-selected),
// STRICT assertions: exact 2px width, real color, position within ±0.5px of the
// strip's bottom line (the ±2px tolerance could not see the -mb-px mutation). ----

describe('WO-TEST-6 check 1 — active tab underline (ProcessInstanceDetail, TabsBar)', () => {
  it('the active tab renders a 2px underline that reaches the strip border line', async () => {
    const wrapper = mountPid()
    await flushPromises()
    await until(() => !!wrapper.element.querySelector('.bpmn-viewer-wrapper'))

    const nav = (wrapper.element as Element).querySelector('nav[role="tablist"]')!
    const tabBtns = Array.from(nav.querySelectorAll<HTMLButtonElement>('button[role="tab"]'))
    expect(tabBtns.length).toBeGreaterThanOrEqual(7)
    const active = tabBtns.find((b) => b.getAttribute('aria-selected') === 'true')!
    expect(active).toBeTruthy()
    const cs = getComputedStyle(active)
    // the underline is a real border, not a class with width 0 (WO-ACL-15 criterion 13:
    // noticeably thicker than the 1px container line). WO-UI-14 (compact headers) changed
    // TabsBar from border-b-[3px] to border-b-2 — 2px, still thicker than the 1px line.
    expect(cs.borderBottomWidth).toBe('2px')
    expect(cs.borderBottomColor).not.toBe('rgba(0, 0, 0, 0)')
    expect(cs.borderBottomColor).not.toBe('transparent')
    // and it reaches the strip's bottom border line (the -mb-px overlap on the
    // <nav>, ACL-14/TabsBar), instead of hovering above it or sliding under the
    // container border (P-55) — strict ±0.5px, not the weak ±2px
    const btnRect = active.getBoundingClientRect()
    const navRect = nav.getBoundingClientRect()
    expect(btnRect.bottom).toBeGreaterThanOrEqual(navRect.bottom - 0.5)
    expect(btnRect.bottom).toBeLessThanOrEqual(navRect.bottom + 0.5)

    // WO-ACL-15 criterion 13: the ACTUAL PAINTED color at a point on the line
    // under the active tab differs from the same point under an inactive tab,
    // and under the active one it is the ACCENT color, not the container border.
    const inactive = tabBtns.find((b) => b.getAttribute('aria-selected') === 'false')!
    const inactiveRect = inactive.getBoundingClientRect()
    const activePoint = document.elementFromPoint(btnRect.left + btnRect.width / 2, btnRect.bottom - 1)
    const inactivePoint = document.elementFromPoint(inactiveRect.left + inactiveRect.width / 2, inactiveRect.bottom - 1)
    expect(activePoint).toBeTruthy()
    expect(inactivePoint).toBeTruthy()
    const activeColor = getComputedStyle(activePoint as Element).borderBottomColor
    const inactiveColor = getComputedStyle(inactivePoint as Element).borderBottomColor
    const containerBorder = getComputedStyle(nav.parentElement!).borderTopColor
    expect(activeColor).not.toBe(inactiveColor)
    expect(activeColor).not.toBe(containerBorder)
    // active caption: accent color and heavier weight than the inactive ones
    expect(getComputedStyle(active).color).not.toBe(getComputedStyle(inactive).color)
    expect(Number.parseFloat(getComputedStyle(active).fontWeight)).toBeGreaterThan(
      Number.parseFloat(getComputedStyle(inactive).fontWeight),
    )

    await page.screenshot({ path: `${SHOT_DIR}/wo-acl-14-instance-tabs.png` })
  })
})

describe('WO-TEST-6 check 1b — active tab underline (ProcessDefinitionDetail, TabsBar)', () => {
  it('the active tab renders a 2px underline that reaches the strip border line', async () => {
    const wrapper = mountPdd()
    await flushPromises()

    const nav = (wrapper.element as Element).querySelector('nav[role="tablist"]')!
    const tabBtns = Array.from(nav.querySelectorAll<HTMLButtonElement>('button[role="tab"]'))
    expect(tabBtns.length).toBeGreaterThanOrEqual(3)
    const active = tabBtns.find((b) => b.getAttribute('aria-selected') === 'true')!
    expect(active).toBeTruthy()
    const cs = getComputedStyle(active)
    expect(cs.borderBottomWidth).toBe('2px')
    expect(cs.borderBottomColor).not.toBe('rgba(0, 0, 0, 0)')
    expect(cs.borderBottomColor).not.toBe('transparent')
    const btnRect = active.getBoundingClientRect()
    const navRect = nav.getBoundingClientRect()
    expect(btnRect.bottom).toBeGreaterThanOrEqual(navRect.bottom - 0.5)
    expect(btnRect.bottom).toBeLessThanOrEqual(navRect.bottom + 0.5)

    // WO-ACL-15 criterion 13 (same point-color assertions as the instance strip)
    const inactive = tabBtns.find((b) => b.getAttribute('aria-selected') === 'false')!
    const inactiveRect = inactive.getBoundingClientRect()
    const activePoint = document.elementFromPoint(btnRect.left + btnRect.width / 2, btnRect.bottom - 1)
    const inactivePoint = document.elementFromPoint(inactiveRect.left + inactiveRect.width / 2, inactiveRect.bottom - 1)
    expect(activePoint).toBeTruthy()
    expect(inactivePoint).toBeTruthy()
    const activeColor = getComputedStyle(activePoint as Element).borderBottomColor
    const inactiveColor = getComputedStyle(inactivePoint as Element).borderBottomColor
    const containerBorder = getComputedStyle(nav.parentElement!).borderTopColor
    expect(activeColor).not.toBe(inactiveColor)
    expect(activeColor).not.toBe(containerBorder)
    expect(getComputedStyle(active).color).not.toBe(getComputedStyle(inactive).color)
    expect(Number.parseFloat(getComputedStyle(active).fontWeight)).toBeGreaterThan(
      Number.parseFloat(getComputedStyle(inactive).fontWeight),
    )

    await page.screenshot({ path: `${SHOT_DIR}/wo-acl-14-definition-tabs.png` })
  })
})

// ---- WO-ACL-14 criterion 5: narrow screen — ribbon scrolls, active tab in view ----

describe('WO-ACL-14 criterion 5 — narrow screen (ProcessInstanceDetail)', () => {
  it('at 500px the instance ribbon overflows horizontally and the active tab is visible', async () => {
    await page.viewport(500, 720)
    // no flex host here: a flex item's min-width:auto would stretch the strip to
    // its content and hide the overflow this check exists to see
    const wrapper = mountPid(500, 720, false)
    await flushPromises()
    await until(() => !!wrapper.element.querySelector('nav[role="tablist"]'))

    const nav = (wrapper.element as Element).querySelector('nav[role="tablist"]')!
    // 7 tabs at ~100px each do not fit into 500px → the ribbon scrolls
    expect(nav.scrollWidth).toBeGreaterThan(nav.clientWidth)
    // the active (first) tab is inside the ribbon's visible area
    const active = nav.querySelector<HTMLElement>('button[aria-selected="true"]')!
    const nr = nav.getBoundingClientRect()
    const ar = active.getBoundingClientRect()
    expect(ar.left).toBeGreaterThanOrEqual(nr.left - 1)
    expect(ar.right).toBeLessThanOrEqual(nr.right + 1)

    await page.screenshot({ path: `${SHOT_DIR}/wo-acl-14-instance-tabs-narrow.png` })
    await page.viewport(1280, 720)
  })
})

// ---- WO-TEST-6 checks 2 & 3: collapsed sidebar — flyout not clipped, no h-scroll ----

describe('WO-TEST-6 checks 2 & 3 — collapsed sidebar flyout and horizontal scroll', () => {
  it('check 2: the collapsed flyout sits next to the hovered button, fully inside the viewport', async () => {
    const wrapper = mountNav(true, 'en', 56, 600)
    const buttons = wrapper.findAll('nav button')
    expect(buttons.length).toBeGreaterThanOrEqual(11)
    const last = buttons[buttons.length - 1]
    await last.trigger('mouseenter')

    const flyout = wrapper.find('.sidebar-flyout')
    expect(flyout.exists()).toBe(true)
    const fr = flyout.element.getBoundingClientRect()
    const br = last.element.getBoundingClientRect()
    // visible at all (jsdom would report zeros)
    expect(fr.width).toBeGreaterThan(0)
    expect(fr.height).toBeGreaterThan(0)
    // positioned from the hovered button's rect (left: rect.right + 8, centered),
    // NOT rendered in-flow elsewhere (e.g. inside the scroll container bottom)
    expect(fr.left).toBeGreaterThanOrEqual(br.right - 1)
    expect(fr.top).toBeLessThanOrEqual(br.bottom)
    expect(fr.bottom).toBeGreaterThanOrEqual(br.top)
    // fully inside the viewport — no ancestor clips it
    expect(fr.left).toBeGreaterThanOrEqual(0)
    expect(fr.top).toBeGreaterThanOrEqual(0)
    expect(fr.right).toBeLessThanOrEqual(window.innerWidth)
    expect(fr.bottom).toBeLessThanOrEqual(window.innerHeight)
  })

  it('check 3: collapsed mode produces no horizontal scroll (menu scroller and document)', async () => {
    const wrapper = mountNav(true, 'en', 56, 600)
    const aside = wrapper.find('aside').element
    // the inner scroll container (the only one in the collapsed nav) must not
    // overflow horizontally — a wide child there would draw a scrollbar
    const scroller = wrapper.find('aside div.overflow-y-auto').element
    expect(scroller.scrollWidth).toBeLessThanOrEqual(scroller.clientWidth)
    expect(aside.scrollWidth).toBeLessThanOrEqual(aside.clientWidth)
    expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(window.innerWidth)
  })
})

// ---- WO-TEST-6 check 4: real Kazakh labels — one line, no ellipsis ----

describe('WO-TEST-6 check 4 — kazakh labels fit one line without ellipsis', () => {
  it('every kz menu label is a single line and is not truncated (real strings, expanded rail)', () => {
    const wrapper = mountNav(false, 'kz', 256, 600)
    const spans = wrapper.findAll('nav button span')
    expect(spans.length).toBeGreaterThanOrEqual(10)
    for (const s of spans) {
      // real kz string, not the t() key stub
      const key = Object.keys(kz).find((k) => (kz as unknown as Record<string, string>)[k] === s.text())
      expect(key).toBeTruthy()
      const el = s.element
      const rect = el.getBoundingClientRect()
      // one line: text-sm ≈ 20px line box; a wrapped label is ~2x that
      expect(rect.height).toBeLessThanOrEqual(22)
      // no ellipsis: full text fits inside the label box
      expect(el.scrollWidth).toBeLessThanOrEqual(el.clientWidth + 1)
    }
  })
})

// ---- WO-ACL-15 criterion 15: the BPMN canvas stays LIGHT in the dark theme ----

function luminance(rgb: string): number {
  const m = rgb.match(/rgba?\((\d+), (\d+), (\d+)/)
  if (!m) return 0
  const [r, g, b] = [Number(m[1]) / 255, Number(m[2]) / 255, Number(m[3]) / 255]
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

describe('WO-ACL-15 criterion 15 — BPMN canvas contrast in the DARK theme', () => {
  it('the canvas background is explicitly light and the shape stroke is distinguishable from it', async () => {
    document.documentElement.classList.add('dark')
    try {
      const wrapper = mountPid()
      await flushPromises()
      // real bpmn-js renders shapes in Chromium
      await until(() => !!wrapper.element.querySelector('.bpmn-container .djs-shape'))

      const canvas = wrapper.find('.bpmn-container').element
      const bg = getComputedStyle(canvas).backgroundColor
      const bgLum = luminance(bg)
      // EXPLICIT light background — not inherited from the dark card (#1e293b ~ 0.06)
      expect(bgLum, `canvas background ${bg} must be light`).toBeGreaterThan(0.8)

      // the shape stroke (bpmn-js paints black by default) must contrast with it
      const shapeVisual = (wrapper.element as Element).querySelector('.djs-shape .djs-visual > :is(rect, path, circle, polygon)')
      expect(shapeVisual).toBeTruthy()
      const stroke = getComputedStyle(shapeVisual as Element).stroke
      const strokeLum = luminance(stroke)
      expect(Math.abs(bgLum - strokeLum), `background ${bg} vs stroke ${stroke}`).toBeGreaterThan(0.5)

      await page.screenshot({ path: `${SHOT_DIR}/wo-acl-15-bpmn-dark.png` })
    } finally {
      document.documentElement.classList.remove('dark')
    }
  })
})

// ---- WO-TEST-6 checks 5 & 6: BPMN canvas to the bottom; properties panel height ----

describe('WO-TEST-6 checks 5 & 6 — BPMN canvas and properties panel geometry', () => {
  it('check 5: the canvas stretches to the bottom edge of its card (no void under it)', async () => {
    const wrapper = mountPid()
    await flushPromises()
    // real bpmn-js renders shapes in Chromium
    await until(() => !!wrapper.element.querySelector('.bpmn-viewer-wrapper .djs-shape'))

    const wr = wrapper.find('.bpmn-viewer-wrapper')
    const wrect = wr.element.getBoundingClientRect()
    const parentRect = wr.element.parentElement!.getBoundingClientRect()
    // a real canvas height (not the 400px-fixed defect, not a stub)
    expect(wrect.height).toBeGreaterThan(300)
    // reaches the bottom edge of the card — no void below
    expect(wrect.bottom).toBeGreaterThanOrEqual(parentRect.bottom - 2)
  })

  it('check 6: the properties panel has the canvas height and scrolls inside itself', async () => {
    const wrapper = mountPid()
    await flushPromises()
    await until(() => !!wrapper.element.querySelector('.bpmn-viewer-wrapper .djs-shape'))

    // open the panel via a REAL click on a bpmn-js shape (production path)
    const shape = (wrapper.element as Element).querySelector<SVGElement>('.bpmn-viewer-wrapper .djs-shape')!
    const srect = shape.getBoundingClientRect()
    const target = document.elementFromPoint(srect.x + srect.width / 2, srect.y + srect.height / 2) ?? shape
    target.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await until(() => !!wrapper.element.querySelector('.w-80'))

    const panel = wrapper.find('.w-80')
    const prect = panel.element.getBoundingClientRect()
    const row = panel.element.parentElement!
    const rowRect = row.getBoundingClientRect()
    // flex stretch: same height as the canvas row (not a fixed max-height panel)
    expect(Math.abs(prect.height - rowRect.height)).toBeLessThanOrEqual(2)
    // scrolls INSIDE itself, not with the page
    expect(getComputedStyle(panel.element).overflowY).toBe('auto')
  })
})

// ---- WO-ACL-14 criterion 8: timer status is ONE element (icon inside the pill) ----

describe('WO-ACL-14 criterion 8 — timer status is one element (TimerList)', () => {
  it('the icon sits INSIDE the badge pill, no loose icon next to it', async () => {
    const wrapper = mount(TimerList, {
      attachTo: mountHost(1280, 720),
      global: { plugins: [createPinia(), makeI18n('ru')] },
    })
    mounted.push(wrapper)
    await flushPromises()

    const firstRow = wrapper.findAll('tbody tr')[0]
    const pill = firstRow.find('span.rounded-full').element
    const svg = firstRow.find('span.rounded-full svg').element
    const pr = pill.getBoundingClientRect()
    const sr = svg.getBoundingClientRect()
    // the icon rectangle is fully INSIDE the pill rectangle
    expect(sr.left).toBeGreaterThanOrEqual(pr.left)
    expect(sr.right).toBeLessThanOrEqual(pr.right)
    expect(sr.top).toBeGreaterThanOrEqual(pr.top)
    expect(sr.bottom).toBeLessThanOrEqual(pr.bottom)
    // and the status cell contains exactly ONE svg (the pill's own icon)
    const cell = firstRow.findAll('td')[3]
    expect(cell.findAll('svg')).toHaveLength(1)

    await page.screenshot({ path: `${SHOT_DIR}/wo-acl-14-timer-status.png` })
  })
})

// ---- WO-ACL-14 criteria 9-12: member dialog (candidates, already-added mark) ----

describe('WO-ACL-14 criteria 9-12 — member dialog (ProcessDefinitionDetail)', () => {
  it('dialog opens from the members tab, lists candidates, marks the already-added one', async () => {
    const wrapper = mountPdd()
    await flushPromises()

    // open the Members tab
    const membersTab = wrapper.findAll('nav[role="tablist"] button').find((b) => b.text() === 'Участники')!
    await membersTab.trigger('click')
    await flushPromises()

    // open the dialog via the "Add member" button
    const addButton = wrapper.findAll('button').find((b) => b.text() === 'Добавить участника')!
    await addButton.trigger('click')
    await flushPromises()

    const input = wrapper.find('input[placeholder*="Поиск по имени"]')
    expect(input.exists()).toBe(true)
    await input.setValue('ann')
    await flushPromises()

    // both candidates render; bob is already a member → marked, disabled
    await until(() => wrapper.findAll('button').some((b) => b.text().includes('annette')))
    const bobRow = wrapper.findAll('button').find((b) => b.text().includes('bob'))!
    // real ru string (alreadyMember): «Пользователь уже является участником этой формы»
    expect(bobRow.text()).toContain('уже является участником')
    expect((bobRow.element as HTMLButtonElement).disabled).toBe(true)
    const annetteRow = wrapper.findAll('button').find((b) => b.text().includes('annette'))!
    expect((annetteRow.element as HTMLButtonElement).disabled).toBe(false)

    await page.screenshot({ path: `${SHOT_DIR}/wo-acl-14-member-dialog.png` })
  })
})

// ---- WO-ACL-14 criterion 17: full ids in tables (no truncation) ----

describe('WO-ACL-14 criterion 17 — full ids in tables (TimerList)', () => {
  it('the id cell shows the WHOLE uuid, not clipped', async () => {
    const wrapper = mount(TimerList, {
      attachTo: mountHost(1280, 720),
      global: { plugins: [createPinia(), makeI18n('ru')] },
    })
    mounted.push(wrapper)
    await flushPromises()

    const LONG_ID = '4394c7b1-aaaa-4000-8000-000000000001'
    const firstRow = wrapper.findAll('tbody tr')[0]
    const idSpan = firstRow.find('span.group').element
    expect(idSpan.textContent).toContain(LONG_ID)
    // the cell does not clip the text: no horizontal overflow of the span itself
    expect(idSpan.scrollWidth).toBeLessThanOrEqual(idSpan.clientWidth + 1)

    await page.screenshot({ path: `${SHOT_DIR}/wo-acl-14-full-ids.png` })
  })
})

// ---- WO-ACL-14 criteria 20-22: My Submissions drawer (max width, long reason) ----

describe('WO-ACL-14 criteria 20-22 — My Submissions drawer', () => {
  it('panel is capped at max-w-4xl; the long reject reason is readable in full (wrapped, not clipped)', async () => {
    const host = mountHost(1280, 720)
    hosts.push(host)
    const wrapper = mount(AppDrawer, {
      attachTo: host,
      props: { open: true, title: 'Мои заявки' },
      slots: { default: MySubmissions },
      global: { plugins: [createPinia(), makeI18n('ru')] },
    })
    mounted.push(wrapper)
    await flushPromises()
    await until(() => !!wrapper.element.querySelector('.text-red-600 span'))

    const panel = wrapper.get('[data-testid="drawer-panel"]').element
    const pr = panel.getBoundingClientRect()
    // max-w-4xl = 56rem = 896px; on a 1280px viewport the panel is capped
    expect(pr.width).toBeLessThanOrEqual(896 + 2)

    const reason = wrapper.element.querySelector('.text-red-600 span') as HTMLElement | null
    expect(reason).not.toBeNull()
    expect(reason!.textContent).toContain('документы не приложены')
    // wrapped inside the cell: the span does not overflow its box horizontally
    expect(reason!.scrollWidth).toBeLessThanOrEqual(reason!.clientWidth + 1)
    // the table container scrolls horizontally inside the panel when needed
    const scroller = wrapper.element.querySelector('div.overflow-x-auto') as HTMLElement | null
    expect(scroller).not.toBeNull()
    expect(getComputedStyle(scroller!).overflowX).toBe('auto')

    await page.screenshot({ path: `${SHOT_DIR}/wo-acl-14-my-submissions.png` })
  })
})

// ---- WO-UI-9 point 3 — MailSettings form is capped at max-w-2xl (like MyApiKey) ----

describe('WO-UI-9 point 3 — MailSettings max-w-2xl', () => {
  it('root is capped at max-w-2xl (672px) — not full-width stretched', async () => {
    mockGetMailHealth.mockResolvedValue({
      configured: true,
      lastSuccess: null,
      lastError: null,
      lastErrorMessage: null,
    } as any)
    mockGetMailSettings.mockResolvedValue({
      host: 'smtp.example.com',
      port: 587,
      username: 'sender',
      password: null,
      from: 'noreply@example.com',
      allowedRecipients: '',
      passwordSet: false,
    } as any)

    const host = mountHost(1280, 720)
    hosts.push(host)
    const wrapper = mount(MailSettings, {
      attachTo: host,
      global: { plugins: [createPinia(), makeI18n('en')] },
    })
    mounted.push(wrapper)
    await flushPromises()
    // WO-UI-10: the form fields (incl. "host") only render in edit mode now — the page opens
    // on a read-only health/status view first (data-testid="edit" enters edit mode).
    await wrapper.find('[data-testid="edit"]').trigger('click')
    await until(() => !!wrapper.find('[data-testid="host"]').exists())

    const root = wrapper.find('div.space-y-6').element as HTMLElement
    expect(root.classList.contains('max-w-2xl')).toBe(true)
    const rect = root.getBoundingClientRect()
    // max-w-2xl = 42rem = 672px. On a 1280px viewport the form must be capped.
    expect(rect.width).toBeLessThanOrEqual(672 + 2)
    expect(rect.width).toBeGreaterThan(0)

    await page.screenshot({ path: `${SHOT_DIR}/wo-ui-9-mail-settings.png` })
  })
})