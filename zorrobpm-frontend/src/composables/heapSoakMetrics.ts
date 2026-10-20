/**
 * WO-UI-42 п.4 — метрика роста кучи в soak-прогоне.
 *
 * Раньше проверка кучи жила прямо в браузерном soak-тесте и стояла под
 * условием `if (замеры есть && samples.length >= 3)`: без замеров
 * (performance.memory недоступен) или при малом числе сэмплов проверка
 * просто НЕ ВЫПОЛНЯЛАСЬ — прогон оставался зелёным, ничего не доказывая.
 *
 * Здесь вынесен ЧИСТЫЙ разбор: решение принимается ВСЕГДА, отсутствие
 * замеров — тоже решение («проверка не выполнена» = провал проверки, а не
 * успех). Браузерный soak-тест вызывает её и падает на «не ok».
 */

/** Замеры soak-прогона: снимок кучи (МБ), узлов DOM и открытых EventSource. */
export interface HeapSoakSample {
  cycle: number
  /** usedJSHeapSize в МБ; null — метрика недоступна в этом браузере. */
  heapMb: number | null
  dom: number
  esOpen: number
}

/** Явный порог роста кучи за весь прогон (МБ). Реальный рост без утечек — единицы. */
export const HEAP_GROWTH_LIMIT_MB = 40

/** Минимум замеров, чтобы «до-после» было осмысленным сравнением. */
export const MIN_HEAP_SAMPLES = 3

export type HeapGrowthReason =
  /** замеров кучи нет вовсе (performance.memory недоступен) */
  | 'no-heap-measurements'
  /** замеров кучи слишком мало для сравнения «до-после» */
  | 'too-few-samples'
  /** куча выросла выше порога */
  | 'heap-growth'
  /** всё в порядке */
  | 'ok'

export interface HeapGrowthVerdict {
  ok: boolean
  reason: HeapGrowthReason
  /** Сколько сэмплов с замером кучи. */
  measured: number
  /** Рост кучи «после − до» (МБ); null — сравнивать нечего. */
  deltaMb: number | null
  firstMb: number | null
  lastMb: number | null
}

/**
 * Разбор замеров кучи. Fail-closed: отсутствие замеров — это провал
 * проверки (иначе soak молча ничего не проверяет).
 */
export function evaluateHeapGrowth(
  samples: HeapSoakSample[],
  limitMb: number = HEAP_GROWTH_LIMIT_MB,
): HeapGrowthVerdict {
  const measured = samples.filter((s) => s.heapMb != null)
  if (measured.length === 0) {
    return { ok: false, reason: 'no-heap-measurements', measured: 0, deltaMb: null, firstMb: null, lastMb: null }
  }
  if (measured.length < MIN_HEAP_SAMPLES) {
    return {
      ok: false,
      reason: 'too-few-samples',
      measured: measured.length,
      deltaMb: null,
      firstMb: measured[0].heapMb,
      lastMb: measured[measured.length - 1].heapMb,
    }
  }
  const firstMb = measured[0].heapMb as number
  const lastMb = measured[measured.length - 1].heapMb as number
  const deltaMb = lastMb - firstMb
  if (deltaMb >= limitMb) {
    return { ok: false, reason: 'heap-growth', measured: measured.length, deltaMb, firstMb, lastMb }
  }
  return { ok: true, reason: 'ok', measured: measured.length, deltaMb, firstMb, lastMb }
}
