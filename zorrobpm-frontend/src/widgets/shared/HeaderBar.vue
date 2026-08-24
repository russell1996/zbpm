<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import { useUiStore } from '@/stores/ui'
import { LogOut, User, Sun, Moon, Menu, FileCode, ChevronDown } from 'lucide-vue-next'
import SearchCommand from './SearchCommand.vue'
import LanguageSwitcher from './LanguageSwitcher.vue'

defineProps<{
  showMenuButton?: boolean
}>()

const emit = defineEmits<{
  toggleSidebar: []
}>()

const auth = useAuthStore()
const ui = useUiStore()
const { t } = useI18n()

const userMenuOpen = ref(false)
const rootEl = ref<HTMLElement | null>(null)

// WO-UI-5 (dropdown): same pattern as LanguageSwitcher.vue:1-46 — close on outside click and Escape
function onDocumentClick(e: MouseEvent) {
  if (!rootEl.value || !rootEl.value.contains(e.target as Node)) {
    userMenuOpen.value = false
  }
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    userMenuOpen.value = false
  }
}

onMounted(() => {
  document.addEventListener('click', onDocumentClick)
  document.addEventListener('keydown', onKeydown)
})

onUnmounted(() => {
  document.removeEventListener('click', onDocumentClick)
  document.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <header class="h-14 border-b border-border flex items-center justify-between px-4 md:px-6 bg-card">
    <div class="flex items-center gap-3">
      <button
        v-if="showMenuButton"
        class="p-2 text-muted-foreground hover:text-foreground hover:bg-muted rounded-md transition-colors"
        @click="emit('toggleSidebar')"
      >
        <Menu class="h-5 w-5" />
      </button>
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
      <div v-if="auth.user" ref="rootEl" class="relative">
        <button
          class="flex items-center gap-2 px-3 py-1.5 text-sm text-muted-foreground hover:text-foreground hover:bg-muted rounded-md transition-colors"
          aria-haspopup="menu"
          :aria-expanded="userMenuOpen"
          @click="userMenuOpen = !userMenuOpen"
        >
          <User class="h-4 w-4" />
          <span class="hidden md:inline">{{ auth.user.fullName || auth.user.username }}</span>
          <ChevronDown class="h-3 w-3 hidden md:inline" />
        </button>
        <div
          v-if="userMenuOpen"
          class="absolute right-0 mt-2 w-56 rounded-md border border-border bg-card shadow-lg z-50 py-1"
        >
          <div class="px-4 py-2 text-sm text-muted-foreground border-b border-border md:hidden">
            {{ auth.user.fullName || auth.user.username }}
          </div>
          <RouterLink
            :to="{ name: 'my-profile' }"
            class="flex items-center gap-2 px-4 py-2 text-sm hover:bg-muted"
            @click="userMenuOpen = false"
          >
            <User class="h-4 w-4" />
            {{ t('myProfile') }}
          </RouterLink>
          <button
            class="w-full flex items-center gap-2 px-4 py-2 text-sm text-red-600 hover:bg-muted"
            @click="auth.logout()"
          >
            <LogOut class="h-4 w-4" />
            {{ t('logout') }}
          </button>
        </div>
      </div>
    </div>
  </header>
</template>
