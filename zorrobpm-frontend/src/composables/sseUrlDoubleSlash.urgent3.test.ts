/**
 * WO-URGENT-3: `VITE_API_URL='/'` (дефолт Dockerfile для топологии "внешний
 * прод-прокси") + наивная конкатенация `${base}/events/stream` даёт
 * `'//events/stream'` — protocol-relative URL с хостом `events`, который CSP
 * `'self'` блокирует. Realtime мёртв для всех пользователей прод-сборки.
 *
 * Ловит ровно этот баг: строгий ассерт на ОДИН слэш, а не `endsWith`
 * (старый `endsWith('/events/stream')` проходил и на `'//events/stream'`).
 */
import { describe, it, expect, vi, afterEach } from 'vitest'

afterEach(() => {
  vi.unstubAllEnvs()
})

describe('WO-URGENT-3 double-slash (criterion 1)', () => {
  it("buildStreamUrl() при VITE_API_URL='/' возвращает ровно /events/stream", async () => {
    vi.stubEnv('VITE_API_URL', '/')
    vi.resetModules()
    const { buildStreamUrl } = await import('@/composables/useRealtimeEvents')
    expect(buildStreamUrl()).toBe('/events/stream')
  })

  it("buildStreamUrl() при VITE_API_URL='/api/' без хвостового слэша", async () => {
    vi.stubEnv('VITE_API_URL', '/api/')
    vi.resetModules()
    const { buildStreamUrl } = await import('@/composables/useRealtimeEvents')
    expect(buildStreamUrl()).toBe('/api/events/stream')
  })

  it("buildStreamUrl() при VITE_API_URL='/api' как раньше", async () => {
    vi.stubEnv('VITE_API_URL', '/api')
    vi.resetModules()
    const { buildStreamUrl } = await import('@/composables/useRealtimeEvents')
    expect(buildStreamUrl()).toBe('/api/events/stream')
  })
})

describe('WO-URGENT-3 double-slash (criterion 2)', () => {
  it("buildRefreshUrl() при VITE_API_URL='/' возвращает ровно /auth/refresh", async () => {
    vi.stubEnv('VITE_API_URL', '/')
    vi.resetModules()
    const { buildRefreshUrl } = await import('@/composables/useRealtimeEvents')
    expect(buildRefreshUrl()).toBe('/auth/refresh')
  })

  it("buildRefreshUrl() при VITE_API_URL='/api/' без хвостового слэша", async () => {
    vi.stubEnv('VITE_API_URL', '/api/')
    vi.resetModules()
    const { buildRefreshUrl } = await import('@/composables/useRealtimeEvents')
    expect(buildRefreshUrl()).toBe('/api/auth/refresh')
  })
})

describe('WO-URGENT-3 double-slash (criterion 3)', () => {
  it("axios baseURL-резолв при VITE_API_URL='/' не даёт //auth/refresh", async () => {
    vi.stubEnv('VITE_API_URL', '/')
    vi.resetModules()
    const { default: api } = await import('@/services/api')
    // baseURL как есть — '/', axios сам нормализует при джойне:
    const resolved = api.getUri({ url: '/auth/refresh' })
    expect(resolved).toBe('/auth/refresh')
    expect(resolved.startsWith('//')).toBe(false)
  })
})
