<script setup lang="ts">
// WO-OBS-5: infra only — no frontend logic change, G2 categorization (frontend file touch)
import { computed } from 'vue'
import { RouterView } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { Toaster as Sonner } from '@/components/ui/sonner'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { useUiStore } from '@/stores/ui'
import { useLastUiError, clearUiError } from '@/services/uiError'

const { t } = useI18n()
const ui = useUiStore()
// WO-UI-8: toasts follow the app theme (.dark on <html>, state in the ui store)
const toastTheme = computed(() => (ui.darkMode ? 'dark' : 'light') as 'dark' | 'light')
// WO-UI-26 Доп.1 (кр.11): белый экран → понятная панель + «Перезагрузить».
const lastError = useLastUiError()

function reloadApp(): void {
  clearUiError()
  window.location.reload()
}
</script>

<template>
  <Sonner position="top-center" :duration="5000" :theme="toastTheme" />
  <!-- WO-UI-26 кр.11: ошибка рендера потомка не оставляет пустой экран. -->
  <div v-if="lastError" class="min-h-screen flex items-center justify-center p-6" data-testid="ui-error-fallback">
    <Alert class="max-w-md">
      <AlertTitle>{{ t('uiErrorTitle') }}</AlertTitle>
      <AlertDescription class="mt-2 break-words">{{ lastError.message }}</AlertDescription>
      <div class="mt-4">
        <Button data-testid="ui-error-reload" @click="reloadApp">{{ t('uiErrorReload') }}</Button>
      </div>
    </Alert>
  </div>
  <RouterView v-else />
</template>
