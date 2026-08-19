/**
 * WO-TEST-6 — browser-geometry checks.
 *
 * jsdom cannot compute layout (`getBoundingClientRect()` is always 0, P-53), so
 * the six visual criteria that bit us on the live stand are asserted HERE, in a
 * real headless Chromium (vitest browser mode + Playwright provider). Every
 * check asserts geometry — rectangles, computed styles, scroll sizes — not
 * screenshots, and none of them duplicates an existing jsdom test (the jsdom
 * suite stays GREEN under every POF mutation below; see the report table).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import ProcessInstanceDetail from '@/pages/processes/ProcessInstanceDetail.vue'
import SidebarNav from '@/widgets/shared/SidebarNav.vue'
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

// ---- file-level mocks. vue-i18n is deliberately NOT mocked: check 4 needs the
// real Kazakh strings (a t()-stub can never wrap, so any layout would pass). ----

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: 'pi-1' }, path: '/' }),
  useRouter: () => ({ push: vi.fn() }),
}))

const mockAuth = vi.hoisted(() => ({ isSuperAdmin: true }))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ isSuperAdmin: mockAuth.isSuperAdmin }),
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
function mountHost(w: number, h: number): HTMLElement {
  const host = document.createElement('div')
  host.style.position = 'absolute'
  host.style.top = '0'
  host.style.left = '0'
  host.style.display = 'flex'
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
})

afterEach(() => {
  for (const w of mounted) w.unmount()
  mounted = []
  for (const h of hosts) h.remove()
  hosts = []
  document.body.innerHTML = ''
})

function mountPid() {
  const host = mountHost(1200, 700)
  hosts.push(host)
  const wrapper = mount(ProcessInstanceDetail, {
    attachTo: host,
    global: { stubs: { teleport: true }, plugins: [createPinia(), makeI18n('en')] },
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

// ---- WO-TEST-6 check 1: the active tab underline is really visible ----

describe('WO-TEST-6 check 1 — active tab underline (ProcessInstanceDetail)', () => {
  it('the active tab renders a 2px underline that reaches the strip border line', async () => {
    const wrapper = mountPid()
    await flushPromises()
    await until(() => !!wrapper.element.querySelector('.bpmn-viewer-wrapper'))

    const tabBtns = wrapper.findAll('button').filter((b) => b.classes().includes('-mb-px'))
    expect(tabBtns.length).toBeGreaterThanOrEqual(7)
    const strip = tabBtns[0].element.parentElement!
    const active = tabBtns.find((b) => b.classes().includes('border-primary'))!
    const cs = getComputedStyle(active.element)
    // the underline is a real 2px border, not a class with width 0
    expect(parseFloat(cs.borderBottomWidth)).toBeGreaterThanOrEqual(2)
    expect(cs.borderBottomColor).not.toBe('rgba(0, 0, 0, 0)')
    // and it reaches the strip's bottom border line (the -mb-px overlap),
    // instead of hovering 1px above it or sliding under the container border
    const btnRect = active.element.getBoundingClientRect()
    const stripRect = strip.getBoundingClientRect()
    expect(btnRect.bottom).toBeGreaterThanOrEqual(stripRect.bottom - 0.5)
    expect(btnRect.bottom).toBeLessThanOrEqual(stripRect.bottom + 0.5)
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
      const key = Object.keys(kz).find((k) => (kz as Record<string, string>)[k] === s.text())
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