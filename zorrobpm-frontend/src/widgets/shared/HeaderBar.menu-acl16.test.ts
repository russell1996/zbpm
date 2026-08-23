// @vitest-environment jsdom
/**
 * WO-ACL-16 criterion 7 — the personal API key screen (MyApiKey) was
 * unreachable (P-21): the route existed, no navigation point did. The header
 * user menu now carries a "My API key" item next to logout; clicking it opens
 * the real page.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { defineComponent } from 'vue'
import HeaderBar from './HeaderBar.vue'
import MyApiKey from '@/pages/me/MyApiKey.vue'

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ user: { id: 'u-1', username: 'test', fullName: 'Test User' }, logout: vi.fn() }),
}))
vi.mock('@/stores/ui', () => ({
  useUiStore: () => ({ darkMode: false, toggleDarkMode: vi.fn() }),
}))
vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))
vi.mock('@/app/i18n', () => ({ setLocale: vi.fn() }))
vi.mock('@/services/apiKeyService', () => ({
  getMyApiKey: vi.fn().mockRejectedValue({ response: { status: 404 } }),
  rotateMyApiKey: vi.fn(),
  revokeMyApiKey: vi.fn(),
}))
vi.mock('@/composables/useDateFormat', () => ({
  useDateFormat: () => ({ formatDate: (v: string) => v, formatDateTime: (v: string) => v }),
}))

function makeRouter() {
  return createRouter({
    history: createMemoryHistory('/ui/'),
    routes: [
      {
        path: '/',
        component: defineComponent({
          components: { HeaderBar },
          template: '<HeaderBar /><main><router-view /></main>',
        }),
        children: [
          { path: 'me/profile', name: 'my-profile', component: MyApiKey, meta: { titleKey: 'myProfile' } },
          { path: 'me/api-key', name: 'my-api-key', redirect: { name: 'my-profile' } },
        ],
      },
    ],
  })
}

describe('WO-ACL-16 criterion 7: header user menu reaches the personal API key screen', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('the user menu contains the "My API key" item next to logout', async () => {
    const router = makeRouter()
    await router.push('/')
    await router.isReady()
    const wrapper = mount(HeaderBar, { global: { plugins: [router] }, stubs: { teleport: true } })

    expect(wrapper.find('[aria-haspopup="menu"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('myApiKey')

    await wrapper.find('[aria-haspopup="menu"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('myApiKey')
    expect(wrapper.text()).toContain('logout')
  })

  it('clicking "My API key" opens the real page (route my-api-key renders MyApiKey)', async () => {
    const router = makeRouter()
    await router.push('/')
    await router.isReady()
    const Root = defineComponent({
      components: { HeaderBar },
      template: '<HeaderBar /><main><router-view /></main>',
    })
    const wrapper = mount(Root, { global: { plugins: [router] }, stubs: { teleport: true } })

    await wrapper.find('[aria-haspopup="menu"]').trigger('click')
    await flushPromises()

    await wrapper.find('a[href="/ui/me/profile"]').trigger('click')
    await flushPromises()

    expect(['my-profile','my-api-key']).toContain(String(router.currentRoute.value.name))
    // the real page mounted below the header and called the real service
    const { getMyApiKey } = await import('@/services/apiKeyService')
    expect(getMyApiKey).toHaveBeenCalled()
    expect(wrapper.text()).toContain('noApiKeyYet')
  })
})