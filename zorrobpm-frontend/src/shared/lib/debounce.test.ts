// @vitest-environment jsdom
/**
 * WO-UI-18 часть B — debounce-хелпер: пачка синхронных вызовов схлопывается
 * в один trailing вызов с последними аргументами.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { debounce } from './debounce'

describe('debounce (WO-UI-18 B)', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('collapses rapid calls into one trailing call with the last args', () => {
    const fn = vi.fn()
    const debounced = debounce(fn, 250)
    debounced('a')
    debounced('b')
    debounced('c')
    expect(fn).not.toHaveBeenCalled()
    vi.advanceTimersByTime(250)
    expect(fn).toHaveBeenCalledTimes(1)
    expect(fn).toHaveBeenCalledWith('c')
  })

  it('fires again after the quiet period', () => {
    const fn = vi.fn()
    const debounced = debounce(fn, 250)
    debounced('a')
    vi.advanceTimersByTime(250)
    debounced('b')
    vi.advanceTimersByTime(250)
    expect(fn).toHaveBeenCalledTimes(2)
  })
})
