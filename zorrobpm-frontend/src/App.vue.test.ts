// @vitest-environment jsdom
import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import App from './App.vue'
import { Toaster as ShadcnSonner } from '@/components/ui/sonner'

// WO-UI-8: the app must render the shadcn-vue Sonner component (ui/sonner), NOT a bare
// vue-sonner <Toaster>. The shadcn wrapper is what ties toasts to the design-system tokens.
const mockUi = vi.hoisted(() => ({ darkMode: false }))
vi.mock('@/stores/ui', () => ({
  useUiStore: () => ({ darkMode: mockUi.darkMode }),
}))

vi.mock('vue-router', () => ({
  RouterView: { template: '<div class="router-view-stub" />' },
}))

describe('App.vue notifications (WO-UI-8)', () => {
  it('criterion1: a fired toast renders with the shadcn design-system classes', async () => {
    const wrapper = mount(App, { attachTo: document.body })
    // fire a toast through the real runtime API (the same one useToast wraps)
    const { toast } = await import('vue-sonner')
    toast.success('wo-ui-8')
    await wrapper.vm.$nextTick()
    await new Promise(r => setTimeout(r, 0))

    // the rendered toast carries ui/sonner design-system classes;
    // a bare vue-sonner <Toaster> would render none of these
    const el = document.querySelector('.toast')
    expect(el).not.toBeNull()
    // Tailwind v4 idiom: group-data-* variants against vue-sonner's data attributes
    expect(el!.className).toContain('group-data-sonner-toaster:bg-background')
    expect(el!.className).toContain('group-data-sonner-toaster:text-foreground')
    expect(el!.className).toContain('group-data-sonner-toaster:border')
    const container = document.querySelector('.toaster')
    expect(container).not.toBeNull()
    expect(container!.className).toContain('group')
    wrapper.unmount()
    document.body.innerHTML = ''
  })

  it('criterion3: position/duration are forwarded, auto-dismiss toasts have no close button', () => {
    const wrapper = mount(App)
    const toaster = wrapper.findComponent(ShadcnSonner)
    expect(toaster.props('position')).toBe('top-center')
    expect(toaster.props('duration')).toBe(5000)
    // WO-UI-8 step 0b: auto-dismiss toasts disappear on their own — no close button
    expect(toaster.props('closeButton')).toBeFalsy()
  })

  it('criterion4: toast theme follows the app dark mode', async () => {
    mockUi.darkMode = false
    let wrapper = mount(App)
    expect(wrapper.findComponent(ShadcnSonner).props('theme')).toBe('light')
    wrapper.unmount()

    mockUi.darkMode = true
    wrapper = mount(App)
    expect(wrapper.findComponent(ShadcnSonner).props('theme')).toBe('dark')
    wrapper.unmount()
    mockUi.darkMode = false
  })
})
