/**
 * WO-UI-39 п.2 — регресс-тест на НАСТОЯЩЕЙ странице списка (реальный Chromium).
 *
 * Зачем он нужен: `useListScroll.browser.test.ts` проверяет композабл через
 * синтетическую ListHarness. Этого НЕ хватало: мутация «убрать
 * `await scroll.restore()` из ProcessInstanceList.vue» оставляла весь набор
 * зелёным — проводка «страница → restore()» не была защищена (риск P-11/G-N:
 * символ в файле ≠ работает). Здесь монтируется РЕАЛЬНАЯ страница
 * `ProcessInstanceList` в контейнер прокрутки, как в MainLayout, и позиция
 * проверяется после list → detail → back.
 *
 * Геометрия считается только здесь: jsdom не считает scrollTop/scrollHeight.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory, RouterView, type Router } from 'vue-router'
import { defineComponent, h } from 'vue'
import ProcessInstanceList from './ProcessInstanceList.vue'
import ru from '@/locales/ru.json'
import en from '@/locales/en.json'
import kz from '@/locales/kz.json'

import '@/style.css'

vi.mock('@/stores/process', async () => {
  const { reactive } = await import('vue')
  const store = reactive({
    instances: null as { data: unknown[]; totalElements: number } | null,
    definitions: { data: [], totalElements: 0 } as { data: unknown[]; totalElements: number },
    loading: false,
    error: null as string | null,
    fetchInstances: vi.fn(),
    fetchDefinitions: vi.fn(),
  })
  return { useProcessStore: () => store }
})
vi.mock('@/composables/useDateFormat', () => ({
  useDateFormat: () => ({ formatDate: (v: string) => v, formatDateTime: (v: string) => v }),
}))
vi.mock('@/shared/lib/export', () => ({ exportToCsv: vi.fn() }))

import { useProcessStore } from '@/stores/process'

const store = useProcessStore() as unknown as {
  instances: { data: unknown[]; totalElements: number } | null
  definitions: { data: unknown[]; totalElements: number }
  fetchInstances: ReturnType<typeof vi.fn>
  fetchDefinitions: ReturnType<typeof vi.fn>
}

/** Столько строк, чтобы список ПРОКРУЧИВАЛСЯ в контейнере 300px. */
const ROWS = 40

function makeInstances() {
  return Array.from({ length: ROWS }, (_, i) => ({
    id: `pi-${i}`,
    processKey: 'vacation',
    processName: `Vacation ${i}`,
    processVersion: 1,
    status: 'ACTIVE',
    startedAt: '2026-01-01T10:00:00Z',
    completedAt: null,
  }))
}

const i18n = createI18n({ legacy: false, locale: 'ru', messages: { ru, en, kz } })

function makeRouter(): Router {
  return createRouter({
    history: createMemoryHistory('/ui/'),
    routes: [
      {
        path: '/',
        // render-функция, а не template: в browser-режиме сборка Vue — runtime-only
        // (runtime compilation не поддерживается), template-компонент не отрисуется.
        component: defineComponent({ setup: () => () => h(RouterView) }),
        children: [
          { path: 'processes/instances', name: 'process-instances', component: ProcessInstanceList },
          {
            path: 'processes/instances/:id',
            name: 'process-instance-detail',
            component: defineComponent({
              setup: () => () => h('div', { 'data-testid': 'detail' }, 'detail'),
            }),
          },
        ],
      },
    ],
  })
}

/** Хост приложения: контейнер прокрутки живёт ВНЕ router-view (как MainLayout). */
const AppHost = defineComponent({
  setup: () => () =>
    h(
      'div',
      { 'data-scroll-container': '', style: 'height:300px; overflow:auto' },
      h(RouterView),
    ),
})

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

function until(cond: () => boolean, timeout = 8000, what = 'condition'): Promise<void> {
  return new Promise((resolve, reject) => {
    const start = Date.now()
    const tick = () => {
      if (cond()) return resolve()
      if (Date.now() - start > timeout) {
        return reject(new Error(`timeout waiting for ${what}`))
      }
      requestAnimationFrame(tick)
    }
    tick()
  })
}

describe('WO-UI-39: реальная страница списка помнит позицию прокрутки', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    store.instances = null
    store.definitions = { data: [], totalElements: 0 }
    store.fetchInstances.mockImplementation(async () => {
      store.instances = { data: makeInstances(), totalElements: ROWS }
      return store.instances
    })
    store.fetchDefinitions.mockImplementation(async () => {
      store.definitions = { data: [], totalElements: 0 }
      return store.definitions
    })
  })

  afterEach(() => {
    for (const w of mounted) w.unmount()
    mounted = []
    for (const h of hosts) h.remove()
    hosts = []
    document.body.innerHTML = ''
  })

  it('позиция прокрутки восстанавливается после list → detail → back', async () => {
    const router = makeRouter()
    await router.push('/processes/instances')
    await router.isReady()

    const wrapper = mount(AppHost, {
      attachTo: mountHost(),
      global: { plugins: [router, createPinia(), i18n] },
    })
    mounted.push(wrapper)
    await flushPromises()
    await until(() => wrapper.findAll('tbody tr').length === ROWS, 8000, 'список отрисован')

    const container = document.querySelector<HTMLElement>('[data-scroll-container]')
    expect(container).not.toBeNull()
    await until(() => container!.scrollHeight > container!.clientHeight, 8000, 'контейнер прокручивается')

    container!.scrollTop = 400
    await until(() => container!.scrollTop > 0)
    const savedTop = container!.scrollTop
    expect(savedTop).toBeGreaterThan(0)

    // Уход в карточку — список размонтируется, позиция запоминается
    await wrapper.find('tbody tr').trigger('click')
    await until(() => wrapper.find('[data-testid="detail"]').exists(), 8000, 'карточка открылась')
    expect(wrapper.find('tbody tr').exists()).toBe(false)

    // Позиция ПОТЕРЯНА: контейнер стал коротким — браузер срезает scrollTop в
    // ноль. Обнуляем явно, чтобы проверялось НАШЕ восстановление, а не
    // поведение браузера.
    container!.scrollTop = 0
    expect(container!.scrollTop).toBe(0)

    // «Назад» — список монтируется заново, позиция восстанавливается
    await router.back()
    await flushPromises()
    await until(() => wrapper.findAll('tbody tr').length === ROWS, 8000, 'список после «Назад»')
    await until(() => container!.scrollTop !== 0, 3000, 'scrollTop после «Назад» (позиция восстановлена)')
    expect(container!.scrollTop).toBe(savedTop)
  })
})
