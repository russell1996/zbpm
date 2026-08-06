<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { setLocale } from '@/app/i18n'
import { useAuthStore } from '@/stores/auth'
import { useUiStore } from '@/stores/ui'
import { LogOut, User, Sun, Moon, Menu, Globe, FileCode } from 'lucide-vue-next'
import SearchCommand from './SearchCommand.vue'

defineProps<{
  showMenuButton?: boolean
}>()

const emit = defineEmits<{
  toggleSidebar: []
}>()

const auth = useAuthStore()
const ui = useUiStore()
const { t, locale } = useI18n()

const showLangMenu = ref(false)

const languages = [
  { code: 'ru', label: 'Русский' },
  { code: 'en', label: 'English' },
  { code: 'kz', label: 'Қазақша' },
]

function switchLang(code: string) {
  setLocale(code)
  showLangMenu.value = false
}

const currentLang = () => languages.find((l) => l.code === locale.value)?.label || 'RU'
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
      <div class="relative">
        <button
          class="flex items-center gap-1.5 px-2 py-1.5 text-sm text-muted-foreground hover:text-foreground hover:bg-muted rounded-md transition-colors"
          @click="showLangMenu = !showLangMenu"
        >
          <Globe class="h-4 w-4" />
          <span class="hidden md:inline">{{ currentLang() }}</span>
        </button>
        <div
          v-if="showLangMenu"
          class="absolute right-0 top-full mt-1 bg-card border border-border rounded-lg shadow-lg z-50 py-1 min-w-[120px]"
        >
          <button
            v-for="lang in languages"
            :key="lang.code"
            class="w-full px-3 py-1.5 text-sm text-left hover:bg-muted transition-colors"
            :class="{ 'font-medium text-primary': locale === lang.code }"
            @click="switchLang(lang.code)"
          >
            {{ lang.label }}
          </button>
        </div>
      </div>
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
        title="API Docs"
      >
        <FileCode class="h-4 w-4" />
      </a>
      <div v-if="auth.user" class="hidden md:flex items-center gap-2 text-sm">
        <User class="h-4 w-4 text-muted-foreground" />
        <span>{{ auth.user.fullName || auth.user.username }}</span>
      </div>
      <button
        class="flex items-center gap-2 px-3 py-1.5 text-sm text-muted-foreground hover:text-foreground hover:bg-muted rounded-md transition-colors"
        @click="auth.logout()"
      >
        <LogOut class="h-4 w-4" />
        <span class="hidden md:inline">{{ t('logout') }}</span>
      </button>
    </div>
  </header>
</template>
