import { describe, it, expect, vi } from 'vitest'
import { usePagination } from './usePagination'

describe('usePagination', () => {
  it('starts at page 0 with hasNext=true when total > pageSize', () => {
    const { page, pageSize, hasNext, hasPrev } = usePagination(() => 25, 10)
    expect(page.value).toBe(0)
    expect(pageSize).toBe(10)
    expect(hasNext.value).toBe(true)
    expect(hasPrev.value).toBe(false)
  })

  it('nextPage increments page', () => {
    const { page, nextPage } = usePagination(() => 25, 10)
    nextPage()
    expect(page.value).toBe(1)
  })

  it('prevPage decrements page', () => {
    const { page, prevPage } = usePagination(() => 25, 10)
    page.value = 2
    prevPage()
    expect(page.value).toBe(1)
  })

  it('prevPage is no-op at page 0', () => {
    const { page, prevPage } = usePagination(() => 25, 10)
    prevPage()
    expect(page.value).toBe(0)
  })

  it('nextPage is no-op at last page', () => {
    const { page, nextPage } = usePagination(() => 25, 10)
    page.value = 2 // (2+1)*10 = 30 >= 25 → last page
    nextPage()
    expect(page.value).toBe(2)
  })

  it('hasNext is false on last page', () => {
    const { page, hasNext } = usePagination(() => 25, 10)
    page.value = 2
    expect(hasNext.value).toBe(false)
  })

  it('hasPrev is true after first page', () => {
    const { page, hasPrev } = usePagination(() => 25, 10)
    page.value = 1
    expect(hasPrev.value).toBe(true)
  })

  it('resetPage sets page back to 0', () => {
    const { page, resetPage } = usePagination(() => 25, 10)
    page.value = 3
    resetPage()
    expect(page.value).toBe(0)
  })

  it('handles undefined totalElements (loading state)', () => {
    const { hasNext, hasPrev } = usePagination(() => undefined, 10)
    expect(hasNext.value).toBe(false)
    expect(hasPrev.value).toBe(false)
  })
})
