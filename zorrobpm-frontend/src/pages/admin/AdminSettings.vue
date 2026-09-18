<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()

const tablistRef = ref<HTMLElement | null>(null)

function onKeydown(e: KeyboardEvent) {
  const tabs = Array.from(tablistRef.value?.querySelectorAll<HTMLButtonElement>('[role="tab"]') ?? [])
  if (!tabs.length) return
  const idx = tabs.indexOf(document.activeElement as HTMLButtonElement)
  if (idx < 0) return
  const last = tabs.length - 1
  let target = -1
  switch (e.key) {
    case 'ArrowDown': target = idx === last ? 0 : idx + 1; break
    case 'ArrowUp': target = idx === 0 ? last : idx - 1; break
    case 'Home': target = 0; break
    case 'End': target = last; break
    default: return
  }
  e.preventDefault()
  tabs[target].focus()
  tabs[target].click()
}

// Tab order per WO-UI-10 brief: mail-settings, users, submissions.
// Tab order per WO-UI-10/15: mail-settings, users, submissions, registrations.
const sections = [
  { to: '/admin/settings/mail-settings', labelKey: 'mailSettings', id: 'panel-mail-settings' },
  { to: '/admin/settings/users', labelKey: 'users', id: 'panel-users' },
  { to: '/admin/settings/submissions', labelKey: 'submissionQueue', id: 'panel-submissions' },
  // WO-UI-15: registration queue moved from standalone sidebar item
  { to: '/admin/settings/registrations', labelKey: 'registrationQueue', id: 'panel-registrations' },
]

function isActive(to: string) {
  return route.path === to || route.path.startsWith(to + '/')
}

const activePanelId = computed(() => sections.find((s) => isActive(s.to))?.id ?? '')

function navigate(to: string) {
  router.push(to)
}
</script>

<template>
  <div class="h-full flex flex-col space-y-6">
    <h1 class="text-2xl font-bold">{{ t('adminSettings') }}</h1>

    <!-- Layout mirrors the account Settings page (MyProfile.vue): plain vertical nav on the
         left (active item = bg-muted, no card/frame), working area on the right as a card.
         Stretched to fill the whole screen; only the right content changes between sections. -->
      <div class="flex gap-6 flex-1 min-h-0">
      <!-- Left nav — plain buttons, not a card -->
      <nav
        ref="tablistRef"
        class="w-48 shrink-0 space-y-1"
        role="tablist"
        aria-orientation="vertical"
        :aria-label="t('adminSettings')"
        @keydown="onKeydown"
      >
        <button
          v-for="section in sections"
          :key="section.to"
          type="button"
          role="tab"
          :aria-selected="isActive(section.to) ? 'true' : 'false'"
          :aria-controls="section.id"
          class="w-full text-left px-3 py-2 text-sm rounded-md transition-colors"
          :class="isActive(section.to) ? 'bg-muted font-medium' : 'hover:bg-muted'"
          @click="navigate(section.to)"
        >
          {{ t(section.labelKey) }}
        </button>
      </nav>

      <!-- Right working area — card surface, fills remaining width/height -->
      <div
        class="flex-1 min-w-0 bg-card border border-border rounded-lg p-6 overflow-auto"
        role="tabpanel"
        :aria-labelledby="activePanelId"
      >
        <router-view />
      </div>
    </div>
  </div>
</template>
