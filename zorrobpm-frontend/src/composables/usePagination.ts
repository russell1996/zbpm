import { ref, computed } from 'vue'

/**
 * Shared pagination logic for list pages.
 *
 * @param getTotalElements - getter returning totalElements from the store (undefined while loading)
 * @param size - page size (default 10)
 */
export function usePagination(getTotalElements: () => number | undefined, size = 10) {
  const page = ref(0)
  const pageSize = size

  const hasNext = computed(() => {
    const total = getTotalElements()
    return total !== undefined && (page.value + 1) * pageSize < total
  })

  const hasPrev = computed(() => page.value > 0)

  function nextPage() {
    if (hasNext.value) page.value++
  }

  function prevPage() {
    if (hasPrev.value) page.value--
  }

  function resetPage() {
    page.value = 0
  }

  return { page, pageSize, nextPage, prevPage, hasNext, hasPrev, resetPage }
}
