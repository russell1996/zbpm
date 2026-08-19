// @vitest-environment jsdom
/**
 * WO-ACL-14 criteria 17-19:
 *  17 — the id is visible in FULL in the cell (no `length` truncation);
 *  18 — the title carries the full value (copy/interaction affordance);
 *  19 — the id cell does not navigate anywhere (click never leaks a route
 *       change — row-level navigation stays on the row).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import CopyableId from './CopyableId.vue'

const FULL = '4394c7b1-aaaa-4000-8000-000000000001'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))

describe('CopyableId (WO-ACL-14 criteria 17/18/19)', () => {
  beforeEach(() => {
    vi.stubGlobal('navigator', { clipboard: { writeText: vi.fn().mockResolvedValue(undefined) } })
  })

  it('criterion 17: shows the WHOLE id in the cell — no ellipsis, no truncation', () => {
    const wrapper = mount(CopyableId, { props: { value: FULL } })
    expect(wrapper.text()).toContain(FULL)
    expect(wrapper.text()).not.toContain('...')
  })

  it('criterion 18: title carries the full value', () => {
    const wrapper = mount(CopyableId, { props: { value: FULL } })
    expect(wrapper.get('span').attributes('title')).toBe(FULL)
  })

  it('criterion 19: clicking the id copies (clipboard) and never navigates', async () => {
    const wrapper = mount(CopyableId, { props: { value: FULL } })
    // the click handler stops propagation; a navigation listener attached to a
    // parent would NOT fire — simulate the row-level navigation case
    const nav = vi.fn()
    wrapper.element.addEventListener('click', nav)
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith(FULL)
    expect(nav).not.toHaveBeenCalled()
  })

  it('criterion 19: clicking the id CELL itself also never navigates (stop propagation)', async () => {
    const wrapper = mount(CopyableId, { props: { value: FULL } })
    // a navigation listener on the PARENT (row-level navigation) must not fire
    const nav = vi.fn()
    wrapper.element.parentElement!.addEventListener('click', nav)
    await wrapper.get('span.group').trigger('click')
    expect(nav).not.toHaveBeenCalled()
  })
})