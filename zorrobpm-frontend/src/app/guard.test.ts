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

describe('resolveGuard', () => {
  it('USER passes when route has no requiresAdmin', () => {
    const route = makeRoute({ requiresAuth: true })
    const auth = { isAuthenticated: true, isAdmin: false, isSuperAdmin: false }
    expect(resolveGuard(route, auth)).toBeNull()
  })

  it('USER on admin route → access-denied', () => {
    const route = makeRoute({ requiresAdmin: true })
    const auth = { isAuthenticated: true, isAdmin: false, isSuperAdmin: false }
    expect(resolveGuard(route, auth)).toEqual({ name: 'access-denied' })
  })

  it('ADMIN on admin route → allowed', () => {
    const route = makeRoute({ requiresAdmin: true })
    const auth = { isAuthenticated: true, isAdmin: true, isSuperAdmin: false }
    expect(resolveGuard(route, auth)).toBeNull()
  })

  it('non-admin on requiresAdmin route blocks access', () => {
    const route = makeRoute({ requiresAdmin: true })
    const auth = { isAuthenticated: true, isAdmin: false, isSuperAdmin: false }
    const result = resolveGuard(route, auth)
    expect(result).not.toBeNull()
    expect(result!.name).toBe('access-denied')
  })

  it('unauthenticated user on admin route → blocked', () => {
    const route = makeRoute({ requiresAdmin: true })
    const auth = { isAuthenticated: false, isAdmin: false, isSuperAdmin: false }
    expect(resolveGuard(route, auth)).toEqual({ name: 'access-denied' })
  })

  it('route without meta → no redirect', () => {
    const route = makeRoute({})
    const auth = { isAuthenticated: true, isAdmin: false, isSuperAdmin: false }
    expect(resolveGuard(route, auth)).toBeNull()
  })
})

/**
 * WO-MT-9: requiresSuperAdmin guard cases.
 */
describe('resolveGuard — requiresSuperAdmin', () => {
  it('SUPER_ADMIN on requiresSuperAdmin route → allowed', () => {
    const route = makeRoute({ requiresSuperAdmin: true })
    const auth = { isAuthenticated: true, isAdmin: true, isSuperAdmin: true }
    expect(resolveGuard(route, auth)).toBeNull()
  })

  it('ADMIN on requiresSuperAdmin route → denied', () => {
    const route = makeRoute({ requiresSuperAdmin: true })
    const auth = { isAuthenticated: true, isAdmin: true, isSuperAdmin: false }
    expect(resolveGuard(route, auth)).toEqual({ name: 'access-denied' })
  })

  it('USER on requiresSuperAdmin route → denied', () => {
    const route = makeRoute({ requiresSuperAdmin: true })
    const auth = { isAuthenticated: true, isAdmin: false, isSuperAdmin: false }
    expect(resolveGuard(route, auth)).toEqual({ name: 'access-denied' })
  })

  it('SUPER_ADMIN on requiresAdmin route → also allowed (SUPER_ADMIN ⊇ ADMIN)', () => {
    const route = makeRoute({ requiresAdmin: true })
    const auth = { isAuthenticated: true, isAdmin: true, isSuperAdmin: true }
    expect(resolveGuard(route, auth)).toBeNull()
  })
})

/**
 * WO-FE-hotfix: store.isAdmin includes SUPER_ADMIN.
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
      const result = resolveGuard(route, { isAuthenticated: true, isAdmin, isSuperAdmin })
      expect(result, `isAdmin=${isAdmin}, isSuperAdmin=${isSuperAdmin}`).toEqual(expected)
    }
  })
})
