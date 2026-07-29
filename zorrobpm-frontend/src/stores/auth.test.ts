// @vitest-environment jsdom
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useAuthStore } from '@/stores/auth'

// hoisted mocks for async store operations
const mockPost = vi.hoisted(() => vi.fn())
const mockGetMe = vi.hoisted(() => vi.fn())

vi.mock('@/services/api', () => ({
  default: { post: mockPost },
}))

vi.mock('@/services/authService', () => ({
  getMe: mockGetMe,
}))

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

/**
 * WO-FE-7: logout calls /auth/logout + clears state (fail-safe).
 * refreshUser wrapped in try/catch — doesn't crash on failure.
 */
describe('auth store — logout (WO-FE-7)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    // Mock window.location.href assignment (vitest/jsdom limitation)
    Object.defineProperty(window, 'location', {
      value: { href: '' },
      writable: true,
    })
  })

  // ──────────────────────────────────────────────
  // Criteria 1: logout calls /auth/logout and clears state
  // ──────────────────────────────────────────────
  it('GREEN: logout calls /auth/logout API and clears store state', async () => {
    mockPost.mockResolvedValue({ data: {} })
    const store = useAuthStore()
    store.user = {
      id: 'test-id',
      username: 'user1',
      fullName: 'User One',
      email: null,
      role: 'USER',
      active: true,
      forcePasswordChange: false,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    }
    store.error = 'some old error'
    expect(store.isAuthenticated).toBe(true)

    await store.logout()

    // API called
    expect(mockPost).toHaveBeenCalledWith('/auth/logout')
    // State cleared
    expect(store.user).toBeNull()
    expect(store.error).toBeNull()
    // Redirect initiated
    expect(window.location.href).toBe('/ui/login')
  })

  // ──────────────────────────────────────────────
  // Criteria 2: logout API failure still clears state (fail-safe)
  // ──────────────────────────────────────────────
  it('RED: logout API fails — state still cleared, user redirected', async () => {
    mockPost.mockRejectedValue(new Error('Network error'))
    const store = useAuthStore()
    store.user = {
      id: 'test-id',
      username: 'user1',
      fullName: 'User One',
      email: null,
      role: 'USER',
      active: true,
      forcePasswordChange: false,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    }
    store.error = 'some old error'

    // Should NOT throw — fail-safe
    await expect(store.logout()).resolves.toBeUndefined()

    // State still cleared despite API failure
    expect(store.user).toBeNull()
    expect(store.error).toBeNull()
    expect(window.location.href).toBe('/ui/login')
  })
})

describe('auth store — refreshUser (WO-FE-7)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  // ──────────────────────────────────────────────
  // Criteria 3: refreshUser error does NOT crash store
  // ──────────────────────────────────────────────
  it('GREEN: refreshUser on success updates user', async () => {
    const userData = {
      id: 'test-id',
      username: 'user1',
      fullName: 'User One',
      email: null,
      role: 'USER',
      active: true,
      forcePasswordChange: false,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    }
    mockGetMe.mockResolvedValue(userData)
    const store = useAuthStore()

    await store.refreshUser()

    expect(store.user).toEqual(userData)
  })

  it('RED: refreshUser on failure does not throw — resets user to null', async () => {
    mockGetMe.mockRejectedValue(new Error('Network error'))
    const store = useAuthStore()
    store.user = {
      id: 'old-id',
      username: 'olduser',
      fullName: 'Old User',
      email: null,
      role: 'USER',
      active: true,
      forcePasswordChange: false,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    }

    // Should NOT throw
    await expect(store.refreshUser()).resolves.toBeUndefined()

    // User reset to null (fail-closed: guest state denies protected content)
    expect(store.user).toBeNull()
  })
})
