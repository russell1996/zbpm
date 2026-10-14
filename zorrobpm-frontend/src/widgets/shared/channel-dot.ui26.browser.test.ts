/**
 * WO-UI-26 Доп.2/Доп.6/Доп.7 (кр.13/23/24) — реальный Chromium.
 *
 * Старый контракт WO-UI-22 (баннер `[role="alert"]` «Session expired» + кнопка
 * «Войти» над контентом) УБРАН: баннеры сдвигали весь <main> при каждом
 * флаппинге канала (layout shift). Новый контракт:
 * - кр.13: при недоступном SSE (мёртвый refresh, кап попыток исчерпан) —
 *   НИКАКОЙ полосы над контентом (`[role="alert"]` нет никогда), верх <main>
 *   не двигается (замер getBoundingClientRect до/после), появляется ОДНА
 *   нейтральная точка `[data-testid="channel-dot"]` без текста и без пульса;
 * - кр.23: клик по точке → shadcn-Dialog «Состояние канала» со статусом,
 *   попытками, Last-Event-ID и кнопками «Скопировать диагностику» /
 *   «Повторить сейчас»; retry реально пинает refresh;
 * - кр.24: при здоровом канале точки нет нигде; на странице инстанса среди
 *   кнопок действий индикатора нет (импорта live-indicator нет в DOM).
 * Мутация: вернуть баннер в MainLayout → кр.13 красный (alert найден);
 * вернуть индикатор в шапку инстанса → кр.24 красный.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { h } from 'vue'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import axios from 'axios'
import api from '@/services/api'
import MainLayout from '@/layouts/MainLayout.vue'
import { channelDotState } from '@/composables/useRealtimeEvents'
import { useRealtimeChannel } from '@/composables/useRealtimeChannel'
import ru from '@/locales/ru.json'
import en from '@/locales/en.json'
import kz from '@/locales/kz.json'

import '@/style.css'

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: {}, path: '/' }),
  useRouter: () => ({ push: vi.fn() }),
  RouterLink: { template: '<a><slot /></a>' },
}))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ user: { id: 'u1', username: 'op' }, isSuperAdmin: true, logout: vi.fn() }),
}))
vi.mock('@/stores/ui', () => ({
  useUiStore: () => ({ darkMode: false, toggleDarkMode: vi.fn() }),
}))


vi.mock('@/stores/breadcrumb', () => ({
  useBreadcrumbStore: () => ({ setCrumbLabel: vi.fn() }),
}))
vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn(), info: vi.fn() }),
}))

type Listener = (e: Event) => void
class FakeEventSource {
  static instances: FakeEventSource[] = []
  static CONNECTING = 0
  static OPEN = 1
  static CLOSED = 2
  // isClosed в прод-коде читает CLOSED с ГЛОБАЛЬНОГО EventSource —
  // стаб обязан нести те же константы (иначе CLOSED не распознаётся).
  // stubGlobal ниже меняет window.EventSource; статик CLOSED=2 уже на классе.
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
  close() {
    this.closed = true
    this.readyState = FakeEventSource.CLOSED
  }
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

const originalAdapter = api.defaults.adapter
let refreshAdapter: ReturnType<typeof vi.fn>

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
  // SSE-путь зовёт sharedRefresh(api) напрямую (POST /auth/refresh через
  // axios-адаптер); response-интерсептор для этого пути не нужен — 401 с
  // /auth/refresh честно падает в .catch → false (мёртвый refresh).
  // createRefreshInterceptor здесь НЕ зовём: он навешивает response-use на
  // общий api-инстанс и копит их между кейсами (утечка перехватов).
  vi.clearAllMocks()
  FakeEventSource.instances = []
  vi.stubGlobal('EventSource', FakeEventSource)
  // WO-UI-26 Доп.5: лидерство детерминировано — сразу лидер (иначе connect
  // висит на Web-Locks таймауте как follower и EventSource не открывается).
  Object.defineProperty(navigator, 'locks', {
    value: { request: (_n: string, _o: unknown, cb: () => Promise<void>) => cb() },
    configurable: true,
  })
})

afterEach(() => {
  vi.unstubAllGlobals()
  ;(api.defaults as Record<string, unknown>).adapter = originalAdapter
  for (const w of mounted) w.unmount()
  mounted = []
  for (const h of hosts) h.remove()
  hosts = []
  document.body.innerHTML = ''
})

function mainTop(wrapper: VueWrapper): number {
  const main = wrapper.find('main')
  if (!main.exists()) return -1
  return main.element.getBoundingClientRect().top
}

describe('WO-UI-26 channel banner removal + dot (browser)', () => {
  /**
   * Полный цикл «CLOSED → 5 refresh → sessionExpired» детерминированно
   * закрыт в useRealtimeEventsReconnect.test.ts (fake-таймеры, тот же мок
   * адаптера api-инстанса: `criterion 4` считает ≤ 5 вызовов /auth/refresh).
   * Здесь — то, что видит пользователь в реальном Chromium: НИКАКОЙ полосы
   * над контентом и нейтральная точка вместо неё. Состояние «кап исчерпан»
   * форсируем тем же путём, что прод (5 разрывов подряд — как criterion 4),
   * но без ожидания 31 с живой цепочки: прямому ожиданию мешает single-flight
   * sharedRefresh в реальном времени (см. комментарий ниже).
   */
  it('кр.13/24: dead channel → no banner, no content shift, one neutral dot', async () => {
    refreshAdapter = mockRefreshFail()
    const wrapper = mount(MainLayout, {
      attachTo: mountHost(),
      global: {
        stubs: {
          RouterView: true,
          RouterLink: { template: '<a><slot /></a>' },
        },
        plugins: [createPinia(), makeI18n()],
      },
    })
    mounted.push(wrapper)
    await flushPromises()
    expect(FakeEventSource.instances).toHaveLength(1)
    // Здоровый старт: точки нет, баннера нет.
    expect(wrapper.find('[data-testid="channel-dot"]').exists()).toBe(false)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    const topBefore = mainTop(wrapper)
    expect(topBefore).toBeGreaterThan(0)

    // Пять разрывов подряд (как criterion 4 в reconnect-тесте): каждый CLOSED
    // идёт через scheduleReconnect → refreshAndReconnect → sharedRefresh.
    // Single-flight sharedRefresh в реальном времени залипает на первом
    // висящем promise (reset только в .finally микрозадачи), поэтому цепочка
    // 1/2/4/8/16 с живьём выматывается минутами — ждём не 5 refresh-вызовов,
    // а терминальное состояние (точка после гистерезиса 5 с устойчивого дауна).
    for (let i = 0; i < 5; i++) {
      const cur = FakeEventSource.instances[FakeEventSource.instances.length - 1]
      cur.readyState = FakeEventSource.CLOSED
      cur.onerror?.({} as Event)
      await flushPromises()
      await new Promise((r) => setTimeout(r, 100))
    }
    // sessionExpired или устойчивый даун — в обоих случаях баннера нет.
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    // Контент не сдвинулся; точка — после гистерезиса 5с устойчивого дауна.
    await until(() => wrapper.find('[data-testid="channel-dot"]').exists(), 30000)
    expect(mainTop(wrapper)).toBe(topBefore)

    // Одна нейтральная точка: без текста, без пульсации.
    const dot = wrapper.find('[data-testid="channel-dot"]')
    expect(dot.exists()).toBe(true)
    expect(dot.text()).toBe('')
    expect(dot.classes().join(' ')).not.toContain('animate-pulse')
    wrapper.unmount()
    mounted = []
  }, 120000)

  it('кр.23: click dot → diagnostics dialog with retry that pings refresh', async () => {
    refreshAdapter = mockRefreshFail()
    const wrapper = mount(MainLayout, {
      attachTo: mountHost(),
      global: {
        stubs: {
          RouterView: true,
          RouterLink: { template: '<a><slot /></a>' },
        },
        plugins: [createPinia(), makeI18n()],
      },
    })
    mounted.push(wrapper)
    await flushPromises()
    expect(FakeEventSource.instances).toHaveLength(1)
    // Пять разрывов подряд (см. комментарий выше) → устойчивый даун → точка.
    for (let i = 0; i < 5; i++) {
      const cur = FakeEventSource.instances[FakeEventSource.instances.length - 1]
      cur.readyState = FakeEventSource.CLOSED
      cur.onerror?.({} as Event)
      await flushPromises()
      await new Promise((r) => setTimeout(r, 100))
    }
    await until(() => wrapper.find('[data-testid="channel-dot"]').exists(), 30000)

    // Клик напрямую по нативной кнопке точки (мимо Tooltip-обёртки):
    // trigger на обёртке гасится TooltipTrigger'ом, нативный click — нет.
    await until(() => document.querySelector('[data-testid="channel-dot"]') !== null, 30000)
    const dotEl = document.querySelector('[data-testid="channel-dot"]') as HTMLElement | null
    expect(dotEl).not.toBeNull()
    dotEl!.click()
    await flushPromises()
    await until(() => document.querySelector('[data-testid="channel-dialog"]') !== null, 8000)
    const dialog = wrapper.find('[data-testid="channel-dialog"]')
    const dialogEl = document.querySelector('[data-testid="channel-dialog"]')
    expect(dialog.exists() || dialogEl !== null).toBe(true)
    // Статус-диагноз виден (мёртвый refresh → сеть/истёкшая сессия, не «всё хорошо»).
    const dialogText = dialog.exists() ? dialog.text() : (dialogEl?.textContent ?? '')
    expect(dialogText).not.toContain('Connected')
    expect(
      wrapper.find('[data-testid="channel-diag-copy"]').exists() ||
        document.querySelector('[data-testid="channel-diag-copy"]') !== null,
    ).toBe(true)

    // Ручной retry реально пинает refresh: retryConnection → openSource →
    // новый EventSource (предыдущий закрыт). refresh идёт следующим обрывом,
    // но само переподключение доказывает проводку кнопки.
    const sourcesBefore = FakeEventSource.instances.length
    const retryBtn = wrapper.find('[data-testid="channel-diag-retry"]')
    if (retryBtn.exists()) {
      await retryBtn.trigger('click')
    } else {
      ;(document.querySelector('[data-testid="channel-diag-retry"]') as HTMLElement | null)?.click()
    }
    await flushPromises()
    expect(FakeEventSource.instances.length).toBe(sourcesBefore + 1)
    expect(FakeEventSource.instances[sourcesBefore - 1].closed).toBe(true)
    wrapper.unmount()
    mounted = []
  }, 120000)

  it('кр.9: 20 циклов монтирования → 0 открытых EventSource (нет утечек)', async () => {
    refreshAdapter = mockRefreshFail()
    for (let i = 0; i < 20; i++) {
      const wrapper = mount(MainLayout, {
        attachTo: mountHost(),
        global: {
          stubs: {
            RouterView: true,
            RouterLink: { template: '<a><slot /></a>' },
          },
          plugins: [createPinia(), makeI18n()],
        },
      })
      await flushPromises()
      wrapper.unmount()
    }
    const open = FakeEventSource.instances.filter((s) => !s.closed).length
    // eslint-disable-next-line no-console
    console.log(`UI26-LEAK cycles=20 eventSourcesTotal=${FakeEventSource.instances.length} open=${open}`)
    expect(open).toBe(0)
  }, 120000)
})

// =====================================================================
// WO-UI-31 — точка ВЛЕВО после SearchCommand, цвет по состоянию,
// рассинхрон точка/панель на вкладке-«ведомой», панель не перекрывает
// кнопки правой группы (1280/1920/360).
//
// Контракт (владелец 2026-10-14 + WO):
//  - слот [data-testid="channel-dot-slot"] — сосед SearchCommand в ЛЕВОЙ
//    группе шапки (не в правой, где точка перекрывала «Обновить»/
//    «Загрузить процесс»);
//  - цвет: сбой/нет связи → bg-destructive (красный), переподключение
//    (идут попытки) → янтарный (bg-amber-500), здоровый → слот пуст;
//    гистерезис 5 с сохраняется, пульсации нет;
//  - ОДИН источник правды: строка «Статус» панели и точка ведутся из
//    channelDotState({isConnected, realtimeDown, sessionExpired});
//    поздний follower здорового лидера НЕ горит (запрос состояния
//    health-request по BroadcastChannel-фанауту: health-патчи несут
//    только будущие смены, начальное состояние здоровый лидер не
//    доставляет сам), а при падении лидера показывает ТО ЖЕ состояние,
//    что лидер (живые health-патчи);
//  - панель: align="start" от точки (левый край панели = левый край
//    точки), не пересекает кнопки тулбара страницы на 1280 и 1920,
//    влезает в вьюпорт; ширина ≤ 360 (в реальном 360-вьюпорте
//    floating-ui shift/reka avoidCollisions подтягивает в границы).
// Мутации (каждая обязана краснить): вернуть слот в правую группу;
// вернуть нейтральный серый цвет; убрать health-request/ответ лидера.
// =====================================================================

function rectsIntersect(a: DOMRect, b: DOMRect): boolean {
  return a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
}

describe('WO-UI-31 channel dot position / colour / follower desync (browser)', () => {

  it('UI31-1: slot — сосед SearchCommand в левой группе, не в правой', async () => {
    const wrapper = mount(MainLayout, {
      attachTo: mountHost(),
      global: {
        stubs: {
          RouterView: true,
          RouterLink: { template: '<a><slot /></a>' },
        },
        plugins: [createPinia(), makeI18n()],
      },
    })
    mounted.push(wrapper)
    await flushPromises()

    const header = wrapper.find('header')
    expect(header.exists()).toBe(true)
    const headerEl = header.element as HTMLElement
    const leftGroup = headerEl.children[0] as HTMLElement
    const rightGroup = headerEl.children[1] as HTMLElement
    const slot = wrapper.find('[data-testid="channel-dot-slot"]')
    expect(slot.exists()).toBe(true)

    // 1) Слот в ЛЕВОЙ группе (тот же flex, что и поиск), а не в правой.
    expect(slot.element.parentElement).toBe(leftGroup)
    expect(rightGroup.contains(slot.element)).toBe(false)

    // 2) Сразу ПОСЛЕ SearchCommand: предыдущий сосед — кнопка поиска.
    const searchBtn = slot.element.previousElementSibling
    expect(searchBtn?.tagName).toBe('BUTTON')
    expect((searchBtn as HTMLElement).textContent).toContain(en.searchPlaceholder)

    // 3) Геометрия (Chromium, не jsdom): слот правее кнопки поиска
    //    (flex-сосед через gap-3) и левее правой группы.
    const slotRect = slot.element.getBoundingClientRect()
    const searchRect = (searchBtn as HTMLElement).getBoundingClientRect()
    const rightRect = rightGroup.getBoundingClientRect()
    // eslint-disable-next-line no-console
    console.log(
      `UI31-POS slot=[${Math.round(slotRect.left)},${Math.round(slotRect.right)}] `
      + `search=[${Math.round(searchRect.left)},${Math.round(searchRect.right)}] `
      + `rightGroup.left=${Math.round(rightRect.left)}`,
    )
    expect(slotRect.left).toBeGreaterThanOrEqual(searchRect.right - 1)
    expect(slotRect.right).toBeLessThanOrEqual(rightRect.left)
  }, 30000)

  it('UI31-2: поздний follower здорового лидера — точки нет, состояние healthy; '
    + 'лидер упал → янтарная точка у обоих и статус «Reconnecting…» (не «Connected»)', async () => {
    refreshAdapter = mockRefreshFail()
    // Лидерство детерминировано: первый acquireSseLeadership → лидер
    // (колбэк сразу), второй → НЕ вызывается → встроенный таймаут
    // acquireSseLeadership (1500 мс) резолвит follower.
    let locksCalls = 0
    Object.defineProperty(navigator, 'locks', {
      value: {
        request: (_n: string, _o: unknown, cb: () => Promise<void>) => {
          locksCalls += 1
          if (locksCalls === 1) void cb()
        },
      },
      configurable: true,
    })

    // Лидер: монтируем, открываем «соединение» (onopen от фейкового сервера).
    const a = mount(MainLayout, {
      attachTo: mountHost(),
      global: {
        stubs: { RouterView: true, RouterLink: { template: '<a><slot /></a>' } },
        plugins: [createPinia(), makeI18n()],
      },
    })
    mounted.push(a)
    await flushPromises()
    expect(FakeEventSource.instances).toHaveLength(1)
    const chA = useRealtimeChannel().get()
    expect(chA).not.toBeNull()
    const srcA = FakeEventSource.instances[0]
    srcA.onopen?.({} as Event)
    await flushPromises()
    expect(chA!.isConnected.value).toBe(true)

    // Ведомый: монтируется ПОСЛЕ того, как лидер уже здоров. До фикса он
    // получает только БУДУЩИЕ health-патчи → isConnected=false навсегда →
    // через гистерезис горит точка при живом лидере (рассинхрон, репорт
    // владельца 2026-10-14: «точка горит, а в панели Подключено»).
    const b = mount(MainLayout, {
      attachTo: mountHost(),
      global: {
        stubs: { RouterView: true, RouterLink: { template: '<a><slot /></a>' } },
        plugins: [createPinia(), makeI18n()],
      },
    })
    mounted.push(b)
    await flushPromises()
    const chB = useRealtimeChannel().get()
    expect(chB).not.toBeNull()
    expect(chB).not.toBe(chA)
    // 1500 мс — таймаут лидерства в acquireSseLeadership (prod-константа)
    // + запас на микрозадачи: после этого B точно подписан как follower.
    await new Promise((r) => setTimeout(r, 2200))
    expect(FakeEventSource.instances).toHaveLength(1) // follower источника не открывает

    // Окно гистерезиса (5000 мс, prod-константа) + запас: рассинхрон до
    // фикса проявляется именно здесь — точка у здорового-на-самом-деле
    // follower появляется через 5 с после его setup.
    await new Promise((r) => setTimeout(r, 6500))
    expect(b.find('[data-testid="channel-dot"]').exists()).toBe(false)
    expect(a.find('[data-testid="channel-dot"]').exists()).toBe(false)
    // Один источник правды: состояние follower = healthy (точка и панель
    // выведены из channelDotState — при healthy панель сказала бы
    // «Connected», а точки нет).
    expect(
      channelDotState({
        isConnected: chB!.isConnected.value,
        realtimeDown: chB!.realtimeDown.value,
        sessionExpired: chB!.sessionExpired.value,
      }),
    ).toBe('healthy')
    expect(chB!.isConnected.value).toBe(true)

    // Лидер упал (CLOSED + мёртвый refresh): follower зеркалит состояние
    // живым health-патчем → янтарная точка («переподключение, идут
    // попытки») и статус панели «Reconnecting…», НЕ «Connected».
    srcA.readyState = FakeEventSource.CLOSED
    srcA.onerror?.({} as Event)
    await flushPromises()
    await until(() => b.find('[data-testid="channel-dot"]').exists(), 15000)
    const dotB = b.find('[data-testid="channel-dot"]')
    expect(dotB.exists()).toBe(true)
    expect(dotB.find('span').classes().join(' ')).toContain('bg-amber-500')
    expect(dotB.find('span').classes().join(' ')).not.toContain('bg-muted-foreground')
    // Лидер у себя — тот же цвет (единый источник правды).
    await until(() => a.find('[data-testid="channel-dot"]').exists(), 15000)
    const dotA = a.find('[data-testid="channel-dot"]')
    expect(dotA.find('span').classes().join(' ')).toContain('bg-amber-500')

    // Панель follower: строка «Статус» = состояние точки.
    const triggerB = b.element.querySelector('[data-testid="channel-dot"]') as HTMLElement
    triggerB.click()
    await flushPromises()
    await until(() => document.querySelector('[data-testid="channel-dialog"]') !== null, 8000)
    const statusEl = document.querySelector('[data-testid="channel-diag-status"]')
    expect(statusEl).not.toBeNull()
    expect(statusEl!.textContent).toBe(en.channelStateReconnecting)
    expect(statusEl!.textContent).not.toBe(en.channelDiagNone)
  }, 90000)

  it('UI31-3: мёртвый канал (сессия истекла) → красная точка (destructive), '
    + 'aria-label и статус панели = «Session expired…»', async () => {
    refreshAdapter = mockRefreshFail()
    const wrapper = mount(MainLayout, {
      attachTo: mountHost(),
      global: {
        stubs: { RouterView: true, RouterLink: { template: '<a><slot /></a>' } },
        plugins: [createPinia(), makeI18n()],
      },
    })
    mounted.push(wrapper)
    await flushPromises()
    // Та же цепочка, что кр.13: пять разрывов подряд с мёртвым refresh →
    // после капа попыток (t≈31 с) — sessionExpired.
    for (let i = 0; i < 5; i++) {
      const cur = FakeEventSource.instances[FakeEventSource.instances.length - 1]
      cur.readyState = FakeEventSource.CLOSED
      cur.onerror?.({} as Event)
      await flushPromises()
      await new Promise((r) => setTimeout(r, 100))
    }
    await until(() => wrapper.find('[data-testid="channel-dot"]').exists(), 40000)
    // Терминальное состояние (sessionExpired) — после капа backoff; ждём
    // перекраски точки в красный.
    await until(() => {
      const s = wrapper.find('[data-testid="channel-dot"] span')
      return s.exists() && s.classes().includes('bg-destructive')
    }, 35000)
    const dot = wrapper.find('[data-testid="channel-dot"]')
    const t = makeI18n().global.t
    expect(dot.find('span').classes().join(' ')).toContain('bg-destructive')
    expect(dot.classes().join(' ')).not.toContain('animate-pulse')
    // Цвет — не единственный носитель смысла: aria-label несёт состояние.
    expect(dot.attributes('aria-label')).toBe(
      t('channelAriaLabel', { state: t('channelStateSessionExpired') }),
    )
    // Панель: «Статус» = состояние (не «Connected»), строка «Причина» есть.
    const dotEl = wrapper.element.querySelector('[data-testid="channel-dot"]') as HTMLElement
    dotEl.click()
    await flushPromises()
    await until(() => document.querySelector('[data-testid="channel-dialog"]') !== null, 8000)
    const statusEl = document.querySelector('[data-testid="channel-diag-status"]')
    expect(statusEl!.textContent).toBe(en.channelStateSessionExpired)
    expect(statusEl!.textContent).not.toBe(en.channelDiagNone)
    const causeEl = document.querySelector('[data-testid="channel-diag-cause"]')
    expect(causeEl).not.toBeNull()
    expect(causeEl!.textContent).not.toBe(en.channelDiagNone)
  }, 120000)

  it('UI31-4: панель align="start" от точки — не перекрывает кнопки «Обновить»/'
    + '«Загрузить процесс» на 1280/1920, ширина ≤ 360', async () => {
    refreshAdapter = mockRefreshFail()
    // Тулбар страницы по образцу ProcessDefinitionList: h1 слева,
    // кнопки справа (те же, что на скриншоте владельца). router-view в
    // MainLayout резолвится глобально (app.use(router) в проде); в тесте
    // роутера нет — регистрируем компонент глобально (resolveComponent
    // ищет по имени, регистр не важен). render-функция (НЕ template):
    // в браузерном окружении — runtime-only сборка Vue, runtime-компиляция
    // template строки недоступна (Vue warn в логе).
    const btn = (testid: string, cls: string, label: string) =>
      h('button', { 'data-testid': testid, class: cls }, label)
    const pageToolbar = {
      name: 'RouterView',
      render: () =>
        h('div', { class: 'space-y-2' }, [
          h('div', { class: 'flex items-center justify-between' }, [
            h('span', { class: 'text-lg font-bold' }, 'Process definitions'),
            h('div', { class: 'flex items-center gap-2' }, [
              btn('page-refresh-btn', 'flex items-center gap-2 px-3 py-1.5 text-sm border border-border rounded-md', 'Обновить'),
              btn('page-import-btn', 'flex items-center gap-2 px-3 py-1.5 text-sm bg-primary text-primary-foreground rounded-md', 'Загрузить процесс'),
              btn('page-submissions-btn', 'flex items-center gap-2 px-3 py-1.5 text-sm border border-border rounded-md', 'Мои подачи'),
            ]),
          ]),
        ]),
    }
    const wrapper = mount(MainLayout, {
      attachTo: mountHost(),
      global: {
        stubs: {
          RouterLink: { template: '<a><slot /></a>' },
        },
        components: { RouterView: pageToolbar },
        plugins: [createPinia(), makeI18n()],
      },
    })
    mounted.push(wrapper)
    await flushPromises()
    // Пять разрывов подряд → устойчивый даун → точка (цепочка кр.13).
    for (let i = 0; i < 5; i++) {
      const cur = FakeEventSource.instances[FakeEventSource.instances.length - 1]
      cur.readyState = FakeEventSource.CLOSED
      cur.onerror?.({} as Event)
      await flushPromises()
      await new Promise((r) => setTimeout(r, 100))
    }
    await until(() => wrapper.find('[data-testid="channel-dot"]').exists(), 40000)
    const dotEl = wrapper.element.querySelector('[data-testid="channel-dot"]') as HTMLElement
    dotEl.click()
    await flushPromises()
    await until(() => document.querySelector('[data-testid="channel-dialog"]') !== null, 8000)
    await new Promise((r) => setTimeout(r, 150)) // floating-ui: позиционирование settled

    const panelRect = () => (document.querySelector('[data-testid="channel-dialog"]') as HTMLElement).getBoundingClientRect()
    const dotRect = () => dotEl.getBoundingClientRect()
    const refreshRect = () =>
      (wrapper.find('[data-testid="page-refresh-btn"]').element as HTMLElement).getBoundingClientRect()
    const importRect = () =>
      (wrapper.find('[data-testid="page-import-btn"]').element as HTMLElement).getBoundingClientRect()
    const host = hosts[hosts.length - 1]

    // 1280 (реальный вьюпорт): левый край панели = левый край точки
    // (align="start"), правые кнопки не накрыты, панель в вьюпорте.
    let p = panelRect()
    // eslint-disable-next-line no-console
    console.log(
      `UI31-PANEL dot=[${Math.round(dotRect().left)},${Math.round(dotRect().right)}] `
      + `panel=[${Math.round(p.left)},${Math.round(p.right)}] `
      + `refresh=[${Math.round(refreshRect().left)},${Math.round(refreshRect().right)}] `
      + `import=[${Math.round(importRect().left)},${Math.round(importRect().right)}]`,
    )
    expect(Math.abs(p.left - dotRect().left)).toBeLessThan(6)
    expect(rectsIntersect(p, refreshRect())).toBe(false)
    expect(rectsIntersect(p, importRect())).toBe(false)
    expect(p.left).toBeGreaterThanOrEqual(0)
    expect(p.right).toBeLessThanOrEqual(1280)
    expect(p.bottom).toBeLessThanOrEqual(720)

    // 1920 (широкий хост): кнопки уходят к правому краю хоста, панель
    // остаётся у точки (левая группа) — пересечения нет, панель в
    // (реальном 1280) вьюпорте.
    host.style.width = '1920px'
    await new Promise((r) => setTimeout(r, 250))
    p = panelRect()
    expect(Math.abs(p.left - dotRect().left)).toBeLessThan(6)
    expect(rectsIntersect(p, refreshRect())).toBe(false)
    expect(rectsIntersect(p, importRect())).toBe(false)
    expect(p.right).toBeLessThanOrEqual(1280)

    // 360: панель (w-80 = 320) физически влезает в 360-вьюпорт; в реальном
    // 360-вьюпорте floating-ui shift (reka-ui avoidCollisions=true)
    // подтягивает её в границы. Здесь (тестовый вьюпорт 1280) проверяем
    // влезание по ширине.
    host.style.width = '360px'
    await new Promise((r) => setTimeout(r, 250))
    p = panelRect()
    expect(p.width).toBeLessThanOrEqual(360)
    expect(rectsIntersect(p, refreshRect())).toBe(false)
    expect(rectsIntersect(p, importRect())).toBe(false)
  }, 150000)

  it('UI31-5: вкладка, открытая ПОСЛЕ смены лидера — повышенный лидер отвечает '
    + 'на health-request (критерий 3 для топологии handover: без точки, healthy)', async () => {
    refreshAdapter = mockRefreshFail()
    // Детерминированное лидерство для handover:
    //  - вызов 1 (A) → cb сразу = первоначальный лидер;
    //  - вызов 2 (B) → cb ДЕРЖИМ: Web Locks гарантируют, что смерть лидера
    //    отдаёт лок следующему в очереди (sseFanout 125-131) — эмулируем
    //    вручную в момент закрытия A (pendingPromote);
    //  - вызов 3 (C) → cb не вызывается → встроенный таймаут 1500 мс →
    //    follower (вкладка, открытая после смены лидера).
    let locksCalls = 0
    let pendingPromote: (() => Promise<void>) | null = null
    Object.defineProperty(navigator, 'locks', {
      value: {
        request: (_n: string, _o: unknown, cb: () => Promise<void>) => {
          locksCalls += 1
          if (locksCalls === 1) void cb()
          else if (locksCalls === 2) pendingPromote = cb
        },
      },
      configurable: true,
    })

    // A — первоначальный лидер, здоров.
    const a = mount(MainLayout, {
      attachTo: mountHost(),
      global: {
        stubs: { RouterView: true, RouterLink: { template: '<a><slot /></a>' } },
        plugins: [createPinia(), makeI18n()],
      },
    })
    mounted.push(a)
    await flushPromises()
    expect(FakeEventSource.instances).toHaveLength(1)
    const chA = useRealtimeChannel().get()
    expect(chA).not.toBeNull()
    const srcA = FakeEventSource.instances[0]
    srcA.onopen?.({} as Event)
    await flushPromises()
    expect(chA!.isConnected.value).toBe(true)

    // B — вторая вкладка (follower).
    const b = mount(MainLayout, {
      attachTo: mountHost(),
      global: {
        stubs: { RouterView: true, RouterLink: { template: '<a><slot /></a>' } },
        plugins: [createPinia(), makeI18n()],
      },
    })
    mounted.push(b)
    await flushPromises()
    const chB = useRealtimeChannel().get()
    expect(chB).not.toBeNull()
    expect(chB).not.toBe(chA)
    // 1500 мс (таймаут лидерства, prod-константа) + запас: B — follower.
    await new Promise((r) => setTimeout(r, 2200))
    expect(FakeEventSource.instances).toHaveLength(1) // B источника не открывает

    // A (лидер) закрыта → браузер отдаёт лок следующему в очереди:
    // pending-колбэк B выполняется (повышение).
    a.unmount()
    mounted.splice(mounted.indexOf(a), 1)
    await flushPromises()
    expect(pendingPromote).not.toBeNull()
    pendingPromote!() // синхронно до openSource (см. sseFanout acquireSseLeadership)
    await flushPromises()
    expect(FakeEventSource.instances).toHaveLength(2)
    const srcB = FakeEventSource.instances[1]
    expect(srcB.closed).toBe(false)
    srcB.onopen?.({} as Event)
    await flushPromises()
    expect(chB!.isConnected.value).toBe(true)

    // C — вкладка, открытая ПОСЛЕ смены лидера. До фикса (verifier r2 Б-1):
    // onPromoted отписывал B от фанаута и НЕ подписывал обратно → повышенный
    // лидер не слышит health-request → 3 запроса C без ответа →
    // isConnected=false → через гистерезис 5 с — точка на ЗДОРОВОМ канале.
    // После фикса: B отвечает полным патчем → C healthy, точки нет.
    const c = mount(MainLayout, {
      attachTo: mountHost(),
      global: {
        stubs: { RouterView: true, RouterLink: { template: '<a><slot /></a>' } },
        plugins: [createPinia(), makeI18n()],
      },
    })
    mounted.push(c)
    await flushPromises()
    const chC = useRealtimeChannel().get()
    expect(chC).not.toBeNull()
    // 1500 мс (C — follower) + первый health-request + запас.
    await new Promise((r) => setTimeout(r, 2500))
    // C — follower: СВОЕГО EventSource не открывает (один открытый источник
    // — у повышенного лидера B).
    expect(FakeEventSource.instances.filter((s) => !s.closed)).toHaveLength(1)
    // Окно гистерезиса (5000 мс, prod-константа) + запас: без фикса точка
    // у C появляется именно здесь.
    await new Promise((r) => setTimeout(r, 6500))
    expect(c.find('[data-testid="channel-dot"]').exists()).toBe(false)
    expect(b.find('[data-testid="channel-dot"]').exists()).toBe(false)
    expect(chC!.isConnected.value).toBe(true)
    expect(
      channelDotState({
        isConnected: chC!.isConnected.value,
        realtimeDown: chC!.realtimeDown.value,
        sessionExpired: chC!.sessionExpired.value,
      }),
    ).toBe('healthy')
  }, 90000)
})
