<script setup lang="ts">
/**
 * WO-ACL-11 criteria 32-34: right-side drawer panel (My Submissions).
 * - slides in from the right (~200 ms), dark overlay behind;
 * - closes by overlay click, Esc and the cross button;
 * - focus moves INTO the panel on open and BACK to the opener on close;
 * - Tab never leaves the panel (trap), content scrolls inside, not the page;
 * - keydown listeners are removed on unmount.
 */
import { ref, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import { X } from 'lucide-vue-next'

const props = defineProps<{
  open: boolean
  title: string
}>()
const emit = defineEmits<{ close: [] }>()

const { t } = useI18n()

const panelRef = ref<HTMLElement | null>(null)
let opener: Element | null = null

function focusableInPanel(): HTMLElement[] {
  const panel = panelRef.value
  if (!panel) return []
  return Array.from(
    panel.querySelectorAll<HTMLElement>(
      'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])',
    ),
  ).filter((el) => !el.hasAttribute('disabled'))
}

function onKeydown(e: KeyboardEvent) {
  if (!props.open) return
  if (e.key === 'Escape') {
    e.preventDefault()
    emit('close')
    return
  }
  if (e.key !== 'Tab') return
  const els = focusableInPanel()
  if (!els.length) return
  const first = els[0]
  const last = els[els.length - 1]
  const active = document.activeElement
  const inside = panelRef.value?.contains(active) ?? false
  if (e.shiftKey && (active === first || !inside)) {
    e.preventDefault()
    last.focus()
  } else if (!e.shiftKey && (active === last || !inside)) {
    e.preventDefault()
    first.focus()
  }
}

watch(
  () => props.open,
  async (open) => {
    if (open) {
      // remember the opener (e.g. the "My Submissions" button) for focus return
      opener = document.activeElement
      document.body.style.overflow = 'hidden'
      await nextTick()
      const target = focusableInPanel()[0] ?? panelRef.value
      target?.focus()
    } else {
      document.body.style.overflow = ''
      if (opener instanceof HTMLElement) opener.focus()
      opener = null
    }
  },
  { immediate: true },
)

onMounted(() => document.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => {
  document.removeEventListener('keydown', onKeydown)
  document.body.style.overflow = ''
  if (opener instanceof HTMLElement) opener.focus()
  opener = null
})
</script>

<template>
  <div class="fixed inset-0 z-50" :class="{ invisible: !open }" :aria-hidden="!open">
    <!-- overlay: click outside closes -->
    <div
      data-testid="drawer-overlay"
      class="absolute inset-0 bg-black/50 transition-opacity duration-200"
      :class="open ? 'opacity-100' : 'opacity-0 pointer-events-none'"
      @click="emit('close')"
    />
    <!-- panel: full height, width up to max-w-4xl (WO-ACL-14 criterion 20 —
         My Submissions has 5 columns incl. a long reject-reason cell, 2xl
         squeezed them into a horizontal overflow that was hard to notice) -->
    <div
      ref="panelRef"
      data-testid="drawer-panel"
      role="dialog"
      aria-modal="true"
      class="absolute right-0 top-0 h-full w-full sm:w-auto sm:max-w-4xl bg-card shadow-xl flex flex-col transition-transform duration-200 ease-out"
      :class="open ? 'translate-x-0' : 'translate-x-full'"
    >
      <div class="flex items-center justify-between px-4 py-3 border-b border-border shrink-0">
        <h2 class="text-lg font-bold">{{ title }}</h2>
        <button
          class="p-1 rounded-md text-muted-foreground hover:text-foreground hover:bg-muted transition-colors"
          :aria-label="t('close')"
          @click="emit('close')"
        >
          <X class="h-4 w-4" />
        </button>
      </div>
      <div class="flex-1 overflow-y-auto px-4 py-4">
        <slot v-if="open" />
      </div>
    </div>
  </div>
</template>