import { describe, it, expect, vi, beforeEach } from 'vitest'
import axios, { AxiosError, AxiosHeaders } from 'axios'
import type { InternalAxiosRequestConfig } from 'axios'
import { createRefreshInterceptor } from './refreshInterceptor'

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

describe('createRefreshInterceptor', () => {
  let instance: ReturnType<typeof axios.create>

  beforeEach(() => {
    vi.restoreAllMocks()
    instance = axios.create({ baseURL: 'http://localhost' })
    createRefreshInterceptor(instance)
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
})
