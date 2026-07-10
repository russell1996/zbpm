import type { AxiosError, AxiosInstance, InternalAxiosRequestConfig } from 'axios'

/**
 * Auto-refresh interceptor: on 401, attempts a single /auth/refresh,
 * and retries the original request if successful.
 * Excludes /auth/login, /auth/refresh, and /auth/logout from refresh attempts.
 */
export function createRefreshInterceptor(instance: AxiosInstance): void {
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

      // Skip refresh for auth endpoints and already-retried requests
      const isAuthEndpoint =
        url.includes('/auth/login') ||
        url.includes('/auth/refresh') ||
        url.includes('/auth/logout')

      if (error.response?.status !== 401 || isAuthEndpoint || originalRequest?._retry) {
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
        return Promise.reject(error)
      } finally {
        isRefreshing = false
      }
    },
  )
}
