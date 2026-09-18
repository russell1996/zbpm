// @vitest-environment jsdom
/**
 * WO-ACL-11 criteria 33-34 — AppDrawer (right-side panel for My Submissions):
 *  33 — closes by Esc, overlay click and the cross; listeners removed on unmount;
 *  34 — focus moves into the panel on open, back to the opener on close, Tab never
 *       leaves the panel.
 * Focus assertions need a REAL document — the wrapper is attached to document.body.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import AppDrawer from './AppDrawer.vue'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k }),
}))

const SLOT = `
  <button class="slot-first">First</button>
  <button class="slot-last">Last</button>
`

function mountDrawer(props: { open: boolean; title?: string }) {
  return mount(AppDrawer, {
    props: { title: 'My Submissions', ...props },
    slots: { default: SLOT },
    attachTo: document.body,
  })
}

describe('AppDrawer (WO-ACL-11 criteria 33-34)', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    document.body.style.overflow = ''
  })

  async function openDrawer() {
    const wrapper = mountDrawer({ open: true })
    await flushPromises()
    return wrapper
  }

  it('criterion 33: Escape closes the panel', async () => {
    const wrapper = await openDrawer()
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  it('criterion 33: clicking the overlay closes the panel', async () => {
    const wrapper = await openDrawer()
    await wrapper.get('[data-testid="drawer-overlay"]').trigger('click')
    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  it('criterion 33: the cross button closes the panel', async () => {
    const wrapper = await openDrawer()
    await wrapper.get('[aria-label="close"]').trigger('click')
    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  it('criterion 33: the keydown listener is removed on unmount', async () => {
    const removeSpy = vi.spyOn(document, 'removeEventListener')
    const wrapper = await openDrawer()
    wrapper.unmount()
    expect(removeSpy).toHaveBeenCalledWith('keydown', expect.any(Function))
  })

  it('criterion 20: the panel is a right-side drawer (max-w-4xl) with internal scrolling', async () => {
    const wrapper = await openDrawer()
    const panel = wrapper.get('[data-testid="drawer-panel"]')
    expect(panel.classes()).toContain('translate-x-0')
    // wide screens: up to max-w-4xl (WO-ACL-14 criterion 20 — My Submissions
    // has 5 columns incl. a long reject-reason cell); narrow: full width
    expect(panel.classes()).toContain('sm:max-w-4xl')
    expect(panel.classes()).not.toContain('sm:max-w-2xl')
    // the CONTENT body scrolls inside the panel, not the page under it
    expect(panel.find('.overflow-y-auto').exists()).toBe(true)
    expect(document.body.style.overflow).toBe('hidden')
    await wrapper.setProps({ open: false })
    await flushPromises()
    expect(wrapper.get('[data-testid="drawer-panel"]').classes()).toContain('translate-x-full')
    expect(document.body.style.overflow).toBe('')
  })

  it('criterion 34: focus moves into the panel on open', async () => {
    const wrapper = mountDrawer({ open: false })
    const opener = document.createElement('button')
    opener.textContent = 'open'
    document.body.appendChild(opener)
    opener.focus()
    expect(document.activeElement).toBe(opener)
    await wrapper.setProps({ open: true })
    await flushPromises()
    // the first focusable inside the panel is the cross button
    expect(document.activeElement).toBe(wrapper.get('[aria-label="close"]').element)
  })

  it('criterion 34: focus returns to the opener on close', async () => {
    const wrapper = mountDrawer({ open: false })
    const opener = document.createElement('button')
    opener.textContent = 'open'
    document.body.appendChild(opener)
    opener.focus()
    await wrapper.setProps({ open: true })
    await flushPromises()
    expect(document.activeElement).not.toBe(opener)
    await wrapper.setProps({ open: false })
    await flushPromises()
    expect(document.activeElement).toBe(opener)
  })

  it('criterion 34: Tab on the last element wraps to the first (trap)', async () => {
    const wrapper = await openDrawer()
    const first = wrapper.get('[aria-label="close"]').element as HTMLElement
    const last = wrapper.get('.slot-last').element as HTMLElement
    last.focus()
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab' }))
    expect(document.activeElement).toBe(first)
  })

  it('criterion 34: Shift+Tab on the first element wraps to the last (trap)', async () => {
    const wrapper = await openDrawer()
    const first = wrapper.get('[aria-label="close"]').element as HTMLElement
    const last = wrapper.get('.slot-last').element as HTMLElement
    first.focus()
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', shiftKey: true }))
    expect(document.activeElement).toBe(last)
  })

  it('criterion 34: Tab does not run the trap while the drawer is closed', async () => {
    const wrapper = mountDrawer({ open: false })
    const outside = document.createElement('button')
    outside.textContent = 'outside'
    document.body.appendChild(outside)
    outside.focus()
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab' }))
    expect(document.activeElement).toBe(outside)
  })
})