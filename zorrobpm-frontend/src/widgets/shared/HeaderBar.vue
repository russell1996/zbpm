<script setup lang="ts">
import { useAuthStore } from '@/stores/auth'
import { useUiStore } from '@/stores/ui'
import { LogOut, User, Sun, Moon, Menu } from 'lucide-vue-next'
import SearchCommand from './SearchCommand.vue'

defineProps<{
  showMenuButton?: boolean
}>()

const emit = defineEmits<{
  toggleSidebar: []
}>()

const auth = useAuthStore()
const ui = useUiStore()
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
      <button
        class="p-2 text-muted-foreground hover:text-foreground hover:bg-muted rounded-md transition-colors"
        :title="ui.darkMode ? 'Switch to light mode' : 'Switch to dark mode'"
        @click="ui.toggleDarkMode()"
      >
        <Sun v-if="ui.darkMode" class="h-4 w-4" />
        <Moon v-else class="h-4 w-4" />
      </button>
      <div v-if="auth.user" class="hidden md:flex items-center gap-2 text-sm">
        <User class="h-4 w-4 text-muted-foreground" />
        <span>{{ auth.user.fullName || auth.user.login }}</span>
      </div>
      <button
        class="flex items-center gap-2 px-3 py-1.5 text-sm text-muted-foreground hover:text-foreground hover:bg-muted rounded-md transition-colors"
        @click="auth.logout()"
      >
        <LogOut class="h-4 w-4" />
        <span class="hidden md:inline">Logout</span>
      </button>
    </div>
  </header>
</template>
