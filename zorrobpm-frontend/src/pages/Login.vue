<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useAuth } from '@/features/auth/useAuth'

const { t } = useI18n()
const { login, isLoading, error } = useAuth()
const route = useRoute()
const router = useRouter()

const username = ref('')
const password = ref('')

async function submit() {
  if (!username.value || !password.value) return
  const ok = await login(username.value, password.value)
  if (ok) {
    const redirect = (route.query.redirect as string) || '/'
    router.replace(redirect)
  }
}
</script>

<template>
  <div class="min-h-screen flex items-center justify-center bg-muted">
    <form class="bg-card rounded-lg shadow-lg w-full max-w-sm p-8 space-y-5" @submit.prevent="submit">
      <div class="text-center space-y-2">
        <div class="flex justify-center">
          <div class="h-12 w-12 rounded-lg bg-primary flex items-center justify-center">
            <span class="text-sm font-black text-primary-foreground">BPM</span>
          </div>
        </div>
        <h1 class="text-2xl font-bold">ZorroBPM</h1>
        <p class="text-sm text-muted-foreground">{{ t('signInToContinue') }}</p>
      </div>

      <div class="space-y-3">
        <div>
          <label class="block text-sm font-medium mb-1">{{ t('username') }}</label>
          <input
            v-model="username"
            type="text"
            autocomplete="username"
            autofocus
            class="w-full px-3 py-2 border border-input rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-ring"
          />
        </div>
        <div>
          <label class="block text-sm font-medium mb-1">{{ t('password') }}</label>
          <input
            v-model="password"
            type="password"
            autocomplete="current-password"
            class="w-full px-3 py-2 border border-input rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-ring"
          />
        </div>
      </div>

      <p v-if="error" class="text-sm text-red-500">{{ error }}</p>

      <button
        type="submit"
        :disabled="isLoading"
        class="w-full px-4 py-2 bg-primary text-primary-foreground rounded-md hover:opacity-90 transition-opacity text-sm disabled:opacity-50"
      >
        {{ isLoading ? t('signingIn') : t('signIn') }}
      </button>
    </form>
  </div>
</template>
