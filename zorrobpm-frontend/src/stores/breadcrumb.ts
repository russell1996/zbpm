import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * WO-ACL-11 criteria 3-5 + WO-ACL-15 criteria 17-19: the breadcrumb leaf label.
 *
 * Why a store (P-54): BreadcrumbNav is mounted in MainLayout ABOVE <router-view>,
 * and the detail pages render below it. inject() only looks UP the tree, so a
 * provide() from a page could never reach the crumbs — the ACL-8/ACL-10 mechanism
 * was physically impossible, and its tests passed only because they mounted
 * BreadcrumbNav alone and injected the value by hand.
 *
 * WO-ACL-15 criterion 19: pages do NOT touch this store directly — the single
 * shared mechanism is `useBreadcrumbLabel()` (composables/useBreadcrumbLabel.ts),
 * which fills the store when the page's data arrives and clears it on unmount.
 * BreadcrumbNav reads the label; a null label falls back to the route's static
 * titleKey (the pre-data state never shows an empty string).
 */
export const useBreadcrumbStore = defineStore('breadcrumb', () => {
  const crumbLabel = ref<string | null>(null)

  function setCrumbLabel(label: string | null) {
    crumbLabel.value = label
  }

  return { crumbLabel, setCrumbLabel }
})