<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute, RouterLink } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { verifyEmail } from '@/services/registrationService'
import { translatedError } from '@/shared/lib/utils'

const { t } = useI18n()
const route = useRoute()

const token = ref((route.query.token as string) || '')
const loading = ref(false)
const success = ref(false)
const error = ref<string | null>(null)

async function doVerify() {
  if (!token.value) {
    error.value = t('verifyEmailTokenMissing')
    return
  }
  loading.value = true
  error.value = null
  try {
    await verifyEmail(token.value)
    success.value = true
  } catch (e: unknown) {
    error.value = translatedError(e, t, t('verifyEmailFailed'))
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void doVerify()
})
</script>

<template>
  <div class="min-h-screen flex items-center justify-center bg-background px-4">
    <div class="w-full max-w-sm space-y-6 bg-card border border-border rounded-lg p-6 shadow-sm">
      <h1 class="text-xl font-bold">{{ t('verifyEmailTitle') }}</h1>

      <div v-if="loading" class="text-sm text-muted-foreground" data-testid="verify-email-loading">
        {{ t('verifyingEmail') }}
      </div>

      <div v-else-if="success" class="text-sm" data-testid="verify-email-success">
        <p class="font-medium text-green-600">{{ t('verifyEmailSuccessTitle') }}</p>
        <p class="text-muted-foreground mt-1">{{ t('verifyEmailSuccessHint') }}</p>
        <RouterLink to="/login" class="inline-block mt-4 text-sm text-primary hover:underline">
          {{ t('backToLogin') }}
        </RouterLink>
      </div>

      <div v-else-if="error" class="text-sm text-red-600" data-testid="verify-email-error">
        {{ error }}
        <RouterLink to="/login" class="block mt-4 text-sm text-primary hover:underline">
          {{ t('backToLogin') }}
        </RouterLink>
      </div>
    </div>
  </div>
</template>
