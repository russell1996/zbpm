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
 *
 * WO-QW-5 (NEW2-10): общий single-flight refresh для axios-пути И SSE-пути
 * (`useRealtimeEvents.refreshAndReconnect` делал прямой `fetch` мимо
 * `isRefreshing`: одновременный 401-refresh от axios гонялся за ту же
 * ротируемую refresh-куку — проигравший получал «already rotated», и если
 * проигрывал axios-путь, пользователя разлогинивало). Модульный promise
 * `sharedRefresh()`: кто первый начал — делает POST, остальные ждут тот же
 * promise; сброс в finally. SSE-сторона зовёт его же вместо прямого fetch
 * (креда `include` — та же ротируемая кука, тот же один refresh).
 */
let sharedRefreshPromise: Promise<boolean> | null = null

export function sharedRefresh(instance: AxiosInstance): Promise<boolean> {
  if (sharedRefreshPromise) {
    return sharedRefreshPromise
  }
  sharedRefreshPromise = instance
    .post('/auth/refresh')
    .then(() => true)
    .catch(() => false)
    .finally(() => {
      sharedRefreshPromise = null
    })
  return sharedRefreshPromise
}
export function createRefreshInterceptor(instance: AxiosInstance, callbacks: AuthCallbacks): void {
  let isRefreshing = false
  // WO-QW-1 (item 12): onUnauthorized must fire at most once per refresh cycle —
  // today the outer guard (interceptors.ts: `if (auth.isAuthenticated)`) makes the
  // double-call a no-op, but that must not be the only thing standing between us
  // and a double logout/side-effect if the guard is ever weakened. The flag resets
  // when a refresh round starts, so consecutive dead cycles still logout each time.
  let unauthorizedNotified = false
  function notifyUnauthorizedOnce(): void {
    if (unauthorizedNotified) return
    unauthorizedNotified = true
    callbacks.onUnauthorized()
  }
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
          notifyUnauthorizedOnce()
        }
        return Promise.reject(error)
      }

      if (isRefreshing) {
        // Another refresh is in progress — queue this request.
        // WO-AUTH-2: mark it retried NOW, not on retry: without this, a queued
        // request whose retry ALSO 401s (refresh "succeeded" but auth is still
        // dead, e.g. token_version bumped elsewhere) would start a brand-new
        // refresh cycle — an infinite 401→refresh→401 loop with no logout.
        // With _retry set, the second 401 goes to onUnauthorized (real logout).
        originalRequest._retry = true
        return new Promise<string>((resolve, reject) => {
          pendingQueue.push({ resolve, reject })
        }).then(() => instance(originalRequest))
      }

      isRefreshing = true
      originalRequest._retry = true
      unauthorizedNotified = false

      try {
        // Attempt refresh (cookie is sent automatically with withCredentials: true).
        // WO-QW-5: через sharedRefresh — SSE-сторона ждёт тот же promise,
        // второго POST на ту же ротируемую куку не будет.
        const ok = await sharedRefresh(instance)
        if (!ok) throw new Error('refresh failed')
        processPendingQueue(null, 'ok')
        return instance(originalRequest)
      } catch (refreshError) {
        processPendingQueue(refreshError, null)
        // Refresh failed — sign out (but not for login itself)
        if (!url.includes('/auth/login')) {
          notifyUnauthorizedOnce()
        }
        return Promise.reject(error)
      } finally {
        isRefreshing = false
      }
    },
  )
}
