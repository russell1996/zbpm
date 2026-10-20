/**
 * WO-UI-39 — доказательство в настоящем браузере (`npm run test:browser`):
 *  1) позиция прокрутки списка восстанавливается при «Назад»;
 *  2) фильтр списка переживает «Назад».
 *
 * jsdom не считает геометрию (scrollTop/scrollHeight всегда 0) — геометрию
 * можно проверить только в реальном Chromium.
 *
 * Структура повторяет прод: контейнер прокрутки — ВНУТРЕННИЙ (как
 * `<main class="flex-1 overflow-auto">` в MainLayout), он живёт ВНЕ
 * <router-view> и переживает навигацию; список внутри перемонтируется.
 */
import { describe, it, expect, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createRouter, createMemoryHistory, RouterView, type Router } from 'vue-router'
import { defineComponent, h, nextTick, onMounted, ref } from 'vue'
import { useListState } from './useListState'
import { useListScroll } from './useListScroll'

const ROWS = 60
/** Маркер карточки — заведомо НЕ встречается в тексте списка (иначе
 *  ожидание «карточка открылась» совпало бы с подписью кнопки). */
const DETAIL_MARKER = 'DETAIL-PAGE-MARKER'

let router: Router

/** Ключ списка у каждого теста — свой: хранилище позиций общее для модуля,
 *  общий ключ сделал бы тесты зависимыми друг от друга. */
let listKey = 'items'

const ListHarness = defineComponent({
  setup() {
    const { state } = useListState<{ key: string }>(listKey, { key: '' })
    const scroll = useListScroll(listKey)
    const rows = ref(0)
    async function load() {
      // Данные приходят асинхронно — как от бэкенда.
      await Promise.resolve()
      rows.value = ROWS
      await nextTick()
    }
    onMounted(async () => {
      await load()
      await scroll.restore()
    })
    return () =>
      h('div', [
        h('div', { 'data-testid': 'filter' }, `filter:${state.key}`),
        h(
          'button',
          {
            'data-testid': 'filter-btn',
            onClick: () => {
              state.key = 'vacation'
            },
          },
          'set-filter',
        ),
        h(
          'div',
          { 'data-testid': 'rows' },
          Array.from({ length: rows.value }, (_, i) => h('div', { key: i, style: 'height:40px' }, `row-${i}`)),
        ),
        h(
          'button',
          {
            'data-testid': 'to-detail',
            onClick: () => {
              void router.push('/items/42')
            },
          },
          'open-card',
        ),
      ])
  },
})

function makeRouter(): Router {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/items', component: ListHarness },
      {
        path: '/items/:id',
        component: defineComponent({ setup: () => () => h('div', DETAIL_MARKER) }),
      },
    ],
  })
  return router
}

/** Карточка ВЫСОКАЯ — иначе браузер сам обрежет scrollTop в 0 и проверка
 *  «карточка открылась с начала» прошла бы и без нашего сброса. */
function makeTallDetailRouter(): Router {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/items', component: ListHarness },
      {
        path: '/items/:id',
        component: defineComponent({
          setup: () => () =>
            h('div', [
              DETAIL_MARKER,
              ...Array.from({ length: 40 }, (_, i) => h('div', { key: i, style: 'height:40px' }, `detail-${i}`)),
            ]),
        }),
      },
    ],
  })
  return router
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

describe('WO-UI-39: список переживает «Назад» (реальный браузер)', () => {
  it('позиция прокрутки восстанавливается после list → detail → back', async () => {
    listKey = 'items-scroll'
    const r = makeRouter()
    await r.push('/items')
    await r.isReady()

    const wrapper = mount(AppHost, {
      attachTo: mountHost(),
      global: { plugins: [r] },
    })
    mounted.push(wrapper)
    await flushPromises()
    await until(() => wrapper.get('[data-testid="rows"]').element.childElementCount === ROWS)

    const container = document.querySelector<HTMLElement>('[data-scroll-container]')
    expect(container).not.toBeNull()
    // Контейнер прокручивается (высота фиксирована, содержимое выше)
    await until(() => container!.scrollHeight > container!.clientHeight)

    container!.scrollTop = 400
    await until(() => container!.scrollTop > 0)
    const savedTop = container!.scrollTop
    expect(savedTop).toBeGreaterThan(0)

    // Уход в карточку — список размонтируется, позиция запоминается
    await wrapper.get('[data-testid="to-detail"]').trigger('click')
    await until(() => wrapper.text().includes(DETAIL_MARKER), 8000, 'карточка открылась')
    expect(wrapper.find('[data-testid="rows"]').exists()).toBe(false)

    // Позиция ПОТЕРЯНА: контейнер стал коротким (одна строка карточки) —
    // браузер срезает scrollTop в ноль. Фиксируем это явным обнулением:
    // иначе проверка зависела бы от того, восстановит ли браузер позицию
    // сам (Chrome умеет при обратной вставке содержимого) — а доказать
    // должно НАШЕ восстановление, а не поведение браузера.
    container!.scrollTop = 0
    expect(container!.scrollTop).toBe(0)

    // «Назад» — список монтируется заново, позиция восстанавливается
    await r.back()
    await flushPromises()
    await until(() => wrapper.get('[data-testid="rows"]').element.childElementCount === ROWS)
    await until(() => container!.scrollTop !== 0, 3000, 'scrollTop после «Назад» (позиция восстановлена)')
    expect(container!.scrollTop).toBe(savedTop)
  })

  it('фильтр списка переживает «Назад»', async () => {
    listKey = 'items-filter'
    const r = makeRouter()
    await r.push('/items')
    await r.isReady()
    const wrapper = mount(AppHost, {
      attachTo: mountHost(),
      global: { plugins: [r] },
    })
    mounted.push(wrapper)
    await flushPromises()
    await until(() => wrapper.get('[data-testid="rows"]').element.childElementCount === ROWS)

    await wrapper.get('[data-testid="filter-btn"]').trigger('click')
    await until(() => r.currentRoute.value.query[`${listKey}.key`] === 'vacation')
    expect(wrapper.get('[data-testid="filter"]').text()).toBe('filter:vacation')

    await wrapper.get('[data-testid="to-detail"]').trigger('click')
    await until(() => wrapper.text().includes(DETAIL_MARKER), 8000, 'карточка открылась')
    expect(wrapper.find('[data-testid="filter"]').exists()).toBe(false)

    await r.back()
    await flushPromises()
    await until(() => wrapper.get('[data-testid="filter"]').text() === 'filter:vacation')
    expect(r.currentRoute.value.query[`${listKey}.key`]).toBe('vacation')
  })

  it('карточка открывается с начала, а не с середины списка', async () => {
    listKey = 'items-top'
    const r = makeTallDetailRouter()
    await r.push('/items')
    await r.isReady()
    const wrapper = mount(AppHost, {
      attachTo: mountHost(),
      global: { plugins: [r] },
    })
    mounted.push(wrapper)
    await flushPromises()
    await until(() => wrapper.get('[data-testid="rows"]').element.childElementCount === ROWS)

    const container = document.querySelector<HTMLElement>('[data-scroll-container]')
    expect(container).not.toBeNull()
    await until(() => container!.scrollHeight > container!.clientHeight)

    container!.scrollTop = 400
    await until(() => container!.scrollTop > 0)
    expect(container!.scrollTop).toBeGreaterThan(0)

    await wrapper.get('[data-testid="to-detail"]').trigger('click')
    await until(() => wrapper.text().includes(DETAIL_MARKER), 8000, 'карточка открылась')
    // Карточка ВЫСОКАЯ — контейнер не обрежет позицию сам, значит ноль
    // может получиться только если позицию сбросили мы (уход со списка).
    await until(() => container!.scrollHeight > container!.clientHeight)
    expect(container!.scrollTop).toBe(0)
  })
})
