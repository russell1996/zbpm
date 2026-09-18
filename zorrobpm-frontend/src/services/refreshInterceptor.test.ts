import { describe, it, expect, vi, beforeEach } from 'vitest'
import axios, { AxiosError, AxiosHeaders } from 'axios'
import type { InternalAxiosRequestConfig } from 'axios'
import { createRefreshInterceptor, type AuthCallbacks } from './refreshInterceptor'

function make401Error(config: Partial<InternalAxiosRequestConfig> = {}): AxiosError {
  const fullConfig: InternalAxiosRequestConfig = {
    url: config.url || '/test',
    method: config.method || 'GET',
    headers: new AxiosHeaders(),
    ...config,
  }
  const error = new AxiosError('Request failed with status code 401')
  error.response = {
    data: null,
    status: 401,
    statusText: 'Unauthorized',
    headers: new AxiosHeaders(),
    config: fullConfig,
  }
  error.config = fullConfig
  return error
}

function make403PcrError(config: Partial<InternalAxiosRequestConfig> = {}): AxiosError {
  const fullConfig: InternalAxiosRequestConfig = {
    url: config.url || '/test',
    method: config.method || 'GET',
    headers: new AxiosHeaders(),
    ...config,
  }
  const error = new AxiosError('Forbidden')
  error.response = {
    data: { code: 'PASSWORD_CHANGE_REQUIRED', message: 'Password change required' },
    status: 403,
    statusText: 'Forbidden',
    headers: new AxiosHeaders(),
    config: fullConfig,
  }
  error.config = fullConfig
  return error
}

describe('createRefreshInterceptor', () => {
  let instance: ReturnType<typeof axios.create>
  let callbacks: AuthCallbacks

  beforeEach(() => {
    vi.clearAllMocks()
    instance = axios.create({ baseURL: 'http://localhost' })
    callbacks = {
      onPasswordChangeRequired: vi.fn(),
      onUnauthorized: vi.fn(),
    }
    createRefreshInterceptor(instance, callbacks)
  })

  // --- Criterion #6: 401 → refresh → retry original request ---

  it('retries original request after successful refresh on 401', async () => {
    const adapterSpy = vi.fn()
      .mockRejectedValueOnce(make401Error({ url: '/process-instances' }))
      .mockResolvedValueOnce({ data: { token: 'new-token' }, status: 200 })
      .mockResolvedValueOnce({ data: [{ id: '1' }], status: 200 })

    ;(instance.defaults as Record<string, unknown>).adapter = adapterSpy

    const response = await instance.get('/process-instances')
    expect(response.status).toBe(200)
    expect(adapterSpy).toHaveBeenCalledTimes(3)
  })

  // --- Does NOT refresh for auth endpoints ---

  it('does NOT attempt refresh for /auth/login 401', async () => {
    const adapterSpy = vi.fn()
      .mockRejectedValueOnce(make401Error({ url: '/auth/login' }))

    ;(instance.defaults as Record<string, unknown>).adapter = adapterSpy

    await expect(instance.post('/auth/login', { username: 'x', password: 'y' }))
      .rejects.toThrow()
    expect(adapterSpy).toHaveBeenCalledTimes(1)
  })

  it('does NOT attempt refresh for /auth/refresh 401', async () => {
    const adapterSpy = vi.fn()
      .mockRejectedValueOnce(make401Error({ url: '/auth/refresh' }))

    ;(instance.defaults as Record<string, unknown>).adapter = adapterSpy

    await expect(instance.post('/auth/refresh')).rejects.toThrow()
    expect(adapterSpy).toHaveBeenCalledTimes(1)
  })

  it('does NOT attempt refresh for /auth/logout 401', async () => {
    const adapterSpy = vi.fn()
      .mockRejectedValueOnce(make401Error({ url: '/auth/logout' }))

    ;(instance.defaults as Record<string, unknown>).adapter = adapterSpy

    await expect(instance.post('/auth/logout')).rejects.toThrow()
    expect(adapterSpy).toHaveBeenCalledTimes(1)
  })

  // --- Refresh fails → original error propagated ---

  it('rejects original error when refresh also fails', async () => {
    const adapterSpy = vi.fn()
      .mockRejectedValueOnce(make401Error({ url: '/process-instances' }))
      .mockRejectedValueOnce(make401Error({ url: '/auth/refresh' }))

    ;(instance.defaults as Record<string, unknown>).adapter = adapterSpy

    await expect(instance.get('/process-instances')).rejects.toThrow()
    expect(adapterSpy).toHaveBeenCalledTimes(2)
  })

  // --- Non-401 errors pass through ---

  it('does NOT attempt refresh for non-401 errors', async () => {
    const error500 = new AxiosError('Server Error')
    error500.response = {
      data: null,
      status: 500,
      statusText: 'Internal Server Error',
      headers: new AxiosHeaders(),
      config: { url: '/test', method: 'GET', headers: new AxiosHeaders() },
    }
    error500.config = { url: '/test', method: 'GET', headers: new AxiosHeaders() }

    const adapterSpy = vi.fn().mockRejectedValueOnce(error500)

    ;(instance.defaults as Record<string, unknown>).adapter = adapterSpy

    await expect(instance.get('/test')).rejects.toThrow()
    expect(adapterSpy).toHaveBeenCalledTimes(1)
  })

  // --- WO-AUTH-2 criterion 2: parallel 401s → exactly ONE /auth/refresh ---

  function refreshCallCount(adapterSpy: ReturnType<typeof vi.fn>): number {
    return adapterSpy.mock.calls.filter(
      (args) => (args[0] as { url?: string })?.url === '/auth/refresh',
    ).length
  }

  /**
   * Faithful 401 harness: the thrown error carries the RECEIVED config (like
   * real axios does), so request flags (`_retry`) survive into the handler.
   * A mock that builds a fresh config would silently drop `_retry` and prove
   * nothing about retry-guard behavior.
   */
  function err401WithCfg(cfg: InternalAxiosRequestConfig): AxiosError {
    const error = new AxiosError('Request failed with status code 401')
    error.response = {
      data: null,
      status: 401,
      statusText: 'Unauthorized',
      headers: new AxiosHeaders(),
      config: cfg,
    }
    error.config = cfg
    return error
  }

  it('parallel 401s trigger exactly one refresh call (single-flight)', async () => {
    type Cfg = InternalAxiosRequestConfig & { _retry?: boolean }
    const adapterSpy = vi.fn(async (cfg: Cfg) => {
      if (cfg.url === '/auth/refresh') return { data: { token: 'new-token' }, status: 200 }
      if (cfg._retry) return { data: [{ id: 'retry-ok' }], status: 200 }
      throw err401WithCfg(cfg)
    })

    ;(instance.defaults as Record<string, unknown>).adapter = adapterSpy

    const [r1, r2, r3] = await Promise.all([
      instance.get('/process-instances'),
      instance.get('/user-tasks'),
      instance.get('/variables'),
    ])
    expect(r1.status).toBe(200)
    expect(r2.status).toBe(200)
    expect(r3.status).toBe(200)
    // 3 original + 1 refresh + 3 retries = 7 adapter calls, refresh exactly once
    expect(adapterSpy).toHaveBeenCalledTimes(7)
    expect(refreshCallCount(adapterSpy)).toBe(1)
  })

  // --- WO-AUTH-2: queued request whose retry ALSO 401s must logout, not loop ---

  it('queued retry that still 401s logs out without a second refresh (no loop)', async () => {
    const adapterSpy = vi.fn(async (cfg: InternalAxiosRequestConfig) => {
      if (cfg.url === '/auth/refresh') return { data: { token: 'new-token' }, status: 200 }
      // refresh "succeeded" but auth is still dead (e.g. version bumped elsewhere)
      throw err401WithCfg(cfg)
    })

    ;(instance.defaults as Record<string, unknown>).adapter = adapterSpy

    const results = await Promise.allSettled([
      instance.get('/process-instances'),
      instance.get('/user-tasks'),
    ])
    expect(results[0].status).toBe('rejected')
    expect(results[1].status).toBe('rejected')
    // exactly ONE refresh attempt total — the queued request must not start a new cycle
    expect(refreshCallCount(adapterSpy)).toBe(1)
    // real logout path taken (not a silent hang/loop)
    expect(callbacks.onUnauthorized).toHaveBeenCalled()
  })

  // --- WO-SEC-19: 403 PASSWORD_CHANGE_REQUIRED ---

  it('does NOT attempt refresh for 403 PASSWORD_CHANGE_REQUIRED (propagates error)', async () => {
    const adapterSpy = vi.fn()
      .mockRejectedValueOnce(make403PcrError({ url: '/process-instances' }))

    ;(instance.defaults as Record<string, unknown>).adapter = adapterSpy

    // 403 PCR should NOT trigger refresh (only 1 adapter call), and error should propagate
    await expect(instance.get('/process-instances')).rejects.toThrow()
    // Only 1 call: the original request, no refresh attempted
    expect(adapterSpy).toHaveBeenCalledTimes(1)
  })
})

describe('WO-QW-1 item 12: onUnauthorized fires at most once per refresh cycle', () => {
  let cycleInstance: ReturnType<typeof axios.create>
  let cycleCallbacks: AuthCallbacks

  beforeEach(() => {
    cycleInstance = axios.create({ baseURL: 'http://localhost' })
    cycleCallbacks = {
      onPasswordChangeRequired: vi.fn(),
      onUnauthorized: vi.fn(),
    }
    createRefreshInterceptor(cycleInstance, cycleCallbacks)
  })

  it('refresh-401 path calls onUnauthorized exactly once despite nested + catch paths', async () => {
    // /auth/refresh itself 401s: the nested-interceptor path (:61) AND the
    // initiator catch path (:91) both fire — the dedup flag must collapse them.
    const adapterSpy = vi.fn(async (cfg: { url?: string }) => {
      throw make401Error({ url: cfg.url || '/auth/refresh' })
    })
    ;(cycleInstance.defaults as Record<string, unknown>).adapter = adapterSpy

    await expect(cycleInstance.get('/process-instances')).rejects.toThrow()
    expect(cycleCallbacks.onUnauthorized).toHaveBeenCalledTimes(1)
  })

  it('N parallel retries with repeated 401s call onUnauthorized exactly once', async () => {
    type Cfg = { url?: string } & Record<string, unknown>
    const adapterSpy = vi.fn(async (cfg: Cfg) => {
      if (cfg.url === '/auth/refresh') return { data: { token: 'new-token' }, status: 200 }
      // every retry 401s again (dead auth)
      throw make401Error({ url: cfg.url, _retry: true } as Partial<InternalAxiosRequestConfig>)
    })
    ;(cycleInstance.defaults as Record<string, unknown>).adapter = adapterSpy

    const results = await Promise.allSettled([
      cycleInstance.get('/a'),
      cycleInstance.get('/b'),
      cycleInstance.get('/c'),
    ])
    expect(results.every((r) => r.status === 'rejected')).toBe(true)
    expect(cycleCallbacks.onUnauthorized).toHaveBeenCalledTimes(1)
  })

  it('separate dead cycles still notify each time (flag resets per round)', async () => {
    const adapterSpy = vi.fn(async (cfg: { url?: string }) => {
      throw make401Error({ url: cfg.url || '/x' })
    })
    ;(cycleInstance.defaults as Record<string, unknown>).adapter = adapterSpy

    await expect(cycleInstance.get('/first')).rejects.toThrow()
    await expect(cycleInstance.get('/second')).rejects.toThrow()
    expect(cycleCallbacks.onUnauthorized).toHaveBeenCalledTimes(2)
  })
})
