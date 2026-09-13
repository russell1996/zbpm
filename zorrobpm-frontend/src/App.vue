<script setup lang="ts">
// WO-OBS-5: infra only — no frontend logic change, G2 categorization (frontend file touch)
import { computed } from 'vue'
import { RouterView } from 'vue-router'
import { Toaster as Sonner } from '@/components/ui/sonner'
import { useUiStore } from '@/stores/ui'

const ui = useUiStore()
// WO-UI-8: toasts follow the app theme (.dark on <html>, state in the ui store)
const toastTheme = computed(() => (ui.darkMode ? 'dark' : 'light') as 'dark' | 'light')
</script>

<template>
  <Sonner position="top-center" :duration="5000" :theme="toastTheme" />
  <RouterView />
</template>
