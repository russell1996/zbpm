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

  it('SUPER_ADMIN → isAdmin is true (BUG: currently false)', () => {
    const store = useAuthStore()
    store.user = {
      id: 'test-id',
      username: 'superadmin',
      fullName: 'Super Admin',
      email: null,
      role: 'SUPER_ADMIN',
      active: true,
      forcePasswordChange: false,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    }
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
      forcePasswordChange: false,
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
      forcePasswordChange: false,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    }
    expect(store.isAdmin).toBe(false)
  })
})

/**
 * WO-SEC-19: forcePasswordChange computed in auth store.
 */
describe('auth store — forcePasswordChange', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('forcePasswordChange=true when user.forcePasswordChange is true', () => {
    const store = useAuthStore()
    store.user = {
      id: 'test-id',
      username: 'forced',
      fullName: 'Forced',
      email: null,
      role: 'SUPER_ADMIN',
      active: true,
      forcePasswordChange: true,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    }
    expect(store.forcePasswordChange).toBe(true)
  })

  it('forcePasswordChange=false when user.forcePasswordChange is false', () => {
    const store = useAuthStore()
    store.user = {
      id: 'test-id',
      username: 'admin',
      fullName: 'Admin',
      email: null,
      role: 'ADMIN',
      active: true,
      forcePasswordChange: false,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    }
    expect(store.forcePasswordChange).toBe(false)
  })

  it('forcePasswordChange=false when no user', () => {
    const store = useAuthStore()
    expect(store.forcePasswordChange).toBe(false)
  })

  it('setForcePasswordChange updates user', () => {
    const store = useAuthStore()
    store.user = {
      id: 'test-id',
      username: 'admin',
      fullName: 'Admin',
      email: null,
      role: 'ADMIN',
      active: true,
      forcePasswordChange: true,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    }
    expect(store.forcePasswordChange).toBe(true)
    store.setForcePasswordChange(false)
    expect(store.forcePasswordChange).toBe(false)
  })
})
