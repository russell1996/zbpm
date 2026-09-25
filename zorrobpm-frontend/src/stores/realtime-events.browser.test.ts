/**
 * WO-UI-18 — browser-доказательства (реальный Chromium, `npm run test:browser`).
 *
 * jsdom здесь не годится дважды: `getBoundingClientRect()` всегда 0 (P-53 —
 * «список обновился» по классу/тексту без геометрии ничего не доказывает) и
 * EventSource/SSE-поведение jsdom не эмулирует (см. WO-файл, критерий 2).
 *
 * A (критерий 2): страница TaskList + живой useRealtimeEvents (как в MainLayout):
 *    именованное SSE-событие `user-task.created` → список в DOM обновляется
 *    без ручного действия, новая строка имеет реальную высоту.
 * C (критерий 7): реальная ProcessInstanceDetail, таб History: первая страница
 *    activities рендерится, кнопка «Show more (100 / 150)» видима, клик
 *    реально догружает вторую страницу — строки прирастают в DOM.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { defineComponent, h, onMounted } from 'vue'
import TaskList from '@/pages/tasks/TaskList.vue'
import ProcessInstanceDetail from '@/pages/processes/ProcessInstanceDetail.vue'
import { useRealtimeEvents } from '@/composables/useRealtimeEvents'
import ru from '@/locales/ru.json'
import en from '@/locales/en.json'
import kz from '@/locales/kz.json'

import '@/style.css'
import '@fontsource/golos-text/400.css'
import '@fontsource/golos-text/500.css'
import '@fontsource/golos-text/600.css'
import '@fontsource/golos-text/700.css'

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: 'pi-1' }, path: '/' }),
  useRouter: () => ({ push: vi.fn() }),
}))

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

const mockGetUserTasks = vi.hoisted(() => vi.fn())
vi.mock('@/services/taskService', () => ({
  getUserTasks: mockGetUserTasks,
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
const mockGetPaged = vi.hoisted(() => vi.fn())
vi.mock('@/services/instanceService', () => ({
  getProcessInstance: vi.fn().mockResolvedValue({ id: 'pi-1', parentActivityId: null, processDefinitionId: 'pd-1', startedAt: '2026-01-01', completedAt: null, processName: 'Test', processKey: 'test', processVersion: 1 }),
  getProcessInstanceActivities: vi.fn().mockResolvedValue([]),
  getProcessInstanceActivitiesPaged: mockGetPaged,
  getProcessInstances: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  startProcessInstance: vi.fn(),
  // WO-UI-21 Раунд 2: новый экспорт сервиса (browser = нативный ESM, без него — import error).
  cancelProcessInstance: vi.fn(),
}))
vi.mock('@/services/variableService', () => ({
  getVariables: vi.fn().mockResolvedValue({ data: [] }),
}))
vi.mock('@/services/processService', () => ({
  getProcessDefinitionXml: vi.fn().mockResolvedValue('<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"><bpmn:process id="P" /></bpmn:definitions>'),
  getProcessDefinitionStructure: vi.fn().mockResolvedValue({ id: 'pd-1', key: 'test', version: 1, name: 'Test', documentation: null, nodes: [], flows: [] }),
  getProcessDefinition: vi.fn().mockResolvedValue(null),
  getProcessDefinitionVersions: vi.fn().mockResolvedValue([]),
  getProcessDefinitions: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
}))
vi.mock('@/services/formService', () => ({
  getTaskForm: vi.fn().mockResolvedValue(null),
  getStartForm: vi.fn().mockResolvedValue(null),
}))

// Управляемый EventSource: перехватывает подписки на именованные типы и
// позволяет выстрелить событием как это делает сервер (SseEmitter .name(type)
// + .id(sequence) + JSON envelope в data).
type Listener = (e: Event) => void
class FakeEventSource {
  static instances: FakeEventSource[] = []
  listeners = new Map<string, Listener[]>()
  onopen: ((e: Event) => void) | null = null
  onerror: ((e: Event) => void) | null = null
  constructor(
    public url: string,
    public opts?: { withCredentials?: boolean },
  ) {
    FakeEventSource.instances.push(this)
  }
  addEventListener(type: string, fn: Listener) {
    const arr = this.listeners.get(type) || []
    arr.push(fn)
    this.listeners.set(type, arr)
  }
  emit(type: string, data: unknown, lastEventId: string) {
    const evt = { data: JSON.stringify(data), lastEventId } as MessageEvent
    for (const fn of this.listeners.get(type) || []) fn(evt as unknown as Event)
  }
  close() {}
}

function makeI18n() {
  return createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { ru, en, kz } })
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

function taskRow(id: string) {
  return { id, code: `code-${id}`, name: `Task ${id}`, status: 'CREATED', createdAt: '2026-09-24', completedAt: null, processInstanceId: 'pi-1' }
}

function activityRow(i: number) {
  return { id: `act-${i}`, bpmnElementId: `Task_${i}`, type: 'userTask', status: 'COMPLETED', createdAt: '2026-09-24', completedAt: '2026-09-24' }
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  FakeEventSource.instances = []
  vi.stubGlobal('EventSource', FakeEventSource)
  mockGetPaged.mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 })
})

afterEach(() => {
  vi.unstubAllGlobals()
  for (const w of mounted) w.unmount()
  mounted = []
  for (const h of hosts) h.remove()
  hosts = []
  document.body.innerHTML = ''
})

function taskRowsText(wrapper: VueWrapper) {
  return wrapper.findAll('tbody tr').map((r) => r.text())
}

describe('WO-UI-18 realtime in a real browser', () => {
  it('criterion 2: SSE user-task.created refreshes the TaskList without user action', async () => {
    mockGetUserTasks.mockResolvedValue({ data: [taskRow('t1')], totalElements: 1, pageIndex: 0, pageSize: 10 })
    // Хост как MainLayout: realtime-подписка живёт над страницей и переживает навигацию.
    const host = defineComponent({
      setup() {
        const rt = useRealtimeEvents()
        onMounted(() => rt.connect())
        return () => h(TaskList)
      },
    })
    const wrapper = mount(host, {
      attachTo: mountHost(),
      global: { stubs: { teleport: true }, plugins: [createPinia(), makeI18n()] },
    })
    mounted.push(wrapper)
    await flushPromises()
    await until(() => taskRowsText(wrapper).some((t) => t.includes('Task t1')))
    expect(FakeEventSource.instances).toHaveLength(1)

    // Сервер публикует user-task.created; стор сам перезапрашивает список —
    // пользователь ничего не нажимал.
    mockGetUserTasks.mockResolvedValue({
      data: [taskRow('t1'), taskRow('t2')], totalElements: 2, pageIndex: 0, pageSize: 10,
    })
    FakeEventSource.instances[0].emit(
      'user-task.created',
      { sequence: 77, id: 'e-77', type: 'user-task.created', version: 1, occurredAt: '2026-09-24T00:00:00Z', data: {} },
      '77',
    )
    await flushPromises()
    await until(() => taskRowsText(wrapper).some((t) => t.includes('Task t2')))

    // Реальная геометрия: новая строка действительно отрисована (в jsdom высота 0 всегда).
    const rows = wrapper.findAll('tbody tr')
    expect(rows.length).toBe(2)
    for (const row of rows) {
      expect(row.element.getBoundingClientRect().height).toBeGreaterThan(0)
    }
    wrapper.unmount()
    mounted = []
  })
})

describe('WO-UI-18 activities pagination in a real browser', () => {
  it('criterion 7: history tab pages through activities with a visible Show more', async () => {
    const first100 = Array.from({ length: 100 }, (_, i) => activityRow(i))
    const last50 = Array.from({ length: 50 }, (_, i) => activityRow(100 + i))
    // Stateful: страница определяется аргументом pageIndex, а не порядком
    // вызовов — переход на таб History перезапрашивает первую страницу
    // (onTabChange → loadTabData), это не должен быть «второй» ответ.
    mockGetPaged.mockImplementation((_id: string, pageIndex: number) =>
      Promise.resolve(pageIndex === 0
        ? { data: first100, totalElements: 150, pageIndex: 0, pageSize: 100 }
        : { data: last50, totalElements: 150, pageIndex: 1, pageSize: 100 }),
    )
    const wrapper = mount(ProcessInstanceDetail, {
      attachTo: mountHost(),
      global: { stubs: { teleport: true }, plugins: [createPinia(), makeI18n()] },
    })
    mounted.push(wrapper)
    await flushPromises()
    await until(() => wrapper.text().includes('Test'))

    const historyTab = wrapper.findAll('[role="tab"]').find((b) => b.text().includes('History'))
    expect(historyTab).toBeTruthy()
    await historyTab!.trigger('click')
    await flushPromises()

    // Первая страница отрисована реальными строками, остаток честно показан.
    await until(() => wrapper.text().includes('100 / 150'))
    let rows = wrapper.findAll('tbody tr')
    expect(rows.length).toBe(100)
    expect(rows[0].element.getBoundingClientRect().height).toBeGreaterThan(0)

    const moreBtn = wrapper.findAll('button').find((b) => b.text().includes('100 / 150'))
    expect(moreBtn).toBeTruthy()
    expect(moreBtn!.element.getBoundingClientRect().height).toBeGreaterThan(0)
    expect(mockGetPaged).toHaveBeenCalledWith('pi-1', 0, 100)

    await moreBtn!.trigger('click')
    await flushPromises()
    await until(() => wrapper.findAll('tbody tr').length === 150)
    rows = wrapper.findAll('tbody tr')
    expect(rows.length).toBe(150)
    expect(mockGetPaged).toHaveBeenLastCalledWith('pi-1', 1, 100)
    // Остатка больше нет — кнопка ушла.
    expect(wrapper.text()).not.toContain('Show more')
    wrapper.unmount()
    mounted = []
  })
})
