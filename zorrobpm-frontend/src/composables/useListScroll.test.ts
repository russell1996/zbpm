// @vitest-environment jsdom
/**
 * WO-UI-39 п.2 — композабл useListScroll: контракт сохранения/восстановления
 * позиции прокрутки (jsdom не считает геометрию — геометрия доказывается в
 * реальном браузере, useListScroll.browser.test.ts).
 */
import { describe, it, expect, afterEach } from 'vitest'
import { defineComponent, h, nextTick, onMounted } from 'vue'
import { mount } from '@vue/test-utils'
import { useListScroll } from './useListScroll'

function makeContainer(): HTMLElement {
  const el = document.createElement('div')
  el.setAttribute('data-scroll-container', '')
  // jsdom не считает геометрию — подменяем scrollTop на обычное свойство
  Object.defineProperty(el, 'scrollTop', { value: 0, writable: true })
  document.body.appendChild(el)
  return el
}

let wrappers: ReturnType<typeof mount>[] = []
afterEach(() => {
  for (const w of wrappers) w.unmount()
  wrappers = []
  document.body.innerHTML = ''
})

describe('useListScroll', () => {
  it('сохраняет и восстанавливает позицию контейнера', async () => {
    const container = makeContainer()
    let scroll: ReturnType<typeof useListScroll> | null = null

    const Harness = defineComponent({
      setup() {
        scroll = useListScroll('items')
        return () => h('div')
      },
    })
    wrappers.push(mount(Harness))
    await nextTick()

    container.scrollTop = 250
    scroll!.save()
    container.scrollTop = 0
    await scroll!.restore()
    expect(container.scrollTop).toBe(250)
  })

  it('восстанавливает позицию при размонтировании/монтировании списка (живой путь)', async () => {
    const container = makeContainer()
    const Harness = defineComponent({
      setup() {
        const scroll = useListScroll('items')
        onMounted(async () => {
          await scroll.restore()
        })
        return () => h('div')
      },
    })
    wrappers.push(mount(Harness))
    await nextTick()

    container.scrollTop = 320
    // Уход в карточку размонтирует список — позиция запоминается
    wrappers[wrappers.length - 1].unmount()
    wrappers = []
    container.scrollTop = 0

    wrappers.push(mount(Harness))
    await nextTick()
    await nextTick()
    expect(container.scrollTop).toBe(320)
  })

  it('состояние одного списка не влияет на другой', async () => {
    const container = makeContainer()
    let first: ReturnType<typeof useListScroll> | null = null
    let second: ReturnType<typeof useListScroll> | null = null
    const Harness = defineComponent({
      setup() {
        first = useListScroll('first')
        second = useListScroll('second')
        return () => h('div')
      },
    })
    wrappers.push(mount(Harness))
    await nextTick()

    container.scrollTop = 100
    first!.save()
    container.scrollTop = 0
    await second!.restore()
    expect(container.scrollTop).toBe(0)

    await first!.restore()
    expect(container.scrollTop).toBe(100)
  })

  it('без контейнера прокрутки не падает (список вне MainLayout)', async () => {
    const Harness = defineComponent({
      setup() {
        const scroll = useListScroll('no-container')
        return () => h('div')
      },
    })
    wrappers.push(mount(Harness))
    await nextTick()
    // Ничего не падает, restore без сохранённой позиции — no-op
    const el = document.querySelector('[data-scroll-container]')
    expect(el).toBeNull()
  })
})
