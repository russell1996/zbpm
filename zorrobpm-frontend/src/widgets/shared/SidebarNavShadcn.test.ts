// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import SidebarNavShadcn from './SidebarNavShadcn.vue'

vi.mock('vue-router', () => ({
  useRoute: () => ({ path: '/' }),
  useRouter: () => ({ push: vi.fn() }),
}))

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))

const mockAuth = vi.hoisted(() => ({ isSuperAdmin: false }))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ isSuperAdmin: mockAuth.isSuperAdmin }),
}))

const mockUi = vi.hoisted(() => ({ darkMode: false }))
vi.mock('@/stores/ui', () => ({
  useUiStore: () => ({ darkMode: mockUi.darkMode }),
}))

const mockSidebarState = vi.hoisted(() => ({ value: 'expanded' }))
vi.mock('@/components/ui/sidebar', () => ({
  Sidebar: { template: '<div><slot /></div>' },
  SidebarContent: { template: '<div><slot /></div>' },
  SidebarGroup: { template: '<div><slot /></div>' },
  SidebarGroupLabel: { template: '<div><slot /></div>' },
  SidebarMenu: { template: '<ul><slot /></ul>' },
  SidebarMenuItem: { template: '<li><slot /></li>' },
  // WO-OBS-4: the mock renders the `as` prop like the real shadcn primitive,
  // so the external Monitoring link renders as <a>, not <button> (the
  // super-admin 12-button count below depends on exactly that).
  SidebarMenuButton: { template: '<component :is="$attrs.as || \'button\'"><slot /></component>' },
  useSidebar: () => ({ state: mockSidebarState }),
}))

describe('SidebarNavShadcn', () => {
  beforeEach(() => {
    mockAuth.isSuperAdmin = false
    mockUi.darkMode = false
  })

  it('renders all 10 non-admin nav items', () => {
    const wrapper = mount(SidebarNavShadcn)
    const buttons = wrapper.findAll('button')
    const labels = buttons.map(b => b.text())
    expect(labels).toContain('dashboard')
    expect(labels).toContain('definitions')
    expect(labels).toContain('instances')
    expect(labels).toContain('tasks')
    expect(labels).toContain('serviceTasks')
    expect(labels).toContain('incidents')
    expect(labels).toContain('timers')
    expect(labels).toContain('messages')
    expect(labels).toContain('analytics')
    expect(labels).toContain('dmn')
    // admin items NOT visible
    expect(labels).not.toContain('users')
    expect(labels).not.toContain('submissionQueue')
    expect(labels).not.toContain('registrationQueue')
  })

  it('super-admin sees the consolidated admin item (WO-UI-10: one hub instead of three)', () => {
    mockAuth.isSuperAdmin = true
    const wrapper = mount(SidebarNavShadcn)
    const buttons = wrapper.findAll('button')
    const labels = buttons.map(b => b.text())
    // Phase 3 consolidation: a single "adminSettings" hub replaces the three
    // separate users/submissions/mail-settings sidebar items (those are now tabs inside it).
    // WO-REG-6: registrationQueue is a separate SUPER_ADMIN item alongside the hub.
    expect(labels).toContain('adminSettings')
    expect(labels).toContain('registrationQueue')
    expect(labels).not.toContain('users')
    expect(labels).not.toContain('submissionQueue')
    expect(labels).not.toContain('mailSettings')
    expect(labels.length).toBe(12)
  })

  it('non-super-admin does NOT see admin items', () => {
    const wrapper = mount(SidebarNavShadcn)
    const buttons = wrapper.findAll('button')
    const labels = buttons.map(b => b.text())
    expect(labels.some(l => l === 'users' || l === 'submissionQueue' || l === 'registrationQueue')).toBe(false)
  })

  it('WO-OBS-4: super-admin sees the external Monitoring link (new tab, no router)', () => {
    mockAuth.isSuperAdmin = true
    const wrapper = mount(SidebarNavShadcn)
    const link = wrapper.find('a[href="/grafana/"]')
    expect(link.exists()).toBe(true)
    expect(link.attributes('target')).toBe('_blank')
    expect(link.attributes('rel')).toContain('noopener')
    expect(link.text()).toBe('monitoring')
    // external link is NOT a router button: the 12-button count above is untouched
    expect(wrapper.findAll('button').length).toBe(12)
  })

  it('WO-OBS-4: non-super-admin does NOT see the Monitoring link', () => {
    const wrapper = mount(SidebarNavShadcn)
    expect(wrapper.find('a[href="/grafana/"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('monitoring')
  })

  it('has 5 domain group labels + admin group when super-admin', () => {
    mockAuth.isSuperAdmin = true
    const wrapper = mount(SidebarNavShadcn)
    const groupLabels = wrapper.findAll('div').filter(
      el => el.text() === 'navOverview' ||
            el.text() === 'navDesign' ||
            el.text() === 'navExecution' ||
            el.text() === 'navMonitoring' ||
            el.text() === 'navAdministration'
    )
    expect(groupLabels.length).toBe(5)
  })

  it('has 4 domain group labels for non-admin (no admin group)', () => {
    const wrapper = mount(SidebarNavShadcn)
    const groupLabels = wrapper.findAll('div').filter(
      el => el.text() === 'navOverview' ||
            el.text() === 'navDesign' ||
            el.text() === 'navExecution' ||
            el.text() === 'navMonitoring' ||
            el.text() === 'navAdministration'
    )
    expect(groupLabels.length).toBe(4)
  })

  it('WO-UI-8 criterion6: logo is text-2xl expanded, lone Z stays text-2xl collapsed', () => {
    mockSidebarState.value = 'expanded'
    const wrapper = mount(SidebarNavShadcn)
    const logo = wrapper.find('span.text-2xl')
    expect(logo.exists()).toBe(true)
    expect(logo.classes()).toContain('tracking-tight')
    // Z keeps its primary accent and heavier weight at the larger size
    const z = logo.find('span.font-black')
    expect(z.exists()).toBe(true)
    expect(z.classes()).toContain('text-primary')
    wrapper.unmount()

    mockSidebarState.value = 'collapsed'
    const collapsed = mount(SidebarNavShadcn)
    const logoCollapsed = collapsed.find('span.text-2xl')
    expect(logoCollapsed.exists()).toBe(true)
    // collapsed: only "Z" renders, still at text-2xl (was text-base before WO-UI-8)
    expect(logoCollapsed.text()).toBe('Z')
    const zCollapsed = logoCollapsed.find('span.font-black')
    expect(zCollapsed.classes()).toContain('text-primary')
    collapsed.unmount()
  })
})
