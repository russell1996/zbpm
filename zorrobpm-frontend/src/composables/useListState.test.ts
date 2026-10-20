// @vitest-environment jsdom
/**
 * WO-UI-39 — композабл `useListState`: состояние списка живёт в query-параметрах
 * маршрута, по одному пространству имён на список.
 *
 * Тесты бьют по НАСТОЯЩЕМУ композаблу с НАСТОЯЩИМ роутером (createRouter +
 * createMemoryHistory) — не по моку useRoute/useRouter: иначе проверка не
 * может упасть по продуктовой причине (см. V11 / P-17).
 */
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest'
import { defineComponent, h, nextTick } from 'vue'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import { useListState } from './useListState'

type Filters = {
  key: string
  completed: boolean
  type: 'ALL' | 'HUMAN' | 'SYSTEM'
  count: number
}

let exposed: ReturnType<typeof useListState<Filters>> | null = null

const Harness = defineComponent({
  setup() {
    exposed = useListState<Filters>(
      'instances',
      {
        key: '',
        completed: false,
        type: { default: 'ALL', values: ['ALL', 'HUMAN', 'SYSTEM'] },
        count: 0,
      },
      { pageSize: 10, getTotalElements: () => 55 },
    )
    return () => h('div')
  },
})

let wrappers: VueWrapper[] = []
afterEach(() => {
  for (const w of wrappers) w.unmount()
  wrappers = []
  exposed = null
})

async function mountOnUrl(url: string): Promise<Router> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/items', component: Harness },
      { path: '/items/:id', component: { template: '<div />' } },
    ],
  })
  await router.push(url)
  await router.isReady()
  wrappers.push(mount(Harness, { global: { plugins: [router] } }))
  await flushPromises()
  return router
}

describe('useListState (WO-UI-39)', () => {
  it('CRIT-2: значения по умолчанию НЕ попадают в URL (чистый URL)', async () => {
    const router = await mountOnUrl('/items')
    await nextTick()
    await flushPromises()
    expect(router.currentRoute.value.query).toEqual({})
    expect(exposed!.state.key).toBe('')
    expect(exposed!.state.completed).toBe(false)
    expect(exposed!.state.type).toBe('ALL')
    expect(exposed!.state.count).toBe(0)
    expect(exposed!.page.value).toBe(0)
  })

  it('CRIT-1: изменённое состояние пишется в URL (параметры пространства списка)', async () => {
    const router = await mountOnUrl('/items')
    exposed!.state.key = 'vacation'
    exposed!.state.completed = true
    exposed!.state.type = 'HUMAN'
    await nextTick()
    await flushPromises()
    expect(router.currentRoute.value.query['instances.key']).toBe('vacation')
    expect(router.currentRoute.value.query['instances.completed']).toBe('1')
    expect(router.currentRoute.value.query['instances.type']).toBe('HUMAN')
  })

  it('CRIT-1: состояние читается из URL при монтировании (прямая ссылка)', async () => {
    await mountOnUrl('/items?instances.key=vacation&instances.completed=1&instances.type=HUMAN&instances.count=7')
    expect(exposed!.state.key).toBe('vacation')
    expect(exposed!.state.completed).toBe(true)
    expect(exposed!.state.type).toBe('HUMAN')
    expect(exposed!.state.count).toBe(7)
  })

  it('CRIT-3: битый параметр молча отбрасывается к дефолту (enum/boolean/number/page)', async () => {
    for (const bad of ['BOGUS', 'human', 'ALL%20']) {
      const router = await mountOnUrl(`/items?instances.type=${bad}`)
      await nextTick()
      await flushPromises()
      expect(exposed!.state.type).toBe('ALL')
      // URL нормализуется — мусор не остаётся в адресе
      expect(router.currentRoute.value.query['instances.type']).toBeUndefined()
      for (const w of wrappers) w.unmount()
      wrappers = []
      exposed = null
    }

    const router2 = await mountOnUrl('/items?instances.completed=maybe')
    await nextTick()
    await flushPromises()
    expect(exposed!.state.completed).toBe(false)
    expect(router2.currentRoute.value.query['instances.completed']).toBeUndefined()

    const router3 = await mountOnUrl('/items?instances.count=abc')
    await nextTick()
    await flushPromises()
    expect(exposed!.state.count).toBe(0)

    const router4 = await mountOnUrl('/items?instances.page=abc')
    await nextTick()
    await flushPromises()
    expect(exposed!.page.value).toBe(0)
    expect(router4.currentRoute.value.query['instances.page']).toBeUndefined()
  })

  it('CRIT-3: повторяющийся параметр (?a=1&a=2) — тоже «битый», отбрасывается к дефолту', async () => {
    const router = await mountOnUrl('/items?instances.key=a&instances.key=b')
    await nextTick()
    await flushPromises()
    expect(exposed!.state.key).toBe('')
    expect(router.currentRoute.value.query['instances.key']).toBeUndefined()
  })

  it('CRIT-3: страница — 1-based в URL, 0-based внутри; сброс убирает параметр', async () => {
    const router = await mountOnUrl('/items?instances.page=3')
    expect(exposed!.page.value).toBe(2)
    exposed!.resetPage()
    await nextTick()
    await flushPromises()
    expect(exposed!.page.value).toBe(0)
    expect(router.currentRoute.value.query['instances.page']).toBeUndefined()
  })

  it('CRIT-4: роутер использует replace (история не растёт), push не вызывается', async () => {
    const router = await mountOnUrl('/items')
    const replaceSpy = vi.spyOn(router, 'replace')
    const pushSpy = vi.spyOn(router, 'push')
    exposed!.state.key = 'vacation'
    await nextTick()
    await flushPromises()
    exposed!.state.key = 'onboarding'
    await nextTick()
    await flushPromises()
    expect(replaceSpy).toHaveBeenCalled()
    expect(pushSpy).not.toHaveBeenCalled()
    expect(router.currentRoute.value.query['instances.key']).toBe('onboarding')
  })

  it('CRIT-1: чужие query-параметры сохраняются', async () => {
    const router = await mountOnUrl('/items?tab=variables&other=1')
    exposed!.state.key = 'vacation'
    await nextTick()
    await flushPromises()
    expect(router.currentRoute.value.query.tab).toBe('variables')
    expect(router.currentRoute.value.query.other).toBe('1')
    expect(router.currentRoute.value.query['instances.key']).toBe('vacation')
  })

  it('CRIT-1: внешнее изменение query (кнопка «Назад») двигает состояние', async () => {
    const router = await mountOnUrl('/items')
    await router.replace({ query: { 'instances.key': 'vacation', 'instances.page': '2' } })
    await nextTick()
    await flushPromises()
    expect(exposed!.state.key).toBe('vacation')
    expect(exposed!.page.value).toBe(1)
  })

  it('CRIT-4: состояние одного списка не протекает в другой (разные ключи)', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/items', component: Harness }],
    })
    await router.push('/items?instances.key=vacation&tasks.key=onboarding')
    await router.isReady()
    wrappers.push(mount(Harness, { global: { plugins: [router] } }))
    await flushPromises()
    // Наш список читает только свои параметры — чужой ключ игнорируется
    expect(exposed!.state.key).toBe('vacation')
    await nextTick()
    await flushPromises()
    // И не затирает чужой параметр при записи своего состояния
    expect(router.currentRoute.value.query['tasks.key']).toBe('onboarding')
  })

  it('hasNext/hasPrev считаются по тоталу, nextPage уважает границу', async () => {
    await mountOnUrl('/items')
    expect(exposed!.hasNext.value).toBe(true)
    expect(exposed!.hasPrev.value).toBe(false)
    exposed!.nextPage()
    expect(exposed!.page.value).toBe(1)
    expect(exposed!.hasPrev.value).toBe(true)
    exposed!.prevPage()
    expect(exposed!.page.value).toBe(0)
  })

  it('без роутера (обычный юнит-тест) состояние остаётся локальным и не падает', () => {
    const local = useListState<{ key: string }>('local', { key: '' })
    expect(local.state.key).toBe('')
    local.state.key = 'x'
    expect(local.state.key).toBe('x')
  })
})

/**
 * WO-UI-41 — сброс фильтров и память списка о последнем query.
 */
describe('useListState: reset() и isDefault (WO-UI-41)', () => {
  beforeEach(() => {
    sessionStorage.clear()
  })

  it('isDefault: дефолтное состояние — true; любое отличие — false', async () => {
    await mountOnUrl('/items')
    expect(exposed!.isDefault.value).toBe(true)

    exposed!.state.key = 'vacation'
    await nextTick()
    await flushPromises()
    expect(exposed!.isDefault.value).toBe(false)

    exposed!.reset()
    await nextTick()
    await flushPromises()
    expect(exposed!.isDefault.value).toBe(true)
  })

  it('isDefault: вторая страница — тоже «не дефолт» (сброс возвращает на первую)', async () => {
    await mountOnUrl('/items?instances.page=3')
    expect(exposed!.page.value).toBe(2)
    expect(exposed!.isDefault.value).toBe(false)
    exposed!.reset()
    await nextTick()
    await flushPromises()
    expect(exposed!.page.value).toBe(0)
    expect(exposed!.isDefault.value).toBe(true)
  })

  it('reset(): все поля к дефолту, страница 1, query списка очищен', async () => {
    const router = await mountOnUrl('/items?instances.key=vacation&instances.completed=1&instances.type=HUMAN&instances.page=3')
    expect(exposed!.state.key).toBe('vacation')
    expect(exposed!.page.value).toBe(2)

    exposed!.reset()
    await nextTick()
    await flushPromises()

    expect(exposed!.state.key).toBe('')
    expect(exposed!.state.completed).toBe(false)
    expect(exposed!.state.type).toBe('ALL')
    expect(exposed!.page.value).toBe(0)
    expect(router.currentRoute.value.query['instances.key']).toBeUndefined()
    expect(router.currentRoute.value.query['instances.completed']).toBeUndefined()
    expect(router.currentRoute.value.query['instances.type']).toBeUndefined()
    expect(router.currentRoute.value.query['instances.page']).toBeUndefined()
  })

  it('reset(): чужие query-параметры не трогаются', async () => {
    const router = await mountOnUrl('/items?tab=variables&instances.key=vacation')
    exposed!.reset()
    await nextTick()
    await flushPromises()
    expect(router.currentRoute.value.query.tab).toBe('variables')
    expect(router.currentRoute.value.query['instances.key']).toBeUndefined()
  })

  it('список помнит свой последний query (sessionStorage) — по нему стрелка «назад» вернёт фильтры', async () => {
    const router = await mountOnUrl('/items')
    exposed!.state.key = 'vacation'
    exposed!.state.completed = true
    await nextTick()
    await flushPromises()

    const saved = JSON.parse(sessionStorage.getItem('zbpm.listState.instances') ?? '{}')
    expect(saved).toEqual({ 'instances.key': 'vacation', 'instances.completed': '1' })
  })

  it('после reset() список помнит ПУСТОЕ состояние (стрелка не вернёт старый фильтр)', async () => {
    await mountOnUrl('/items?instances.key=vacation')
    exposed!.reset()
    await nextTick()
    await flushPromises()
    const saved = JSON.parse(sessionStorage.getItem('zbpm.listState.instances') ?? '{}')
    expect(saved).toEqual({})
  })

  it('query приходит извне (кнопка «Назад» браузера) — список запоминает и его', async () => {
    const router = await mountOnUrl('/items')
    await router.replace({ query: { 'instances.key': 'onboarding' } })
    await nextTick()
    await flushPromises()
    const saved = JSON.parse(sessionStorage.getItem('zbpm.listState.instances') ?? '{}')
    expect(saved).toEqual({ 'instances.key': 'onboarding' })
  })

  it('уход в карточку НЕ затирает память списка (query карточки — не наш)', async () => {
    const router = await mountOnUrl('/items?instances.key=vacation')
    expect(JSON.parse(sessionStorage.getItem('zbpm.listState.instances') ?? '{}')).toEqual({
      'instances.key': 'vacation',
    })
    // Уход в карточку: адрес меняется РАНЬШЕ, чем список размонтируется —
    // адрес карточки не должен затирать память списка (иначе стрелка «назад»
    // потеряла бы фильтры).
    await router.push('/items/42')
    await nextTick()
    await flushPromises()
    expect(JSON.parse(sessionStorage.getItem('zbpm.listState.instances') ?? '{}')).toEqual({
      'instances.key': 'vacation',
    })
  })
})
