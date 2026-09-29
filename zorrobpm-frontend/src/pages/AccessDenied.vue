<script setup lang="ts">
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
</script>

<template>
  <div class="min-h-screen flex items-center justify-center bg-muted">
    <div class="text-center max-w-md">
      <h1 class="text-2xl font-bold mb-4">{{ t('accessDenied') }}</h1>
      <p class="text-muted-foreground mb-6">{{ t('accessDeniedMessage') }}</p>
      <button
        class="px-4 py-2 bg-primary text-primary-foreground rounded-md hover:opacity-90 transition-opacity"
        @click="$router.push({ name: 'login' })"
      >
        {{ t('tryAgain') }}
      </button>
    </div>
  </div>
</template>
