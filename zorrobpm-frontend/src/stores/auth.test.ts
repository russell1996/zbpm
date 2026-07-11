import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useAuthStore } from '@/stores/auth'

/**
 * WO-FE-hotfix: store.isAdmin must include SUPER_ADMIN.
 *
 * Proof-of-failure (V3):
 *   RED:  on CURRENT code, isAdmin=false for SUPER_ADMIN → test fails
 *   GREEN: after fix, isAdmin=true for SUPER_ADMIN → test passes
 */
describe('auth store — isAdmin includes SUPER_ADMIN', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  /**
   * This is the critical test: when user.role is 'SUPER_ADMIN',
   * store.isAdmin MUST return true (SUPER_ADMIN ⊇ ADMIN).
   *
   * On CURRENT code: isAdmin=false → RED (bug confirmed)
   * After fix: isAdmin=true → GREEN
   */
  it('SUPER_ADMIN → isAdmin is true (BUG: currently false)', () => {
    const store = useAuthStore()

    // Simulate a SUPER_ADMIN user (as /auth/me would return)
    store.user = {
      id: 'test-id',
      username: 'superadmin',
      fullName: 'Super Admin',
      email: null,
      role: 'SUPER_ADMIN',
      active: true,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    }

    // On CURRENT code: isAdmin = false → BUG
    // After fix: isAdmin = true
    expect(store.isAdmin).toBe(true)
  })

  it('ADMIN → isAdmin is true', () => {
    const store = useAuthStore()
    store.user = {
      id: 'test-id',
      username: 'admin',
      fullName: 'Admin',
      email: null,
      role: 'ADMIN',
      active: true,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    }
    expect(store.isAdmin).toBe(true)
  })

  it('USER → isAdmin is false', () => {
    const store = useAuthStore()
    store.user = {
      id: 'test-id',
      username: 'user',
      fullName: 'User',
      email: null,
      role: 'USER',
      active: true,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    }
    expect(store.isAdmin).toBe(false)
  })
})
