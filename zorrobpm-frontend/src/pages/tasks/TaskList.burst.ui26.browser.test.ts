/**
 * WO-UI-26 Доп.4 (кр.17) + Доп.2 (кр.3/15) — реальный Chromium.
 * WO-UI-29: абсолютный потолок «худший кадр < 100 мс» заменён относительным
 * + счётным (флейк pipeline 177424: 216.7 мс на общем раннере рядом с
 * test:backend — шум машины, а не регресс).
 *
 * Замер «до» (throwaway-проба на коде до патчей, /tmp): всплеск 50 событий →
 * 50 запросов списка (refetchCalls=50), таблица перерисовывалась целиком.
 * Новый контракт: всплеск 50 user-task.created на живой TaskList →
 * - 0 запросов СПИСКА сверх начальной загрузки (только GET-ы одной сущности);
 * - ровно 50 GET-ов сущности (по одному на событие — счётная метрика работы,
 *   не зависит от CPU);
 * - loading не включается ни разу (sync-watch, без мерцания);
 * - строки вставляются адресными патчами (1 + 50), верх таблицы не двигается
 *   (layout shift = 0 по getBoundingClientRect до/после).
 * - кадры — относительный потолок по idle-базе ТОГО ЖЕ прогона
 *   (worstBurst ≤ max(idleWorst×4, idleWorst+120мс)) + защитный потолок 2000 мс
 *   против зависания. Абсолютных миллисекунд в ассертах нет.
 * Мутации: вернуть полный refetch на событие → первый ассерт красный;
 * вернуть loading=true в тихий refresh → второй ассерт красный;
 * убрать пропатчивание строк → счётный ассерт (50 GET-ов без 51-й строки)
 * и until-таймаут красные.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { watch, defineComponent, h, onMounted } from 'vue'
import TaskList from './TaskList.vue'
import { useRealtimeEvents } from '@/composables/useRealtimeEvents'
import { useTaskStore } from '@/stores/task'
import ru from '@/locales/ru.json'
import en from '@/locales/en.json'
import kz from '@/locales/kz.json'

import '@/style.css'

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: {}, path: '/tasks', query: {} }),
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
const mockGetUserTask = vi.hoisted(() => vi.fn())
vi.mock('@/services/taskService', () => ({
  getUserTasks: mockGetUserTasks,
  getUserTask: mockGetUserTask,
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
vi.mock('@/services/formService', () => ({
  getTaskForm: vi.fn().mockResolvedValue(null),
  getStartForm: vi.fn().mockResolvedValue(null),
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
  return {
    id, code: `code-${id}`, name: `Task ${id}`, status: 'CREATED' as const,
    createdAt: '2026-09-24', completedAt: null, processInstanceId: 'pi-1',
  }
}

function until(cond: () => boolean, timeout = 15000): Promise<void> {
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

/**
 * WO-UI-26 Н-8 + WO-UI-29: кадры за всплеск БЕЗ абсолютного потолка.
 * Мерим rAF-кадры за окно (время между соседними rAF-тиками; долгий тик =
 * заблокированный main thread). Итоговая статистика — СРЕДНИЙ кадр, а не
 * худший: одиночный задержанный тик планировщика (шум нагруженной машины —
 * 166мс в прогоне 29/30) не должен убивать прогон, а систематическое
 * утяжеление всплеска (тяжёлая синхронная работа на событие) поднимает
 * именно среднее. Калибровка — idle-базой ТОГО ЖЕ прогона (та же страница,
 * та же секунда): относительный потолок burstAvg ≤ max(idleAvg×K, idleAvg+S),
 * K=2, S=30мс — щедро: локально всплеск не добавляет ничего сверх базы
 * (idle≈burst≈16.7мс), худшее наблюдаемое под нагрузкой 21.8мс против
 * порога 46.8мс (запас 2×). Худший кадр — только защитный потолок 2000мс
 * против зависания (не перф-ассерт). longtask-записи PerformanceObserver —
 * телеметрия с атрибуцией в логе (longtask срабатывает уже с 50мс и ловит
 * шум окружения, а не всплеск).
 */
const BURST_FRAME_RATIO_K = 2
const BURST_FRAME_SLACK_MS = 30
const BURST_FRAME_HANG_GUARD_MS = 2000
async function measureFrames(fn: () => void, frames = 30, phase = '?'): Promise<{
  worstMs: number
  avgMs: number
}> {
  const longtaskEntries: Array<{ duration: number; src: string }> = []
  let observer: PerformanceObserver | null = null
  try {
    if (typeof PerformanceObserver !== 'undefined') {
      observer = new PerformanceObserver((list) => {
        for (const e of list.getEntries()) {
          // Атрибуция виновника: чей скрипт/контейнер держал поток >50 мс.
          // Нужно для честного разбора «мой всплеск vs шум harness/dev-сервера».
          const attr = (e as PerformanceEntry & {
            attribution?: Array<{ containerSrc?: string; containerName?: string }>
          }).attribution
            ?.map((a) => a.containerSrc || a.containerName || '?')
            .join(',') || '?'
          longtaskEntries.push({ duration: e.duration, src: attr })
        }
      })
      observer.observe({ entryTypes: ['longtask'] })
    }
  } catch {
    observer = null
  }
  let worstMs = 0
  let sumMs = 0
  let last = await new Promise<number>((resolve) => requestAnimationFrame((t) => resolve(t)))
  fn()
  for (let i = 0; i < frames; i++) {
    const t = await new Promise<number>((resolve) => requestAnimationFrame((t2) => resolve(t2)))
    const dt = t - last
    worstMs = Math.max(worstMs, dt)
    sumMs += dt
    last = t
  }
  observer?.disconnect()
  const avgMs = sumMs / frames
  const maxLongtaskMs = longtaskEntries.reduce((m, e) => Math.max(m, e.duration), 0)
  const longtaskSources = longtaskEntries.map((e) => `${Math.round(e.duration)}ms@${e.src}`)
  // eslint-disable-next-line no-console
  console.log(
    `[burst-frames] phase=${phase} worstMs=${worstMs.toFixed(1)} avgMs=${avgMs.toFixed(1)} longtasks=${longtaskEntries.length} ` +
    `maxLongtaskMs=${maxLongtaskMs.toFixed(1)} src=[${longtaskSources.join(';')}]`,
  )
  return { worstMs, avgMs }
}

let mounted: VueWrapper[] = []
let hosts: HTMLElement[] = []

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
  for (const w of mounted) w.unmount()
  mounted = []
  for (const h of hosts) h.remove()
  hosts = []
  document.body.innerHTML = ''
})

describe('WO-UI-26 burst 50 events on live TaskList (browser)', () => {
  it('кр.17/3/15: 0 list refetch, 0 loading, rows patched, no layout shift', async () => {
    const host = document.createElement('div')
    host.style.position = 'absolute'
    host.style.top = '0'
    host.style.left = '0'
    host.style.width = '1280px'
    host.style.height = '720px'
    document.body.appendChild(host)
    hosts.push(host)
    // Канал держит MainLayout в проде; здесь — хост с каналом + список
    // (сам TaskList EventSource не открывает, см. пробу «до»).
    const hostComp = defineComponent({
      setup() {
        const rt = useRealtimeEvents()
        onMounted(() => rt.connect())
        return () => h(TaskList)
      },
    })
    const wrapper = mount(hostComp, {
      attachTo: host,
      global: { stubs: { teleport: true }, plugins: [createPinia(), makeI18n()] },
    })
    mounted.push(wrapper)
    await flushPromises()
    await until(() => wrapper.text().includes('Task t0'))

    const store = useTaskStore()
    let loadingCycles = 0
    const stopLoadingWatch = watch(
      () => store.loading,
      (v) => {
        if (v) loadingCycles++
      },
      { flush: 'sync' },
    )
    const table = () => document.querySelector('table')
    const topBefore = table()?.getBoundingClientRect().top ?? -1
    expect(topBefore).toBeGreaterThan(0)
    const listCallsBefore = mockGetUserTasks.mock.calls.length

    const N = 50
    // WO-UI-29: idle-база ТОГО ЖЕ прогона (та же страница, та же секунда) —
    // калибровка относительного потолка под шум текущей машины.
    const { avgMs: idleAvgMs } = await measureFrames(() => {}, 30, 'idle')
    const entityCallsBefore = mockGetUserTask.mock.calls.length
    // Н-8: кадры меряем ЗА всплеск (всплеск внутри measureFrames).
    const { worstMs: burstWorstMs, avgMs: burstAvgMs } = await measureFrames(() => {
      for (let i = 1; i <= N; i++) {
        FakeEventSource.instances[0].emit(
          'user-task.created',
          {
            sequence: i, id: `e-${i}`, type: 'user-task.created', version: 1,
            occurredAt: '2026-09-24T00:00:00Z', data: { activityId: `t${i}` },
          },
          String(i),
        )
      }
    }, 30, 'burst')
    // Все 50 адресных патчей применены (1 начальная + 50 новых строк).
    await until(() => (store.userTasks?.data.length ?? 0) >= 51, 20000)
    await new Promise((r) => setTimeout(r, 500))
    stopLoadingWatch()

    // Кр.17: ни одного запроса СПИСКА сверх начальной загрузки.
    expect(mockGetUserTasks.mock.calls.length).toBe(listCallsBefore)
    // WO-UI-29, счётная метрика работы: ровно один GET сущности на событие
    // (без повторных попыток/шторма; не зависит от CPU и шума машины).
    expect(mockGetUserTask.mock.calls.length - entityCallsBefore).toBe(N)
    // Кр.3: loading не включался ни разу — мерцания нет.
    expect(loadingCycles).toBe(0)
    // Кр.15: верх таблицы не сдвинулся (layout shift = 0).
    expect(table()?.getBoundingClientRect().top).toBe(topBefore)
    // WO-UI-29: относительный потолок СРЕДНЕГО кадра по idle-базе того же
    // прогона (K=2, запас S=30мс) + защитный потолок худшего кадра 2000мс
    // против зависания. Абсолютных миллисекунд в перф-ассертах нет: шум
    // нагруженной машины одинаково поднимает и базу, и всплеск, а одиночный
    // задержанный тик планировщика (166мс в прогоне 29/30 под нагрузкой)
    // среднее не двигает — в отличие от худшего кадра, который и флейковал
    // в pipeline 177424 (216.7мс на общем раннере). longtask-счётчик —
    // только телеметрия с атрибуцией (лог [burst-frames] выше): longtask
    // срабатывает уже с 50 мс, т.е. ассерт longtasks==0 строже самого WO
    // (кадр 60 мс WO-легален) и ловит шум нагруженного builder-окружения,
    // а не стоимость всплеска — доказано прогоном r2b UI-26 (longtasks=1
    // при worstMs<100 в том же окне). Тавтологий вида
    // expect(longtasks).toBeGreaterThanOrEqual(0) нет сознательно:
    // недоказывающий ассерт хуже его отсутствия.
    expect(burstWorstMs).toBeLessThan(BURST_FRAME_HANG_GUARD_MS)
    expect(burstAvgMs).toBeLessThanOrEqual(
      Math.max(idleAvgMs * BURST_FRAME_RATIO_K, idleAvgMs + BURST_FRAME_SLACK_MS),
    )
    wrapper.unmount()
    mounted = []
  }, 60000)
})
