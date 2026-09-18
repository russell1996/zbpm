// @vitest-environment jsdom
/**
 * WO-ACL-14 criteria 1, 2, 4, 5 — the SHARED tab component (TabsBar):
 *   1  — the component is the single tab implementation (the two old local
 *        strips in ProcessDefinitionDetail / ProcessInstanceDetail are gone;
 *        grep-level proof lives in the report, structure-level here);
 *   2  — the active tab carries the visible underline classes (border-b-2 +
 *        border-primary on the button, the single -mb-px on the nav — the
 *        visual visibility itself is a browser check, see the browser suite);
 *   4  — keyboard: ArrowLeft/Right rotate, Home/End jump to the edges,
 *        roles tablist/tab present;
 *   5  — narrow screens: the nav scrolls horizontally (overflow-x-auto) —
 *        jsdom cannot compute scroll geometry, the browser suite checks the
 *        active tab stays in view.
 */
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import TabsBar from './TabsBar.vue'

const TABS = [
  { id: 'a', label: 'Alpha' },
  { id: 'b', label: 'Beta' },
  { id: 'c', label: 'Gamma' },
  { id: 'd', label: 'Delta' },
]

function render(activeId = 'a') {
  return mount(TabsBar, {
    props: { tabs: TABS, activeId },
    attachTo: document.body,
  })
}

describe('TabsBar (WO-ACL-14 criteria 1/2/4/5)', () => {
  it('criterion 1: renders a tablist with role=tab buttons and labels', () => {
    const wrapper = render()
    const nav = wrapper.get('nav[role="tablist"]')
    const tabs = nav.findAll('button[role="tab"]')
    expect(tabs).toHaveLength(4)
    expect(tabs[0].text()).toBe('Alpha')
    expect(tabs[3].text()).toBe('Delta')
  })

  it('criterion 2: the active tab carries the underline (border-primary), exactly one', () => {
    const wrapper = render('b')
    const active = wrapper.findAll('button[role="tab"]').filter((b) => b.classes().includes('border-primary'))
    expect(active).toHaveLength(1)
    expect(active[0].text()).toBe('Beta')
    expect(active[0].classes()).toContain('border-b-2')
    // WO-ACL-15 criterion 13: the active caption is accent + heavier
    expect(active[0].classes()).toContain('text-primary')
    expect(active[0].classes()).toContain('font-semibold')
    // aria-selected mirrors the active id
    expect(active[0].attributes('aria-selected')).toBe('true')
  })

  it('criterion 2: the single -mb-px lives on the nav, never on buttons', () => {
    const wrapper = render()
    const nav = wrapper.get('nav[role="tablist"]')
    expect(nav.classes()).toContain('-mb-px')
    for (const b of nav.findAll('button')) {
      expect(b.classes()).not.toContain('-mb-px')
      expect(b.classes()).toContain('border-b-2')
    }
  })

  it('criterion 4: ArrowRight moves focus+selection to the next tab, wraps at the end', async () => {
    const wrapper = render('c')
    const tabs = wrapper.findAll('button[role="tab"]')
    ;(tabs[2].element as HTMLElement).focus()
    await tabs[2].trigger('keydown', { key: 'ArrowRight' })
    expect(document.activeElement).toBe(tabs[3].element)
    expect(wrapper.emitted('update:activeId')).toEqual([['d']])
    await tabs[3].trigger('keydown', { key: 'ArrowRight' })
    expect(document.activeElement).toBe(tabs[0].element)
    expect(wrapper.emitted('update:activeId')).toEqual([['d'], ['a']])
  })

  it('criterion 4: ArrowLeft moves back, wraps at the start', async () => {
    const wrapper = render('a')
    const tabs = wrapper.findAll('button[role="tab"]')
    ;(tabs[0].element as HTMLElement).focus()
    await tabs[0].trigger('keydown', { key: 'ArrowLeft' })
    expect(document.activeElement).toBe(tabs[3].element)
    expect(wrapper.emitted('update:activeId')).toEqual([['d']])
  })

  it('criterion 4: Home and End jump to the edges', async () => {
    const wrapper = render('b')
    const tabs = wrapper.findAll('button[role="tab"]')
    ;(tabs[1].element as HTMLElement).focus()
    await tabs[1].trigger('keydown', { key: 'Home' })
    expect(document.activeElement).toBe(tabs[0].element)
    expect(wrapper.emitted('update:activeId')).toEqual([['a']])
    await tabs[0].trigger('keydown', { key: 'End' })
    expect(document.activeElement).toBe(tabs[3].element)
    expect(wrapper.emitted('update:activeId')).toEqual([['a'], ['d']])
  })

  it('criterion 4: plain keys do not hijack the arrow handling', async () => {
    const wrapper = render('a')
    const tabs = wrapper.findAll('button[role="tab"]')
    ;(tabs[0].element as HTMLElement).focus()
    await tabs[0].trigger('keydown', { key: 'x' })
    expect(wrapper.emitted('update:activeId')).toBeUndefined()
  })

  it('criterion 5: the nav scrolls horizontally on narrow screens (overflow-x-auto)', () => {
    const wrapper = render()
    expect(wrapper.get('nav[role="tablist"]').classes()).toContain('overflow-x-auto')
  })

  it('criterion 5: clicking a tab emits update:activeId with its id', async () => {
    const wrapper = render('a')
    await wrapper.findAll('button[role="tab"]')[2].trigger('click')
    expect(wrapper.emitted('update:activeId')).toEqual([['c']])
  })
})