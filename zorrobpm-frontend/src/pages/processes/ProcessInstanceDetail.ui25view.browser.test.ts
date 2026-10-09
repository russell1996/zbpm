/**
 * WO-UI-25 ДОПОЛНЕНИЕ 2026-10-09 (критерий 8) — RED в живом Chromium.
 *
 * Баг владельца: «при обновлении, если я нахожусь в подпроцессе, выкидывает
 * в основной». Два механизма (воспроизвести ОБА до правок, не угадывать):
 *  (а) F5/«Обновить»: вкладка, выбранный элемент и плоскость drill-down живут
 *      только в локальных ref — в адресе их нет, F5 сбрасывает всё на
 *      BPMN-схему корня;
 *  (б) reloadAll (кнопка «Обновить») делает bpmnXml='' → v-if уничтожает
 *      BpmnViewer → viewer пересоздаётся, плоскость/масштаб/позиция слетают
 *      в корень («выкидывает в основной»).
 *
 * F5 симулируется честно: unmount + монтаж заново с ТЕМ ЖЕ URL (после
 * настоящего F5 у компонента тоже остаётся только URL). Настоящий router
 * (memory history) — замоканный useRoute для этого не годится: проверять
 * нечего, query писать некуда.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import { defineComponent, h } from 'vue'
import ProcessInstanceDetail from './ProcessInstanceDetail.vue'
import { publishRealtimeEvent } from '@/services/realtimeBus'
import ru from '@/locales/ru.json'
import en from '@/locales/en.json'
import kz from '@/locales/kz.json'

import '@/style.css'
import '@fontsource/golos-text/400.css'
import '@fontsource/golos-text/500.css'
import '@fontsource/golos-text/600.css'
import '@fontsource/golos-text/700.css'
import 'bpmn-js/dist/assets/bpmn-js.css'
import 'bpmn-js/dist/assets/diagram-js.css'
import 'bpmn-js/dist/assets/bpmn-font/css/bpmn-embedded.css'

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ user: { id: 'u1', username: 'op' }, isSuperAdmin: true }),
}))
vi.mock('@/stores/breadcrumb', () => ({
  useBreadcrumbStore: () => ({ setCrumbLabel: vi.fn() }),
}))
vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn(), info: vi.fn() }),
}))
vi.mock('@/composables/useDateFormat', () => ({
  useDateFormat: () => ({ formatDate: (v: string) => v, formatDateTime: (v: string) => v }),
}))
vi.mock('@/shared/lib/export', () => ({ exportToCsv: vi.fn() }))
vi.mock('@/services/adminService', () => ({
  listMembers: vi.fn().mockResolvedValue([]),
  changeMemberRole: vi.fn(),
  removeMember: vi.fn(),
}))

// vi.mock-фабрики в браузере выполняются изолированно: всё, что они
// используют, обязано лежать в vi.hoisted (иначе «There was an error when
// mocking a module»).
const { COLLAPSED_XML, first100, last50, mockInstanceRow } = vi.hoisted(() => {
  const xml = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" xmlns:di="http://www.omg.org/spec/DD/20100524/DI" id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
  <bpmn:process id="Process_1" isExecutable="true">
    <bpmn:startEvent id="StartEvent_1"><bpmn:outgoing>Flow_1</bpmn:outgoing></bpmn:startEvent>
    <bpmn:subProcess id="SubProcess_1" name="sub"><bpmn:incoming>Flow_1</bpmn:incoming><bpmn:outgoing>Flow_2</bpmn:outgoing>
      <bpmn:startEvent id="Sub_Start"><bpmn:outgoing>Sub_Flow</bpmn:outgoing></bpmn:startEvent>
      <bpmn:task id="Sub_Task" name="inner"><bpmn:incoming>Sub_Flow</bpmn:incoming></bpmn:task>
    </bpmn:subProcess>
    <bpmn:endEvent id="EndEvent_1"><bpmn:incoming>Flow_2</bpmn:incoming></bpmn:endEvent>
    <bpmn:sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="SubProcess_1" />
    <bpmn:sequenceFlow id="Flow_2" sourceRef="SubProcess_1" targetRef="EndEvent_1" />
    <bpmn:sequenceFlow id="Sub_Flow" sourceRef="Sub_Start" targetRef="Sub_Task" />
  </bpmn:process>
  <bpmndi:BPMNDiagram id="BPMNDiagram_1">
    <bpmndi:BPMNPlane id="BPMNPlane_1" bpmnElement="Process_1">
      <bpmndi:BPMNShape id="StartEvent_1_di" bpmnElement="StartEvent_1"><dc:Bounds x="150" y="100" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="SubProcess_1_di" bpmnElement="SubProcess_1" isExpanded="false"><dc:Bounds x="250" y="80" width="100" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="EndEvent_1_di" bpmnElement="EndEvent_1"><dc:Bounds x="400" y="100" width="36" height="36" /></bpmndi:BPMNShape>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
  <bpmndi:BPMNDiagram id="BPMNDiagram_2">
    <bpmndi:BPMNPlane id="SubProcess_1_plane" bpmnElement="SubProcess_1">
      <bpmndi:BPMNShape id="Sub_Start_di" bpmnElement="Sub_Start"><dc:Bounds x="150" y="100" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Sub_Task_di" bpmnElement="Sub_Task"><dc:Bounds x="250" y="80" width="100" height="80" /></bpmndi:BPMNShape>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>`
  const row = (id: string, parentActivityId: string | null) => ({
    id, parentActivityId, processDefinitionId: 'pd-1',
    processName: 'Test', processKey: 'test', processVersion: 1,
    startedAt: '2026-01-01', completedAt: null, cancelled: false,
  })
  const act = (i: number) => {
    // bpmnElementId ОБЯЗАН существовать в диаграмме: BpmnViewer.applyHighlights
    // зовёт canvas.addMarker на каждый id, несуществующий id роняет весь render
    // (V7-находка рядом со scope — см. отчёт, здесь не чиним).
    const ids = ['StartEvent_1', 'SubProcess_1', 'Sub_Task', 'EndEvent_1']
    return {
      id: `act-${i}`, bpmnElementId: ids[i % ids.length], type: 'userTask',
      status: 'COMPLETED', createdAt: '2026-10-09', completedAt: '2026-10-09',
    }
  }
  const f100 = Array.from({ length: 100 }, (_, i) => act(i))
  const l50 = Array.from({ length: 50 }, (_, i) => act(100 + i))
  return { COLLAPSED_XML: xml, first100: f100, last50: l50, mockInstanceRow: row }
})

vi.mock('@/services/instanceService', () => ({
  getProcessInstance: vi.fn(async (id: string) => mockInstanceRow(id, id === 'sub-1' ? 'SubProcess_1' : null)),
  getProcessInstanceActivities: vi.fn().mockResolvedValue([]),
  getProcessInstanceActivitiesPaged: vi.fn(async (_id: string, pageIndex: number) =>
    Promise.resolve(pageIndex === 0
      ? { data: first100, totalElements: 150, pageIndex: 0, pageSize: 100 }
      : { data: last50, totalElements: 150, pageIndex: 1, pageSize: 100 }),
  ),
  getProcessInstances: vi.fn(async (q: Record<string, unknown>) =>
    Promise.resolve(q?.parentProcessInstanceId === 'pi-1'
      ? { data: [mockInstanceRow('sub-1', 'SubProcess_1')], totalElements: 1, pageIndex: 0, pageSize: 100 }
      : { data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  ),
  startProcessInstance: vi.fn(),
  cancelProcessInstance: vi.fn(),
}))
vi.mock('@/services/variableService', () => ({
  getVariables: vi.fn().mockResolvedValue({ data: [] }),
}))
vi.mock('@/services/taskService', () => ({
  getUserTasks: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  getUserTask: vi.fn().mockResolvedValue(null),
  completeUserTask: vi.fn().mockResolvedValue(undefined),
  getServiceTasks: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  getServiceTask: vi.fn().mockResolvedValue(null),
  completeServiceTask: vi.fn().mockResolvedValue(undefined),
}))
vi.mock('@/services/incidentService', () => ({
  getIncidents: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  getIncident: vi.fn().mockResolvedValue(null),
  resolveIncident: vi.fn().mockResolvedValue(undefined),
}))
vi.mock('@/services/processService', () => ({
  getProcessDefinitionXml: vi.fn().mockResolvedValue(COLLAPSED_XML),
  getProcessDefinitionStructure: vi.fn().mockResolvedValue({
    id: 'pd-1', key: 'test', version: 1, name: 'Test', documentation: null,
    nodes: [
      { id: 'StartEvent_1', type: 'startEvent', name: null, properties: {}, boundaryEvents: [] },
      { id: 'SubProcess_1', type: 'subProcess', name: 'sub', properties: {}, boundaryEvents: [] },
      { id: 'EndEvent_1', type: 'endEvent', name: null, properties: {}, boundaryEvents: [] },
    ],
    flows: [],
  }),
  getProcessDefinition: vi.fn().mockResolvedValue(null),
  getProcessDefinitionVersions: vi.fn().mockResolvedValue([]),
  getProcessDefinitions: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
}))
vi.mock('@/services/formService', () => ({
  getTaskForm: vi.fn().mockResolvedValue(null),
  getStartForm: vi.fn().mockResolvedValue(null),
}))

function makeI18n() {
  return createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { ru, en, kz } })
}

async function makeRouter(initialUrl: string): Promise<Router> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      // Именованные маршруты как в проде (шапка страницы ссылается на
      // 'process-instances' через RouterLink — без него useLink бросает).
      { path: '/processes/instances', name: 'process-instances', component: defineComponent({ setup: () => () => h('div', 'list') }) },
      { path: '/processes/instances/:id', name: 'process-instance-detail', component: ProcessInstanceDetail },
    ],
  })
  await router.push(initialUrl)
  await router.isReady()
  return router
}

let mounted: VueWrapper[] = []
let hosts: HTMLElement[] = []

function mountHost(): HTMLElement {
  const host = document.createElement('div')
  host.style.position = 'absolute'
  host.style.top = '0'
  host.style.left = '0'
  host.style.display = 'flex'
  host.style.width = '1280px'
  host.style.height = '720px'
  document.body.appendChild(host)
  hosts.push(host)
  return host
}

function until(cond: () => boolean, timeout = 15000): Promise<void> {
  return new Promise((resolve, reject) => {
    const start = Date.now()
    const tick = () => {
      try {
        if (cond()) return resolve()
      } catch {
        // DOM ещё не готов — ждём дальше
      }
      if (Date.now() - start > timeout) return reject(new Error('timeout waiting for condition'))
      requestAnimationFrame(tick)
    }
    tick()
  })
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
})

afterEach(() => {
  for (const w of mounted) {
    try { w.unmount() } catch { /* уже размонтирован */ }
  }
  mounted = []
  for (const h of hosts) h.remove()
  hosts = []
  document.body.innerHTML = ''
})

async function mountDetail(router: Router): Promise<VueWrapper> {
  const wrapper = mount(ProcessInstanceDetail, {
    attachTo: mountHost(),
    global: { stubs: { teleport: true }, plugins: [createPinia(), makeI18n(), router] },
  })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

async function mountAt(url: string): Promise<{ wrapper: VueWrapper; router: Router }> {
  setActivePinia(createPinia())
  const router = await makeRouter(url)
  const wrapper = await mountDetail(router)
  return { wrapper, router }
}

function tabButton(wrapper: VueWrapper, label: string) {
  const btn = wrapper.findAll('[role="tab"]').find((b) => b.text().includes(label))
  if (!btn) throw new Error(`tab "${label}" not found`)
  return btn
}

async function openHistory(wrapper: VueWrapper) {
  await tabButton(wrapper, 'History').trigger('click')
  await flushPromises()
  await until(() => wrapper.text().includes('100 / 150'))
}

function activeTabLabel(wrapper: VueWrapper): string {
  const active = wrapper.find('[role="tab"][aria-selected="true"]')
  return active.exists() ? active.text() : ''
}

function svgRoot(wrapper: VueWrapper): SVGSVGElement {
  const svg = wrapper.element.querySelector('.bpmn-container svg')
  if (!svg) throw new Error('bpmn svg not rendered yet')
  return svg as unknown as SVGSVGElement
}

function svgReplacements(wrapper: VueWrapper): { observe: () => void; count: () => number } {
  let n = 0
  let observer: MutationObserver | null = null
  return {
    observe() {
      const box = wrapper.find('.bpmn-container').element
      observer = new MutationObserver((mutations) => {
        for (const m of mutations) {
          for (const node of Array.from(m.addedNodes)) {
            if (node instanceof SVGElement || (node instanceof Element && node.querySelector('svg'))) n += 1
          }
        }
      })
      observer.observe(box, { childList: true, subtree: true })
    },
    count: () => n,
  }
}

async function drillIntoSubprocess(wrapper: VueWrapper) {
  await until(() => !!wrapper.element.querySelector('.bjs-drilldown'))
  ;(wrapper.element.querySelector('.bjs-drilldown') as HTMLElement).click()
  await until(() => !!wrapper.element.querySelector('.bjs-breadcrumbs-shown'))
}

describe('WO-UI-25 criterion 8 (browser RED): view state must live in the URL', () => {
  it('RED-A1 (механизм а): переключение вкладки пишется в адрес (?tab=)', async () => {
    const router = await makeRouter('/processes/instances/pi-1')
    const wrapper = await mountDetail(router)
    await until(() => wrapper.text().includes('Test'))
    await openHistory(wrapper)
    // Состояние «где я» обязано жить в адресе — иначе F5 его убивает.
    expect(router.currentRoute.value.query.tab).toBe('history')
  })

  it('RED-A2 (механизм а): F5 не выкидывает из вида подпроцесса', async () => {
    const first = await mountAt('/processes/instances/pi-1')
    await until(() => first.wrapper.text().includes('Test'))
    await until(() => first.wrapper.find('.bpmn-container svg').exists())
    // Вид подпроцесса: drill-down на bpmn-плоскости, затем вкладка истории.
    await drillIntoSubprocess(first.wrapper)
    await flushPromises()
    await tabButton(first.wrapper, 'History').trigger('click')
    await flushPromises()
    await until(() => first.wrapper.text().includes('100 / 150'))
    const url = first.router.currentRoute.value.fullPath
    // F5: процесс-локальное умерло, выжил только адрес.
    first.wrapper.unmount()
    mounted = []
    const second = await mountAt(url)
    await until(() => second.wrapper.text().includes('Test'))
    // Та же вкладка — история на месте, а не корень.
    expect(activeTabLabel(second.wrapper), 'F5 must keep the history tab').toContain('History')
    expect(second.wrapper.text()).toContain('100 / 150')
    // Возврат на bpmn — плоскость восстановлена из адреса, а не корень.
    await tabButton(second.wrapper, 'BPMN').trigger('click')
    await flushPromises()
    await until(
      () => !!second.wrapper.find('.bpmn-container').element.querySelector('.bjs-breadcrumbs-shown'),
      15000,
    )
    expect(
      second.wrapper.find('.bpmn-container').element.querySelector('.bjs-breadcrumbs-shown'),
      'F5 must restore the drill-down plane',
    ).not.toBeNull()
  })

  it('RED-B (механизм б): «Обновить» не пересоздаёт viewer и не сбрасывает плоскость', async () => {
    const { wrapper } = await mountAt('/processes/instances/pi-1')
    await until(() => wrapper.text().includes('Test'))
    await until(() => wrapper.find('.bpmn-container svg').exists())
    await drillIntoSubprocess(wrapper)
    await flushPromises()
    const renders = svgReplacements(wrapper)
    renders.observe()

    const refreshBtn = wrapper.findAll('button').find((b: { text: () => string }) => b.text().trim() === 'Refresh')
    expect(refreshBtn, 'refresh button must exist').toBeTruthy()
    await refreshBtn!.trigger('click')
    await flushPromises()
    await until(() => wrapper.find('.bpmn-container svg').exists())

    // Механизм (б): ни одного нового svg за окно «Обновить» — viewer живёт,
    // плоскость цела («выкидывает в основной» закрыто).
    expect(renders.count(), 'refresh must not recreate the viewer').toBe(0)
    expect(
      wrapper.find('.bpmn-container').element.querySelector('.bjs-breadcrumbs-shown'),
      'refresh must keep the drill-down plane',
    ).not.toBeNull()
  })

  it('RED-B2 (механизм б): 20 SSE-событий не сбрасывают вид', async () => {
    const { wrapper } = await mountAt('/processes/instances/pi-1')
    await until(() => wrapper.text().includes('Test'))
    await until(() => wrapper.find('.bpmn-container svg').exists())
    await drillIntoSubprocess(wrapper)
    await flushPromises()
    const renders = svgReplacements(wrapper)
    renders.observe()

    for (let i = 1; i <= 20; i++) {
      publishRealtimeEvent({
        sequence: i, id: `e-${i}`, type: 'activity.completed', version: 1,
        occurredAt: '2026-10-09T00:00:00Z', processInstanceId: 'pi-1', data: {},
      })
    }
    await flushPromises()

    expect(renders.count(), 'SSE must not recreate the viewer').toBe(0)
    expect(
      wrapper.find('.bpmn-container').element.querySelector('.bjs-breadcrumbs-shown'),
      'SSE must keep the drill-down plane',
    ).not.toBeNull()
  })

  it('RED-C: выбор элемента и плоскость живут в адресе (?element=, ?plane=)', async () => {
    const { wrapper, router } = await mountAt('/processes/instances/pi-1')
    await until(() => wrapper.text().includes('Test'))
    await until(() => wrapper.find('.bpmn-container svg').exists())
    await drillIntoSubprocess(wrapper)
    // Клик по фигуре внутри плоскости подпроцесса выбирает элемент.
    await until(() => !!wrapper.element.querySelector('[data-element-id="Sub_Task"]'))
    ;(wrapper.element.querySelector('[data-element-id="Sub_Task"]') as SVGElement).dispatchEvent(
      new MouseEvent('click', { bubbles: true }),
    )
    await flushPromises()

    expect(router.currentRoute.value.query.plane).toBe('SubProcess_1')
    expect(router.currentRoute.value.query.element).toBe('Sub_Task')
  })

  it('RED-D: deep link открывает тот же вид (вкладка + плоскость + элемент)', async () => {
    const { wrapper } = await mountAt(
      '/processes/instances/pi-1?tab=history&plane=SubProcess_1&element=Sub_Task',
    )
    await until(() => wrapper.text().includes('Test'))

    // Вкладка из адреса активна сразу.
    expect(activeTabLabel(wrapper), 'deep link must activate the tab').toContain('History')
    expect(wrapper.text()).toContain('100 / 150')
    // Переход на bpmn — плоскость уже восстановлена из адреса.
    await tabButton(wrapper, 'BPMN').trigger('click')
    await flushPromises()
    await until(
      () => !!wrapper.find('.bpmn-container').element.querySelector('.bjs-breadcrumbs-shown'),
      15000,
    )
    expect(
      wrapper.find('.bpmn-container').element.querySelector('.bjs-breadcrumbs-shown'),
      'deep link must restore the drill-down plane',
    ).not.toBeNull()
  })

  it('RED-E: переход в подпроцесс — навигация, back возвращает вид родителя', async () => {
    const { wrapper, router } = await mountAt('/processes/instances/pi-1?tab=subprocesses')
    await until(() => wrapper.text().includes('Test'))
    // Вид из адреса: сразу вкладка подпроцессов, а не корень.
    expect(activeTabLabel(wrapper), 'address must activate the tab').toContain('Subprocesses')
    await until(() => wrapper.text().includes('sub-1'))
    const subRow = wrapper.findAll('tbody tr')[0]
    await subRow.trigger('click')
    await flushPromises()
    await until(() => router.currentRoute.value.params.id === 'sub-1')
    expect(router.currentRoute.value.fullPath).toContain('/processes/instances/sub-1')

    // Back — возвращаемся на родителя с его видом (история query в адресе).
    await router.back()
    await flushPromises()
    await until(() => router.currentRoute.value.params.id === 'pi-1')
    expect(router.currentRoute.value.query.tab, 'back must restore the tab in the address').toBe('subprocesses')
    await until(() => wrapper.text().includes('Test'))
    expect(activeTabLabel(wrapper), 'back must restore the tab in the view').toContain('Subprocesses')
  })
})
