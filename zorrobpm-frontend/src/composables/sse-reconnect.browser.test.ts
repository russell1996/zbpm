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
import axios from 'axios'
import api from '@/services/api'
import TaskList from '@/pages/tasks/TaskList.vue'
import MainLayout from '@/layouts/MainLayout.vue'
import { useRealtimeEvents } from '@/composables/useRealtimeEvents'
import { useRealtimeChannel } from '@/composables/useRealtimeChannel'
import { resetSseFanoutForTest } from '@/services/sseFanout'
import ru from '@/locales/ru.json'
import en from '@/locales/en.json'
import kz from '@/locales/kz.json'

import '@/style.css'

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: {}, path: '/' }),
  useRouter: () => ({ push: mockRouterPush }),
  RouterLink: { template: '<a><slot /></a>' },
}))
const mockRouterPush = vi.hoisted(() => vi.fn())
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ user: { id: 'u1', username: 'op' }, isSuperAdmin: true, logout: vi.fn() }),
}))
// WO-UI-26: HeaderBar (с точкой канала) читает useUiStore напрямую — без
// мока падает, точка не рендерится (поймано сравнением с channel-dot тестом).
vi.mock('@/stores/ui', () => ({
  useUiStore: () => ({ darkMode: false, toggleDarkMode: vi.fn() }),
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
const mockGetUserTaskOne = vi.hoisted(() => vi.fn())
vi.mock('@/services/taskService', () => ({
  getUserTasks: mockGetUserTasks,
  getUserTask: mockGetUserTaskOne,
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

function until(cond: () => boolean, timeout = 8000, label = ''): Promise<void> {
  return new Promise((resolve, reject) => {
    const start = Date.now()
    const tick = () => {
      let ok = false
      try {
        ok = cond()
      } catch (e) {
        return reject(new Error(`until[${label}] threw: ${(e as Error).message}`))
      }
      if (ok) return resolve()
      if (Date.now() - start > timeout) return reject(new Error(`timeout waiting for condition [${label}]`))
      requestAnimationFrame(tick)
    }
    tick()
  })
}

// WO-URGENT-2: refresh идёт через sharedRefresh(api) — axios-путь с single-flight
// (WO-QW-5 перевёл refreshAndReconnect с прямого fetch), поэтому мокаем адаптер
// api-инстанса, а не глобальный fetch. Формы дословно по образцу
// useRealtimeEventsReconnect.test.ts (mockRefreshOk/mockRefreshFail оттуда).
const originalAdapter = api.defaults.adapter
let refreshAdapter: ReturnType<typeof vi.fn>

function mockRefreshOk() {
  const adapter = vi.fn(async (cfg: { url?: string }) => {
    if (cfg.url === '/auth/refresh') return { data: {}, status: 200 }
    return { data: {}, status: 200 }
  })
  ;(api.defaults as Record<string, unknown>).adapter = adapter
  return adapter
}

function mockRefreshFail() {
  const adapter = vi.fn(async (cfg: { url?: string }) => {
    const error = new axios.AxiosError('Request failed with status code 401')
    error.config = { url: cfg.url || '/x', method: 'GET', headers: new axios.AxiosHeaders() } as never
    error.response = {
      data: null, status: 401, statusText: 'Unauthorized',
      headers: new axios.AxiosHeaders(), config: error.config,
    }
    throw error
  })
  ;(api.defaults as Record<string, unknown>).adapter = adapter
  return adapter
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  FakeEventSource.instances = []
  useRealtimeChannel().resetForTest()
  resetSseFanoutForTest()
  vi.stubGlobal('EventSource', FakeEventSource)
  // WO-UI-26 Доп.5: лидерство детерминировано — сразу лидер (иначе connect
  // висит на Web-Locks таймауте как follower и EventSource не открывается).
  Object.defineProperty(navigator, 'locks', {
    value: { request: (_n: string, _o: unknown, cb: () => Promise<void>) => cb() },
    configurable: true,
  })
  refreshAdapter = mockRefreshOk()
})

afterEach(() => {
  vi.unstubAllGlobals()
  ;(api.defaults as Record<string, unknown>).adapter = originalAdapter
  // WO-UI-26: модульный синглтон канала + fanout переживают unmount —
  // сбрасываем между кейсами, иначе следующий кейс читает чужой инстанс.
  useRealtimeChannel().resetForTest()
  resetSseFanoutForTest()
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
    expect(refreshAdapter.mock.calls.filter(([cfg]) => (cfg as { url?: string }).url === '/auth/refresh'))
      .toHaveLength(1)

    // Новый источник жив: событие долетает до списка без reload страницы.
    // WO-UI-26 Доп.4: адресный патч — событие несёт activityId, стор делает
    // один GET сущности (getUserTask), список не перезапрашивает.
    mockGetUserTaskOne.mockResolvedValue(taskRow('t2'))
    mockGetUserTasks.mockClear()
    FakeEventSource.instances[1].emit(
      'user-task.created',
      { sequence: 78, id: 'e-78', type: 'user-task.created', version: 1, occurredAt: '2026-09-24T00:00:00Z', data: { activityId: 't2' } },
      '78',
    )
    await flushPromises()
    await until(() => taskRowsText(wrapper).some((t) => t.includes('Task t2')))
    expect(mockGetUserTaskOne).toHaveBeenCalledWith('t2')
    expect(mockGetUserTasks).not.toHaveBeenCalled()

    const rows = wrapper.findAll('tbody tr')
    expect(rows.length).toBe(2)
    for (const row of rows) {
      expect(row.element.getBoundingClientRect().height).toBeGreaterThan(0)
    }
    wrapper.unmount()
    mounted = []
  })

  it('criterion 3 (UI): dead refresh shows NO banner — neutral dot + dialog (WO-UI-26)', async () => {
    // WO-UI-26 Доп.2/Доп.7 ЗАМЕНИЛ контракт WO-UI-22: баннер [role="alert"]
    // «Session expired» + кнопка «Войти» над контентом УБРАНЫ (layout shift).
    // Вместо них — нейтральная точка в шапке (гистерезис 5 с) + shadcn-диалог
    // «Состояние канала» с кнопкой «Повторить сейчас» (ручной retry — только
    // внутри панели, не кнопка слева сверху). Тест переписан на новый контракт;
    // мутация «вернуть баннер в MainLayout» роняет его (alert найден).
    // ВАЖНО: beforeEach ставит mockRefreshOk — здесь нужен МЁРТВЫЙ refresh.
    refreshAdapter = mockRefreshFail()
    const wrapper = mount(MainLayout, {
      attachTo: mountHost(),
      global: {
        // HeaderBar НЕ стаблим: точка канала живёт внутри него (застабленный
        // HeaderBar точку не рендерит — как поймано при переписывании).
        stubs: { teleport: true, RouterView: true, SidebarNavShadcn: true },
        plugins: [createPinia(), makeI18n()],
      },
    })
    mounted.push(wrapper)
    await flushPromises()
    expect(FakeEventSource.instances).toHaveLength(1)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)

    // Разрыв за разрывом (как в моём channel-dot тесте — там цикл 5 подряд
    // зелёный 10/10): каждый CLOSED идёт в scheduleReconnect; условие
    // `length === 1` НЕ ставим — при живом refresh новый источник создаётся,
    // и старый цикл вис на первом же пересоздании.
    for (let i = 0; i < 5; i++) {
      const cur = FakeEventSource.instances[FakeEventSource.instances.length - 1]
      cur.readyState = FakeEventSource.CLOSED
      cur.onerror?.({} as Event)
      await flushPromises()
      await new Promise((r) => setTimeout(r, 100))
    }
    // НИКАКОГО баннера — ни на одном этапе; вместо него точка в шапке.
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    await until(() => wrapper.find('[data-testid="channel-dot"]').exists(), 30000, 'channel-dot')
    const dot = wrapper.find('[data-testid="channel-dot"]')
    expect(dot.exists()).toBe(true)
    // Клик → диалог «Состояние канала» с retry внутри панели.
    const dotEl = document.querySelector('[data-testid="channel-dot"]') as HTMLElement | null
    expect(dotEl).not.toBeNull()
    dotEl!.click()
    await flushPromises()
    await until(() => document.querySelector('[data-testid="channel-dialog"]') !== null, 8000, 'channel-dialog')
    const retry = document.querySelector('[data-testid="channel-diag-retry"]') as HTMLElement | null
    expect(retry).not.toBeNull()
    const sourcesBefore = FakeEventSource.instances.length
    retry!.click()
    await flushPromises()
    // Ручной retry переподключает канал (новый EventSource, старый закрыт).
    expect(FakeEventSource.instances.length).toBe(sourcesBefore + 1)
    wrapper.unmount()
    mounted = []
  })
})
