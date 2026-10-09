// @vitest-environment node
/**
 * WO-UI-25 критерий 2 — контракт событий «фронт ⊇ бэк».
 *
 * Backend-источник истины — снимок значений DomainEventType
 * (`./backend-event-types.json`, копия строковых Value из
 * `zorrobpm-contract/.../dto/event/DomainEventType.java`). Тест читает ТОЛЬКО
 * этот снимок: фронт-сборка в CI (`docker build`, контекст `zorrobpm-frontend/`)
 * не видит файлов бэкенда, прямое чтение `.java` давало ENOENT (пост-мерж
 * дефект на master 4ea33378, pipeline 177171). Свежесть снимка сторожит
 * бэкенд-тест `DomainEventTypeSnapshotTest` (zorrobpm-contract) — расхождение
 * enum/снимок краснит его, а не молчит.
 *
 * Каждое значение снимка обязано быть подписано фронтом
 * (REALTIME_EVENT_TYPES), кроме явного списка IGNORED (UI не нужно).
 * Добавление нового типа на бэке без решения на фронте КРАСНИТ этот тест —
 * потеря больше не молчаливая.
 */
import { describe, it, expect } from 'vitest'
import snapshot from './backend-event-types.json'
import { REALTIME_EVENT_TYPES } from './useRealtimeEvents'

/** Типы, которые сервер шлёт, но UI осознанно не слушает (решение, не пропуск). */
const IGNORED_BACKEND_TYPES = ['outbox.quarantined']

function backendEventTypes(): string[] {
  const values = (snapshot as { values: unknown }).values
  expect(Array.isArray(values)).toBe(true)
  const list = values as string[]
  expect(list.length).toBeGreaterThan(0)
  expect(new Set(list).size).toBe(list.length)
  return list
}

describe('WO-UI-25 criterion 2: frontend subscription covers backend event types', () => {
  it('every snapshotted backend event type is subscribed, except the explicit ignore-list', () => {
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
