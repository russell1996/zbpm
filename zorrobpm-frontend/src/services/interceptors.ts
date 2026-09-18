import api from './api'
import { useAuthStore } from '@/stores/auth'
import { createRefreshInterceptor } from './refreshInterceptor'

/**
 * Set up API interceptors that require the auth store.
 * Called once during app initialization.
 * Separate from api.ts to break the circular dependency:
 *   api → refreshInterceptor → auth (via useAuthStore) → api
 */
export function setupApiInterceptors(): void {
  createRefreshInterceptor(api, {
    onPasswordChangeRequired: () => {
      const auth = useAuthStore()
      if (auth.isAuthenticated) {
        auth.setForcePasswordChange(true)
        window.location.href = '/ui/change-password'
      }
    },
    onUnauthorized: () => {
      const auth = useAuthStore()
      if (auth.isAuthenticated) auth.logout()
    },
  })
}
