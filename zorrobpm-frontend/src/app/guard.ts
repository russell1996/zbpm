import type { RouteLocationNormalized } from 'vue-router'

interface AuthState {
  isAuthenticated: boolean
  isAdmin: boolean
  isSuperAdmin: boolean
  forcePasswordChange: boolean
}

/**
 * Route guard: checks if the user has the required role for the route.
 * Returns the redirect target or null if access is allowed.
 */
export function resolveGuard(
  to: RouteLocationNormalized,
  auth: AuthState,
): { name: string } | null {
  // WO-SEC-19: force password change → block all routes except the two that can
  // complete or serve the unlock: 'change-password' (dedicated screen) and
  // 'my-profile' (WO-SEC-58: profile page calls PUT /me/password). Blocking
  // my-profile too made the fix unreachable exactly for locked-out users (P-65).
  if (
    auth.isAuthenticated &&
    auth.forcePasswordChange &&
    to.name !== 'change-password' &&
    to.name !== 'my-profile'
  ) {
    return { name: 'change-password' }
  }
  if (to.meta.requiresSuperAdmin && !auth.isSuperAdmin) {
    return { name: 'access-denied' }
  }
  if (to.meta.requiresAdmin && !auth.isAdmin) {
    return { name: 'access-denied' }
  }
  return null
}
