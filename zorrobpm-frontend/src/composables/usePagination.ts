import { ref, computed, watch, getCurrentInstance } from 'vue'
import { useRoute, useRouter } from 'vue-router'

/**
 * Shared pagination logic for list pages.
 *
 * WO-UI-24: `page` is synced with the `page` query parameter of the URL so that
 * a page survives unmount/remount (list → detail → back) and can be deep-linked.
 *
 * Convention (documented, consistent everywhere):
 * - internally 0-based (`pageIndex` as the backend expects);
 * - in the URL 1-based human-readable (`?page=4` = 4-я страница, matches the
 *   `page + 1` label already rendered by the list pages);
 * - first page (0) is represented by the ABSENCE of the parameter (clean
 *   canonical URL; existing bookmarks without `?page` keep working);
 * - invalid values (`?page=abc`, `?page=0`, `?page=-3`, `?page=2.5`) → page 0,
 *   the URL is normalized to canonical on mount.
 *
 * URL writes go through `router.replace` (never `push`) so next/prev clicks
 * don't grow the browser history; other query params are preserved.
 * External query changes (browser back/forward) are picked up via a watcher,
 * so the state follows the URL in both directions.
 *
 * Router is optional: when the composable runs outside a router-enabled setup
 * context (plain unit tests calling it directly), it falls back to the old
 * local-ref-only behavior — no signature change for existing callers.
 *
 * @param getTotalElements - getter returning totalElements from the store (undefined while loading)
 * @param size - page size (default 10)
 */
export function usePagination(getTotalElements: () => number | undefined, size = 10) {
  const pageSize = size

  // useRoute()/useRouter() warn and return undefined outside a setup context
  // (plain unit tests call this composable directly) — check first, don't invent.
  // Partial vue-router mocks in existing tests (useRoute without `query`,
  // useRouter without `replace`) also mean "no usable router" → same fallback.
  const inSetup = getCurrentInstance() !== null
  let route: { query: Record<string, unknown> } | null = null
  let router: { replace: (to: unknown) => unknown } | null = null
  if (inSetup) {
    try {
      const r = useRoute() as unknown
      const ro = useRouter() as unknown
      if (
        r !== null && typeof r === 'object' &&
        (r as { query?: unknown }).query !== null &&
        typeof (r as { query?: unknown }).query === 'object' &&
        ro !== null && typeof ro === 'object' &&
        typeof (ro as { replace?: unknown }).replace === 'function'
      ) {
        route = r as { query: Record<string, unknown> }
        router = ro as { replace: (to: unknown) => unknown }
      }
    } catch {
      route = null
      router = null
    }
  }
  const synced = route !== null && router !== null

  function parsePageParam(raw: unknown): number {
    if (typeof raw !== 'string') return 0
    if (!/^\d+$/.test(raw)) return 0
    const n = Number(raw)
    // 1-based in URL → 0-based inside; ?page=0 is invalid → 0
    return n >= 1 ? n - 1 : 0
  }

  /** Canonical query value for a 0-based page: null = param must be absent. */
  function serializePage(p: number): string | null {
    return p > 0 ? String(p + 1) : null
  }

  const page = ref(synced ? parsePageParam(route!.query.page) : 0)

  if (synced) {
    const r = route!
    const ro = router!
    // Normalize an invalid/absent-canonical query on mount (self-healing URL),
    // e.g. ?page=abc → param removed. No-op when already canonical.
    const canonical = serializePage(page.value)
    const current = typeof r.query.page === 'string' ? r.query.page : null
    if (current !== canonical) {
      const query = { ...r.query }
      if (canonical === null) delete query.page
      else query.page = canonical
      void ro.replace({ query })
    }

    // state → URL (replace, never push — don't grow browser history)
    watch(page, (p) => {
      const want = serializePage(p)
      const cur = typeof r.query.page === 'string' ? r.query.page : null
      if (cur === want) return
      const query = { ...r.query }
      if (want === null) delete query.page
      else query.page = want
      void ro.replace({ query })
    })

    // URL → state (browser back/forward, manual query edit while alive)
    watch(
      () => r.query.page,
      (raw) => {
        const parsed = parsePageParam(raw)
        if (parsed !== page.value) page.value = parsed
      },
    )
  }

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
