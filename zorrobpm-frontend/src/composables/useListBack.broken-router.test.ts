// @vitest-environment jsdom
/**
 * WO-UI-41 — `useListBack` при ЧАСТИЧНОМ моке роутера.
 *
 * Многие юнит-тесты страниц подменяют `vue-router` частично
 * (`useRouter: () => ({ push })`) — без `resolve`/`options`/`back`. Стрелка
 * «назад» не должна ронять страницу из-за отсутствующих методов: она
 * остаётся на месте, без адреса, а клик уходит в `push`.
 *
 * Отдельный файл: `vi.mock('vue-router')` действует на весь модуль — рядом
 * с настоящим роутером такой мок невозможен (см. useListBack.test.ts).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'

const pushMock = vi.fn()

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: pushMock }),
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
  pushMock.mockClear()
  sessionStorage.clear()
})

describe('useListBack: частичный мок роутера (WO-UI-41)', () => {
  it('страница не падает: ссылки нет, клик уходит в push', async () => {
    const wrapper = mount(Harness)
    await flushPromises()

    expect(exposed!.href.value).toBeUndefined()
    expect(exposed!.canGoBack()).toBe(false)
    expect(() => exposed!.back()).not.toThrow()
    expect(pushMock).toHaveBeenCalledTimes(1)
    expect(pushMock.mock.calls[0][0]).toEqual({ name: 'items', query: {} })
    wrapper.unmount()
  })
})
