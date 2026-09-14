// @vitest-environment jsdom
import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import AdminSettings from './AdminSettings.vue'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))

// The parent route component is a stub (NOT AdminSettings itself) so that mounting
// AdminSettings standalone doesn't recurse through its own <router-view>.
const router = createRouter({
  history: createWebHistory('/ui/'),
  routes: [
    { path: '/', name: 'dashboard', component: { template: '<div />' } },
    {
      path: '/admin/settings',
      name: 'admin-settings',
      component: { template: '<div />' },
      children: [
        { path: '', name: 'admin-settings-root', redirect: { name: 'admin-settings-mail' } },
        { path: 'mail-settings', name: 'admin-settings-mail', component: { template: '<div>mail</div>' } },
        { path: 'users', name: 'admin-settings-users', component: { template: '<div>users</div>' } },
        { path: 'submissions', name: 'admin-settings-submissions', component: { template: '<div>subs</div>' } },
      ],
    },
  ],
})

describe('WO-UI-10 Phase 3: AdminSettings tab hub', () => {
  it('renders four admin tabs and navigates between them', async () => {
    router.push('/admin/settings')
    await router.isReady()
    const wrapper = mount(AdminSettings, { global: { plugins: [router] }, attachTo: document.body })

    const tabs = wrapper.findAll('[role="tab"]')
    expect(tabs.length).toBe(4)
    const labels = tabs.map((t) => t.text())
    expect(labels).toContain('mailSettings')
    expect(labels).toContain('users')
    expect(labels).toContain('submissionQueue')
    expect(labels).toContain('registrationQueue')

    // navigate to the users tab
    await tabs[1].trigger('click')
    await flushPromises()
    await router.isReady()
    expect(router.currentRoute.value.name).toBe('admin-settings-users')

    wrapper.unmount()
  })
})
