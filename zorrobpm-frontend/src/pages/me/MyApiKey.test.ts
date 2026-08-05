// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import MyApiKey from './MyApiKey.vue'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))

// Mock the apiKeyService
vi.mock('@/services/apiKeyService', () => ({
  getMyApiKey: vi.fn(),
  rotateMyApiKey: vi.fn(),
  revokeMyApiKey: vi.fn(),
}))

import { getMyApiKey, rotateMyApiKey, revokeMyApiKey } from '@/services/apiKeyService'

/**
 * WO-MT-9 criterion #2: key must NOT persist in state after modal close.
 * Proof-of-failure: RED (remove clearing → key stays), GREEN (clearing works).
 */
describe('MyApiKey — key state isolation', () => {
  beforeEach(() => {
    vi.mocked(getMyApiKey).mockResolvedValue({
      id: 'test-id',
      ownerUserId: 'user-id',
      prefix: 'zbpm_sk_abc',
      createdAt: '2026-01-01T00:00:00Z',
      lastUsedAt: null,
      expiresAt: null,
      revokedAt: null,
      grants: [],
    })
    vi.mocked(rotateMyApiKey).mockResolvedValue({
      id: 'test-id',
      ownerUserId: 'user-id',
      prefix: 'zbpm_sk_xyz',
      key: 'zbpm_sk_test_secret_123',
      createdAt: '2026-01-01T00:00:00Z',
      lastUsedAt: null,
      expiresAt: null,
      revokedAt: null,
      grants: [],
    })
    vi.mocked(revokeMyApiKey).mockResolvedValue(undefined)
  })

  /**
   * Proof-of-failure cycle:
   * 1. Mount → rotate (sets displayedKey='zbpm_sk_test_secret_123')
   * 2. Verify key IS shown (modal open, displayedKey has value)
   * 3. Close modal (closeKeyModal)
   * 4. Assert displayedKey === '' (key cleared)
   *
   * RED scenario: if closeKeyModal does NOT clear displayedKey,
   * step 4 fails → proves the bug.
   * GREEN scenario: closeKeyModal clears → step 4 passes.
   */
  it('clears displayedKey when modal closes (key never stays in state)', async () => {
    const wrapper = mount(MyApiKey, {
      global: {
        stubs: { teleport: true },
      },
    })

    // Wait for initial load
    await vi.dynamicImportSettled()
    await wrapper.vm.$nextTick()

    // Step 1: Trigger rotate to set displayedKey
    const vm = wrapper.vm as any
    await vm.rotate()
    await wrapper.vm.$nextTick()

    // Step 2: Verify key IS shown
    expect(vm.displayedKey).toBe('zbpm_sk_test_secret_123')
    expect(vm.showKeyModal).toBe(true)

    // Step 3: Close modal
    vm.closeKeyModal()
    await wrapper.vm.$nextTick()

    // Step 4: Key MUST be cleared — this is the critical assertion
    expect(vm.displayedKey).toBe('')
    expect(vm.showKeyModal).toBe(false)
  })

  it('displayedKey is empty string by default (no leakage on mount)', () => {
    const wrapper = mount(MyApiKey, {
      global: {
        stubs: { teleport: true },
      },
    })
    const vm = wrapper.vm as any
    expect(vm.displayedKey).toBe('')
    expect(vm.showKeyModal).toBe(false)
  })
})
