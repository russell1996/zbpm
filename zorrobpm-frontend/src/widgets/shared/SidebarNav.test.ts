// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import SidebarNav from './SidebarNav.vue'

vi.mock('vue-router', () => ({
  useRoute: () => ({ path: '/' }),
  useRouter: () => ({ push: vi.fn() }),
}))

// t returns the key, so nav labels render as their labelKey
vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))

// WO-ACL-8 criteria 1-2: admin-only items are visible to super-admins only.
const mockAuth = vi.hoisted(() => ({ isSuperAdmin: false }))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ isSuperAdmin: mockAuth.isSuperAdmin }),
}))

describe('SidebarNav', () => {
  beforeEach(() => {
    mockAuth.isSuperAdmin = false
  })

  it('Forms menu item removed — form/schema editing now lives inside Process Definitions detail', () => {
    // adminOnly items are invisible to a non-admin regardless of this assertion — mount as
    // super-admin so this actually exercises "forms is gone from the array", not "non-admins
    // don't see admin items" (WO-UI-4 re-added it under adminOnly and this test stayed green
    // for the wrong reason).
    mockAuth.isSuperAdmin = true
    const wrapper = mount(SidebarNav)
    // forms was superseded by the per-process SchemaEditorPanel (same pattern as processSchemas below)
    const buttons = wrapper.findAll('button')
    const formsBtn = buttons.find(b => b.text().includes('forms'))
    expect(formsBtn).toBeFalsy()
  })

  it('WO-ACL-8 criterion 1: a non-super-admin does NOT see Users', () => {
    const wrapper = mount(SidebarNav)
    const buttons = wrapper.findAll('button')
    expect(buttons.some(b => b.text().includes('users'))).toBe(false)
  })

  it('WO-ACL-8 criterion 1: a non-super-admin does NOT see Submission Queue', () => {
    const wrapper = mount(SidebarNav)
    const buttons = wrapper.findAll('button')
    expect(buttons.some(b => b.text().includes('submissionQueue'))).toBe(false)
  })

  it('WO-ACL-8 criterion 2: a super-admin DOES see both admin items', () => {
    mockAuth.isSuperAdmin = true
    const wrapper = mount(SidebarNav)
    const buttons = wrapper.findAll('button')
    expect(buttons.some(b => b.text().includes('users'))).toBe(true)
    expect(buttons.some(b => b.text().includes('submissionQueue'))).toBe(true)
  })

  it('Process Schemas menu item removed — now inside definitions detail (WO-VM-12)', () => {
    // same reasoning as the forms test above: mount as super-admin or this is blind to the
    // adminOnly item actually being back in the array.
    mockAuth.isSuperAdmin = true
    const wrapper = mount(SidebarNav)
    // processSchemas was moved into ProcessDefinitionDetail, so it should NOT be in the sidebar
    const buttons = wrapper.findAll('button')
    const processSchemasBtn = buttons.find(b => b.text().includes('processSchemas'))
    expect(processSchemasBtn).toBeFalsy()
  })

  it('WO-ACL-6 criterion 4: standalone "Deploy Process" menu item removed — upload lives inside definitions', () => {
    const wrapper = mount(SidebarNav)
    const buttons = wrapper.findAll('button')
    const deployBtn = buttons.find(b => b.text().includes('deploy'))
    expect(deployBtn).toBeFalsy()
  })

  it('WO-ACL-8 criterion 10: My Submissions removed from sidebar (now a dialog on definitions page)', () => {
    const wrapper = mount(SidebarNav)
    const buttons = wrapper.findAll('button')
    expect(buttons.some(b => b.text().includes('mySubmissions'))).toBe(false)
  })

  it('WO-ACL-6: Submission Queue item present for super-admin (criterion 6)', () => {
    mockAuth.isSuperAdmin = true
    const wrapper = mount(SidebarNav)
    const buttons = wrapper.findAll('button')
    expect(buttons.some(b => b.text().includes('submissionQueue'))).toBe(true)
  })

  // WO-ACL-8 criterion 34: each menu item has a unique icon for readability in collapsed mode
  it('WO-ACL-8 criterion 34: each visible nav item has a unique icon component', () => {
    const wrapper = mount(SidebarNav)
    // find all icon components (the <svg> elements rendered by lucide)
    const svgs = wrapper.findAll('svg')
    const classes = svgs.map(s => s.classes().join(' '))
    // all icon SVGs should have different class sets (each icon renders differently)
    const unique = new Set(classes)
    // 10 nav items → 10 unique icons (plus collapse button = 11 total SVGs)
    expect(svgs.length).toBeGreaterThanOrEqual(10)
    expect(unique.size).toBeGreaterThanOrEqual(10)
  })

  // WO-ACL-8 criterion 35 (WO-ACL-10 rework): the flyout tooltip appears on hover
  // when collapsed. Rendered by JS as .sidebar-flyout outside the scroll container.
  it('WO-ACL-8 criterion 35: flyout tooltip is present when collapsed', async () => {
    const wrapper = mount(SidebarNav, { props: { collapsed: true } })
    const btn = wrapper.findAll('nav button')[2]
    await btn.trigger('mouseenter')
    const flyout = wrapper.find('.sidebar-flyout')
    expect(flyout.exists()).toBe(true)
    expect(flyout.text()).toBe('instances')
  })

  // WO-ACL-8 criterion 36 (WO-ACL-10 rework): no flyout on button hover when
  // expanded — only a truncated label shows one (criterion 23).
  it('WO-ACL-8 criterion 36: no flyout tooltip when expanded', async () => {
    const wrapper = mount(SidebarNav, { props: { collapsed: false } })
    const btn = wrapper.findAll('nav button')[2]
    await btn.trigger('mouseenter')
    expect(wrapper.find('.sidebar-flyout').exists()).toBe(false)
  })

  // WO-ACL-10 criterion 1: the flyout must not be clipped — it must have no
  // ancestor with a scrolling/hiding overflow class between it and the root.
  it('WO-ACL-10 criterion 1: flyout is not clipped by any overflow ancestor', async () => {
    const wrapper = mount(SidebarNav, { props: { collapsed: true } })
    const btn = wrapper.findAll('nav button')[2]
    await btn.trigger('mouseenter')
    const flyout = wrapper.find('.sidebar-flyout')
    expect(flyout.exists()).toBe(true)
    let p = flyout.element.parentElement
    while (p && p !== document.body) {
      expect(p.className).not.toMatch(/(^|\s)overflow-(y|x|hidden)/)
      p = p.parentElement
    }
  })

  // WO-ACL-10 criterion 21: the same rework kills the horizontal scrollbar — the
  // flyout lives OUTSIDE every scrolling ancestor, so it cannot widen any of them.
  // One mutation (overflow-y-auto back on the nav) breaks BOTH this test and
  // criterion 1, because an overflow on any ancestor of the flyout produces the
  // clipped flyout AND the horizontal scrollbar the product owner reported.
  it('WO-ACL-10 criterion 21: collapsed flyout has no scrolling ancestor', async () => {
    const wrapper = mount(SidebarNav, { props: { collapsed: true } })
    const btn = wrapper.findAll('nav button')[2]
    await btn.trigger('mouseenter')
    const flyout = wrapper.find('.sidebar-flyout')
    expect(flyout.exists()).toBe(true)
    let p = flyout.element.parentElement
    while (p && p !== document.body) {
      expect(p.className).not.toMatch(/(^|\s)overflow-(y|x)-(auto|scroll)/)
      p = p.parentElement
    }
  })

  // WO-ACL-10 criterion 15: labels must never wrap — every label span carries
  // whitespace-nowrap (plus truncate, which implies the same).
  it('WO-ACL-10 criterion 15: nav labels never wrap', () => {
    const wrapper = mount(SidebarNav)
    const spans = wrapper.findAll('nav button span')
    expect(spans.length).toBeGreaterThanOrEqual(10)
    for (const s of spans) {
      expect(s.classes()).toContain('whitespace-nowrap')
    }
  })

  // WO-ACL-10 criterion 23: a truncated label shows the full text on hover…
  it('WO-ACL-10 criterion 23: truncated label shows full text on hover', async () => {
    const wrapper = mount(SidebarNav) // expanded
    const span = wrapper.findAll('nav button span')[0]
    // simulate truncation: content wider than the box
    Object.defineProperty(span.element, 'scrollWidth', { value: 300, configurable: true })
    Object.defineProperty(span.element, 'clientWidth', { value: 100, configurable: true })
    await span.trigger('mouseenter')
    const flyout = wrapper.find('.sidebar-flyout')
    expect(flyout.exists()).toBe(true)
    expect(flyout.text()).toBe('dashboard')
  })

  // WO-ACL-10 criterion 23: …while a label that fits shows no tooltip at all.
  it('WO-ACL-10 criterion 23: non-truncated label shows no tooltip', async () => {
    const wrapper = mount(SidebarNav) // expanded
    const span = wrapper.findAll('nav button span')[0]
    // jsdom default: scrollWidth === clientWidth (0), nothing is truncated
    await span.trigger('mouseenter')
    expect(wrapper.find('.sidebar-flyout').exists()).toBe(false)
  })
})
