import { watch, onUnmounted } from 'vue'
import { useBreadcrumbStore } from '@/stores/breadcrumb'

/**
 * WO-ACL-15 criteria 17-19: THE single shared breadcrumb mechanism for every
 * detail card (process definition, process instance, user/service task, DMN
 * decision, incident).
 *
 * Pass a getter that returns the human-readable leaf label once the page's data
 * is available (and null before — the crumb then falls back to the route's
 * static titleKey, so the pre-data state never shows an empty string).
 *
 *   useBreadcrumbLabel(() => {
 *     const i = store.currentInstance
 *     return i ? `${i.processName || i.processKey} · ${i.id.slice(0, 8)}` : null
 *   })
 *
 * The composable writes to the store itself (immediately, and reactively on
 * every data change) and CLEARS it on unmount — without the cleanup the next
 * card would briefly show a stale foreign name.
 */
export function useBreadcrumbLabel(getLabel: () => string | null | undefined) {
  const store = useBreadcrumbStore()
  watch(
    getLabel,
    (val) => store.setCrumbLabel(val ?? null),
    { immediate: true },
  )
  onUnmounted(() => store.setCrumbLabel(null))
}