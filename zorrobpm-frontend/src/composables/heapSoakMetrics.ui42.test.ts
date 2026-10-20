// @vitest-environment jsdom
/**
 * WO-UI-42 п.4 — проверка кучи в soak-прогоне больше не может «пройти молча».
 *
 * Старая форма (в браузерном soak-тесте):
 *   if (first?.heapMb != null && last?.heapMb != null && samples.length >= 3) {
 *     expect(last.heapMb - first.heapMb).toBeLessThan(40)
 *   }
 * — условие не выполнилось → утверждение НЕ выполнялось → прогон зелёный,
 * хотя про кучу ничего не проверено (нет performance.memory, мало сэмплов).
 *
 * Здесь проверяется чистый разбор: отсутствие замеров — провал проверки.
 */
import { describe, it, expect } from 'vitest'
import {
  evaluateHeapGrowth,
  HEAP_GROWTH_LIMIT_MB,
  MIN_HEAP_SAMPLES,
  type HeapSoakSample,
} from './heapSoakMetrics'

function sample(heapMb: number | null, cycle = 1): HeapSoakSample {
  return { cycle, heapMb, dom: 100, esOpen: 1 }
}

describe('WO-UI-42 п.4: разбор замеров кучи (fail-closed)', () => {
  it('плоская куча — ok, дельта считается «после − до»', () => {
    const verdict = evaluateHeapGrowth([sample(100), sample(101), sample(102)])
    expect(verdict.ok).toBe(true)
    expect(verdict.reason).toBe('ok')
    expect(verdict.measured).toBe(3)
    expect(verdict.firstMb).toBe(100)
    expect(verdict.lastMb).toBe(102)
    expect(verdict.deltaMb).toBe(2)
  })

  it('замеров кучи нет вовсе — проверка ПРОВАЛЕНА (а не пропущена молча)', () => {
    const verdict = evaluateHeapGrowth([sample(null), sample(null), sample(null)])
    expect(verdict.ok).toBe(false)
    expect(verdict.reason).toBe('no-heap-measurements')
    expect(verdict.measured).toBe(0)
    expect(verdict.deltaMb).toBeNull()
  })

  it('замеров кучи меньше минимума — проверка ПРОВАЛЕНА (сравнивать нечего)', () => {
    const verdict = evaluateHeapGrowth([sample(100), sample(101)])
    expect(verdict.ok).toBe(false)
    expect(verdict.reason).toBe('too-few-samples')
    expect(verdict.measured).toBe(2)
  })

  it('пустой набор замеров — провал (не «нулевого роста»)', () => {
    const verdict = evaluateHeapGrowth([])
    expect(verdict.ok).toBe(false)
    expect(verdict.reason).toBe('no-heap-measurements')
  })

  it('утечка кучи (рост выше порога) — провал с измеримой дельтой', () => {
    const verdict = evaluateHeapGrowth([sample(100), sample(150), sample(220)])
    expect(verdict.ok).toBe(false)
    expect(verdict.reason).toBe('heap-growth')
    expect(verdict.deltaMb).toBe(120)
    expect(verdict.deltaMb!).toBeGreaterThan(HEAP_GROWTH_LIMIT_MB)
  })

  it('рост ровно на пороге — провал (порог строгий: «меньше порога»)', () => {
    const verdict = evaluateHeapGrowth([sample(100), sample(120), sample(100 + HEAP_GROWTH_LIMIT_MB)])
    expect(verdict.ok).toBe(false)
    expect(verdict.reason).toBe('heap-growth')
    expect(verdict.deltaMb).toBe(HEAP_GROWTH_LIMIT_MB)
  })

  it('порог задан явно и замеров достаточно — константы согласованы', () => {
    expect(HEAP_GROWTH_LIMIT_MB).toBe(40)
    expect(MIN_HEAP_SAMPLES).toBe(3)
    const justEnough = evaluateHeapGrowth([sample(10), sample(11), sample(12)])
    expect(justEnough.ok).toBe(true)
  })

  it('сэмплы без замера кучи не портят сравнение «до-после»', () => {
    const verdict = evaluateHeapGrowth([
      sample(null),
      sample(100),
      sample(null),
      sample(105),
      sample(null),
      sample(106),
    ])
    expect(verdict.ok).toBe(true)
    expect(verdict.measured).toBe(3)
    expect(verdict.firstMb).toBe(100)
    expect(verdict.lastMb).toBe(106)
  })

  it('два замера среди пустых — всё равно «слишком мало» (fail-closed)', () => {
    const verdict = evaluateHeapGrowth([sample(null), sample(100), sample(null), sample(105)])
    expect(verdict.ok).toBe(false)
    expect(verdict.reason).toBe('too-few-samples')
    expect(verdict.measured).toBe(2)
  })
})
