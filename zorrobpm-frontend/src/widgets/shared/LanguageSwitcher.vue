<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { setLocale } from '@/app/i18n'
import { Globe } from 'lucide-vue-next'

const { locale } = useI18n()
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
</template>
