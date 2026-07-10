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
  // --- Proof-of-failure (V3): guard without requiresAdmin lets USER through ---
  it('USER passes when route has no requiresAdmin', () => {
    const route = makeRoute({ requiresAuth: true })
    const auth = { isAuthenticated: true, isAdmin: false }
    expect(resolveGuard(route, auth)).toBeNull()
  })

  // --- Criterion #5: USER on admin route → redirect ---
  it('USER on admin route → access-denied', () => {
    const route = makeRoute({ requiresAdmin: true })
    const auth = { isAuthenticated: true, isAdmin: false }
    expect(resolveGuard(route, auth)).toEqual({ name: 'access-denied' })
  })

  // --- Criterion #6: ADMIN on admin route → allowed ---
  it('ADMIN on admin route → allowed', () => {
    const route = makeRoute({ requiresAdmin: true })
    const auth = { isAuthenticated: true, isAdmin: true }
    expect(resolveGuard(route, auth)).toBeNull()
  })

  // --- Criterion #7: proof-of-failure was RED (USER passed), now GREEN ---
  it('non-admin on requiresAdmin route blocks access', () => {
    const route = makeRoute({ requiresAdmin: true })
    const auth = { isAuthenticated: true, isAdmin: false }
    const result = resolveGuard(route, auth)
    expect(result).not.toBeNull()
    expect(result!.name).toBe('access-denied')
  })

  // --- Edge cases ---
  it('unauthenticated user on admin route → blocked (auth guard handles redirect)', () => {
    const route = makeRoute({ requiresAdmin: true })
    const auth = { isAuthenticated: false, isAdmin: false }
    // Auth guard runs first; this guard only checks role
    expect(resolveGuard(route, auth)).toEqual({ name: 'access-denied' })
  })

  it('route without meta → no redirect', () => {
    const route = makeRoute({})
    const auth = { isAuthenticated: true, isAdmin: false }
    expect(resolveGuard(route, auth)).toBeNull()
  })
})
