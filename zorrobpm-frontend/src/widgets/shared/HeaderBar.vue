<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import { useUiStore } from '@/stores/ui'
import { LogOut, User, Sun, Moon, Menu, FileCode, ChevronDown, PanelLeft, Activity, Gauge } from 'lucide-vue-next'
import SearchCommand from './SearchCommand.vue'
import LanguageSwitcher from './LanguageSwitcher.vue'
import { SidebarTrigger } from '@/components/ui/sidebar'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'

defineProps<{
  showMenuButton?: boolean
}>()

const emit = defineEmits<{
  toggleSidebar: []
}>()

const auth = useAuthStore()
const ui = useUiStore()
const { t } = useI18n()
</script>

<template>
  <header class="h-14 border-b border-border flex items-center justify-between px-3 md:px-5 bg-card">
    <div class="flex items-center gap-3">
      <button
        v-if="showMenuButton"
        class="p-2 text-muted-foreground hover:text-foreground hover:bg-muted rounded-md transition-colors"
        @click="emit('toggleSidebar')"
      >
        <Menu class="h-5 w-5" />
      </button>
      <SidebarTrigger v-if="!showMenuButton" />
      <SearchCommand />
    </div>
    <div class="flex items-center gap-4">
      <LanguageSwitcher />
      <button
        class="p-2 text-muted-foreground hover:text-foreground hover:bg-muted rounded-md transition-colors"
        :title="ui.darkMode ? t('lightMode') : t('darkMode')"
        @click="ui.toggleDarkMode()"
      >
        <Sun v-if="ui.darkMode" class="h-4 w-4" />
        <Moon v-else class="h-4 w-4" />
      </button>
      <a
        href="/swagger-ui/index.html"
        target="_blank"
        rel="noopener noreferrer"
        class="p-2 text-muted-foreground hover:text-foreground hover:bg-muted rounded-md transition-colors"
        :title="t('apiDocs')"
      >
        <FileCode class="h-4 w-4" />
      </a>
      <!-- WO-OBS-4: observability shortcuts (shared ZBPM login via nginx auth_request).
           Same <a target="_blank"> primitive and classes as the API-docs icon above.
           SUPER_ADMIN-only (unlike apiDocs): cosmetic gate, real enforcement is
           nginx auth_request (+ hard role check on /prometheus/ — Prometheus has no RBAC).
           No iframes/embeds anywhere. -->
      <a
        v-if="auth.isSuperAdmin"
        href="/grafana/"
        target="_blank"
        rel="noopener"
        class="p-2 text-muted-foreground hover:text-foreground hover:bg-muted rounded-md transition-colors"
        :title="t('monitoring')"
      >
        <Activity class="h-4 w-4" />
      </a>
      <a
        v-if="auth.isSuperAdmin"
        href="/prometheus/"
        target="_blank"
        rel="noopener"
        class="p-2 text-muted-foreground hover:text-foreground hover:bg-muted rounded-md transition-colors"
        :title="t('metrics')"
      >
        <Gauge class="h-4 w-4" />
      </a>
      <DropdownMenu v-if="auth.user">
        <DropdownMenuTrigger as-child>
          <button
            class="flex items-center gap-2 px-3 py-1.5 text-sm text-muted-foreground hover:text-foreground hover:bg-muted rounded-md transition-colors outline-none"
          >
            <User class="h-4 w-4" />
            <span class="hidden md:inline">{{ auth.user.fullName || auth.user.username }}</span>
            <ChevronDown class="h-3 w-3 hidden md:inline" />
          </button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" class="w-56">
          <DropdownMenuItem as-child class="hover:bg-accent hover:text-accent-foreground">
            <RouterLink :to="{ name: 'my-profile' }" class="flex items-center gap-2">
              <User class="h-4 w-4" />
              {{ t('myProfile') }}
            </RouterLink>
          </DropdownMenuItem>
          <DropdownMenuItem class="text-destructive focus:text-destructive hover:bg-accent hover:text-destructive" @select="auth.logout()">
            <LogOut class="h-4 w-4" />
            {{ t('logout') }}
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  </header>
</template>
