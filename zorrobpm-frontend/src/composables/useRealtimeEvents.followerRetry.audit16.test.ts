// @vitest-environment jsdom
/**
 * WO-AUDIT-16 (F-13, полный аудит 2026-10-19) — follower-вкладка НЕ открывает
 * собственный EventSource по «Повторить сейчас».
 *
 * `retryConnection()` (кнопка в `ChannelStatusDot.vue`, доступна в ЛЮБОЙ вкладке)
 * без гварда лидера вызывал `openSource()` → `new EventSource(...)`: гвард
 * «только лидер» есть в `connect()`, а в retry/openSource — нет. Последствия:
 *  - второй realtime-канал на браузер: двойная обработка событий (follower +
 *    лидер), конфликтующие health-патчи;
 *  - при последующем promotion `openSource()` перезаписывает переменную
 *    `eventSource`, первый источник остаётся висеть НЕЗАКРЫТЫМ (утечка слота
 *    HTTP/1.1 — ровно то, от чего уходили WO-UI-26 Доп.5).
 *
 * Мок EventSource со счётчиком открытых (FakeEventSource.instances).
 * Ассерты — на КОНКРЕТНЫХ значениях (число созданных/открытых источников,
 * число health-request), не на «что-то произошло»: мутация «вернуть
 * безусловный openSource» обязана краснить оба теста (см. отчёт WO-AUDIT-16).
 *
 * Тоже-контекстная топология (один JS-контекст, две «вкладки» = два
 * экземпляра composable): публикация fanout доставляется локально
 * синхронно, поэтому follower, подписавшийся ДО события, его видит —
 * его isConnected синхронизировано health-запросами ещё до retry, и тест
 * не строит «follower не знал о здоровье» (в реальных вкладках так было бы,
 * здесь это свойство топологии, а не предмет теста). Предмет — КАНАЛ.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useRealtimeEvents } from './useRealtimeEvents'
import {
  resetSseFanoutForTest,
  subscribeFanout,
  type SseFanoutMessage,
} from '@/services/sseFanout'
import * as processService from '@/services/processService'
import * as instanceService from '@/services/instanceService'
import * as variableService from '@/services/variableService'
import * as taskService from '@/services/taskService'
import * as incidentService from '@/services/incidentService'

vi.mock('@/services/processService', () => ({
  getProcessDefinitions: vi.fn().mockResolvedValue({ data: [], totalElements: 0 }),
  getProcessDefinition: vi.fn().mockResolvedValue(null),
  getProcessDefinitionStructure: vi.fn().mockResolvedValue(null),
  getProcessDefinitionVersions: vi.fn().mockResolvedValue([]),
}))
vi.mock('@/services/instanceService', () => ({
  getProcessInstances: vi.fn().mockResolvedValue({ data: [], totalElements: 0 }),
  getProcessInstance: vi.fn().mockResolvedValue(null),
  getProcessInstanceActivities: vi.fn().mockResolvedValue([]),
  getProcessInstanceActivitiesPaged: vi.fn().mockResolvedValue({ data: [], totalElements: 0 }),
  startProcessInstance: vi.fn(),
}))
vi.mock('@/services/variableService', () => ({
  getVariables: vi.fn().mockResolvedValue({ data: [] }),
}))
vi.mock('@/services/taskService', () => ({
  getUserTasks: vi.fn().mockResolvedValue({ data: [], totalElements: 0 }),
  getUserTask: vi.fn().mockResolvedValue(null),
  completeUserTask: vi.fn(),
  getServiceTasks: vi.fn().mockResolvedValue({ data: [], totalElements: 0 }),
  getServiceTask: vi.fn().mockResolvedValue(null),
  completeServiceTask: vi.fn(),
}))
vi.mock('@/services/incidentService', () => ({
  getIncidents: vi.fn().mockResolvedValue({ data: [], totalElements: 0 }),
  getIncident: vi.fn().mockResolvedValue(null),
  resolveIncident: vi.fn(),
}))

type Listener = (e: Event) => void

/** Мок со счётчиком ВСЕХ созданных экземпляров (включая закрытые). */
class FakeEventSource {
  static instances: FakeEventSource[] = []
  listeners = new Map<string, Listener[]>()
  onopen: ((e: Event) => void) | null = null
  onerror: ((e: Event) => void) | null = null
  closed = false
  readyState = 1
  url: string
  constructor(url: string) {
    this.url = url
    FakeEventSource.instances.push(this)
  }
  addEventListener(type: string, fn: Listener) {
    const arr = this.listeners.get(type) || []
    arr.push(fn)
    this.listeners.set(type, arr)
  }
  close() {
    this.closed = true
  }
}

// Web Locks стаб: первый запросивший — лидер, лок держится до release;
// ждущие отзываются на releaseLockForTest (опрос 10 мс).
let lockOwner: (() => void) | null = null
function stubLocks() {
  const locks = {
    request: vi.fn((_name: string, _opts: unknown, cb: () => Promise<void>) => {
      if (lockOwner) {
        // занято — висим (follower ждёт освобождения)
        return new Promise<void>((resolve) => {
          const check = () => {
            if (!lockOwner) {
              lockOwner = () => {}
              void cb().finally(() => {})
            } else {
              setTimeout(check, 10)
            }
          }
          setTimeout(check, 10)
          void resolve
        })
      }
      lockOwner = () => {}
      return cb()
    }),
  }
  Object.defineProperty(navigator, 'locks', { value: locks, configurable: true })
  return locks
}

function releaseLockForTest() {
  lockOwner = null
}

/**
 * Реальное ожидание устойчивого follower: 1500 мс — таймаут follower-резолва
 * acquireSseLeadership, ещё ~1000 мс — его 3 health-запроса лидеру (500 мс
 * интервал) и ответы на них. Итого все health-request ДО подписки наблюдателя
 * в тесте: счётчик `seen` чист для самого retry.
 */
async function waitFollowerSettled(): Promise<void> {
  await new Promise((r) => setTimeout(r, 2800))
  await Promise.resolve()
}

describe('follower retry не создаёт EventSource (WO-AUDIT-16, F-13)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    FakeEventSource.instances = []
    vi.stubGlobal('EventSource', FakeEventSource)
    resetSseFanoutForTest()
    lockOwner = null
    stubLocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    resetSseFanoutForTest()
    lockOwner = null
  })

  it('follower retry не создаёт EventSource — спрашивает лидера о состоянии', async () => {
    const leader = useRealtimeEvents()
    let follower: ReturnType<typeof useRealtimeEvents> | undefined
    let unsub: (() => void) | undefined
    // finally обязателен: при RED-падении ассерта cleanup всё равно должен
    // отпустить лок и инкрементировать поколения — иначе висящий lock-опрос
    // follower сработает ПОСЛЕ teardown (unstub globals) на живом поколении.
    try {
      leader.connect()
      await vi.waitFor(() => expect(FakeEventSource.instances).toHaveLength(1))
      // Здоровое рукопожатие лидера.
      FakeEventSource.instances[0].onopen?.(new Event('open'))
      expect(leader.isConnected.value).toBe(true)

      follower = useRealtimeEvents()
      follower.connect()
      await waitFollowerSettled()
      expect(follower.isLeaderTab()).toBe(false)
      // Follower своего канала не открывает (механизм Доп.5, кр.21).
      expect(FakeEventSource.instances).toHaveLength(1)
      // Свойство тоже-контекстной топологии: follower уже синхронизирован
      // health-запросами — предмет теста НЕ «знал/не знал», а канал.
      expect(follower.isConnected.value).toBe(true)

      const seen: SseFanoutMessage[] = []
      unsub = subscribeFanout((m) => seen.push(m))
      follower.retryConnection()
      unsub()

      // ЯДРО F-13: второго EventSource нет ни создано, ни открыто.
      expect(FakeEventSource.instances).toHaveLength(1)
      expect(FakeEventSource.instances.filter((s) => !s.closed)).toHaveLength(1)
      // Смысленное действие follower — health-request к лидеру (ровно один —
      // начальные 3 запроса были до подписки `seen`).
      expect(seen.filter((m) => m.kind === 'health-request')).toHaveLength(1)
      // Лидер ответил полным health-патчем (локальная доставка) — состояние
      // follower обновлено БЕЗ открытия канала.
      expect(seen.filter((m) => m.kind === 'health')).toHaveLength(1)
      expect(follower.isConnected.value).toBe(true)
    } finally {
      unsub?.()
      follower?.disconnect()
      leader.disconnect()
      releaseLockForTest()
    }
  })

  it('promotion после retry не оставляет висящего EventSource', async () => {
    const leader = useRealtimeEvents()
    let follower: ReturnType<typeof useRealtimeEvents> | undefined
    try {
      leader.connect()
      await vi.waitFor(() => expect(FakeEventSource.instances).toHaveLength(1))
      FakeEventSource.instances[0].onopen?.(new Event('open'))

      follower = useRealtimeEvents()
      // const-алиас с narrowed-типом: `let follower: ... | undefined` не
      // сужается внутри замыканий (vi.waitFor ниже).
      const follow = follower
      follower.connect()
      await waitFollowerSettled()
      expect(follow.isLeaderTab()).toBe(false)
      expect(FakeEventSource.instances).toHaveLength(1)

      // Retry на follower-вкладке (кнопка «Повторить сейчас»).
      follow.retryConnection()

      // Смерть лидера: disconnect закрывает его ES и отпускает лок — ждущий
      // в очереди лока follower повышен (onPromoted → openSource).
      leader.disconnect()
      releaseLockForTest()
      await vi.waitFor(() => expect(follow.isLeaderTab()).toBe(true))
      await vi.waitFor(() => expect(FakeEventSource.instances).toHaveLength(2))

      // Ровно ОДИН открытый источник — ES повышенного лидера. Первый закрыт
      // (disconnect лидера), а retry НЕ оставил второй висящим: мутация
      // «без гварда — openSource всегда» даёт здесь 2 открытых (ретраевский
      // источник перезаписывается promotion-переменной и не закрывается).
      expect(FakeEventSource.instances.filter((s) => !s.closed)).toHaveLength(1)
      expect(FakeEventSource.instances[0].closed).toBe(true)
      expect(FakeEventSource.instances[1].closed).toBe(false)
    } finally {
      follower?.disconnect()
      leader.disconnect()
      releaseLockForTest()
    }
  })
})
