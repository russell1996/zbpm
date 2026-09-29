/**
 * WO-UI-24 критерий 6 — браузерное доказательство (реальный Chromium,
 * `npm run test:browser`): пагинация живёт в URL.
 *
 * jsdom здесь не годится: критерий 3 — это навигация туда-обратно с
 * размонтированием/повторным монтированием страницы (navigation-класс,
 * см. P-53/P-54 про невозможные конфигурации в jsdom).
 *
 * Сценарий (одно живое приложение с настоящим <router-view>, как MainLayout
 * в проде: уход на деталь размонтирует список, back монтирует его заново при
 * живом роутере с пережившим URL): прямой заход на /items?page=3 → 3-я
 * страница сразу (крит. 1); клик next → ?page=4 в URL через replace, не push
 * (крит. 2, 4); переход на деталь + router.back() → список снова на 4-й
 * странице (крит. 3).
 */
import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createRouter, createMemoryHistory, RouterView, type Router } from 'vue-router'
import { defineComponent, h, nextTick } from 'vue'
import { usePagination } from './usePagination'

const ListHarness = defineComponent({
  setup() {
    const { page, nextPage } = usePagination(() => 55, 10)
    return () =>
      h('div', [
        h('div', { 'data-testid': 'page-label' }, `page ${page.value + 1}`),
        h('button', { 'data-testid': 'next', onClick: () => nextPage() }, 'next'),
      ])
  },
})

function makeRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/items', component: ListHarness },
      // render-функция, не template: в браузерной ESM-сборке vue нет
      // рантайм-компилятора шаблонов, навигация на template-стаб падает.
      { path: '/items/:id', component: defineComponent({ setup: () => () => h('div', 'detail') }) },
    ],
  })
}

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

let mounted: VueWrapper[] = []
let hosts: HTMLElement[] = []

afterEach(() => {
  for (const w of mounted) w.unmount()
  mounted = []
  for (const h of hosts) h.remove()
  hosts = []
  document.body.innerHTML = ''
})

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

describe('WO-UI-24 pagination survives list → detail → back (real browser)', () => {
  it('direct link → next → detail → back keeps the page', async () => {
    const router = makeRouter()
    await router.push('/items?page=3')
    await router.isReady()
    // Шпионы — ПОСЛЕ начального push: дальше меряем только действия пагинации.
    const replaceSpy = vi.spyOn(router, 'replace')
    const pushSpy = vi.spyOn(router, 'push')

    // Как настоящий MainLayout: страница живёт ТОЛЬКО внутри RouterView,
    // приложение и роутер живы весь тест — навигация размонтирует список и
    // монтирует его заново штатно. Состояние переживает это только через URL.
    // (Размонтировать приложение целиком через wrapper.unmount() НЕЛЬЗЯ:
    // router.install патчит app.unmount — последний unmount отписывает роутер
    // от history и сбрасывает currentRoute в START, после чего back() мёртв.
    // В проде приложение никогда не размонтируется, это артефакт харнесса.)
    const wrapper = mount(
      defineComponent({ setup: () => () => h(RouterView) }),
      { attachTo: mountHost(), global: { plugins: [router] } },
    )
    mounted.push(wrapper)
    await flushPromises()

    // Критерий 1: прямой заход на ?page=3 сразу показывает 3-ю страницу.
    await until(() => wrapper.text().includes('page 3'))
    expect(wrapper.get('[data-testid="page-label"]').element.getBoundingClientRect().height).toBeGreaterThan(0)

    // Критерии 2+4: клик next → ?page=4 через replace, push не вызван.
    await wrapper.get('[data-testid="next"]').trigger('click')
    await nextTick()
    await flushPromises()
    await until(() => router.currentRoute.value.query.page === '4')
    expect(wrapper.text()).toContain('page 4')
    expect(replaceSpy).toHaveBeenCalled()
    expect(pushSpy).not.toHaveBeenCalled()

    // Критерий 3: уход на деталь и возврат назад — страница НЕ сбрасывается.
    await router.push('/items/some-id')
    await flushPromises()
    await until(() => wrapper.text().includes('detail'))
    await router.back()
    await flushPromises()
    await until(() => wrapper.text().includes('page 4'))
    expect(router.currentRoute.value.query.page).toBe('4')
    expect(wrapper.get('[data-testid="page-label"]').element.getBoundingClientRect().height).toBeGreaterThan(0)
  })
})
