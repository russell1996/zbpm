// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest'
import { defineComponent, h, nextTick } from 'vue'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import { usePagination } from './usePagination'

describe('usePagination', () => {
  it('starts at page 0 with hasNext=true when total > pageSize', () => {
    const { page, pageSize, hasNext, hasPrev } = usePagination(() => 25, 10)
    expect(page.value).toBe(0)
    expect(pageSize).toBe(10)
    expect(hasNext.value).toBe(true)
    expect(hasPrev.value).toBe(false)
  })

  it('nextPage increments page', () => {
    const { page, nextPage } = usePagination(() => 25, 10)
    nextPage()
    expect(page.value).toBe(1)
  })

  it('prevPage decrements page', () => {
    const { page, prevPage } = usePagination(() => 25, 10)
    page.value = 2
    prevPage()
    expect(page.value).toBe(1)
  })

  it('prevPage is no-op at page 0', () => {
    const { page, prevPage } = usePagination(() => 25, 10)
    prevPage()
    expect(page.value).toBe(0)
  })

  it('nextPage is no-op at last page', () => {
    const { page, nextPage } = usePagination(() => 25, 10)
    page.value = 2 // (2+1)*10 = 30 >= 25 → last page
    nextPage()
    expect(page.value).toBe(2)
  })

  it('hasNext is false on last page', () => {
    const { page, hasNext } = usePagination(() => 25, 10)
    page.value = 2
    expect(hasNext.value).toBe(false)
  })

  it('hasPrev is true after first page', () => {
    const { page, hasPrev } = usePagination(() => 25, 10)
    page.value = 1
    expect(hasPrev.value).toBe(true)
  })

  it('resetPage sets page back to 0', () => {
    const { page, resetPage } = usePagination(() => 25, 10)
    page.value = 3
    resetPage()
    expect(page.value).toBe(0)
  })

  it('handles undefined totalElements (loading state)', () => {
    const { hasNext, hasPrev } = usePagination(() => undefined, 10)
    expect(hasNext.value).toBe(false)
    expect(hasPrev.value).toBe(false)
  })
})

// WO-UI-24: URL-синхронизация — с РЕАЛЬНЫМ vue-router
// (createRouter/createMemoryHistory, не мок useRoute), по требованию WO п.6.
let exposed: ReturnType<typeof usePagination> | null = null
const RouterHarness = defineComponent({
  setup() {
    exposed = usePagination(() => 25, 10)
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
      { path: '/items', component: RouterHarness },
      { path: '/items/:id', component: { template: '<div />' } },
    ],
  })
  await router.push(url)
  await router.isReady()
  wrappers.push(mount(RouterHarness, { global: { plugins: [router] } }))
  await flushPromises()
  return router
}

describe('usePagination URL sync (WO-UI-24)', () => {
  it('criterion 1: direct entry with ?page=3 opens page index 2', async () => {
    await mountOnUrl('/items?page=3')
    expect(exposed).not.toBeNull()
    expect(exposed!.page.value).toBe(2)
  })

  it('criterion 1: no ?page param opens page 0 without touching the URL', async () => {
    const router = await mountOnUrl('/items')
    expect(exposed!.page.value).toBe(0)
    // clean-URL уже каноничен (первая страница = отсутствие параметра) —
    // нормализация при монтировании ничего не пишет
    expect(router.currentRoute.value.query.page).toBeUndefined()
  })

  it('criterion 2: nextPage writes ?page to the URL (1-based)', async () => {
    const router = await mountOnUrl('/items')
    exposed!.nextPage()
    await nextTick()
    await flushPromises()
    expect(exposed!.page.value).toBe(1)
    expect(router.currentRoute.value.query.page).toBe('2')
  })

  it('criterion 4: pagination uses router.replace, never push', async () => {
    const router = await mountOnUrl('/items')
    const replaceSpy = vi.spyOn(router, 'replace')
    const pushSpy = vi.spyOn(router, 'push')
    exposed!.nextPage()
    await nextTick()
    await flushPromises()
    exposed!.nextPage()
    await nextTick()
    await flushPromises()
    expect(replaceSpy).toHaveBeenCalled()
    expect(pushSpy).not.toHaveBeenCalled()
    expect(router.currentRoute.value.query.page).toBe('3')
  })

  it('criterion 5 (task): resetPage clears the query param', async () => {
    const router = await mountOnUrl('/items?page=3')
    expect(exposed!.page.value).toBe(2)
    exposed!.resetPage()
    await nextTick()
    await flushPromises()
    expect(exposed!.page.value).toBe(0)
    expect(router.currentRoute.value.query.page).toBeUndefined()
  })

  it('invalid ?page values fall back to 0 and the URL is normalized', async () => {
    for (const bad of ['abc', '0', '-3', '2.5', '']) {
      const router = await mountOnUrl(`/items?page=${bad}`)
      await nextTick()
      await flushPromises()
      expect(exposed!.page.value).toBe(0)
      expect(router.currentRoute.value.query.page).toBeUndefined()
      for (const w of wrappers) w.unmount()
      wrappers = []
      exposed = null
    }
  })

  it('external query change (browser back/forward) moves the state', async () => {
    const router = await mountOnUrl('/items')
    await router.replace({ query: { page: '3' } })
    await nextTick()
    await flushPromises()
    expect(exposed!.page.value).toBe(2)
  })

  it('other query params are preserved when paginating', async () => {
    const router = await mountOnUrl('/items?filter=active')
    exposed!.nextPage()
    await nextTick()
    await flushPromises()
    expect(router.currentRoute.value.query.filter).toBe('active')
    expect(router.currentRoute.value.query.page).toBe('2')
  })
})
