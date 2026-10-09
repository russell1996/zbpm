/**
 * WO-UI-26 Н-4 — долгий heap-прогон под потоком событий (реальный Chromium).
 *
 * Критерий 9: рост heap после циклов навигации список↔инстанс ≈ 0,
 * стабильное число EventSource. jsdom для этого не годится (нет GC/heap).
 *
 * Долгий (≥10 мин по умолчанию) — в обычном `npm run test:browser` и в CI
 * НЕ гоняется: запуск только с `ZBPM_HEAP_SOAK=1 npm run test:browser`
 * (иначе skip). Длительность — `ZBPM_SOAK_MINUTES` (по умолчанию 10).
 * Цифры прогона — в отчёте WO-UI-26 §0 (таблица «после»).
 *
 * Сценарий цикла: mount TaskList + канал → пачка событий → unmount →
 * mount ProcessInstanceDetail → пачка событий → unmount. Замеры каждые
 * 5 циклов: usedJSHeapSize (performance.memory, Chromium), DOM-узлы,
 * открытые EventSource (FakeEventSource.счётчик).
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

// Браузерный контекст (vitest browser/provider playwright) не имеет
// process.env: флаг/длительность — через import.meta.env (Vite прокидывает
// переменные окружения с префиксом VITE_ — см. vitest.browser.config.ts).
// Запуск: `VITE_ZBPM_HEAP_SOAK=1 VITE_ZBPM_SOAK_MINUTES=10 npm run test:browser`.
const RUN_SOAK = (import.meta.env.VITE_ZBPM_HEAP_SOAK ?? '') === '1'
const SOAK_MINUTES = Number(import.meta.env.VITE_ZBPM_SOAK_MINUTES ?? 10)

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: 'pi-1' }, path: '/instances/pi-1', query: {} }),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
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
vi.mock('@/services/adminService', () => ({
  listMembers: vi.fn().mockResolvedValue([]),
  changeMemberRole: vi.fn(),
  removeMember: vi.fn(),
}))

function taskRow(id: string) {
  return {
    id, code: `code-${id}`, name: `Task ${id}`, status: 'CREATED' as const,
    createdAt: '2026-09-24', completedAt: null, processInstanceId: 'pi-1',
    processDefinitionId: 'pd-1', formKey: null,
  }
}

const mockGetUserTasks = vi.hoisted(() => vi.fn())
const mockGetUserTask = vi.hoisted(() => vi.fn())
vi.mock('@/services/taskService', () => ({
  getUserTasks: mockGetUserTasks,
  getUserTask: mockGetUserTask,
  completeUserTask: vi.fn().mockResolvedValue(undefined),
  getServiceTasks: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  getServiceTask: vi.fn().mockResolvedValue(null),
  completeServiceTask: vi.fn().mockResolvedValue(undefined),
  failServiceTask: vi.fn().mockResolvedValue(undefined),
  throwServiceTaskError: vi.fn().mockResolvedValue(undefined),
}))
vi.mock('@/services/incidentService', () => ({
  getIncidents: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  getIncident: vi.fn().mockResolvedValue(null),
  resolveIncident: vi.fn().mockResolvedValue(undefined),
}))
vi.mock('@/services/instanceService', () => ({
  getProcessInstance: vi.fn().mockResolvedValue({
    id: 'pi-1', parentActivityId: null, processDefinitionId: 'pd-1',
    startedAt: '2026-01-01', completedAt: null, cancelled: false,
    processName: 'Test', processKey: 'test', processVersion: 1,
  }),
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
vi.mock('@/services/formService', () => ({
  getTaskForm: vi.fn().mockResolvedValue(null),
  getStartForm: vi.fn().mockResolvedValue(null),
}))

type Listener = (e: Event) => void
class FakeEventSource {
  static instances: FakeEventSource[] = []
  listeners = new Map<string, Listener[]>()
  onopen: ((e: Event) => void) | null = null
  onerror: ((e: Event) => void) | null = null
  readyState = 1
  closed = false
  constructor(public url: string) {
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
  }
  static openCount() {
    return FakeEventSource.instances.filter((s) => !s.closed).length
  }
}

function makeI18n() {
  return createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { ru, en, kz } })
}

function heapMb(): number | null {
  const pm = (performance as unknown as { memory?: { usedJSHeapSize: number } }).memory
  return pm ? pm.usedJSHeapSize / 1048576 : null
}

function domNodes(): number {
  return document.getElementsByTagName('*').length
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

describe.skipIf(!RUN_SOAK)('WO-UI-26 Н-4 heap soak под потоком (Chromium)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    FakeEventSource.instances = []
    vi.stubGlobal('EventSource', FakeEventSource)
    Object.defineProperty(navigator, 'locks', {
      value: { request: (_n: string, _o: unknown, cb: () => Promise<void>) => cb() },
      configurable: true,
    })
    mockGetUserTasks.mockResolvedValue({ data: [taskRow('t0')], totalElements: 1, pageIndex: 0, pageSize: 10 })
    mockGetUserTask.mockImplementation(async (id: string) => taskRow(id))
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    document.body.innerHTML = ''
  })

  it(`циклы навигации под потоком ${SOAK_MINUTES} мин: heap ≈ const, ES стабилен`, async () => {
    const hostComp = (page: 'list' | 'detail') => defineComponent({
      setup() {
        const rt = useRealtimeEvents()
        onMounted(() => rt.connect())
        return () => h(page === 'list' ? TaskList : ProcessInstanceDetail)
      },
    })
    const samples: Array<{ cycle: number; heapMb: number | null; dom: number; esOpen: number }> = []
    const t0 = Date.now()
    const deadline = t0 + SOAK_MINUTES * 60 * 1000
    let cycle = 0
    let seq = 0
    const mounted: VueWrapper[] = []
    const unmountAll = () => {
      for (const w of mounted.splice(0)) w.unmount()
    }
    try {
      while (Date.now() < deadline) {
        cycle++
        for (const page of ['list', 'detail'] as const) {
          const host = document.createElement('div')
          document.body.appendChild(host)
          const wrapper = mount(hostComp(page), {
            attachTo: host,
            global: { stubs: { teleport: true, BpmnViewer: { template: '<div class="bpmn-stub" />' } }, plugins: [createPinia(), makeI18n()] },
          })
          mounted.push(wrapper)
          await flushPromises()
          // Пачка событий за монтирование (реалистичный поток 5–10/с).
          for (let i = 0; i < 20; i++) {
            seq++
            const src = FakeEventSource.instances[FakeEventSource.instances.length - 1]
            src?.emit('user-task.created', {
              sequence: seq, id: `e-soak-${seq}`, type: 'user-task.created', version: 1,
              occurredAt: '2026-09-24T00:00:00Z', data: { activityId: `soak-${seq}` },
            }, String(seq))
          }
          await sleep(400)
          // Замер В СМОНТИРОВАННОМ состоянии (до unmount): ловит накопление
          // подписок/EventSource/узлов за цикл, а не только пост-размонт.
          if (cycle % 5 === 0 && page === 'detail') {
            samples.push({ cycle, heapMb: heapMb(), dom: domNodes(), esOpen: FakeEventSource.openCount() })
          }
          unmountAll()
          host.remove()
        }
        if (cycle % 10 === 0) {
          samples.push({ cycle, heapMb: heapMb(), dom: domNodes(), esOpen: FakeEventSource.openCount() })
        }
        // Дыхание для GC/таймеров между циклами.
        await sleep(800)
      }
    } finally {
      unmountAll()
    }
    // eslint-disable-next-line no-console
    console.log(`[heap-soak] cycles=${cycle} events=${seq} samples=${JSON.stringify(samples)}`)

    expect(cycle).toBeGreaterThanOrEqual(10)
    expect(seq).toBeGreaterThanOrEqual(200)
    // EventSource не копятся: лидерский ES максимум 1 открытый на контекст.
    const esOpen = FakeEventSource.openCount()
    expect(esOpen).toBeLessThanOrEqual(2)
    // Heap: рост за весь прогон — в пределах шума GC (щедрый порог 40 МБ,
    // реальный рост без утечек — единицы МБ; утечка подписок дала бы сотни).
    const first = samples.find((s) => s.heapMb !== null)
    const last = [...samples].reverse().find((s) => s.heapMb !== null)
    if (first?.heapMb != null && last?.heapMb != null && samples.length >= 3) {
      expect(last.heapMb - first.heapMb).toBeLessThan(40)
    }
    // DOM после размонтирования — без наростов (утечки узлов нет).
    const domFirst = samples[0]?.dom ?? 0
    const domLast = samples[samples.length - 1]?.dom ?? 0
    expect(Math.abs(domLast - domFirst)).toBeLessThan(500)
  }, SOAK_MINUTES * 60 * 1000 + 120000)
})
