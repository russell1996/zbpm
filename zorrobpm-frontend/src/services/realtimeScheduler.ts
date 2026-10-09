/**
 * WO-UI-26 Доп.4 — общий планировщик запасного refetch списков.
 *
 * Полный refetch — ТОЛЬКО запасной путь (разрыв sequence, реконнект, возврат
 * на скрытую вкладку, ошибка патча). Склеенный: trailing-debounce 500 мс +
 * cap 3 с (первое событие сразу ставит cap-таймер — долгое окно не висит);
 * скрытая вкладка — 0 запросов, один refetch по visibilitychange; без loading
 * (тихий, stale-while-revalidate — Доп.2).
 */
type RefreshFn = () => void

const pending = new Map<string, { fn: RefreshFn; capTimer: ReturnType<typeof setTimeout> | null }>()
let debounceTimer: ReturnType<typeof setTimeout> | null = null
const DEBOUNCE_MS = 500
const CAP_MS = 3000
let hiddenUnsub: (() => void) | null = null
let hiddenPending = false

function isHidden(): boolean {
  return typeof document !== 'undefined' && document.visibilityState === 'hidden'
}

function flush(): void {
  debounceTimer = null
  if (isHidden()) {
    hiddenPending = true
    return
  }
  hiddenPending = false
  const batch = [...pending.values()]
  pending.clear()
  for (const { fn, capTimer } of batch) {
    if (capTimer) clearTimeout(capTimer)
    try {
      fn()
    } catch (e) {
      console.error('[RealtimeScheduler] refresh failed:', e)
    }
  }
}

function ensureVisibilityHook(): void {
  if (hiddenUnsub || typeof document === 'undefined') return
  const onVis = () => {
    if (!isHidden() && hiddenPending && pending.size > 0) {
      if (debounceTimer) {
        clearTimeout(debounceTimer)
        debounceTimer = null
      }
      flush()
    }
  }
  document.addEventListener('visibilitychange', onVis)
  hiddenUnsub = () => document.removeEventListener('visibilitychange', onVis)
}

/** Запланировать запасной refetch списка `key` (идемпотентно в окне). */
export function scheduleListRefresh(key: string, fn: RefreshFn): void {
  ensureVisibilityHook()
  if (isHidden()) {
    // Скрытая вкладка: копим, шлём один по возврату.
    if (!pending.has(key)) {
      pending.set(key, { fn, capTimer: null })
    } else {
      pending.get(key)!.fn = fn
    }
    hiddenPending = true
    return
  }
  const existing = pending.get(key)
  if (existing) {
    existing.fn = fn
  } else {
    const entry: { fn: RefreshFn; capTimer: ReturnType<typeof setTimeout> | null } = { fn, capTimer: null }
    entry.capTimer = setTimeout(() => {
      entry.capTimer = null
      pending.delete(key)
      if (pending.size === 0 && debounceTimer) {
        clearTimeout(debounceTimer)
        debounceTimer = null
      }
      if (!isHidden()) {
        try {
          fn()
        } catch (e) {
          console.error('[RealtimeScheduler] refresh failed:', e)
        }
      } else {
        hiddenPending = true
        pending.set(key, { fn, capTimer: null })
      }
    }, CAP_MS)
    pending.set(key, entry)
  }
  if (!debounceTimer) {
    debounceTimer = setTimeout(flush, DEBOUNCE_MS)
  }
}

/** Только для тестов: сброс планировщика между кейсами. */
export function resetRealtimeSchedulerForTest(): void {
  if (debounceTimer) clearTimeout(debounceTimer)
  debounceTimer = null
  for (const { capTimer } of pending.values()) {
    if (capTimer) clearTimeout(capTimer)
  }
  pending.clear()
  hiddenPending = false
  if (hiddenUnsub) {
    hiddenUnsub()
    hiddenUnsub = null
  }
}

/** Только для тестов: число склеенных ожидающих ключей. */
export function pendingRefreshCountForTest(): number {
  return pending.size
}
