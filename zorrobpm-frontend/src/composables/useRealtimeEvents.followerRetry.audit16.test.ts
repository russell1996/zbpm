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
 *
 * WO-UI-40 — ДЕТЕРМИНИРОВАННОСТЬ. Тест больше НЕ ждёт стенного времени:
 * фейковые таймеры (`vi.useFakeTimers` + `vi.advanceTimersByTimeAsync`)
 * проматывают ровно те интервалы ПРОДА, на которых стоит топология кейсов.
 * На реальных таймерах (`await new Promise(r => setTimeout(r, 2800))` +
 * `vi.waitFor` с таймаутом 1000 мс) тест падал под нагрузкой (load average
 * 30–50): воспроизведено 4 красных прогона из 6 (см. отчёт WO-UI-40).
 * Поведение кейсов НЕ ослаблено: те же ассерты, те же два сценария.
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

/**
 * Тайминги ПРОДА, на которых стоит топология обоих кейсов (WO-UI-40:
 * выписаны константами, чтобы тест зависел от поведения, а не от цифр,
 * размазанных по коду):
 *  - 1500 мс — таймаут follower-резолва в `acquireSseLeadership`
 *    (`sseFanout.ts`: лок занят другой вкладкой → резолв «я follower»);
 *  - 500 мс — интервал повторного health-запроса лидеру
 *    (`useRealtimeEvents.connect()`, ветка follower: 3 попытки, t = 1500/2000/2500);
 *  - 10 мс — период опроса лока в стабе Web Locks (передача лока ожидающему).
 */
const FOLLOWER_RESOLVE_TIMEOUT_MS = 1500
const HEALTH_ASK_INTERVAL_MS = 500
const HEALTH_ASK_TRIES = 3
const LOCK_POLL_INTERVAL_MS = 10

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
 * Детерминированное «устаканивание» follower-вкладки (было: 2800 мс стенного
 * ожидания — ровно те интервалы, но по реальным часам, из-за чего кейс падал
 * под нагрузкой):
 *  1) резолв follower'а — 1500 мс (таймаут в `acquireSseLeadership`);
 *  2) все его health-запросы лидеру — 3 попытки с интервалом 500 мс
 *     (t = 1500 / 2000 / 2500). Все они ДО подписки наблюдателя в тесте —
 *     счётчик `seen` чист для самого retry.
 */
async function settleFollower(): Promise<void> {
  await vi.advanceTimersByTimeAsync(FOLLOWER_RESOLVE_TIMEOUT_MS)
  await vi.advanceTimersByTimeAsync(HEALTH_ASK_INTERVAL_MS * (HEALTH_ASK_TRIES - 1))
}

/**
 * Передача лока ожидающему follower'у: стаб отдаёт лок на ближайшем poll-тике
 * (10 мс) → `onPromoted` → свой EventSource. Два интервала — чтобы окно
 * гарантированно накрыло poll-тик независимо от его фазы.
 */
async function awaitPromotion(): Promise<void> {
  await vi.advanceTimersByTimeAsync(LOCK_POLL_INTERVAL_MS * 2)
}

describe('follower retry не создаёт EventSource (WO-AUDIT-16, F-13)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    // WO-UI-40: никакого стенного времени — все интервалы прод-таймингов
    // проматываются фейковыми часами (детерминированно и мгновенно).
    vi.useFakeTimers()
    FakeEventSource.instances = []
    vi.stubGlobal('EventSource', FakeEventSource)
    resetSseFanoutForTest()
    lockOwner = null
    stubLocks()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
    resetSseFanoutForTest()
    lockOwner = null
  })

  it('follower retry не создаёт EventSource — спрашивает лидера о состоянии', async () => {
    const leader = useRealtimeEvents()
    const follower = useRealtimeEvents()
    let unsub: (() => void) | undefined
    // finally обязателен: при RED-падении ассерта cleanup всё равно должен
    // отпустить лок и инкрементировать поколения — иначе висящий lock-опрос
    // follower сработает ПОСЛЕ teardown (unstub globals) на живом поколении.
    try {
      leader.connect()
      // Резолв лидерства — микротаска: фейковые часы её смывают.
      await vi.advanceTimersByTimeAsync(0)
      expect(FakeEventSource.instances).toHaveLength(1)
      // Здоровое рукопожатие лидера.
      FakeEventSource.instances[0].onopen?.(new Event('open'))
      expect(leader.isConnected.value).toBe(true)

      follower.connect()
      await settleFollower()
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
      follower.disconnect()
      leader.disconnect()
      releaseLockForTest()
    }
  })

  it('promotion после retry не оставляет висящего EventSource', async () => {
    const leader = useRealtimeEvents()
    const follower = useRealtimeEvents()
    try {
      leader.connect()
      await vi.advanceTimersByTimeAsync(0)
      expect(FakeEventSource.instances).toHaveLength(1)
      FakeEventSource.instances[0].onopen?.(new Event('open'))

      follower.connect()
      await settleFollower()
      expect(follower.isLeaderTab()).toBe(false)
      expect(FakeEventSource.instances).toHaveLength(1)

      // Retry на follower-вкладке (кнопка «Повторить сейчас»).
      follower.retryConnection()

      // Смерть лидера: disconnect закрывает его ES и отпускает лок — ждущий
      // в очереди лока follower повышен (onPromoted → openSource).
      leader.disconnect()
      releaseLockForTest()
      await awaitPromotion()
      expect(follower.isLeaderTab()).toBe(true)
      expect(FakeEventSource.instances).toHaveLength(2)

      // Ровно ОДИН открытый источник — ES повышенного лидера. Первый закрыт
      // (disconnect лидера), а retry НЕ оставил второй висящим: мутация
      // «без гварда — openSource всегда» даёт здесь 2 открытых (ретраевский
      // источник перезаписывается promotion-переменной и не закрывается).
      expect(FakeEventSource.instances.filter((s) => !s.closed)).toHaveLength(1)
      expect(FakeEventSource.instances[0].closed).toBe(true)
      expect(FakeEventSource.instances[1].closed).toBe(false)
    } finally {
      follower.disconnect()
      leader.disconnect()
      releaseLockForTest()
    }
  })
})
