<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { setLocale } from '@/app/i18n'
import { Globe } from 'lucide-vue-next'

const { locale } = useI18n()
const showLangMenu = ref(false)
const rootEl = ref<HTMLElement | null>(null)

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

// WO-ACL-10 criterion 11: the menu closes on outside click and on Escape.
// Listeners are attached on mount and removed on unmount.
function onDocumentClick(e: MouseEvent) {
  if (!rootEl.value || !rootEl.value.contains(e.target as Node)) {
    showLangMenu.value = false
  }
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    showLangMenu.value = false
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
  <div ref="rootEl" class="relative">
    <button
      type="button"
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
        type="button"
        class="w-full px-3 py-1.5 text-sm text-left hover:bg-muted transition-colors"
        :class="{ 'font-medium text-primary': locale === lang.code }"
        @click="switchLang(lang.code)"
      >
        {{ lang.label }}
      </button>
    </div>
  </div>
</template>
