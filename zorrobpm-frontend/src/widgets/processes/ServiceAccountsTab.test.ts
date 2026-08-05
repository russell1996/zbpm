// @vitest-environment jsdom
import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ServiceAccountsTab from './ServiceAccountsTab.vue'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))

/**
 * WO-MT-6 criterion #2: API key does NOT persist in component state after modal close.
 *
 * Proof-of-failure (V3):
 *   RED:  remove displayedKey = '' from closeKeyModal() → step 4 FAILS (key still present)
 *   GREEN: restored → displayedKey === '' after close
 *
 * This test actually exercises the full cycle:
 *   1. Set displayedKey + showKeyModal=true (simulating create/rotate response)
 *   2. Verify key is shown (displayedKey contains key, modal open)
 *   3. Call closeKeyModal()
 *   4. Assert displayedKey === '' AND showKeyModal === false
 */
describe('ServiceAccountsTab — key state isolation', () => {
  it('clears displayedKey when modal closes (key never stays in state)', async () => {
    const wrapper = mount(ServiceAccountsTab, {
      props: { processKey: 'test-process' },
      global: {
        stubs: { teleport: true },
      },
    })

    const vm = wrapper.vm as any

    // Step 1: Simulate receiving a key (as createServiceAccount would return)
    vm.displayedKey = 'zbpm_sk_test123abc'
    vm.showKeyModal = true
    await wrapper.vm.$nextTick()

    // Step 2: Verify key IS present (pre-condition for the test to be meaningful)
    expect(vm.displayedKey).toBe('zbpm_sk_test123abc')
    expect(vm.showKeyModal).toBe(true)

    // Step 3: Close the modal
    vm.closeKeyModal()
    await wrapper.vm.$nextTick()

    // Step 4: Key must be cleared — this is the critical assertion
    expect(vm.displayedKey).toBe('')
    expect(vm.showKeyModal).toBe(false)
  })

  it('displayedKey is empty string by default (no leakage on mount)', () => {
    const wrapper = mount(ServiceAccountsTab, {
      props: { processKey: 'test-process' },
      global: {
        stubs: { teleport: true },
      },
    })
    const vm = wrapper.vm as any
    expect(vm.displayedKey).toBe('')
    expect(vm.showKeyModal).toBe(false)
  })
})
