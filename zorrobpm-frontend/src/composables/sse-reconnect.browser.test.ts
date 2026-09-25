/**
 * WO-UI-22 (NEW-09) — browser-доказательство (реальный Chromium,
 * `npm run test:browser`): после CLOSED realtime восстанавливается БЕЗ
 * перезагрузки страницы — новый EventSource создан, именованные события
 * снова долетают до сторов.
 *
 * Честная граница: укороченный JWT TTL без живого бэкенда невозможен, поэтому
 * CLOSED инжектируется напрямую — но это ровно то состояние, в которое
 * переводит источник 401 с истёкшей кукой (EventSource.CLOSED навсегда).
 * Тест доказывает восстановление ИЗ этого состояния, а не сам переход в него.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { defineComponent, h, onMounted } from 'vue'
import TaskList from '@/pages/tasks/TaskList.vue'
import MainLayout from '@/layouts/MainLayout.vue'
import { useRealtimeEvents } from '@/composables/useRealtimeEvents'
import ru from '@/locales/ru.json'
import en from '@/locales/en.json'
import kz from '@/locales/kz.json'

import '@/style.css'

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: {}, path: '/' }),
  useRouter: () => ({ push: mockRouterPush }),
}))
const mockRouterPush = vi.hoisted(() => vi.fn())
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
vi.mock('@/services/instanceService', () => ({
  getProcessInstance: vi.fn().mockResolvedValue(null),
  getProcessInstanceActivities: vi.fn().mockResolvedValue([]),
  getProcessInstanceActivitiesPaged: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  getProcessInstances: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  startProcessInstance: vi.fn(),
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

type Listener = (e: Event) => void
class FakeEventSource {
  static instances: FakeEventSource[] = []
  static CONNECTING = 0
  static OPEN = 1
  static CLOSED = 2
  listeners = new Map<string, Listener[]>()
  onopen: ((e: Event) => void) | null = null
  onerror: ((e: Event) => void) | null = null
  readyState = FakeEventSource.OPEN
  closed = false
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
  close() {
    this.closed = true
    this.readyState = FakeEventSource.CLOSED
  }
}

function makeI18n() {
  return createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { ru, en, kz } })
}

function taskRow(id: string) {
  return { id, code: `code-${id}`, name: `Task ${id}`, status: 'CREATED', createdAt: '2026-09-24', completedAt: null, processInstanceId: 'pi-1' }
}

let mounted: VueWrapper[] = []
let hosts: HTMLElement[] = []

function mountHost(): HTMLElement {
  const host = document.createElement('div')
  host.style.position = 'absolute'
  host.style.top = '0'
  host.style.left = '0'
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

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  FakeEventSource.instances = []
  vi.stubGlobal('EventSource', FakeEventSource)
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true }))
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

describe('WO-UI-22 SSE reconnect in a real browser', () => {
  it('criterion 2: CLOSED → refresh → new source, TaskList live again without reload', async () => {
    mockGetUserTasks.mockResolvedValue({ data: [taskRow('t1')], totalElements: 1, pageIndex: 0, pageSize: 10 })
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

    // Сервер закрыл поток (истёк JWT): источник мёртв навсегда.
    FakeEventSource.instances[0].readyState = FakeEventSource.CLOSED
    FakeEventSource.instances[0].onerror?.({} as Event)
    // Backoff первой попытки — 1с реального времени.
    await until(() => FakeEventSource.instances.length === 2, 10000)
    expect(fetch).toHaveBeenCalledTimes(1)

    // Новый источник жив: событие долетает до списка без reload страницы.
    mockGetUserTasks.mockResolvedValue({
      data: [taskRow('t1'), taskRow('t2')], totalElements: 2, pageIndex: 0, pageSize: 10,
    })
    FakeEventSource.instances[1].emit(
      'user-task.created',
      { sequence: 78, id: 'e-78', type: 'user-task.created', version: 1, occurredAt: '2026-09-24T00:00:00Z', data: {} },
      '78',
    )
    await flushPromises()
    await until(() => taskRowsText(wrapper).some((t) => t.includes('Task t2')))

    const rows = wrapper.findAll('tbody tr')
    expect(rows.length).toBe(2)
    for (const row of rows) {
      expect(row.element.getBoundingClientRect().height).toBeGreaterThan(0)
    }
    wrapper.unmount()
    mounted = []
  })

  it('criterion 3 (UI): dead refresh shows the banner, Sign in routes to login', async () => {
    vi.mocked(fetch).mockResolvedValue({ ok: false, status: 401 } as Response)
    const wrapper = mount(MainLayout, {
      attachTo: mountHost(),
      global: {
        stubs: { teleport: true, RouterView: true, SidebarNavShadcn: true, HeaderBar: true },
        plugins: [createPinia(), makeI18n()],
      },
    })
    mounted.push(wrapper)
    await flushPromises()
    expect(FakeEventSource.instances).toHaveLength(1)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)

    // Разрыв за разрывом: кап 5 попыток выматывается backoff-таймерами.
    for (let i = 0; i < 6 && FakeEventSource.instances.length === 1; i++) {
      const cur = FakeEventSource.instances[0]
      cur.readyState = FakeEventSource.CLOSED
      cur.onerror?.({} as Event)
      await new Promise((r) => setTimeout(r, 1100))
      await flushPromises()
    }
    await until(() => wrapper.find('[role="alert"]').exists(), 15000)
    const alert = wrapper.find('[role="alert"]')
    expect(alert.text()).toContain('Session expired')
    const btn = alert.find('button')
    expect(btn.exists()).toBe(true)
    await btn.trigger('click')
    // Именованный роут — base '/ui/' подставит сам history, без дубля /ui/ui.
    expect(mockRouterPush).toHaveBeenCalledWith({ name: 'login' })
    wrapper.unmount()
    mounted = []
  })
})
