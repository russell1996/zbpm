// @vitest-environment jsdom
/**
 * WO-UI-41 — `useListBack` при РАЗНЫХ формах роутера.
 *
 * Три независимых случая, которые обязаны не ронять страницу и не ломать
 * определение «предыдущая запись — этот список»:
 *  1) предыдущая запись БЕЗ базы ('/items') — так vue-router пишет
 *     `history.state.back` (проверено живым замером в Chromium с base '/ui/');
 *  2) предыдущая запись С базой ('/ui/items') — другая форма записи;
 *  3) роутер без `resolve`/`options`/`back` — частичный мок в юнит-тестах
 *     страниц: стрелка остаётся безопасной (ссылки нет, клик уходит в push).
 *
 * Отдельный файл: `vi.mock('vue-router')` действует на весь модуль — рядом
 * с настоящим роутером такой мок невозможен (см. useListBack.test.ts).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'

interface FakeRouter {
  push: ReturnType<typeof vi.fn>
  back?: ReturnType<typeof vi.fn>
  resolve?: ReturnType<typeof vi.fn>
  options?: { history?: { state?: { back?: string } } }
}

function makeFakeRouter(back?: string): FakeRouter {
  return {
    push: vi.fn(),
    back: vi.fn(),
    resolve: vi.fn((to: { name: string }) => ({ path: '/items', href: '/ui/items', name: to.name })),
    options: { history: { state: { back } } },
  }
}

let fakeRouter: FakeRouter = makeFakeRouter('/items')

vi.mock('vue-router', () => ({
  useRouter: () => fakeRouter,
}))

import { useListBack } from './useListBack'

let exposed: ReturnType<typeof useListBack> | null = null

const Harness = defineComponent({
  setup() {
    exposed = useListBack('items', 'items')
    return () => h('div')
  },
})

beforeEach(() => {
  fakeRouter = makeFakeRouter('/items')
  sessionStorage.clear()
})

describe('useListBack: разные формы роутера (WO-UI-41)', () => {
  it('предыдущая запись БЕЗ базы — список: возврат по истории', async () => {
    const wrapper = mount(Harness)
    await flushPromises()
    expect(exposed!.canGoBack()).toBe(true)
    exposed!.back()
    expect(fakeRouter.back).toHaveBeenCalledTimes(1)
    expect(fakeRouter.push).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('предыдущая запись С базой — список: тоже возврат по истории', async () => {
    fakeRouter = makeFakeRouter('/ui/items')
    const wrapper = mount(Harness)
    await flushPromises()
    expect(exposed!.canGoBack()).toBe(true)
    exposed!.back()
    expect(fakeRouter.back).toHaveBeenCalledTimes(1)
    expect(fakeRouter.push).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('предыдущая запись — НЕ список: push списка с сохранённым query', async () => {
    fakeRouter = makeFakeRouter('/other')
    const wrapper = mount(Harness)
    await flushPromises()
    expect(exposed!.canGoBack()).toBe(false)
    exposed!.back()
    expect(fakeRouter.back).not.toHaveBeenCalled()
    expect(fakeRouter.push).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('роутер без resolve/options/back: страница не падает', async () => {
    fakeRouter = { push: vi.fn() }
    const wrapper = mount(Harness)
    await flushPromises()
    expect(exposed!.href.value).toBeUndefined()
    expect(exposed!.canGoBack()).toBe(false)
    expect(() => exposed!.back()).not.toThrow()
    expect(fakeRouter.push).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })
})
