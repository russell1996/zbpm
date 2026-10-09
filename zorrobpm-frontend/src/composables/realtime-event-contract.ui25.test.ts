// @vitest-environment node
/**
 * WO-UI-25 критерий 2 — контракт событий «фронт ⊇ бэк».
 *
 * Backend-источник истины — DomainEventType.java (zorrobpm-contract): каждое
 * значение Value обязано быть подписано фронтом (REALTIME_EVENT_TYPES), кроме
 * явного списка IGNORED (UI не нужно). Добавление нового типа на бэке без
 * решения на фронте КРАСНИТ этот тест — потеря больше не молчаливая.
 *
 * RED на коде до WO-UI-25: activity.completed, user-task.assigned,
 * user-task.unassigned отсутствуют в подписке.
 */
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { resolve, dirname } from 'path'
import { fileURLToPath } from 'url'
import { REALTIME_EVENT_TYPES } from './useRealtimeEvents'

/** Типы, которые сервер шлёт, но UI осознанно не слушает (решение, не пропуск). */
const IGNORED_BACKEND_TYPES = ['outbox.quarantined']

function backendEventTypes(): string[] {
  const source = readFileSync(
    resolve(
      dirname(fileURLToPath(import.meta.url)),
      '../../../zorrobpm-contract/src/main/java/com/zorrodev/bpm/contract/dto/event/DomainEventType.java',
    ),
    'utf-8',
  )
  const values = [...source.matchAll(/\(\s*"([^"]+)"\s*\)/g)].map((m) => m[1])
  expect(values.length).toBeGreaterThan(0)
  return values
}

describe('WO-UI-25 criterion 2: frontend subscription covers backend event types', () => {
  it('every backend DomainEventType is subscribed, except the explicit ignore-list', () => {
    const missing = backendEventTypes().filter(
      (t) => !IGNORED_BACKEND_TYPES.includes(t)
        && !(REALTIME_EVENT_TYPES as readonly string[]).includes(t),
    )
    expect(missing).toEqual([])
  })

  it('the ignore-list names only types the backend really emits (no stale entries)', () => {
    const backend = backendEventTypes()
    for (const ignored of IGNORED_BACKEND_TYPES) {
      expect(backend).toContain(ignored)
    }
  })

  it('mutation guard: activity.completed (70% of the prod stream) is subscribed', () => {
    expect(REALTIME_EVENT_TYPES as readonly string[]).toContain('activity.completed')
  })

  it('mutation guard: user-task.assigned/unassigned are subscribed', () => {
    expect(REALTIME_EVENT_TYPES as readonly string[]).toContain('user-task.assigned')
    expect(REALTIME_EVENT_TYPES as readonly string[]).toContain('user-task.unassigned')
  })
})
