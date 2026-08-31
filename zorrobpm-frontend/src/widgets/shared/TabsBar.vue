<script setup lang="ts">
/**
 * WO-ACL-14 criteria 1-5: ONE tab component for the whole app.
 *
 * Both pre-existing tab implementations (ProcessDefinitionDetail, ProcessInstanceDetail)
 * are gone — they differed in padding (py-2.5 vs py-2), gap (0 vs 1), active text color
 * (text-foreground vs text-primary) and, fatally, the duplicated -mb-px that pushed the
 * active underline under the container border on one of the two pages (P-55). This
 * component owns all of it:
 *   - the underline is border-b-2 on the ACTIVE button only;
 *   - the single -mb-px lives on the <nav>, never on buttons (WO-ACL-10 criteria 9-10);
 *   - keyboard: ArrowLeft/Right rotate, Home/End jump to the edges (WAI-ARIA tabs);
 *   - narrow screens: the ribbon scrolls horizontally, the active tab stays visible
 *     (scrollIntoView on activation).
 */
import { ref, watch, nextTick } from 'vue'

export interface TabItem {
  id: string
  label: string
}

const props = defineProps<{
  tabs: TabItem[]
  activeId: string
}>()
const emit = defineEmits<{ 'update:activeId': [id: string] }>()

const listRef = ref<HTMLElement | null>(null)

function onKeydown(e: KeyboardEvent) {
  const tabs = Array.from(listRef.value?.querySelectorAll<HTMLButtonElement>('[role="tab"]') ?? [])
  if (!tabs.length) return
  const idx = tabs.indexOf(document.activeElement as HTMLButtonElement)
  if (idx < 0) return
  const last = tabs.length - 1
  let target = -1
  switch (e.key) {
    case 'ArrowRight': target = idx === last ? 0 : idx + 1; break
    case 'ArrowLeft': target = idx === 0 ? last : idx - 1; break
    case 'Home': target = 0; break
    case 'End': target = last; break
    default: return
  }
  e.preventDefault()
  tabs[target].focus()
  tabs[target].click()
}

// keep the active tab in view when the ribbon is narrower than its content
watch(
  () => props.activeId,
  async () => {
    await nextTick()
    const active = listRef.value?.querySelector<HTMLElement>('[role="tab"][aria-selected="true"]')
    // jsdom does not implement scrollIntoView — the browser suite verifies the scroll
    if (typeof active?.scrollIntoView === 'function') {
      active.scrollIntoView({ inline: 'nearest', block: 'nearest' })
    }
  },
)
</script>

<template>
  <nav
    ref="listRef"
    class="flex gap-0 -mb-px overflow-x-auto"
    role="tablist"
    @keydown="onKeydown"
  >
    <button
      v-for="tab in tabs"
      :key="tab.id"
      role="tab"
      :aria-selected="activeId === tab.id ? 'true' : 'false'"
      class="shrink-0 px-3 py-1.5 text-[13px] font-medium border-b-2 transition-colors whitespace-nowrap"
      :class="activeId === tab.id
        ? 'border-primary text-primary font-semibold'
        : 'border-transparent text-muted-foreground hover:text-foreground hover:border-border'"
      @click="emit('update:activeId', tab.id)"
    >
      {{ tab.label }}
    </button>
  </nav>
</template>