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

vi.mock('@/components/ui/sidebar', () => ({
  Sidebar: { template: '<div><slot /></div>' },
  SidebarContent: { template: '<div><slot /></div>' },
  SidebarGroup: { template: '<div><slot /></div>' },
  SidebarGroupLabel: { template: '<div><slot /></div>' },
  SidebarMenu: { template: '<ul><slot /></ul>' },
  SidebarMenuItem: { template: '<li><slot /></li>' },
  SidebarMenuButton: { template: '<button><slot /></button>' },
  useSidebar: () => ({ state: { value: 'expanded' } }),
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
  })

  it('super-admin sees all 12 items including admin group', () => {
    mockAuth.isSuperAdmin = true
    const wrapper = mount(SidebarNavShadcn)
    const buttons = wrapper.findAll('button')
    const labels = buttons.map(b => b.text())
    expect(labels).toContain('users')
    expect(labels).toContain('submissionQueue')
    expect(labels.length).toBe(12)
  })

  it('non-super-admin does NOT see admin items', () => {
    const wrapper = mount(SidebarNavShadcn)
    const buttons = wrapper.findAll('button')
    const labels = buttons.map(b => b.text())
    expect(labels.some(l => l === 'users' || l === 'submissionQueue')).toBe(false)
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
})
