import { describe, it, expect } from 'vitest'
import { resolveGuard } from './guard'
import type { RouteLocationNormalized } from 'vue-router'

function makeRoute(meta: Record<string, unknown> = {}): RouteLocationNormalized {
  return {
    path: '/test',
    name: 'test',
    hash: '',
    query: {},
    params: {},
    fullPath: '/test',
    matched: [],
    redirectedFrom: undefined,
    meta,
  } as unknown as RouteLocationNormalized
}

function authState(overrides: Partial<{ isAuthenticated: boolean; isAdmin: boolean; isSuperAdmin: boolean; forcePasswordChange: boolean }> = {}) {
  return { isAuthenticated: true, isAdmin: false, isSuperAdmin: false, forcePasswordChange: false, ...overrides }
}

describe('resolveGuard', () => {
  it('USER passes when route has no requiresAdmin', () => {
    const route = makeRoute({ requiresAuth: true })
    expect(resolveGuard(route, authState())).toBeNull()
  })

  it('USER on admin route → access-denied', () => {
    const route = makeRoute({ requiresAdmin: true })
    expect(resolveGuard(route, authState())).toEqual({ name: 'access-denied' })
  })

  it('ADMIN on admin route → allowed', () => {
    const route = makeRoute({ requiresAdmin: true })
    expect(resolveGuard(route, authState({ isAdmin: true }))).toBeNull()
  })

  it('non-admin on requiresAdmin route blocks access', () => {
    const route = makeRoute({ requiresAdmin: true })
    const result = resolveGuard(route, authState())
    expect(result).not.toBeNull()
    expect(result!.name).toBe('access-denied')
  })

  it('unauthenticated user on admin route → blocked', () => {
    const route = makeRoute({ requiresAdmin: true })
    expect(resolveGuard(route, authState({ isAuthenticated: false }))).toEqual({ name: 'access-denied' })
  })

  it('route without meta → no redirect', () => {
    expect(resolveGuard(makeRoute({}), authState())).toBeNull()
  })
})

/**
 * WO-MT-9: requiresSuperAdmin guard cases.
 */
describe('resolveGuard — requiresSuperAdmin', () => {
  it('SUPER_ADMIN on requiresSuperAdmin route → allowed', () => {
    const route = makeRoute({ requiresSuperAdmin: true })
    expect(resolveGuard(route, authState({ isAdmin: true, isSuperAdmin: true }))).toBeNull()
  })

  it('ADMIN on requiresSuperAdmin route → denied', () => {
    const route = makeRoute({ requiresSuperAdmin: true })
    expect(resolveGuard(route, authState({ isAdmin: true }))).toEqual({ name: 'access-denied' })
  })

  it('USER on requiresSuperAdmin route → denied', () => {
    const route = makeRoute({ requiresSuperAdmin: true })
    expect(resolveGuard(route, authState())).toEqual({ name: 'access-denied' })
  })

  it('SUPER_ADMIN on requiresAdmin route → also allowed (SUPER_ADMIN ⊇ ADMIN)', () => {
    const route = makeRoute({ requiresAdmin: true })
    expect(resolveGuard(route, authState({ isAdmin: true, isSuperAdmin: true }))).toBeNull()
  })
})

/**
 * WO-MT-9: store.isAdmin includes SUPER_ADMIN.
 */
describe('auth store — isAdmin includes SUPER_ADMIN', () => {
  it('SUPER_ADMIN on requiresAdmin route → access allowed', () => {
    const route = makeRoute({ requiresAdmin: true })
    const roles = [
      { isAdmin: true, isSuperAdmin: true, expected: null },
      { isAdmin: true, isSuperAdmin: false, expected: null },
      { isAdmin: false, isSuperAdmin: false, expected: { name: 'access-denied' } },
    ]
    for (const { isAdmin, isSuperAdmin, expected } of roles) {
      const result = resolveGuard(route, authState({ isAuthenticated: true, isAdmin, isSuperAdmin }))
      expect(result, `isAdmin=${isAdmin}, isSuperAdmin=${isSuperAdmin}`).toEqual(expected)
    }
  })
})

/**
 * WO-SEC-19: forcePasswordChange guard cases.
 *
 * Proof-of-failure (§1b):
 *   RED:  Without the forcePasswordChange check in resolveGuard,
 *         a user with forcePasswordChange=true navigates to '/' (dashboard) and gets null (allowed).
 *         → Test expects { name: 'change-password' } but gets null → FAILS.
 *   GREEN: With the check, resolveGuard returns { name: 'change-password' } → PASS.
 */
describe('resolveGuard — forcePasswordChange (WO-SEC-19)', () => {
  it('forcePasswordChange=true + target ≠ change-password → redirect to change-password', () => {
    const route = makeRoute({ requiresAuth: true })
    const result = resolveGuard(route, authState({ forcePasswordChange: true }))
    expect(result).toEqual({ name: 'change-password' })
  })

  it('forcePasswordChange=true + target = change-password → allowed', () => {
    const route = { ...makeRoute(), name: 'change-password' } as unknown as RouteLocationNormalized
    const result = resolveGuard(route, authState({ forcePasswordChange: true }))
    expect(result).toBeNull()
  })

  it('forcePasswordChange=false → no redirect', () => {
    const route = makeRoute({ requiresAuth: true })
    const result = resolveGuard(route, authState({ forcePasswordChange: false }))
    expect(result).toBeNull()
  })

  // WO-SEC-58 HOLD-fix (P-65): the profile page is the second screen that can
  // complete the forced change (PUT /me/password) — it must stay reachable while
  // locked out, and everything else must still be blocked.
  it('forcePasswordChange=true + target = my-profile → allowed (WO-SEC-58)', () => {
    const route = { ...makeRoute(), name: 'my-profile' } as unknown as RouteLocationNormalized
    const result = resolveGuard(route, authState({ forcePasswordChange: true }))
    expect(result).toBeNull()
  })

  it('forcePasswordChange=true + target = dashboard → still redirected (exemption is not "see all")', () => {
    const route = { ...makeRoute({ requiresAuth: true }), name: 'dashboard' } as unknown as RouteLocationNormalized
    const result = resolveGuard(route, authState({ forcePasswordChange: true }))
    expect(result).toEqual({ name: 'change-password' })
  })
})
