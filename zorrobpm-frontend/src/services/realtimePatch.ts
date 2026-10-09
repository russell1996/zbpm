import type { EventEnvelope } from '@/types/api'

/**
 * WO-UI-26 Доп.4 — адресные патчи вместо полного refetch на каждое событие.
 *
 * Конверт уже несёт адрес изменения (type/processInstanceId/elementId/
 * processDefinitionKey/sequence/data): обновление идёт по событию, по ключу.
 * Полный refetch — только запасной путь (разрыв sequence, реконнект, возврат
 * на скрытую вкладку, ошибка применения патча).
 *
 * Порядок и идемпотентность: патчи применяются по sequence; повтор (тот же
 * id/sequence) и событие старее применённого — no-op. Состояние — на стор
 * (lastAppliedSequence + seenIds), чтобы переживать всплески в рамках сессии.
 */
export interface PatchTracker {
  /** Можно ли применить событие как патч (false → нужен запасной refetch). */
  shouldPatch: (envelope: EventEnvelope) => boolean
  /** Отметить событие применённым (патчем или refetch). */
  markApplied: (envelope: EventEnvelope) => void
  /** Последний применённый sequence (для тестов/диагностики). */
  lastSequence: () => number
}

export function createPatchTracker(): PatchTracker {
  let lastSequence = 0
  const seenIds = new Set<string>()
  // Память ограничена: старые id вытесняем (sequence монотонна, окно 5000
  // покрывает любой реальный дубль/реконнект без утечки).
  const seenOrder: string[] = []
  const SEEN_CAP = 5000

  function remember(id: string): void {
    if (seenIds.has(id)) return
    seenIds.add(id)
    seenOrder.push(id)
    if (seenOrder.length > SEEN_CAP) {
      const drop = seenOrder.splice(0, seenOrder.length - SEEN_CAP)
      for (const d of drop) seenIds.delete(d)
    }
  }

  return {
    shouldPatch(envelope: EventEnvelope): boolean {
      // Дубль (тот же id) — no-op, refetch не нужен вообще.
      if (envelope.id && seenIds.has(envelope.id)) return false
      // Устаревшее (sequence не новее применённого) — no-op.
      if (typeof envelope.sequence === 'number' && envelope.sequence <= lastSequence) {
        return false
      }
      // Разрыв (пропущены события) — патч нельзя, нужен refetch.
      // Первый sequence сессии неизвестен: sequence<=1 считаем стартом без разрыва.
      if (typeof envelope.sequence === 'number' && lastSequence > 0 && envelope.sequence > lastSequence + 1) {
        return false
      }
      return true
    },
    markApplied(envelope: EventEnvelope): void {
      if (envelope.id) remember(envelope.id)
      if (typeof envelope.sequence === 'number' && envelope.sequence > lastSequence) {
        lastSequence = envelope.sequence
      }
    },
    lastSequence: () => lastSequence,
  }
}
