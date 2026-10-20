// @vitest-environment jsdom
/**
 * WO-UI-41 — композабл `useListBack`: стрелка «назад» из карточки.
 *
 * Две ветки поведения:
 *  1) предыдущая запись истории — ЭТОТ список → возврат по истории
 *     (`router.back()`): пользователь возвращается ровно туда, откуда пришёл,
 *     вместе с фильтрами и позицией прокрутки;
 *  2) предыдущей записи списка нет (карточку открыли по прямой ссылке) →
 *     «push» списка с ПОСЛЕДНИМ СОХРАНЁННЫМ query (`useListState` помнит его
 *     в sessionStorage) — фильтры не теряются и при таком входе.
 *
 * Тест бьёт по НАСТОЯЩЕМУ композаблу с НАСТОЯЩИМ роутером (createRouter +
 * createWebHistory — только у web-истории есть `state.back`, предыдущая
 * запись), а не по моку useRouter: иначе проверка не может упасть по
 * продуктовой причине (V11 / P-17).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createRouter, createWebHistory, type Router } from 'vue-router'
import { useListBack } from './useListBack'

let exposed: ReturnType<typeof useListBack> | null = null
let wrappers: VueWrapper[] = []

const Harness = defineComponent({
  setup() {
    exposed = useListBack('items', 'items')
    return () => h('div')
  },
})

async function makeRouter(): Promise<Router> {
  const router = createRouter({
    history: createWebHistory('/'),
    routes: [
      { path: '/items', name: 'items', component: { template: '<div/>' } },
      { path: '/items/:id', name: 'item', component: { template: '<div/>' } },
      { path: '/other', name: 'other', component: { template: '<div/>' } },
    ],
  })
  await router.push('/items')
  await router.isReady()
  return router
}

/** Перемонтировать композабл (он читает сохранённый query при создании). */
async function mountHarness(router: Router): Promise<void> {
  wrappers.push(mount(Harness, { global: { plugins: [router] } }))
  await flushPromises()
}

beforeEach(() => {
  sessionStorage.clear()
  vi.clearAllMocks()
})

afterEach(() => {
  for (const w of wrappers) w.unmount()
  wrappers = []
  exposed = null
})

describe('useListBack (WO-UI-41)', () => {
  it('предыдущая запись — этот список: стрелка идёт ПО ИСТОРИИ (router.back)', async () => {
    const router = await makeRouter()
    await mountHarness(router)
    await router.push('/items/42')
    await flushPromises()

    expect(exposed!.canGoBack(), 'предыдущая запись — список').toBe(true)
    const backSpy = vi.spyOn(router, 'back')
    const pushSpy = vi.spyOn(router, 'push')
    exposed!.back()
    expect(backSpy).toHaveBeenCalledTimes(1)
    expect(pushSpy, 'push не вызывается — идём по истории').not.toHaveBeenCalled()
  })

  it('предыдущей записи списка нет: стрелка делает push списка с сохранённым query', async () => {
    const router = await makeRouter()
    await mountHarness(router)
    // Карточку открыли не из списка — истории «список → карточка» нет.
    await router.push('/other')
    await router.push('/items/42')
    await flushPromises()

    // Список помнит свой последний query (его записал useListState).
    sessionStorage.setItem('zbpm.listState.items', JSON.stringify({ 'items.key': 'vacation' }))
    for (const w of wrappers) w.unmount()
    wrappers = []
    exposed = null
    await mountHarness(router)

    expect(exposed!.canGoBack(), 'предыдущая запись — не список').toBe(false)
    const backSpy = vi.spyOn(router, 'back')
    const pushSpy = vi.spyOn(router, 'push')
    exposed!.back()
    expect(backSpy).not.toHaveBeenCalled()
    expect(pushSpy).toHaveBeenCalledTimes(1)
    expect(pushSpy.mock.calls[0][0]).toEqual({ name: 'items', query: { 'items.key': 'vacation' } })
  })

  it('href ссылки содержит сохранённые фильтры списка', async () => {
    const router = await makeRouter()
    sessionStorage.setItem('zbpm.listState.items', JSON.stringify({ 'items.key': 'vacation', 'items.page': '2' }))
    await mountHarness(router)
    expect(exposed!.href.value).toBe('/items?items.key=vacation&items.page=2')
    expect(router.currentRoute.value.path).toBe('/items')
  })

  it('без сохранённого состояния href — просто адрес списка', async () => {
    const router = await makeRouter()
    await mountHarness(router)
    expect(exposed!.href.value).toBe('/items')
  })
})
