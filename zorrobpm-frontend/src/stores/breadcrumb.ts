import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * WO-ACL-11 criteria 3-5: breadcrumb process name, moved from provide/inject to
 * a store.
 *
 * Why (P-54): BreadcrumbNav is mounted in MainLayout ABOVE <router-view>, and the
 * detail page renders below it. inject() only looks UP the tree, so a provide()
 * from the page could never reach the crumbs — the ACL-8/ACL-10 mechanism was
 * physically impossible, and its tests passed only because they mounted
 * BreadcrumbNav alone and injected the value by hand. The page fills this store
 * when the definition arrives and clears it on unmount; BreadcrumbNav reads it.
 */
export const useBreadcrumbStore = defineStore('breadcrumb', () => {
  const processName = ref<string | null>(null)

  function setProcessName(name: string | null) {
    processName.value = name
  }

  return { processName, setProcessName }
})
