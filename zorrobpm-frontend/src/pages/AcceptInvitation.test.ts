// @vitest-environment jsdom
/**
 * Hotfix regression test — same bug as ResetPassword.test.ts (copy-pasted component): `computed()`
 * used in <script setup> without importing it from 'vue', white-screening /ui/accept-invitation
 * with `ReferenceError: computed is not defined` thrown during setup(). See ResetPassword.test.ts
 * for why mounting is the check that actually catches this class of bug.
 */
import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'
import AcceptInvitation from './AcceptInvitation.vue'
import en from '@/locales/en.json'
import ru from '@/locales/ru.json'
import kz from '@/locales/kz.json'

const mockAcceptInvitation = vi.hoisted(() => vi.fn().mockResolvedValue(undefined))
vi.mock('@/services/userService', () => ({ acceptInvitation: mockAcceptInvitation }))
vi.mock('@/composables/useToast', () => ({ useToast: () => ({ success: vi.fn(), error: vi.fn() }) }))

function mountPage(token = 'tok-1') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/accept-invitation', component: AcceptInvitation },
      { path: '/login', component: { template: '<div />' } },
    ],
  })
  const i18n = createI18n({ legacy: false, locale: 'en', messages: { en, ru, kz } })
  router.push(`/accept-invitation?token=${token}`)
  return { router, i18n }
}

describe('AcceptInvitation.vue', () => {
  it('criterion1: mounts and renders without throwing (the computed() import bug crashed setup entirely)', async () => {
    const { router, i18n } = mountPage()
    await router.isReady()
    const wrapper = mount(AcceptInvitation, { global: { plugins: [router, i18n] } })
    expect(wrapper.find('button[type="submit"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('criterion2: passwordWeak computed reactively disables submit for a blocklisted password', async () => {
    const { router, i18n } = mountPage()
    await router.isReady()
    const wrapper = mount(AcceptInvitation, { global: { plugins: [router, i18n] } })
    const button = wrapper.find('button[type="submit"]')
    const input = wrapper.find('input[type="password"]')

    await input.setValue('password')
    expect((button.element as HTMLButtonElement).disabled).toBe(true)

    await input.setValue('a-genuinely-strong-Passphrase-42')
    expect((button.element as HTMLButtonElement).disabled).toBe(false)
    wrapper.unmount()
  })

  it('criterion3: submits token + password to acceptInvitation on a valid form', async () => {
    const { router, i18n } = mountPage('tok-xyz')
    await router.isReady()
    const wrapper = mount(AcceptInvitation, { global: { plugins: [router, i18n] } })
    const inputs = wrapper.findAll('input[type="password"]')
    await inputs[0].setValue('a-genuinely-strong-Passphrase-42')
    await inputs[1].setValue('a-genuinely-strong-Passphrase-42')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()
    expect(mockAcceptInvitation).toHaveBeenCalledWith('tok-xyz', 'a-genuinely-strong-Passphrase-42')
    wrapper.unmount()
  })
})
