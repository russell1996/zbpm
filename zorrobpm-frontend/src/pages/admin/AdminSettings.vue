<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { SidebarProvider, SidebarMenu, SidebarMenuItem, SidebarMenuButton } from '@/components/ui/sidebar'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()

const sections = [
  { to: '/admin/settings/users', labelKey: 'users', id: 'panel-users' },
  { to: '/admin/settings/submissions', labelKey: 'submissionQueue', id: 'panel-submissions' },
  { to: '/admin/settings/mail-settings', labelKey: 'mailSettings', id: 'panel-mail-settings' },
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
  <div class="w-full">
    <h1 class="text-2xl font-bold mb-6">{{ t('adminSettings') }}</h1>

    <!-- Single unified container for the whole "Настройки администратора" section.
         Width/position/nav are constant; only the right content changes between sections. -->
    <div class="bg-card border border-border rounded-lg">
      <SidebarProvider class="!min-h-0">
        <div class="flex gap-0">
          <!-- Left nav — fixed vertical section navigation -->
          <nav
            class="w-48 shrink-0 border-r border-border p-3"
            role="tablist"
            aria-orientation="vertical"
            :aria-label="t('adminSettings')"
          >
            <SidebarMenu class="space-y-1">
              <SidebarMenuItem v-for="section in sections" :key="section.to">
                <SidebarMenuButton
                  :is-active="isActive(section.to)"
                  role="tab"
                  :aria-selected="isActive(section.to) ? 'true' : 'false'"
                  :aria-controls="section.id"
                  @click="navigate(section.to)"
                >
                  {{ t(section.labelKey) }}
                </SidebarMenuButton>
              </SidebarMenuItem>
            </SidebarMenu>
          </nav>

          <!-- Right working area — fills remaining width; height is content-driven -->
          <div
            class="flex-1 min-w-0 p-6"
            role="tabpanel"
            :aria-labelledby="activePanelId"
          >
            <router-view />
          </div>
        </div>
      </SidebarProvider>
    </div>
  </div>
</template>
