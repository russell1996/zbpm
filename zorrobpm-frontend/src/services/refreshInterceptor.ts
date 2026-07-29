import type { AxiosError, AxiosInstance, InternalAxiosRequestConfig } from 'axios'

/**
 * Callbacks provided by the app wiring layer to handle auth-related events
 * without importing the auth store directly (avoids circular dependency).
 */
export interface AuthCallbacks {
  /** Called when a 403 PASSWORD_CHANGE_REQUIRED is received */
  onPasswordChangeRequired: () => void
  /** Called when a non-login 401 reaches the interceptor */
  onUnauthorized: () => void
}

/**
 * Auto-refresh interceptor: on 401, attempts a single /auth/refresh,
 * and retries the original request if successful.
 * Excludes /auth/login, /auth/refresh, and /auth/logout from refresh attempts.
 *
 * WO-SEC-19: Also handles 403 PASSWORD_CHANGE_REQUIRED via callbacks.onPasswordChangeRequired.
 * WO-FE-8: callbacks injected to break circular import api ↔ auth.
 */
export function createRefreshInterceptor(instance: AxiosInstance, callbacks: AuthCallbacks): void {
  let isRefreshing = false
  let pendingQueue: Array<{
    resolve: (token: string) => void
    reject: (err: unknown) => void
  }> = []

  function processPendingQueue(error: unknown, token: string | null) {
    pendingQueue.forEach(({ resolve, reject }) => {
      if (error || !token) reject(error)
      else resolve(token)
    })
    pendingQueue = []
  }

  instance.interceptors.response.use(
    (response) => response,
    async (error: AxiosError) => {
      const originalRequest = error.config as InternalAxiosRequestConfig & { _retry?: boolean }
      const url: string = originalRequest?.url || ''

      // WO-SEC-19: Handle 403 PASSWORD_CHANGE_REQUIRED
      if (error.response?.status === 403) {
        const body = error.response?.data as Record<string, unknown> | undefined
        if (body?.code === 'PASSWORD_CHANGE_REQUIRED') {
          callbacks.onPasswordChangeRequired()
          return Promise.reject(error)
        }
      }

      // Skip refresh for auth endpoints and already-retried requests
      const isAuthEndpoint =
        url.includes('/auth/login') ||
        url.includes('/auth/refresh') ||
        url.includes('/auth/logout')

      if (error.response?.status !== 401 || isAuthEndpoint || originalRequest?._retry) {
        // After refresh failed or 401 on auth endpoint, sign out (but not for login itself)
        if (error.response?.status === 401 && !url.includes('/auth/login')) {
          callbacks.onUnauthorized()
        }
        return Promise.reject(error)
      }

      if (isRefreshing) {
        // Another refresh is in progress — queue this request
        return new Promise<string>((resolve, reject) => {
          pendingQueue.push({ resolve, reject })
        }).then(() => instance(originalRequest))
      }

      isRefreshing = true
      originalRequest._retry = true

      try {
        // Attempt refresh (cookie is sent automatically with withCredentials: true)
        await instance.post('/auth/refresh')
        processPendingQueue(null, 'ok')
        return instance(originalRequest)
      } catch (refreshError) {
        processPendingQueue(refreshError, null)
        // Refresh failed — sign out (but not for login itself)
        if (!url.includes('/auth/login')) {
          callbacks.onUnauthorized()
        }
        return Promise.reject(error)
      } finally {
        isRefreshing = false
      }
    },
  )
}
