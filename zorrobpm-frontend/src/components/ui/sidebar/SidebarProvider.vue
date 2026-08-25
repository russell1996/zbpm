<script setup lang="ts">
import type { HTMLAttributes } from "vue"
import { defaultDocument, useEventListener, useMediaQuery } from "@vueuse/core"
import { TooltipProvider } from "reka-ui"
import { computed, ref } from "vue"
import { cn } from '@/shared/lib/utils'
import { provideSidebarContext, SIDEBAR_COOKIE_MAX_AGE, SIDEBAR_COOKIE_NAME, SIDEBAR_KEYBOARD_SHORTCUT, SIDEBAR_WIDTH, SIDEBAR_WIDTH_ICON } from "./utils"

const props = withDefaults(defineProps<{
  /** Explicit initial state; overrides the persisted cookie when provided. */
  defaultOpen?: boolean
  /** Controlled mode: pass `open` to drive the sidebar externally. */
  open?: boolean
  class?: HTMLAttributes["class"]
}>(), {
  // Explicit undefined defaults are REQUIRED for optional booleans: without them
  // Vue's absent-boolean casting turns a missing `open`/`defaultOpen` into `false`,
  // which silently forces the sidebar into controlled-collapsed mode (WO-UI-7).
  defaultOpen: undefined,
  open: undefined,
})

const emits = defineEmits<{
  "update:open": [open: boolean]
}>()

const isMobile = useMediaQuery("(max-width: 768px)")
const openMobile = ref(false)

// Read the persisted state lazily, per instance: a static prop default is
// evaluated once at import time — before any real cookie exists — so the stored
// sidebar_state would be silently ignored (WO-UI-7, caught by persist test).
function readStoredDefaultOpen(): boolean {
  return props.defaultOpen ?? !defaultDocument?.cookie.includes(`${SIDEBAR_COOKIE_NAME}=false`)
}

// Uncontrolled mode: plain internal ref seeded from the persisted cookie.
// Controlled mode (`open` prop) overrides it via the computed below.
const internalOpen = ref(readStoredDefaultOpen())

const open = computed<boolean>({
  get: () => (props.open !== undefined ? props.open : internalOpen.value),
  set: (value) => {
    if (props.open !== undefined) emits("update:open", value)
    else internalOpen.value = value
  },
})

function setOpen(value: boolean) {
  open.value = value

  // This sets the cookie to keep the sidebar state.
  document.cookie = `${SIDEBAR_COOKIE_NAME}=${open.value}; path=/; max-age=${SIDEBAR_COOKIE_MAX_AGE}`
}

function setOpenMobile(value: boolean) {
  openMobile.value = value
}

// Helper to toggle the sidebar.
function toggleSidebar() {
  return isMobile.value ? setOpenMobile(!openMobile.value) : setOpen(!open.value)
}

useEventListener("keydown", (event: KeyboardEvent) => {
  if (event.key === SIDEBAR_KEYBOARD_SHORTCUT && (event.metaKey || event.ctrlKey)) {
    event.preventDefault()
    toggleSidebar()
  }
})

// We add a state so that we can do data-state="expanded" or "collapsed".
// This makes it easier to style the sidebar with Tailwind classes.
const state = computed(() => open.value ? "expanded" : "collapsed")

provideSidebarContext({
  state,
  open,
  setOpen,
  isMobile,
  openMobile,
  setOpenMobile,
  toggleSidebar,
})
</script>

<template>
  <TooltipProvider :delay-duration="0">
    <div
      :style="{
        '--sidebar-width': SIDEBAR_WIDTH,
        '--sidebar-width-icon': SIDEBAR_WIDTH_ICON,
      }"
      :class="cn('group/sidebar-wrapper flex min-h-svh w-full has-[[data-variant=inset]]:bg-sidebar', props.class)"
      v-bind="$attrs"
    >
      <slot />
    </div>
  </TooltipProvider>
</template>
