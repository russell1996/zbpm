/**
 * WO-UI-30 часть B — «Перейти к подпроцессу» с диаграммы (живой Chromium).
 *
 * Механика: панель выбранного элемента (ProcessInstanceDetail) показывает
 * для call activity кнопку перехода к дочернему инстансу (1 → прямая кнопка,
 * N → список со статусами, 0 → disabled с подсказкой), для embedded
 * subProcess — кнопку drill-down в плоскость (?plane=, механизм UI-25) и
 * шапку плоскости с «назад к родителю»; элементы с доступным переходом
 * получают ⤵-маркер (оверлей diagram-js, pointer-events:none).
 * Дочерние инстансы маппятся через parentActivityId → id активностей
 * элемента (CallActivityHandler:127 передаёт activityId родителя).
 *
 * Мутации: убрать кнопку перехода → красный (кнопки нет); убрать маркеры →
 * красный (0 оверлеев); маппить детей по processInstanceId вместо
 * parentActivityId → красный (кнопка disabled вместо перехода).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import { defineComponent, h } from 'vue'
import ProcessInstanceDetail from './ProcessInstanceDetail.vue'
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

// Переключаемая фикстура: дочерние инстансы задаёт каждый тест ДО монтирования
// (стор запрашивает их при загрузке страницы).
const { NAV_XML, cfg, mockInstanceRow } = vi.hoisted(() => {
  const xml = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" xmlns:di="http://www.omg.org/spec/DD/20100524/DI" id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
  <bpmn:process id="Process_1" isExecutable="true">
    <bpmn:startEvent id="StartEvent_1"><bpmn:outgoing>Flow_1</bpmn:outgoing></bpmn:startEvent>
    <bpmn:callActivity id="Call_1" name="child"><bpmn:incoming>Flow_1</bpmn:incoming><bpmn:outgoing>Flow_2</bpmn:outgoing></bpmn:callActivity>
    <bpmn:subProcess id="SubProcess_1" name="sub"><bpmn:incoming>Flow_2</bpmn:incoming><bpmn:outgoing>Flow_3</bpmn:outgoing>
      <bpmn:startEvent id="Sub_Start"><bpmn:outgoing>Sub_Flow</bpmn:outgoing></bpmn:startEvent>
      <bpmn:task id="Sub_Task" name="inner"><bpmn:incoming>Sub_Flow</bpmn:incoming></bpmn:task>
    </bpmn:subProcess>
    <bpmn:endEvent id="EndEvent_1"><bpmn:incoming>Flow_3</bpmn:incoming></bpmn:endEvent>
    <bpmn:sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="Call_1" />
    <bpmn:sequenceFlow id="Flow_2" sourceRef="Call_1" targetRef="SubProcess_1" />
    <bpmn:sequenceFlow id="Flow_3" sourceRef="SubProcess_1" targetRef="EndEvent_1" />
    <bpmn:sequenceFlow id="Sub_Flow" sourceRef="Sub_Start" targetRef="Sub_Task" />
  </bpmn:process>
  <bpmndi:BPMNDiagram id="BPMNDiagram_1">
    <bpmndi:BPMNPlane id="BPMNPlane_1" bpmnElement="Process_1">
      <bpmndi:BPMNShape id="StartEvent_1_di" bpmnElement="StartEvent_1"><dc:Bounds x="150" y="100" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Call_1_di" bpmnElement="Call_1"><dc:Bounds x="250" y="80" width="100" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="SubProcess_1_di" bpmnElement="SubProcess_1" isExpanded="false"><dc:Bounds x="400" y="80" width="100" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="EndEvent_1_di" bpmnElement="EndEvent_1"><dc:Bounds x="550" y="100" width="36" height="36" /></bpmndi:BPMNShape>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
  <bpmndi:BPMNDiagram id="BPMNDiagram_2">
    <bpmndi:BPMNPlane id="SubProcess_1_plane" bpmnElement="SubProcess_1">
      <bpmndi:BPMNShape id="Sub_Start_di" bpmnElement="Sub_Start"><dc:Bounds x="150" y="100" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Sub_Task_di" bpmnElement="Sub_Task"><dc:Bounds x="250" y="80" width="100" height="80" /></bpmndi:BPMNShape>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>`
  const row = (id: string, parentActivityId: string | null, processName = 'Child') => ({
    id, parentActivityId, processDefinitionId: 'pd-1',
    processName, processKey: 'child', processVersion: 1,
    startedAt: '2026-01-01', completedAt: null, cancelled: false,
  })
  return { NAV_XML: xml, cfg: { childRows: [] as ReturnType<typeof row>[] }, mockInstanceRow: row }
})

vi.mock('@/services/instanceService', () => ({
  getProcessInstance: vi.fn(async (id: string) => mockInstanceRow(id, null, 'Test')),
  getProcessInstanceActivities: vi.fn().mockResolvedValue([]),
  getProcessInstanceActivitiesPaged: vi.fn(async () => ({
    data: [{ id: 'act-call-1', processInstanceId: 'pi-1', bpmnElementId: 'Call_1', type: 'callActivity', status: 'IN_PROGRESS', createdAt: '2026-10-09', completedAt: null }],
    totalElements: 1, pageIndex: 0, pageSize: 100,
  })),
  getProcessInstances: vi.fn(async (q: Record<string, unknown>) =>
    Promise.resolve(q?.parentProcessInstanceId === 'pi-1'
      ? { data: cfg.childRows, totalElements: cfg.childRows.length, pageIndex: 0, pageSize: 100 }
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
  getProcessDefinitionXml: vi.fn().mockResolvedValue(NAV_XML),
  getProcessDefinitionStructure: vi.fn().mockResolvedValue({
    id: 'pd-1', key: 'test', version: 1, name: 'Test', documentation: null,
    nodes: [
      { id: 'StartEvent_1', type: 'startEvent', name: null, eventDefinition: null, documentation: null, incoming: [], outgoing: ['Flow_1'], properties: {}, boundaryEvents: [], children: null },
      { id: 'Call_1', type: 'callActivity', name: 'child', eventDefinition: null, documentation: null, incoming: ['Flow_1'], outgoing: ['Flow_2'], properties: { calledProcessId: 'child' }, boundaryEvents: [], children: null },
      { id: 'SubProcess_1', type: 'subProcess', name: 'sub', eventDefinition: null, documentation: null, incoming: ['Flow_2'], outgoing: ['Flow_3'], properties: {}, boundaryEvents: [], children: { nodes: [{ id: 'Sub_Task', type: 'task', name: 'inner', eventDefinition: null, documentation: null, incoming: ['Sub_Flow'], outgoing: [], properties: {}, boundaryEvents: [], children: null }], flows: [] } },
      { id: 'EndEvent_1', type: 'endEvent', name: null, eventDefinition: null, documentation: null, incoming: ['Flow_3'], outgoing: [], properties: {}, boundaryEvents: [], children: null },
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
  cfg.childRows = []
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

/** Клик по фигуре диаграммы — как живой пользователь (не прямой вызов метода). */
async function clickDiagramElement(wrapper: VueWrapper, elementId: string): Promise<void> {
  await until(() => !!wrapper.element.querySelector(`[data-element-id="${elementId}"]`))
  ;(wrapper.element.querySelector(`[data-element-id="${elementId}"]`) as SVGElement).dispatchEvent(
    new MouseEvent('click', { bubbles: true }),
  )
  await flushPromises()
}

function navMarkers(): HTMLElement[] {
  return [...document.querySelectorAll('.bpmn-subprocess-nav')] as HTMLElement[]
}

describe('WO-UI-30 part B: go to subprocess from the diagram (browser)', () => {
  it('call activity с одним дочерним: кнопка ведёт в дочерний инстанс, адрес чистый', async () => {
    cfg.childRows = [mockInstanceRow('sub-1', 'act-call-1')]
    const { wrapper, router } = await mountAt('/processes/instances/pi-1')
    await until(() => wrapper.text().includes('Test'))
    await clickDiagramElement(wrapper, 'Call_1')

    const btn = wrapper.find('[data-testid="goto-subprocess"]')
    expect(btn.exists(), 'go-to-subprocess button must render').toBe(true)
    expect(btn.attributes('aria-label')).toBe('Go to subprocess')
    await btn.trigger('click')
    await flushPromises()
    await until(() => router.currentRoute.value.params.id === 'sub-1')
    expect(router.currentRoute.value.fullPath).toContain('/processes/instances/sub-1')
    // Навигация — чистый адрес дочернего (вид родителя не протекает).
    expect(router.currentRoute.value.query.plane).toBeUndefined()
  })

  it('call activity с двумя дочерними: список со статусами, переход в каждый', async () => {
    cfg.childRows = [mockInstanceRow('sub-1', 'act-call-1'), mockInstanceRow('sub-2', 'act-call-1')]
    const { wrapper, router } = await mountAt('/processes/instances/pi-1')
    await until(() => wrapper.text().includes('Test'))
    await clickDiagramElement(wrapper, 'Call_1')

    const items = wrapper.findAll('[data-testid="goto-subprocess-item"]')
    expect(items.length, 'two child instances → two buttons').toBe(2)
    // Статусы видны (та же StatusBadge-семантика, что во вкладке «Подпроцессы»).
    expect(items[0].text()).toContain('Running')
    await items[1].trigger('click')
    await flushPromises()
    await until(() => router.currentRoute.value.params.id === 'sub-2')
  })

  it('call activity без дочернего: кнопка неактивна с подсказкой, маркера на нём нет', async () => {
    cfg.childRows = []
    const { wrapper } = await mountAt('/processes/instances/pi-1')
    await until(() => wrapper.text().includes('Test'))
    await clickDiagramElement(wrapper, 'Call_1')

    const btn = wrapper.find('[data-testid="goto-subprocess-disabled"]')
    expect(btn.exists(), 'disabled button with hint must render').toBe(true)
    expect((btn.element as HTMLButtonElement).disabled).toBe(true)
    expect(btn.attributes('title')).toBe('Child instance not created yet')
    // Маркер — только на SubProcess_1 (у Call_1 перехода пока нет).
    await until(() => navMarkers().length === 1)
  })

  it('embedded subProcess: открыть плоскость, шапка «где я», назад, F5 держит', async () => {
    cfg.childRows = [mockInstanceRow('sub-1', 'act-call-1')]
    const first = await mountAt('/processes/instances/pi-1')
    await until(() => first.wrapper.text().includes('Test'))
    await clickDiagramElement(first.wrapper, 'SubProcess_1')

    const open = first.wrapper.find('[data-testid="open-subprocess-plane"]')
    expect(open.exists(), 'open-plane button must render').toBe(true)
    await open.trigger('click')
    await flushPromises()
    expect(first.router.currentRoute.value.query.plane).toBe('SubProcess_1')
    // Шапка плоскости с кнопкой «назад».
    await until(() => first.wrapper.find('[data-testid="subprocess-plane-bar"]').exists())
    const back = first.wrapper.find('[data-testid="back-to-parent"]')
    expect(back.exists()).toBe(true)
    await back.trigger('click')
    await flushPromises()
    expect(first.router.currentRoute.value.query.plane).toBeUndefined()
    expect(first.wrapper.find('[data-testid="subprocess-plane-bar"]').exists()).toBe(false)

    // F5: вид плоскости переживает перезагрузку через адрес.
    await first.wrapper.find('[data-testid="open-subprocess-plane"]').trigger('click')
    await flushPromises()
    const url = first.router.currentRoute.value.fullPath
    first.wrapper.unmount()
    mounted = []
    const second = await mountAt(url)
    await until(() => second.wrapper.text().includes('Test'))
    expect(second.wrapper.find('[data-testid="subprocess-plane-bar"]').exists()).toBe(true)
  })

  it('маркер ⤵: два оверлея, вне раскладки, клики проходят в canvas', async () => {
    cfg.childRows = [mockInstanceRow('sub-1', 'act-call-1')]
    const { wrapper } = await mountAt('/processes/instances/pi-1')
    await until(() => wrapper.text().includes('Test'))
    await until(() => navMarkers().length === 2)

    // Чистый индикатор: aria-hidden + pointer-events:none (клики — в canvas).
    for (const m of navMarkers()) {
      expect(m.getAttribute('aria-hidden')).toBe('true')
      expect(getComputedStyle(m).pointerEvents).toBe('none')
    }
    // Оверлей-слой diagram-js абсолютно позиционирован — SVG-раскладка не едет.
    const layer = document.querySelector('.djs-overlays') as HTMLElement | null
    expect(layer, 'overlays layer must exist').not.toBeNull()
    expect(getComputedStyle(layer!).position).toBe('absolute')
    // Клик по фигуре ПОД маркером выбирает элемент (перехватчика нет).
    await clickDiagramElement(wrapper, 'Call_1')
    expect(wrapper.find('[data-testid="subprocess-nav"]').exists()).toBe(true)
  })

  it('обычный элемент без подпроцесса: секции перехода нет', async () => {
    cfg.childRows = [mockInstanceRow('sub-1', 'act-call-1')]
    const { wrapper } = await mountAt('/processes/instances/pi-1')
    await until(() => wrapper.text().includes('Test'))
    await clickDiagramElement(wrapper, 'StartEvent_1')
    await flushPromises()
    expect(wrapper.find('[data-testid="subprocess-nav"]').exists()).toBe(false)
  })
})
