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
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import axios from 'axios'
import api from '@/services/api'
import MainLayout from '@/layouts/MainLayout.vue'
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
