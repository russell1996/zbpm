import type { RouteLocationNormalized } from 'vue-router'

interface AuthState {
  isAuthenticated: boolean
  isAdmin: boolean
}

/**
 * Route guard: checks if the user has the required role for the route.
 * Returns the redirect target or null if access is allowed.
 */
export function resolveGuard(
  to: RouteLocationNormalized,
  auth: AuthState,
): { name: string } | null {
  if (to.meta.requiresAdmin && !auth.isAdmin) {
    return { name: 'access-denied' }
  }
  return null
}
