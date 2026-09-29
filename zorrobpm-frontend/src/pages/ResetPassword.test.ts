// @vitest-environment jsdom
/**
 * Hotfix regression test: `computed()` was called in <script setup> without being imported
 * from 'vue' (`import { ref } from 'vue'` only) — a real bug that shipped to production and
 * white-screened /ui/reset-password with `ReferenceError: computed is not defined` (thrown
 * during setup(), before anything renders). Neither `tsc` nor `vite build` caught it — .vue
 * <script setup> blocks aren't type-checked by plain `tsc`, and undefined-identifier calls are
 * valid JS/TS syntax to a bundler. The only thing that actually exercises this line is mounting
 * the component, which is exactly what criterion1 does — it would have failed loudly on this bug.
 */
import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'
import ResetPassword from './ResetPassword.vue'
import en from '@/locales/en.json'
import ru from '@/locales/ru.json'
import kz from '@/locales/kz.json'

const mockResetPassword = vi.hoisted(() => vi.fn().mockResolvedValue(undefined))
vi.mock('@/services/userService', () => ({ resetPassword: mockResetPassword }))
vi.mock('@/composables/useToast', () => ({ useToast: () => ({ success: vi.fn(), error: vi.fn() }) }))

function mountPage(token = 'tok-1') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/reset-password', component: ResetPassword },
      { path: '/login', component: { template: '<div />' } },
    ],
  })
  const i18n = createI18n({ legacy: false, locale: 'en', messages: { en, ru, kz } })
  router.push(`/reset-password?token=${token}`)
  return { router, i18n }
}

describe('ResetPassword.vue', () => {
  it('criterion1: mounts and renders without throwing (the computed() import bug crashed setup entirely)', async () => {
    const { router, i18n } = mountPage()
    await router.isReady()
    const wrapper = mount(ResetPassword, { global: { plugins: [router, i18n] } })
    expect(wrapper.find('button[type="submit"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('criterion2: passwordWeak computed reactively disables submit for a blocklisted password', async () => {
    const { router, i18n } = mountPage()
    await router.isReady()
    const wrapper = mount(ResetPassword, { global: { plugins: [router, i18n] } })
    const button = wrapper.find('button[type="submit"]')
    const input = wrapper.find('input[type="password"]')

    await input.setValue('password')
    expect((button.element as HTMLButtonElement).disabled).toBe(true)

    await input.setValue('a-genuinely-strong-Passphrase-42')
    expect((button.element as HTMLButtonElement).disabled).toBe(false)
    wrapper.unmount()
  })

  it('criterion3: submits token + password to resetPassword on a valid form', async () => {
    const { router, i18n } = mountPage('tok-xyz')
    await router.isReady()
    const wrapper = mount(ResetPassword, { global: { plugins: [router, i18n] } })
    const inputs = wrapper.findAll('input[type="password"]')
    await inputs[0].setValue('a-genuinely-strong-Passphrase-42')
    await inputs[1].setValue('a-genuinely-strong-Passphrase-42')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()
    expect(mockResetPassword).toHaveBeenCalledWith('tok-xyz', 'a-genuinely-strong-Passphrase-42')
    wrapper.unmount()
  })
})
